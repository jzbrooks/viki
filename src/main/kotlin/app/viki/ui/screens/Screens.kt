package app.viki.ui.screens

import androidx.compose.runtime.Immutable
import app.viki.avd.AndroidReleasePreset
import app.viki.avd.AvdDevice
import app.viki.avd.DeviceFormFactor
import app.viki.avd.DevicePreset
import app.viki.avd.EmulatorRunStatus
import app.viki.sdk.SdkInstallProgress
import com.slack.circuit.runtime.CircuitUiEvent
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen

/**
 * Onboarding / SDK configuration screen shown when the Android SDK is missing or incomplete, or
 * when the designer opens SDK settings from the Device Manager toolbar.
 */
data class SdkSetupScreen(val allowAutoRedirect: Boolean = true) : Screen {
  @Immutable
  data class State(
    val osDisplayName: String,
    val archDisplayName: String,
    val targetSdkPath: String,
    val isReady: Boolean,
    val isIncomplete: Boolean,
    val missingComponents: List<String>,
    val searchedPaths: List<String>,
    val installProgress: SdkInstallProgress?,
    val errorMessage: String?,
    val canDismiss: Boolean,
    val eventSink: (Event) -> Unit,
  ) : CircuitUiState

  sealed interface Event : CircuitUiEvent {
    data object InstallOrRepairClicked : Event

    data class CustomPathSelected(val path: String) : Event

    data object ReScanClicked : Event

    data object ContinueToDevicesClicked : Event

    data object DismissError : Event
  }
}

/**
 * Main Android Studio-inspired Device Manager dashboard displaying installed virtual devices, their
 * runtime states, and quick launch / stop / cold boot actions.
 */
data object DeviceListScreen : Screen {
  @Immutable
  data class DeviceRowItem(
    val avd: AvdDevice,
    val runStatus: EmulatorRunStatus,
    val adbSerial: String?,
    val lastError: String?,
  )

  @Immutable
  data class State(
    val sdkPath: String,
    val hostArchLabel: String,
    val searchQuery: String,
    val selectedFormFactorFilter: DeviceFormFactor?,
    val devices: List<DeviceRowItem>,
    val totalDeviceCount: Int,
    val isLoading: Boolean,
    val eventSink: (Event) -> Unit,
  ) : CircuitUiState

  sealed interface Event : CircuitUiEvent {
    data class SearchQueryChanged(val query: String) : Event

    data class FormFactorFilterSelected(val formFactor: DeviceFormFactor?) : Event

    data class LaunchDevice(val avdId: String, val coldBoot: Boolean = false) : Event

    data class StopDevice(val avdId: String) : Event

    data class DismissDeviceError(val avdId: String) : Event

    data object RefreshClicked : Event

    data object CreateDeviceClicked : Event

    data object OpenSdkSettingsClicked : Event
  }
}

/**
 * Guided designer emulator creation flow: pick a curated hardware preset and Android version, and
 * Viki automatically downloads any missing system image and creates the AVD.
 */
data object CreateDeviceScreen : Screen {
  @Immutable
  data class ReleaseOption(
    val preset: AndroidReleasePreset,
    val isSystemImageDownloaded: Boolean,
  )

  @Immutable
  data class State(
    val selectedFormFactor: DeviceFormFactor,
    val availablePresets: List<DevicePreset>,
    val selectedPreset: DevicePreset,
    val releaseOptions: List<ReleaseOption>,
    val selectedRelease: ReleaseOption,
    val customDeviceName: String,
    val hostArchAbi: String,
    val creationProgress: SdkInstallProgress?,
    val errorMessage: String?,
    val eventSink: (Event) -> Unit,
  ) : CircuitUiState

  sealed interface Event : CircuitUiEvent {
    data class SelectFormFactor(val formFactor: DeviceFormFactor) : Event

    data class SelectDevicePreset(val preset: DevicePreset) : Event

    data class SelectRelease(val release: ReleaseOption) : Event

    data class UpdateDeviceName(val name: String) : Event

    data object CreateClicked : Event

    data object CancelClicked : Event

    data object DismissError : Event
  }
}
