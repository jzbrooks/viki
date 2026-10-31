package app.viki.sdk

import app.viki.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SdkToolsPaths(
  val sdkRoot: Path,
  val sdkManagerBin: Path?,
  val avdManagerBin: Path?,
  val emulatorBin: Path?,
  val adbBin: Path?,
) {
  val hasCmdlineTools: Boolean
    get() = sdkManagerBin != null && avdManagerBin != null

  val hasEmulator: Boolean
    get() = emulatorBin != null

  val hasPlatformTools: Boolean
    get() = adbBin != null

  val isComplete: Boolean
    get() = hasCmdlineTools && hasEmulator && hasPlatformTools

  val missingComponents: List<String>
    get() = buildList {
      if (!hasCmdlineTools) add("Android Command-Line Tools")
      if (!hasPlatformTools) add("Android SDK Platform-Tools (adb)")
      if (!hasEmulator) add("Android Emulator")
    }
}

sealed interface SdkStatus {
  data class Ready(
    val tools: SdkToolsPaths,
    val sourceDescription: String,
  ) : SdkStatus

  data class Incomplete(
    val tools: SdkToolsPaths,
    val sourceDescription: String,
  ) : SdkStatus

  data class NotFound(
    val recommendedInstallPath: Path,
    val searchedPaths: List<Path>,
  ) : SdkStatus
}

@SingleIn(AppScope::class)
@Inject
class AndroidSdkLocator(private val platformDetector: HostPlatformDetector) {
  private val _sdkStatus =
    MutableStateFlow<SdkStatus>(
      SdkStatus.NotFound(
        recommendedInstallPath = platformDetector.current.defaultSdkRoot,
        searchedPaths = emptyList(),
      )
    )
  val sdkStatus: StateFlow<SdkStatus> = _sdkStatus.asStateFlow()

  init {
    refresh()
  }

  fun refresh(): SdkStatus {
    val detected = detectSdk()
    _sdkStatus.value = detected
    return detected
  }

  fun setCustomSdkRoot(path: Path): SdkStatus {
    val normalized = path.toAbsolutePath().normalize()
    savePreferredSdkPath(normalized)
    val tools = inspectSdkRoot(normalized)
    val status =
      when {
        tools.isComplete -> SdkStatus.Ready(tools, "Custom location")
        else -> SdkStatus.Incomplete(tools, "Custom location")
      }
    _sdkStatus.value = status
    return status
  }

  fun detectSdk(envProvider: (String) -> String? = System::getenv): SdkStatus {
    val platform = platformDetector.current
    val candidates = mutableListOf<Pair<Path, String>>()

    loadPreferredSdkPath()?.let { saved -> candidates.add(saved to "Saved Viki setting") }

    envProvider("ANDROID_HOME")
      ?.takeIf { it.isNotBlank() }
      ?.let { candidates.add(Paths.get(it) to "ANDROID_HOME") }

    envProvider("ANDROID_SDK_ROOT")
      ?.takeIf { it.isNotBlank() }
      ?.let { candidates.add(Paths.get(it) to "ANDROID_SDK_ROOT") }

    for (commonPath in platform.commonSdkCandidatePaths) {
      candidates.add(commonPath to "Standard ${platform.osFamily.displayName} location")
    }

    val distinctCandidates = candidates.distinctBy { it.first.toAbsolutePath().normalize() }

    // 1. Prefer a candidate that is completely ready
    for ((candidatePath, source) in distinctCandidates) {
      if (candidatePath.exists() && candidatePath.isDirectory()) {
        val tools = inspectSdkRoot(candidatePath)
        if (tools.isComplete) {
          return SdkStatus.Ready(tools, source)
        }
      }
    }

    // 2. Otherwise check if any candidate has at least one SDK tool already present
    for ((candidatePath, source) in distinctCandidates) {
      if (candidatePath.exists() && candidatePath.isDirectory()) {
        val tools = inspectSdkRoot(candidatePath)
        if (tools.hasCmdlineTools || tools.hasEmulator || tools.hasPlatformTools) {
          return SdkStatus.Incomplete(tools, source)
        }
      }
    }

    return SdkStatus.NotFound(
      recommendedInstallPath = platform.defaultSdkRoot,
      searchedPaths = distinctCandidates.map { it.first },
    )
  }

  fun inspectSdkRoot(sdkRoot: Path): SdkToolsPaths {
    val platform = platformDetector.current
    val normalizedRoot = sdkRoot.toAbsolutePath().normalize()

    val sdkManagerName = platform.scriptName("sdkmanager")
    val avdManagerName = platform.scriptName("avdmanager")
    val emulatorName = platform.executableName("emulator")
    val adbName = platform.executableName("adb")

    val cmdlineBinDir = findCmdlineToolsBinDir(normalizedRoot, sdkManagerName)
    val sdkManagerBin = cmdlineBinDir?.resolve(sdkManagerName)?.takeIf { it.isRegularFile() }
    val avdManagerBin = cmdlineBinDir?.resolve(avdManagerName)?.takeIf { it.isRegularFile() }

    val emulatorBin =
      normalizedRoot.resolve("emulator").resolve(emulatorName).takeIf { it.isRegularFile() }
    val adbBin =
      normalizedRoot.resolve("platform-tools").resolve(adbName).takeIf { it.isRegularFile() }

    return SdkToolsPaths(
      sdkRoot = normalizedRoot,
      sdkManagerBin = sdkManagerBin,
      avdManagerBin = avdManagerBin,
      emulatorBin = emulatorBin,
      adbBin = adbBin,
    )
  }

  private fun findCmdlineToolsBinDir(sdkRoot: Path, sdkManagerScriptName: String): Path? {
    val cmdlineToolsDir = sdkRoot.resolve("cmdline-tools")
    if (cmdlineToolsDir.exists() && cmdlineToolsDir.isDirectory()) {
      // Check cmdline-tools/latest/bin first
      val latestBin = cmdlineToolsDir.resolve("latest/bin")
      if (latestBin.resolve(sdkManagerScriptName).isRegularFile()) {
        return latestBin
      }
      // Check versioned directories e.g. cmdline-tools/19.0/bin, sorted descending
      val versionDirs = runCatching {
        cmdlineToolsDir
          .listDirectoryEntries()
          .filter { it.isDirectory() && it.name != "latest" }
          .sortedByDescending { it.name }
      }
        .getOrDefault(emptyList())

      for (dir in versionDirs) {
        val bin = dir.resolve("bin")
        if (bin.resolve(sdkManagerScriptName).isRegularFile()) {
          return bin
        }
      }
      // Also check direct cmdline-tools/bin (e.g. Homebrew cask structure)
      val directBin = cmdlineToolsDir.resolve("bin")
      if (directBin.resolve(sdkManagerScriptName).isRegularFile()) {
        return directBin
      }
    }

    // Legacy tools/bin fallback
    val legacyBin = sdkRoot.resolve("tools/bin")
    if (legacyBin.resolve(sdkManagerScriptName).isRegularFile()) {
      return legacyBin
    }

    return null
  }

  private fun settingsFile(): Path =
    platformDetector.current.vikiConfigDir.resolve("settings.properties")

  fun loadPreferredSdkPath(): Path? {
    val file = settingsFile()
    if (!file.isRegularFile()) return null
    return runCatching {
      val props = Properties()
      Files.newInputStream(file).use { props.load(it) }
      props.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
    }
      .getOrNull()
  }

  fun savePreferredSdkPath(sdkRoot: Path) {
    runCatching {
      val configDir = platformDetector.current.vikiConfigDir
      Files.createDirectories(configDir)
      val file = settingsFile()
      val props = Properties()
      if (file.isRegularFile()) {
        Files.newInputStream(file).use { props.load(it) }
      }
      props.setProperty("sdk.dir", sdkRoot.toAbsolutePath().normalize().toString())
      Files.newOutputStream(file).use {
        props.store(it, "Viki Android Virtual Device Manager Settings")
      }
    }
  }
}
