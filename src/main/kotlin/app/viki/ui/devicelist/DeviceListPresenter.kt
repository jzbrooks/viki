package app.viki.ui.devicelist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.viki.avd.AvdRepository
import app.viki.avd.DeviceFormFactor
import app.viki.avd.EmulatorProcessManager
import app.viki.avd.EmulatorRunStatus
import app.viki.di.AppScope
import app.viki.sdk.AndroidSdkLocator
import app.viki.sdk.HostPlatformDetector
import app.viki.sdk.SdkStatus
import app.viki.ui.screens.CreateDeviceScreen
import app.viki.ui.screens.DeviceListScreen
import app.viki.ui.screens.SdkSetupScreen
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.runtime.Navigator
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.launch

@CircuitInject(DeviceListScreen::class, AppScope::class)
@Inject
@Composable
fun DeviceListPresenter(
  navigator: Navigator,
  platformDetector: HostPlatformDetector,
  sdkLocator: AndroidSdkLocator,
  avdRepository: AvdRepository,
  emulatorManager: EmulatorProcessManager,
): DeviceListScreen.State {
  val sdkStatus by sdkLocator.sdkStatus.collectAsState()
  val avdList by avdRepository.devices.collectAsState()
  val emulatorStates by emulatorManager.states.collectAsState()
  val scope = rememberCoroutineScope()

  var searchQuery by remember { mutableStateOf("") }
  var selectedFormFactor by remember { mutableStateOf<DeviceFormFactor?>(null) }
  var isLoading by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    isLoading = true
    avdRepository.refreshDevices()
    emulatorManager.pollRunningEmulators()
    isLoading = false
  }

  val sdkPath =
    when (val status = sdkStatus) {
      is SdkStatus.Ready -> status.tools.sdkRoot.toString()
      is SdkStatus.Incomplete -> status.tools.sdkRoot.toString()
      is SdkStatus.NotFound -> status.recommendedInstallPath.toString()
    }

  val filteredRows =
    remember(avdList, emulatorStates, searchQuery, selectedFormFactor) {
      val q = searchQuery.trim().lowercase()
      avdList
        .filter { avd ->
          val matchesFormFactor = selectedFormFactor == null || avd.formFactor == selectedFormFactor
          val matchesQuery =
            q.isEmpty() ||
              avd.displayName.lowercase().contains(q) ||
              avd.androidReleaseLabel.lowercase().contains(q) ||
              avd.avdId.lowercase().contains(q)
          matchesFormFactor && matchesQuery
        }
        .map { avd ->
          val instance = emulatorStates[avd.avdId]
          DeviceListScreen.DeviceRowItem(
            avd = avd,
            runStatus = instance?.status ?: EmulatorRunStatus.STOPPED,
            adbSerial = instance?.adbSerial,
            lastError = instance?.lastError,
          )
        }
    }

  return DeviceListScreen.State(
    sdkPath = sdkPath,
    hostArchLabel = platformDetector.current.cpuArch.abiName,
    searchQuery = searchQuery,
    selectedFormFactorFilter = selectedFormFactor,
    devices = filteredRows,
    totalDeviceCount = avdList.size,
    isLoading = isLoading,
  ) { event ->
    when (event) {
      is DeviceListScreen.Event.SearchQueryChanged -> {
        searchQuery = event.query
      }

      is DeviceListScreen.Event.FormFactorFilterSelected -> {
        selectedFormFactor = event.formFactor
      }

      is DeviceListScreen.Event.LaunchDevice -> {
        scope.launch { emulatorManager.launchAvd(avdId = event.avdId, coldBoot = event.coldBoot) }
      }

      is DeviceListScreen.Event.StopDevice -> {
        scope.launch { emulatorManager.stopAvd(avdId = event.avdId) }
      }

      is DeviceListScreen.Event.DismissDeviceError -> {
        emulatorManager.clearError(event.avdId)
      }

      DeviceListScreen.Event.RefreshClicked -> {
        scope.launch {
          isLoading = true
          avdRepository.refreshDevices()
          emulatorManager.pollRunningEmulators()
          isLoading = false
        }
      }

      DeviceListScreen.Event.CreateDeviceClicked -> {
        navigator.goTo(CreateDeviceScreen)
      }

      DeviceListScreen.Event.OpenSdkSettingsClicked -> {
        navigator.goTo(SdkSetupScreen(allowAutoRedirect = false))
      }
    }
  }
}
