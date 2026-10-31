package app.viki.ui.sdksetup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.viki.di.AppScope
import app.viki.sdk.AndroidSdkInstaller
import app.viki.sdk.AndroidSdkLocator
import app.viki.sdk.HostPlatformDetector
import app.viki.sdk.SdkInstallProgress
import app.viki.sdk.SdkStatus
import app.viki.ui.screens.DeviceListScreen
import app.viki.ui.screens.SdkSetupScreen
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.runtime.Navigator
import dev.zacsweers.metro.Inject
import java.nio.file.Paths
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@CircuitInject(SdkSetupScreen::class, AppScope::class)
@Inject
@Composable
fun SdkSetupPresenter(
  screen: SdkSetupScreen,
  navigator: Navigator,
  platformDetector: HostPlatformDetector,
  sdkLocator: AndroidSdkLocator,
  sdkInstaller: AndroidSdkInstaller,
): SdkSetupScreen.State {
  val sdkStatus by sdkLocator.sdkStatus.collectAsState()
  val scope = rememberCoroutineScope()
  val platform = platformDetector.current

  var installProgress by remember { mutableStateOf<SdkInstallProgress?>(null) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  val targetSdkPath =
    when (val status = sdkStatus) {
      is SdkStatus.Ready -> status.tools.sdkRoot
      is SdkStatus.Incomplete -> status.tools.sdkRoot
      is SdkStatus.NotFound -> status.recommendedInstallPath
    }

  val isReady = sdkStatus is SdkStatus.Ready
  val isIncomplete = sdkStatus is SdkStatus.Incomplete

  val missingComponents =
    when (val status = sdkStatus) {
      is SdkStatus.Ready -> emptyList()
      is SdkStatus.Incomplete -> status.tools.missingComponents
      is SdkStatus.NotFound ->
        listOf(
          "Android Command-Line Tools",
          "Android SDK Platform-Tools (adb)",
          "Android Emulator",
        )
    }

  val searchedPaths =
    when (val status = sdkStatus) {
      is SdkStatus.NotFound -> status.searchedPaths.map { it.toString() }
      else -> platform.commonSdkCandidatePaths.map { it.toString() }
    }

  // Automatically navigate to DeviceListScreen on startup if SDK is already ready
  LaunchedEffect(isReady, screen.allowAutoRedirect) {
    if (isReady && screen.allowAutoRedirect && installProgress == null) {
      navigator.resetRoot(DeviceListScreen)
    }
  }

  return SdkSetupScreen.State(
    osDisplayName = platform.osFamily.displayName,
    archDisplayName = platform.cpuArch.displayName,
    targetSdkPath = targetSdkPath.toString(),
    isReady = isReady,
    isIncomplete = isIncomplete,
    missingComponents = missingComponents,
    searchedPaths = searchedPaths,
    installProgress = installProgress,
    errorMessage = errorMessage,
    canDismiss = isReady,
  ) { event ->
    when (event) {
      SdkSetupScreen.Event.InstallOrRepairClicked -> {
        if (installProgress != null) return@State
        errorMessage = null
        scope.launch {
          sdkInstaller
            .installOrRepairSdk(targetSdkPath)
            .catch { err ->
              errorMessage = err.message ?: "Failed to install Android SDK."
              installProgress = null
            }
            .collect { progress ->
              installProgress = progress
              if (progress.fraction != null && progress.fraction >= 1.0f) {
                installProgress = null
                navigator.resetRoot(DeviceListScreen)
              }
            }
        }
      }

      is SdkSetupScreen.Event.CustomPathSelected -> {
        errorMessage = null
        val path = runCatching { Paths.get(event.path.trim()) }.getOrNull()
        if (path != null) {
          val updated = sdkLocator.setCustomSdkRoot(path)
          if (updated is SdkStatus.Ready && screen.allowAutoRedirect) {
            navigator.resetRoot(DeviceListScreen)
          }
        } else {
          errorMessage = "Invalid directory path: ${event.path}"
        }
      }

      SdkSetupScreen.Event.ReScanClicked -> {
        errorMessage = null
        val refreshed = sdkLocator.refresh()
        if (refreshed is SdkStatus.Ready && screen.allowAutoRedirect) {
          navigator.resetRoot(DeviceListScreen)
        }
      }

      SdkSetupScreen.Event.ContinueToDevicesClicked -> {
        if (sdkLocator.sdkStatus.value is SdkStatus.Ready) {
          navigator.resetRoot(DeviceListScreen)
        }
      }

      SdkSetupScreen.Event.DismissError -> {
        errorMessage = null
      }
    }
  }
}
