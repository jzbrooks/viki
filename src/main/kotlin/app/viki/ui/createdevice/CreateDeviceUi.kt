package app.viki.ui.createdevice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import app.viki.avd.DeviceFormFactor
import app.viki.avd.DevicePreset
import app.viki.di.AppScope
import app.viki.resources.Res
import app.viki.resources.arrow_back
import app.viki.resources.check_circle
import app.viki.resources.cloud_download
import app.viki.resources.error
import app.viki.ui.devicelist.formFactorIcon
import app.viki.ui.screens.CreateDeviceScreen
import app.viki.ui.theme.StudioBorder
import app.viki.ui.theme.StudioError
import app.viki.ui.theme.StudioSecondary
import app.viki.ui.theme.StudioSurfaceElevated
import com.slack.circuit.codegen.annotations.CircuitInject
import dev.zacsweers.metro.Inject
import org.jetbrains.compose.resources.vectorResource

@CircuitInject(CreateDeviceScreen::class, AppScope::class)
@Inject
@Composable
fun CreateDeviceUi(
  state: CreateDeviceScreen.State,
  modifier: Modifier = Modifier,
) {
  val isCreating = state.creationProgress != null

  Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    // Top Header Bar
    Surface(
      color = MaterialTheme.colorScheme.surface,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          IconButton(
            onClick = { state.eventSink(CreateDeviceScreen.Event.CancelClicked) },
            enabled = !isCreating,
          ) {
            Icon(
              imageVector = vectorResource(Res.drawable.arrow_back),
              contentDescription = "Back to Device Manager",
            )
          }
          Column {
            Text(
              text = "Create Virtual Device",
              style = MaterialTheme.typography.headlineMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text =
                "Pick a hardware profile and Android version — Viki handles system images and hardware tuning automatically",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        HorizontalDivider(color = StudioBorder)
      }
    }

    // Two-Pane Studio Layout: Left Configuration Steps + Right Live Device Preview & Summary
    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
      // Left Pane: Guided Selection
      Column(
        modifier =
          Modifier.weight(1.8f)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
      ) {
        // Step 1: Hardware Profile
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            text = "1. Choose Hardware Profile",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )

          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DeviceFormFactor.entries.forEach { formFactor ->
              FilterChip(
                selected = state.selectedFormFactor == formFactor,
                onClick = {
                  if (!isCreating) {
                    state.eventSink(CreateDeviceScreen.Event.SelectFormFactor(formFactor))
                  }
                },
                leadingIcon = {
                  Icon(
                    imageVector = formFactorIcon(formFactor),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                  )
                },
                label = { Text(formFactor.label) },
              )
            }
          }

          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.availablePresets.forEach { preset ->
              DevicePresetCard(
                preset = preset,
                isSelected = preset.id == state.selectedPreset.id,
                enabled = !isCreating,
                onClick = { state.eventSink(CreateDeviceScreen.Event.SelectDevicePreset(preset)) },
              )
            }
          }
        }

        HorizontalDivider(color = StudioBorder)

        // Step 2: Android Version
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            text = "2. Choose Android Version",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )

          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.releaseOptions.forEach { option ->
              ReleaseOptionCard(
                option = option,
                isSelected = option.preset.apiLevel == state.selectedRelease.preset.apiLevel,
                enabled = !isCreating,
                onClick = { state.eventSink(CreateDeviceScreen.Event.SelectRelease(option)) },
              )
            }
          }
        }

        HorizontalDivider(color = StudioBorder)

        // Step 3: Device Name
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "3. Virtual Device Name",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
          OutlinedTextField(
            value = state.customDeviceName,
            onValueChange = { state.eventSink(CreateDeviceScreen.Event.UpdateDeviceName(it)) },
            enabled = !isCreating,
            singleLine = true,
            label = { Text("Display Name") },
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }

      // Right Pane: Visual Viewport Preview & Summary
      Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.weight(1.1f).fillMaxHeight(),
        border = BorderStroke(1.dp, StudioBorder),
      ) {
        Column(
          modifier = Modifier.fillMaxSize().padding(24.dp),
          verticalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = "Viewport Preview & Specs",
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )

            // Proportional Wireframe Preview
            DeviceWireframePreview(preset = state.selectedPreset)

            Surface(
              color = StudioSurfaceElevated,
              shape = MaterialTheme.shapes.medium,
              border = BorderStroke(1.dp, StudioBorder),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                SummarySpecRow("Hardware Model", state.selectedPreset.displayName)
                SummarySpecRow(
                  "Form Factor",
                  "${state.selectedPreset.formFactor.label} (${state.selectedPreset.diagonalInches})",
                )
                SummarySpecRow(
                  "Resolution",
                  "${state.selectedPreset.widthPx} × ${state.selectedPreset.heightPx} px",
                )
                SummarySpecRow(
                  "Density Bucket",
                  "${state.selectedPreset.densityBucket} (${state.selectedPreset.densityDpi} dpi)",
                )
                SummarySpecRow("Android Version", state.selectedRelease.preset.displayTitle)
                SummarySpecRow("CPU Architecture", "${state.hostArchAbi} (Native Host Speed)")
                SummarySpecRow("Hardware Keyboard", "Enabled (Laptop keyboard input)")
                SummarySpecRow("Graphics Acceleration", "Hardware GPU (Auto)")
              }
            }

            if (state.creationProgress != null) {
              val progress = state.creationProgress
              Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(
                  modifier = Modifier.padding(14.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Text(
                    text = progress.stageTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
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
                    style =
                      MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
            }

            if (state.errorMessage != null) {
              Surface(
                color = StudioError.copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, StudioError.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  Icon(
                    imageVector = vectorResource(Res.drawable.error),
                    contentDescription = null,
                    tint = StudioError,
                  )
                  Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                  )
                }
              }
            }
          }

          // Bottom Action Buttons
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
          ) {
            OutlinedButton(
              onClick = { state.eventSink(CreateDeviceScreen.Event.CancelClicked) },
              enabled = !isCreating,
            ) {
              Text("Cancel")
            }

            Button(
              onClick = { state.eventSink(CreateDeviceScreen.Event.CreateClicked) },
              enabled = !isCreating,
              colors =
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
              if (!state.selectedRelease.isSystemImageDownloaded) {
                Icon(
                  imageVector = vectorResource(Res.drawable.cloud_download),
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Download & Create Emulator")
              } else {
                Text("Create Emulator")
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DevicePresetCard(
  preset: DevicePreset,
  isSelected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    color =
      if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
      } else {
        MaterialTheme.colorScheme.surface
      },
    shape = MaterialTheme.shapes.medium,
    border =
      BorderStroke(
        width = if (isSelected) 1.5.dp else 1.dp,
        color = if (isSelected) MaterialTheme.colorScheme.primary else StudioBorder,
      ),
    modifier =
      Modifier.fillMaxWidth()
        .clip(MaterialTheme.shapes.medium)
        .clickable(enabled = enabled, onClick = onClick),
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Icon(
          imageVector = formFactorIcon(preset.formFactor),
          contentDescription = null,
          tint =
            if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(26.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = "${preset.displayName} (${preset.diagonalInches})",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = preset.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Text(
        text = preset.resolutionSummary,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ReleaseOptionCard(
  option: CreateDeviceScreen.ReleaseOption,
  isSelected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val release = option.preset
  Surface(
    color =
      if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
      } else {
        MaterialTheme.colorScheme.surface
      },
    shape = MaterialTheme.shapes.medium,
    border =
      BorderStroke(
        width = if (isSelected) 1.5.dp else 1.dp,
        color = if (isSelected) MaterialTheme.colorScheme.primary else StudioBorder,
      ),
    modifier =
      Modifier.fillMaxWidth()
        .clip(MaterialTheme.shapes.medium)
        .clickable(enabled = enabled, onClick = onClick),
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = release.displayTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "“${release.codename}”",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (release.isRecommended) {
            Surface(
              color = StudioSecondary.copy(alpha = 0.16f),
              shape = CircleShape,
            ) {
              Text(
                text = "Recommended",
                style = MaterialTheme.typography.labelMedium,
                color = StudioSecondary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
              )
            }
          }
        }
        Text(
          text = release.summary,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(modifier = Modifier.width(12.dp))

      if (option.isSystemImageDownloaded) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            imageVector = vectorResource(Res.drawable.check_circle),
            contentDescription = null,
            tint = StudioSecondary,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text = "Downloaded",
            style = MaterialTheme.typography.labelMedium,
            color = StudioSecondary,
          )
        }
      } else {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            imageVector = vectorResource(Res.drawable.cloud_download),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text = "Auto-download",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
    }
  }
}

@Composable
private fun DeviceWireframePreview(preset: DevicePreset) {
  val (boxWidth, boxHeight) =
    when (preset.formFactor) {
      DeviceFormFactor.PHONE -> 96.dp to 196.dp
      DeviceFormFactor.FOLDABLE -> 156.dp to 164.dp
      DeviceFormFactor.TABLET -> 200.dp to 132.dp
    }

  Box(
    modifier = Modifier.height(210.dp).fillMaxWidth(),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      color = StudioSurfaceElevated,
      shape = RoundedCornerShape(18.dp),
      border = BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary),
      modifier = Modifier.size(width = boxWidth, height = boxHeight),
    ) {
      Column(
        modifier = Modifier.fillMaxSize().padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        // Camera cutout pill
        Box(
          modifier =
            Modifier.size(width = 20.dp, height = 4.dp).clip(CircleShape).background(StudioBorder)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = preset.diagonalInches,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = preset.densityBucket,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        // Gesture navigation bar
        Box(
          modifier =
            Modifier.size(width = 32.dp, height = 3.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        )
      }
    }
  }
}

@Composable
private fun SummarySpecRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}
