package app.viki

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import app.viki.avd.EmulatorRunStatus
import app.viki.di.VikiAppGraph
import app.viki.resources.Res
import app.viki.resources.android
import app.viki.sdk.SdkStatus
import app.viki.ui.screens.CreateDeviceScreen
import app.viki.ui.screens.DeviceListScreen
import app.viki.ui.screens.SdkSetupScreen
import app.viki.ui.theme.StudioSecondary
import app.viki.ui.theme.VikiTheme
import com.slack.circuit.backstack.rememberSaveableBackStack
import com.slack.circuit.foundation.CircuitCompositionLocals
import com.slack.circuit.foundation.NavigableCircuitContent
import com.slack.circuit.foundation.rememberCircuitNavigator
import com.slack.circuit.runtime.screen.Screen
import dev.zacsweers.metro.createGraph
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource

fun main() = application {
  val appGraph = remember { createGraph<VikiAppGraph>() }
  val devices by appGraph.avdRepository.devices.collectAsState()
  val emulatorStates by appGraph.emulatorProcessManager.states.collectAsState()

  var isWindowVisible by remember { mutableStateOf(true) }
  var pendingScreen by remember { mutableStateOf<Screen?>(null) }

  val windowState =
    rememberWindowState(
      size = DpSize(1160.dp, 760.dp),
      position = WindowPosition.Aligned(Alignment.Center),
    )

  LaunchedEffect(Unit) {
    appGraph.avdRepository.refreshDevices()
    appGraph.emulatorProcessManager.pollRunningEmulators()
  }

  if (isTraySupported) {
    val trayState = rememberTrayState()
    val trayIcon = rememberTrayIconPainter()

    Tray(
      state = trayState,
      icon = trayIcon,
      tooltip = "Viki Device Manager",
      onAction = {
        isWindowVisible = true
        windowState.isMinimized = false
      },
      menu = {
        Item(
          text = "Open Viki Device Manager",
          onClick = {
            isWindowVisible = true
            windowState.isMinimized = false
          },
        )
        Item(
          text = "Create Virtual Device...",
          onClick = {
            isWindowVisible = true
            windowState.isMinimized = false
            pendingScreen =
              if (appGraph.sdkLocator.sdkStatus.value is SdkStatus.Ready) {
                CreateDeviceScreen
              } else {
                SdkSetupScreen(allowAutoRedirect = true)
              }
          },
        )
        Separator()
        if (devices.isEmpty()) {
          Item(
            text = "No Virtual Devices Created",
            enabled = false,
            onClick = {},
          )
        } else {
          devices.forEach { device ->
            val status = emulatorStates[device.avdId]?.status ?: EmulatorRunStatus.STOPPED
            when (status) {
              EmulatorRunStatus.STOPPED -> {
                Item(
                  text = "Launch ${device.displayName}",
                  onClick = {
                    appGraph.appCoroutineScope.launch {
                      appGraph.emulatorProcessManager.launchAvd(device.avdId)
                    }
                  },
                )
              }

              EmulatorRunStatus.BOOTING -> {
                Item(
                  text = "${device.displayName} (Starting...)",
                  enabled = false,
                  onClick = {},
                )
                Item(
                  text = "Stop ${device.displayName}",
                  onClick = {
                    appGraph.appCoroutineScope.launch {
                      appGraph.emulatorProcessManager.stopAvd(device.avdId)
                    }
                  },
                )
              }

              EmulatorRunStatus.RUNNING -> {
                Item(
                  text = "Stop ${device.displayName} (Running)",
                  onClick = {
                    appGraph.appCoroutineScope.launch {
                      appGraph.emulatorProcessManager.stopAvd(device.avdId)
                    }
                  },
                )
              }
            }
          }
        }
        Separator()
        Item("Quit Viki", onClick = ::exitApplication)
      },
    )
  }

  if (isWindowVisible) {
    Window(
      onCloseRequest = {
        if (isTraySupported) {
          isWindowVisible = false
        } else {
          exitApplication()
        }
      },
      state = windowState,
      title = "Viki — Android Virtual Device Manager",
    ) {
      VikiTheme {
        CircuitCompositionLocals(appGraph.circuit) {
          val backStack = rememberSaveableBackStack(root = SdkSetupScreen(allowAutoRedirect = true))
          val navigator =
            rememberCircuitNavigator(backStack) {
              if (isTraySupported) {
                isWindowVisible = false
              } else {
                exitApplication()
              }
            }

          LaunchedEffect(pendingScreen) {
            val target = pendingScreen ?: return@LaunchedEffect
            pendingScreen = null
            if (target is CreateDeviceScreen) {
              if (backStack.topRecord?.screen !is CreateDeviceScreen) {
                if (backStack.topRecord?.screen !is DeviceListScreen) {
                  navigator.resetRoot(DeviceListScreen)
                }
                navigator.goTo(CreateDeviceScreen)
              }
            } else {
              navigator.resetRoot(target)
            }
          }

          NavigableCircuitContent(
            navigator = navigator,
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
    }
  }
}

@Composable
private fun rememberTrayIconPainter(): Painter {
  val vectorPainter = rememberVectorPainter(vectorResource(Res.drawable.android))
  return remember(vectorPainter) {
    object : Painter() {
      override val intrinsicSize = vectorPainter.intrinsicSize

      override fun DrawScope.onDraw() {
        with(vectorPainter) {
          draw(
            size = size,
            alpha = 1f,
            colorFilter = ColorFilter.tint(StudioSecondary),
          )
        }
      }
    }
  }
}
