package app.viki.ui.createdevice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.viki.avd.AvdRepository
import app.viki.avd.DesignerCatalog
import app.viki.avd.DeviceFormFactor
import app.viki.avd.DevicePreset
import app.viki.di.AppScope
import app.viki.sdk.HostPlatformDetector
import app.viki.sdk.SdkInstallProgress
import app.viki.ui.screens.CreateDeviceScreen
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.runtime.Navigator
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@CircuitInject(CreateDeviceScreen::class, AppScope::class)
@Inject
@Composable
fun CreateDevicePresenter(
  navigator: Navigator,
  platformDetector: HostPlatformDetector,
  avdRepository: AvdRepository,
): CreateDeviceScreen.State {
  val scope = rememberCoroutineScope()

  var selectedFormFactor by remember { mutableStateOf(DeviceFormFactor.PHONE) }
  val presetsForFormFactor =
    remember(selectedFormFactor) {
      DesignerCatalog.devicePresets.filter { it.formFactor == selectedFormFactor }
    }

  var selectedPreset by remember { mutableStateOf(DesignerCatalog.devicePresets.first()) }
  var refreshTrigger by remember { mutableStateOf(0) }

  val releaseOptions =
    remember(refreshTrigger) {
      DesignerCatalog.androidReleases.map { release ->
        CreateDeviceScreen.ReleaseOption(
          preset = release,
          isSystemImageDownloaded = avdRepository.isReleaseSystemImageInstalled(release),
        )
      }
    }

  var selectedRelease by remember { mutableStateOf(releaseOptions.first()) }

  var hasEditedCustomName by remember { mutableStateOf(false) }
  var customDeviceName by remember {
    mutableStateOf(defaultAvdDisplayName(selectedPreset, selectedRelease))
  }

  var creationProgress by remember { mutableStateOf<SdkInstallProgress?>(null) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  return CreateDeviceScreen.State(
    selectedFormFactor = selectedFormFactor,
    availablePresets = presetsForFormFactor,
    selectedPreset = selectedPreset,
    releaseOptions = releaseOptions,
    selectedRelease = selectedRelease,
    customDeviceName = customDeviceName,
    hostArchAbi = platformDetector.current.cpuArch.abiName,
    creationProgress = creationProgress,
    errorMessage = errorMessage,
  ) { event ->
    when (event) {
      is CreateDeviceScreen.Event.SelectFormFactor -> {
        selectedFormFactor = event.formFactor
        val firstPreset = DesignerCatalog.devicePresets.first { it.formFactor == event.formFactor }
        selectedPreset = firstPreset
        if (!hasEditedCustomName) {
          customDeviceName = defaultAvdDisplayName(firstPreset, selectedRelease)
        }
      }

      is CreateDeviceScreen.Event.SelectDevicePreset -> {
        selectedPreset = event.preset
        if (!hasEditedCustomName) {
          customDeviceName = defaultAvdDisplayName(event.preset, selectedRelease)
        }
      }

      is CreateDeviceScreen.Event.SelectRelease -> {
        selectedRelease = event.release
        if (!hasEditedCustomName) {
          customDeviceName = defaultAvdDisplayName(selectedPreset, event.release)
        }
      }

      is CreateDeviceScreen.Event.UpdateDeviceName -> {
        hasEditedCustomName = true
        customDeviceName = event.name
      }

      CreateDeviceScreen.Event.CreateClicked -> {
        if (creationProgress != null) return@State
        errorMessage = null
        scope.launch {
          avdRepository
            .createDesignerAvd(
              displayName =
                customDeviceName.ifBlank { defaultAvdDisplayName(selectedPreset, selectedRelease) },
              devicePreset = selectedPreset,
              releasePreset = selectedRelease.preset,
            )
            .catch { err ->
              errorMessage = err.message ?: "Failed to create virtual device."
              creationProgress = null
            }
            .collect { progress ->
              creationProgress = progress
              if (progress.fraction != null && progress.fraction >= 1.0f) {
                refreshTrigger++
                creationProgress = null
                navigator.pop()
              }
            }
        }
      }

      CreateDeviceScreen.Event.CancelClicked -> {
        if (creationProgress == null) {
          navigator.pop()
        }
      }

      CreateDeviceScreen.Event.DismissError -> {
        errorMessage = null
      }
    }
  }
}

private fun defaultAvdDisplayName(
  preset: DevicePreset,
  release: CreateDeviceScreen.ReleaseOption,
): String = "${preset.displayName} (Android ${release.preset.versionName})"
