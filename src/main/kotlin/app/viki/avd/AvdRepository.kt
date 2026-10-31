package app.viki.avd

import app.viki.di.AppScope
import app.viki.sdk.AndroidSdkInstaller
import app.viki.sdk.AndroidSdkLocator
import app.viki.sdk.HostPlatformDetector
import app.viki.sdk.SdkInstallProgress
import app.viki.sdk.SdkStatus
import com.android.prefs.AndroidLocationsSingleton
import com.android.repository.api.ProgressIndicatorAdapter
import com.android.sdklib.devices.DeviceManager
import com.android.sdklib.internal.avd.AvdInfo
import com.android.sdklib.internal.avd.AvdManager
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.utils.NullLogger
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
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
  private val logger = NullLogger()

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
   * 2. Creates the AVD with the matching device profile.
   * 3. Applies designer-friendly hardware defaults to its `config.ini`.
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
    val sdkHandler = AndroidSdkHandler.getInstance(AndroidLocationsSingleton, tools.sdkRoot)
    val deviceManager = DeviceManager.createInstance(sdkHandler, logger)
    val device =
      devicePreset.avdManagerDeviceIds.firstNotNullOfOrNull {
        deviceManager.getDevice(it, "Google")
      }
        ?: deviceManager.getDevices(DeviceManager.DeviceCategory.DEFAULT).firstOrNull {
          it.id.contains("pixel", ignoreCase = true)
        }
        ?: error("No Pixel hardware profile is available in this SDK")
    val systemImage =
      sdkHandler.getSystemImageManager(NoOpProgress).images.firstOrNull {
        it.`package`.path == finalSystemImagePkg
      } ?: error("System image $finalSystemImagePkg is not installed")

    emit(
      SdkInstallProgress(
        stageTitle = "Creating Virtual Device",
        detailMessage = "Creating \"$displayName\" ($avdId)...",
        fraction = 0.94f,
      )
    )

    val avdManager =
      AvdManager.createInstance(
        sdkHandler,
        platformDetector.current.avdHomeDir,
        deviceManager,
        logger,
      )
    avdManager.getAvd(avdId, false)?.let { avdManager.deleteAvd(it) }
    val avd =
      avdManager.createAvd(
        avdManager.createAvdBuilder(device).apply {
          avdName = avdId
          this.displayName =
            displayName.trim().ifBlank {
              "${devicePreset.displayName} API ${releasePreset.apiLevel}"
            }
          this.systemImage = systemImage
        }
      )

    emit(
      SdkInstallProgress(
        stageTitle = "Configuring Hardware Profile",
        detailMessage =
          "Applying display dimensions (${devicePreset.widthPx}×${devicePreset.heightPx}) & GPU settings...",
        fraction = 0.98f,
      )
    )

    // updateAvd replaces config.ini wholesale, so merge onto the existing properties.
    avdManager.updateAvd(avd, avd.properties + designerHardwareConfig(devicePreset))

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

  internal fun designerHardwareConfig(devicePreset: DevicePreset): Map<String, String> =
    mapOf(
      "hw.lcd.width" to devicePreset.widthPx.toString(),
      "hw.lcd.height" to devicePreset.heightPx.toString(),
      "hw.lcd.density" to devicePreset.densityDpi.toString(),
      "hw.keyboard" to "yes",
      "hw.gpu.enabled" to "yes",
      "hw.gpu.mode" to "auto",
      "hw.ramSize" to "4096",
      "disk.dataPartition.size" to "6442450944",
      "showDeviceFrame" to "yes",
    )

  internal fun avdManager(avdHomeDir: Path): AvdManager {
    val sdkHandler = AndroidSdkHandler.getInstance(AndroidLocationsSingleton, currentSdkRoot())
    return AvdManager.createInstance(
      sdkHandler,
      avdHomeDir,
      DeviceManager.createInstance(sdkHandler, logger),
      logger,
    )
  }

  internal fun loadAvdsFromDirectory(avdHomeDir: Path): List<AvdDevice> {
    if (!avdHomeDir.isDirectory()) return emptyList()
    return avdManager(avdHomeDir)
      .allAvds
      .map { it.toAvdDevice() }
      .sortedBy { it.displayName.lowercase() }
  }

  private fun currentSdkRoot(): Path? =
    when (val status = sdkLocator.sdkStatus.value) {
      is SdkStatus.Ready -> status.tools.sdkRoot
      is SdkStatus.Incomplete -> status.tools.sdkRoot
      is SdkStatus.NotFound -> null
    }

  private fun AvdInfo.toAvdDevice(): AvdDevice {
    val widthPx = properties["hw.lcd.width"]?.toIntOrNull() ?: 1080
    val heightPx = properties["hw.lcd.height"]?.toIntOrNull() ?: 2400
    val deviceProfileName = deviceName.ifBlank { "pixel" }
    val apiLevel = androidVersion.apiLevel.takeIf { it > 1 }
    return AvdDevice(
      avdId = name,
      displayName = displayName,
      deviceProfileName = deviceProfileName,
      formFactor =
        DesignerCatalog.inferFormFactor("$deviceProfileName $displayName", widthPx, heightPx),
      apiLevel = apiLevel,
      androidReleaseLabel = DesignerCatalog.releaseNameForApi(apiLevel),
      abi = abiType?.takeIf { it.isNotBlank() } ?: platformDetector.current.cpuArch.abiName,
      tagId = tag.id,
      widthPx = widthPx,
      heightPx = heightPx,
      densityDpi = properties["hw.lcd.density"]?.toIntOrNull() ?: 420,
      diskSizeBytes = computeDirectorySizeBytes(dataFolderPath),
      avdFolder = dataFolderPath,
      iniFile = iniFile,
    )
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

  private fun computeDirectorySizeBytes(dir: Path): Long = runCatching {
    Files.walk(dir, 2).use { stream ->
      stream
        .filter { Files.isRegularFile(it) }
        .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }
        .sum()
    }
  }
    .getOrDefault(0L)
}

private object NoOpProgress : ProgressIndicatorAdapter()
