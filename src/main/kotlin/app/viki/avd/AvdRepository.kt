package app.viki.avd

import app.viki.di.AppScope
import app.viki.sdk.AndroidSdkInstaller
import app.viki.sdk.AndroidSdkLocator
import app.viki.sdk.HostPlatformDetector
import app.viki.sdk.SdkInstallProgress
import app.viki.sdk.SdkStatus
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

data class AvdDevice(
  val avdId: String,
  val displayName: String,
  val deviceProfileName: String,
  val formFactor: DeviceFormFactor,
  val apiLevel: Int?,
  val androidReleaseLabel: String,
  val abi: String,
  val tagId: String,
  val widthPx: Int,
  val heightPx: Int,
  val densityDpi: Int,
  val diskSizeBytes: Long,
  val avdFolder: Path,
  val iniFile: Path,
) {
  val formattedDiskSize: String
    get() {
      val mb = diskSizeBytes / (1024.0 * 1024.0)
      return when {
        mb >= 1024.0 -> String.format("%.1f GB", mb / 1024.0)
        mb >= 1.0 -> String.format("%.0f MB", mb)
        else -> "< 1 MB"
      }
    }

  val resolutionLabel: String
    get() =
      if (widthPx > 0 && heightPx > 0) {
        val dpiSuffix = if (densityDpi > 0) " · ${densityDpi} dpi" else ""
        "${widthPx} × ${heightPx}$dpiSuffix"
      } else {
        "Standard resolution"
      }

  val googlePlayServicesBadge: String
    get() =
      when {
        "playstore" in tagId.lowercase() -> "Google Play"
        "google_apis" in tagId.lowercase() -> "Google APIs"
        else -> "AOSP"
      }
}

@SingleIn(AppScope::class)
@Inject
class AvdRepository(
  private val platformDetector: HostPlatformDetector,
  private val sdkLocator: AndroidSdkLocator,
  private val sdkInstaller: AndroidSdkInstaller,
) {
  private val _devices = MutableStateFlow<List<AvdDevice>>(emptyList())
  val devices: StateFlow<List<AvdDevice>> = _devices.asStateFlow()

  suspend fun refreshDevices(): List<AvdDevice> =
    withContext(Dispatchers.IO) {
      val parsed = loadAvdsFromDirectory(platformDetector.current.avdHomeDir)
      _devices.value = parsed
      parsed
    }

  fun isReleaseSystemImageInstalled(release: AndroidReleasePreset): Boolean {
    val status = sdkLocator.sdkStatus.value
    val sdkRoot =
      when (status) {
        is SdkStatus.Ready -> status.tools.sdkRoot
        is SdkStatus.Incomplete -> status.tools.sdkRoot
        is SdkStatus.NotFound -> return false
      }
    val arch = platformDetector.current.cpuArch
    return sdkInstaller.isPackageInstalledOnDisk(
      sdkRoot,
      release.preferredSystemImagePackage(arch),
    ) || sdkInstaller.isPackageInstalledOnDisk(sdkRoot, release.fallbackSystemImagePackage(arch))
  }

  /**
   * Guided designer workflow:
   * 1. Ensures the platform (`platforms;android-<api>`) and system image are installed.
   * 2. Invokes `avdmanager create avd` with the matching device profile.
   * 3. Applies designer-friendly hardware defaults to `<avd>.avd/config.ini`.
   */
  fun createDesignerAvd(
    displayName: String,
    devicePreset: DevicePreset,
    releasePreset: AndroidReleasePreset,
  ): Flow<SdkInstallProgress> = flow {
    val status = sdkLocator.sdkStatus.value
    val tools =
      (status as? SdkStatus.Ready)?.tools
        ?: error("Android SDK is not ready. Please complete SDK setup first.")
    val avdManagerBin =
      tools.avdManagerBin ?: error("avdmanager binary was not found in ${tools.sdkRoot}")

    val hostArch = platformDetector.current.cpuArch
    val preferredImagePkg = releasePreset.preferredSystemImagePackage(hostArch)
    val fallbackImagePkg = releasePreset.fallbackSystemImagePackage(hostArch)

    val chosenSystemImagePkg =
      when {
        sdkInstaller.isPackageInstalledOnDisk(tools.sdkRoot, preferredImagePkg) -> preferredImagePkg
        sdkInstaller.isPackageInstalledOnDisk(tools.sdkRoot, fallbackImagePkg) -> fallbackImagePkg
        else -> preferredImagePkg
      }

    val packagesNeeded = buildList {
      if (!sdkInstaller.isPackageInstalledOnDisk(tools.sdkRoot, releasePreset.platformPackage)) {
        add(releasePreset.platformPackage)
      }
      if (!sdkInstaller.isPackageInstalledOnDisk(tools.sdkRoot, chosenSystemImagePkg)) {
        add(chosenSystemImagePkg)
      }
    }

    var finalSystemImagePkg = chosenSystemImagePkg

    if (packagesNeeded.isNotEmpty()) {
      val downloadResult = runCatching {
        emitAll(
          sdkInstaller.installSdkPackages(
            packages = packagesNeeded,
            stageTitle =
              "Downloading ${releasePreset.displayTitle} System Image (${hostArch.abiName})",
          )
        )
      }
      if (downloadResult.isFailure && chosenSystemImagePkg == preferredImagePkg) {
        // Fallback to google_apis if google_apis_playstore is unavailable for a preview API level
        finalSystemImagePkg = fallbackImagePkg
        val fallbackPackages = listOf(releasePreset.platformPackage, fallbackImagePkg)
        emitAll(
          sdkInstaller.installSdkPackages(
            packages = fallbackPackages,
            stageTitle =
              "Downloading ${releasePreset.displayTitle} Google APIs Image (${hostArch.abiName})",
          )
        )
      } else if (downloadResult.isFailure) {
        throw downloadResult.exceptionOrNull()!!
      }
    }

    emit(
      SdkInstallProgress(
        stageTitle = "Creating Virtual Device",
        detailMessage = "Resolving hardware profile for ${devicePreset.displayName}...",
        fraction = 0.90f,
      )
    )

    val avdId = sanitizeAvdId(displayName)
    val availableDevices = queryAvailableAvdDeviceIds(tools.sdkRoot, avdManagerBin)
    val resolvedDeviceId =
      devicePreset.avdManagerDeviceIds.firstOrNull { it in availableDevices }
        ?: availableDevices.firstOrNull { it.contains("pixel", ignoreCase = true) }

    emit(
      SdkInstallProgress(
        stageTitle = "Creating Virtual Device",
        detailMessage = "Running avdmanager for \"$displayName\" ($avdId)...",
        fraction = 0.94f,
      )
    )

    val command = buildList {
      add(avdManagerBin.toString())
      add("create")
      add("avd")
      add("-n")
      add(avdId)
      add("-k")
      add(finalSystemImagePkg)
      if (resolvedDeviceId != null) {
        add("-d")
        add(resolvedDeviceId)
      }
      add("--force")
    }

    val process = sdkInstaller.createSdkToolProcessBuilder(tools.sdkRoot, command).start()
    OutputStreamWriter(process.outputStream).use { writer ->
      writer.write("no\n")
      writer.flush()
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (exitCode != 0) {
      error("Failed to create AVD (exit code $exitCode):\n${output.trim()}")
    }

    emit(
      SdkInstallProgress(
        stageTitle = "Configuring Hardware Profile",
        detailMessage =
          "Applying display dimensions (${devicePreset.widthPx}×${devicePreset.heightPx}) & GPU settings...",
        fraction = 0.98f,
      )
    )

    val avdHome = platformDetector.current.avdHomeDir
    val avdFolder = resolveAvdFolder(avdHome, avdId)
    val configIni = avdFolder.resolve("config.ini")
    applyDesignerConfigOverrides(
      configIniPath = configIni,
      avdId = avdId,
      displayName =
        displayName.trim().ifBlank { "${devicePreset.displayName} API ${releasePreset.apiLevel}" },
      devicePreset = devicePreset,
    )

    refreshDevices()

    emit(
      SdkInstallProgress(
        stageTitle = "Virtual Device Created",
        detailMessage = "$displayName is ready to launch.",
        fraction = 1.0f,
      )
    )
  }
    .flowOn(Dispatchers.IO)

  private fun queryAvailableAvdDeviceIds(sdkRoot: Path, avdManagerBin: Path): Set<String> =
    runCatching {
      val process =
        sdkInstaller
          .createSdkToolProcessBuilder(
            sdkRoot = sdkRoot,
            command = listOf(avdManagerBin.toString(), "list", "device", "-c"),
          )
          .start()
      val output = process.inputStream.bufferedReader().use { it.readLines() }
      process.waitFor()
      output.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    .getOrDefault(emptySet())

  private fun resolveAvdFolder(avdHome: Path, avdId: String): Path {
    val rootIni = avdHome.resolve("$avdId.ini")
    if (rootIni.isRegularFile()) {
      val rootMap = parseKeyValueIni(rootIni)
      rootMap["path"]?.let { pathStr ->
        val p = Paths.get(pathStr)
        if (p.exists()) return p
      }
    }
    return avdHome.resolve("$avdId.avd")
  }

  internal fun applyDesignerConfigOverrides(
    configIniPath: Path,
    avdId: String,
    displayName: String,
    devicePreset: DevicePreset,
  ) {
    val entries =
      if (configIniPath.isRegularFile()) {
        parseKeyValueIni(configIniPath).toMutableMap()
      } else {
        linkedMapOf()
      }

    entries["AvdId"] = avdId
    entries["avd.ini.displayname"] = displayName
    entries["hw.device.name"] = entries["hw.device.name"] ?: devicePreset.id
    entries["hw.lcd.width"] = devicePreset.widthPx.toString()
    entries["hw.lcd.height"] = devicePreset.heightPx.toString()
    entries["hw.lcd.density"] = devicePreset.densityDpi.toString()
    entries["hw.keyboard"] = "yes"
    entries["hw.gpu.enabled"] = "yes"
    entries["hw.gpu.mode"] = "auto"
    entries["hw.ramSize"] = "4096"
    entries["disk.dataPartition.size"] = "6442450944"
    entries["showDeviceFrame"] = "yes"

    Files.createDirectories(configIniPath.parent)
    val content = entries.entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k=$v" }
    Files.writeString(configIniPath, content, StandardCharsets.UTF_8)
  }

  internal fun loadAvdsFromDirectory(avdHomeDir: Path): List<AvdDevice> {
    if (!avdHomeDir.exists() || !avdHomeDir.isDirectory()) {
      return emptyList()
    }

    val iniFiles = runCatching {
      avdHomeDir.listDirectoryEntries("*.ini")
    }
      .getOrDefault(emptyList())

    return iniFiles
      .mapNotNull { iniFile ->
        runCatching { parseAvdFromIniFile(avdHomeDir, iniFile) }.getOrNull()
      }
      .sortedBy { it.displayName.lowercase() }
  }

  internal fun parseAvdFromIniFile(avdHomeDir: Path, iniFile: Path): AvdDevice? {
    val rootProps = parseKeyValueIni(iniFile)
    val avdId = iniFile.nameWithoutExtension
    val avdFolder =
      rootProps["path"]?.let { Paths.get(it) }
        ?: rootProps["path.rel"]?.let { avdHomeDir.resolve(it) }
        ?: avdHomeDir.resolve("$avdId.avd")

    if (!avdFolder.exists() || !avdFolder.isDirectory()) {
      return null
    }

    val configIni = avdFolder.resolve("config.ini")
    val configProps =
      if (configIni.isRegularFile()) {
        parseKeyValueIni(configIni)
      } else {
        emptyMap()
      }

    val rawDisplayName =
      configProps["avd.ini.displayname"]?.takeIf { it.isNotBlank() } ?: avdId.replace('_', ' ')

    val deviceProfileName = configProps["hw.device.name"]?.takeIf { it.isNotBlank() } ?: "pixel"

    val sysDir = configProps["image.sysdir.1"].orEmpty()
    val targetProp = rootProps["target"].orEmpty()
    val apiLevel = extractApiLevel(sysDir, targetProp)

    val abi =
      configProps["abi.type"]
        ?: extractAbiFromSysDir(sysDir)
        ?: platformDetector.current.cpuArch.abiName

    val tagId = configProps["tag.id"] ?: extractTagFromSysDir(sysDir) ?: "google_apis"

    val widthPx = configProps["hw.lcd.width"]?.toIntOrNull() ?: 1080
    val heightPx = configProps["hw.lcd.height"]?.toIntOrNull() ?: 2400
    val densityDpi = configProps["hw.lcd.density"]?.toIntOrNull() ?: 420

    val formFactor =
      DesignerCatalog.inferFormFactor(
        deviceName = "$deviceProfileName $rawDisplayName",
        widthPx = widthPx,
        heightPx = heightPx,
      )

    val diskBytes = computeDirectorySizeBytes(avdFolder)

    return AvdDevice(
      avdId = avdId,
      displayName = rawDisplayName,
      deviceProfileName = deviceProfileName,
      formFactor = formFactor,
      apiLevel = apiLevel,
      androidReleaseLabel = DesignerCatalog.releaseNameForApi(apiLevel),
      abi = abi,
      tagId = tagId,
      widthPx = widthPx,
      heightPx = heightPx,
      densityDpi = densityDpi,
      diskSizeBytes = diskBytes,
      avdFolder = avdFolder,
      iniFile = iniFile,
    )
  }

  internal fun parseKeyValueIni(path: Path): Map<String, String> {
    val result = linkedMapOf<String, String>()
    if (!path.isRegularFile()) return result
    Files.readAllLines(path, StandardCharsets.UTF_8).forEach { rawLine ->
      val line = rawLine.trim()
      if (line.isNotEmpty() && !line.startsWith("#") && !line.startsWith(";")) {
        val eqIdx = line.indexOf('=')
        if (eqIdx > 0) {
          val key = line.substring(0, eqIdx).trim()
          val value = line.substring(eqIdx + 1).trim()
          result[key] = value
        }
      }
    }
    return result
  }

  internal fun sanitizeAvdId(displayName: String): String {
    val cleaned =
      displayName
        .trim()
        .replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
        .replace(Regex("""_+"""), "_")
        .trim('_')
    return cleaned.ifBlank { "Viki_Emulator" }
  }

  private fun extractApiLevel(sysDir: String, targetProp: String): Int? {
    val match = API_LEVEL_REGEX.find(sysDir) ?: API_LEVEL_REGEX.find(targetProp)
    return match?.groupValues?.getOrNull(1)?.toIntOrNull()
  }

  private fun extractAbiFromSysDir(sysDir: String): String? {
    val parts = sysDir.replace('\\', '/').trim('/').split('/')
    return parts.lastOrNull()?.takeIf { it.isNotBlank() }
  }

  private fun extractTagFromSysDir(sysDir: String): String? {
    val parts = sysDir.replace('\\', '/').trim('/').split('/')
    return if (parts.size >= 2) parts[parts.size - 2] else null
  }

  private fun computeDirectorySizeBytes(dir: Path): Long = runCatching {
    Files.walk(dir, 2).use { stream ->
      stream
        .filter { Files.isRegularFile(it) }
        .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }
        .sum()
    }
  }
    .getOrDefault(0L)

  companion object {
    private val API_LEVEL_REGEX = Regex("""android-(\d+)""")
  }
}
