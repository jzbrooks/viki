package app.viki.ui.sdksetup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.viki.di.AppScope
import app.viki.resources.Res
import app.viki.resources.android
import app.viki.resources.check_circle
import app.viki.resources.cloud_download
import app.viki.resources.error
import app.viki.resources.folder_open
import app.viki.resources.refresh
import app.viki.resources.warning
import app.viki.ui.screens.SdkSetupScreen
import app.viki.ui.theme.StudioBorder
import app.viki.ui.theme.StudioError
import app.viki.ui.theme.StudioSecondary
import app.viki.ui.theme.StudioSurfaceElevated
import app.viki.ui.theme.StudioWarning
import com.slack.circuit.codegen.annotations.CircuitInject
import dev.zacsweers.metro.Inject
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import org.jetbrains.compose.resources.vectorResource

@CircuitInject(SdkSetupScreen::class, AppScope::class)
@Inject
@Composable
fun SdkSetupUi(
  state: SdkSetupScreen.State,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
    contentAlignment = Alignment.Center,
  ) {
    Card(
      modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.dp, StudioBorder),
    ) {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        // Header
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Box(
            modifier =
              Modifier.size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = vectorResource(Res.drawable.android),
              contentDescription = null,
              tint = StudioSecondary,
              modifier = Modifier.size(30.dp),
            )
          }
          Column {
            Text(
              text = "Welcome to Viki Virtual Device Studio",
              style = MaterialTheme.typography.headlineMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = "Detected ${state.osDisplayName} · ${state.archDisplayName}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        // Status Banner
        Surface(
          color = StudioSurfaceElevated,
          shape = MaterialTheme.shapes.medium,
          border = BorderStroke(1.dp, StudioBorder),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              when {
                state.isReady -> {
                  Icon(
                    imageVector = vectorResource(Res.drawable.check_circle),
                    contentDescription = null,
                    tint = StudioSecondary,
                    modifier = Modifier.size(20.dp),
                  )
                  Text(
                    text = "Android SDK Ready",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioSecondary,
                  )
                }

                state.isIncomplete -> {
                  Icon(
                    imageVector = vectorResource(Res.drawable.warning),
                    contentDescription = null,
                    tint = StudioWarning,
                    modifier = Modifier.size(20.dp),
                  )
                  Text(
                    text = "Partial Android SDK Found — Missing Tools Needed",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioWarning,
                  )
                }

                else -> {
                  Icon(
                    imageVector = vectorResource(Res.drawable.cloud_download),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                  )
                  Text(
                    text = "No Android SDK Found on This Computer",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                }
              }
            }

            Text(
              text =
                if (state.isReady) {
                  "All required emulator and SDK tools are installed and ready to launch virtual devices."
                } else {
                  "Viki can automatically download and configure the official Android Command-Line Tools, Platform-Tools (adb), and Android Emulator in the standard ${state.osDisplayName} SDK location—no Android Studio or separate JDK setup required."
                },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
              text = "SDK Location:",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
              color = MaterialTheme.colorScheme.background,
              shape = MaterialTheme.shapes.small,
              border = BorderStroke(1.dp, StudioBorder),
            ) {
              Text(
                text = state.targetSdkPath,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
              )
            }

            if (state.missingComponents.isNotEmpty()) {
              Text(
                text = "Components to install automatically:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
              )
              state.missingComponents.forEach { component ->
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Box(
                    modifier =
                      Modifier.size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                  )
                  Text(
                    text = component,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                }
              }
            }
          }
        }

        // Progress Indicator during Installation
        val progress = state.installProgress
        if (progress != null) {
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = progress.stageTitle,
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                if (progress.fraction != null) {
                  Text(
                    text = "${(progress.fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                  )
                }
              }
              if (progress.fraction != null) {
                LinearProgressIndicator(
                  progress = { progress.fraction },
                  modifier = Modifier.fillMaxWidth(),
                )
              } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              }
              Text(
                text = progress.detailMessage,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }

        // Error Banner
        if (state.errorMessage != null) {
          Surface(
            color = StudioError.copy(alpha = 0.12f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, StudioError.copy(alpha = 0.5f)),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Icon(
                imageVector = vectorResource(Res.drawable.error),
                contentDescription = null,
                tint = StudioError,
              )
              Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
              )
              OutlinedButton(onClick = { state.eventSink(SdkSetupScreen.Event.DismissError) }) {
                Text("Dismiss")
              }
            }
          }
        }

        // Action Buttons
        val isInstalling = state.installProgress != null
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedButton(
            onClick = {
              chooseDirectoryWithSwing(state.targetSdkPath) { chosenPath ->
                state.eventSink(SdkSetupScreen.Event.CustomPathSelected(chosenPath))
              }
            },
            enabled = !isInstalling,
          ) {
            Icon(
              imageVector = vectorResource(Res.drawable.folder_open),
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Choose Existing SDK Folder...")
          }

          OutlinedButton(
            onClick = { state.eventSink(SdkSetupScreen.Event.ReScanClicked) },
            enabled = !isInstalling,
          ) {
            Icon(
              imageVector = vectorResource(Res.drawable.refresh),
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Re-scan")
          }

          if (state.isReady) {
            Button(
              onClick = { state.eventSink(SdkSetupScreen.Event.ContinueToDevicesClicked) },
              enabled = !isInstalling,
            ) {
              Text("Open Device Manager")
            }
          } else {
            Button(
              onClick = { state.eventSink(SdkSetupScreen.Event.InstallOrRepairClicked) },
              enabled = !isInstalling,
              colors =
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
              Icon(
                imageVector = vectorResource(Res.drawable.cloud_download),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                if (state.isIncomplete) {
                  "Install Missing SDK Components"
                } else {
                  "Install Android SDK Automatically"
                }
              )
            }
          }
        }
      }
    }
  }
}

private fun chooseDirectoryWithSwing(
  initialPath: String,
  onDirectoryPicked: (String) -> Unit,
) {
  SwingUtilities.invokeLater {
    val chooser =
      JFileChooser(initialPath).apply {
        dialogTitle = "Select Android SDK Directory"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
      }
    val result = chooser.showOpenDialog(null)
    if (result == JFileChooser.APPROVE_OPTION && chooser.selectedFile != null) {
      onDirectoryPicked(chooser.selectedFile.absolutePath)
    }
  }
}
