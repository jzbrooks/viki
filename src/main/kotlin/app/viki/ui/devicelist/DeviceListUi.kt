package app.viki.ui.devicelist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DevicesFold
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TabletMac
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.viki.avd.DeviceFormFactor
import app.viki.avd.EmulatorRunStatus
import app.viki.di.AppScope
import app.viki.ui.screens.DeviceListScreen
import app.viki.ui.theme.StudioBorder
import app.viki.ui.theme.StudioError
import app.viki.ui.theme.StudioSecondary
import app.viki.ui.theme.StudioSurfaceElevated
import app.viki.ui.theme.StudioWarning
import com.slack.circuit.codegen.annotations.CircuitInject
import dev.zacsweers.metro.Inject

@CircuitInject(DeviceListScreen::class, AppScope::class)
@Inject
@Composable
fun DeviceListUi(
  state: DeviceListScreen.State,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    // Top Studio Toolbar
    Surface(
      color = MaterialTheme.colorScheme.surface,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Text(
                text = "Device Manager",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Surface(
                color = StudioSurfaceElevated,
                shape = MaterialTheme.shapes.extraSmall,
                border = BorderStroke(1.dp, StudioBorder),
              ) {
                Text(
                  text = state.hostArchLabel,
                  style =
                    MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
              }
            }
            Text(
              text = "SDK: ${state.sdkPath}",
              style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            OutlinedButton(
              onClick = { state.eventSink(DeviceListScreen.Event.OpenSdkSettingsClicked) }
            ) {
              Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("SDK Location")
            }

            OutlinedButton(onClick = { state.eventSink(DeviceListScreen.Event.RefreshClicked) }) {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("Refresh")
            }

            Button(
              onClick = { state.eventSink(DeviceListScreen.Event.CreateDeviceClicked) },
              colors =
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
              Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("Create Virtual Device")
            }
          }
        }
        HorizontalDivider(color = StudioBorder)
      }
    }

    // Search & Form Factor Filter Bar
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      OutlinedTextField(
        value = state.searchQuery,
        onValueChange = { state.eventSink(DeviceListScreen.Event.SearchQueryChanged(it)) },
        placeholder = { Text("Filter virtual devices by name or Android release...") },
        leadingIcon = {
          Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
        },
        trailingIcon =
          if (state.searchQuery.isNotEmpty()) {
            {
              IconButton(
                onClick = { state.eventSink(DeviceListScreen.Event.SearchQueryChanged("")) }
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Clear search",
                  modifier = Modifier.size(16.dp),
                )
              }
            }
          } else {
            null
          },
        singleLine = true,
        modifier = Modifier.width(360.dp),
      )

      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilterChip(
          selected = state.selectedFormFactorFilter == null,
          onClick = { state.eventSink(DeviceListScreen.Event.FormFactorFilterSelected(null)) },
          label = { Text("All (${state.totalDeviceCount})") },
        )
        DeviceFormFactor.entries.forEach { formFactor ->
          FilterChip(
            selected = state.selectedFormFactorFilter == formFactor,
            onClick = {
              val next = if (state.selectedFormFactorFilter == formFactor) null else formFactor
              state.eventSink(DeviceListScreen.Event.FormFactorFilterSelected(next))
            },
            label = { Text(formFactor.label) },
          )
        }
      }
    }

    // Main Content Area
    if (state.devices.isEmpty()) {
      EmptyDevicesView(
        hasAnyInstalled = state.totalDeviceCount > 0,
        onCreateClick = { state.eventSink(DeviceListScreen.Event.CreateDeviceClicked) },
        onClearFiltersClick = {
          state.eventSink(DeviceListScreen.Event.SearchQueryChanged(""))
          state.eventSink(DeviceListScreen.Event.FormFactorFilterSelected(null))
        },
        modifier = Modifier.fillMaxSize().padding(24.dp),
      )
    } else {
      // Table Column Headers (Android Studio Device Manager style)
      Surface(
        color = StudioSurfaceElevated,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, StudioBorder),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Virtual Device",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(2.2f),
          )
          Text(
            text = "Android Release",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.4f),
          )
          Text(
            text = "Display Specs",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.4f),
          )
          Text(
            text = "Size on Disk",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.8f),
          )
          Text(
            text = "Status",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.0f),
          )
          Text(
            text = "Actions",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(210.dp),
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(
          items = state.devices,
          key = { it.avd.avdId },
        ) { row ->
          DeviceRowCard(
            row = row,
            onLaunch = { coldBoot ->
              state.eventSink(DeviceListScreen.Event.LaunchDevice(row.avd.avdId, coldBoot))
            },
            onStop = { state.eventSink(DeviceListScreen.Event.StopDevice(row.avd.avdId)) },
            onDismissError = {
              state.eventSink(DeviceListScreen.Event.DismissDeviceError(row.avd.avdId))
            },
          )
        }
      }
    }
  }
}

@Composable
private fun DeviceRowCard(
  row: DeviceListScreen.DeviceRowItem,
  onLaunch: (coldBoot: Boolean) -> Unit,
  onStop: () -> Unit,
  onDismissError: () -> Unit,
) {
  val avd = row.avd
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = MaterialTheme.shapes.small,
    border =
      BorderStroke(
        width = 1.dp,
        color =
          when (row.runStatus) {
            EmulatorRunStatus.RUNNING -> StudioSecondary.copy(alpha = 0.6f)
            EmulatorRunStatus.BOOTING -> StudioWarning.copy(alpha = 0.6f)
            EmulatorRunStatus.STOPPED -> StudioBorder
          },
      ),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // 1. Virtual Device Name & Form Factor Icon
        Row(
          modifier = Modifier.weight(2.2f),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Box(
            modifier =
              Modifier.size(40.dp)
                .clip(MaterialTheme.shapes.small)
                .background(StudioSurfaceElevated),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = formFactorIcon(avd.formFactor),
              contentDescription = avd.formFactor.label,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(22.dp),
            )
          }
          Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
              text = avd.displayName,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "${avd.formFactor.label} · ${avd.abi}",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        // 2. Android Release & Play Store Tag
        Column(
          modifier = Modifier.weight(1.4f),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Text(
            text = avd.androidReleaseLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = avd.googlePlayServicesBadge,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        // 3. Display Specs
        Text(
          text = avd.resolutionLabel,
          style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1.4f),
        )

        // 4. Size on Disk
        Text(
          text = avd.formattedDiskSize,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(0.8f),
        )

        // 5. Status Badge
        Box(modifier = Modifier.weight(1.0f)) {
          StatusBadge(status = row.runStatus, adbSerial = row.adbSerial)
        }

        // 6. Actions (Launch / Cold Boot / Stop)
        Row(
          modifier = Modifier.width(210.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          when (row.runStatus) {
            EmulatorRunStatus.STOPPED -> {
              Button(
                onClick = { onLaunch(false) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StudioSecondary),
              ) {
                Icon(
                  imageVector = Icons.Default.PlayArrow,
                  contentDescription = "Launch emulator",
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = "Launch",
                  fontWeight = FontWeight.SemiBold,
                )
              }

              OutlinedButton(
                onClick = { onLaunch(true) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
              ) {
                Icon(
                  imageVector = Icons.Default.RestartAlt,
                  contentDescription = "Cold boot",
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Cold")
              }
            }

            EmulatorRunStatus.BOOTING,
            EmulatorRunStatus.RUNNING -> {
              OutlinedButton(
                onClick = onStop,
                border = BorderStroke(1.dp, StudioError.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
              ) {
                Icon(
                  imageVector = Icons.Default.Stop,
                  contentDescription = "Stop emulator",
                  tint = StudioError,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = "Stop",
                  color = StudioError,
                )
              }
            }
          }
        }
      }

      if (row.lastError != null) {
        HorizontalDivider(color = StudioBorder)
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .background(StudioError.copy(alpha = 0.12f))
              .padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = StudioError,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text = row.lastError,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          IconButton(
            onClick = onDismissError,
            modifier = Modifier.size(24.dp),
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Dismiss error",
              modifier = Modifier.size(14.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun StatusBadge(
  status: EmulatorRunStatus,
  adbSerial: String?,
) {
  when (status) {
    EmulatorRunStatus.RUNNING -> {
      Surface(
        color = StudioSecondary.copy(alpha = 0.15f),
        shape = CircleShape,
        border = BorderStroke(1.dp, StudioSecondary.copy(alpha = 0.5f)),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(StudioSecondary))
          Text(
            text = "Running",
            style = MaterialTheme.typography.labelMedium,
            color = StudioSecondary,
          )
        }
      }
    }

    EmulatorRunStatus.BOOTING -> {
      Surface(
        color = StudioWarning.copy(alpha = 0.15f),
        shape = CircleShape,
        border = BorderStroke(1.dp, StudioWarning.copy(alpha = 0.5f)),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          CircularProgressIndicator(
            modifier = Modifier.size(10.dp),
            strokeWidth = 1.5.dp,
            color = StudioWarning,
          )
          Text(
            text = "Starting...",
            style = MaterialTheme.typography.labelMedium,
            color = StudioWarning,
          )
        }
      }
    }

    EmulatorRunStatus.STOPPED -> {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Box(
          modifier =
            Modifier.size(8.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        )
        Text(
          text = "Offline",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun EmptyDevicesView(
  hasAnyInstalled: Boolean,
  onCreateClick: () -> Unit,
  onClearFiltersClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      color = MaterialTheme.colorScheme.surface,
      shape = MaterialTheme.shapes.large,
      border = BorderStroke(1.dp, StudioBorder),
      modifier = Modifier.width(460.dp),
    ) {
      Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Box(
          modifier = Modifier.size(56.dp).clip(CircleShape).background(StudioSurfaceElevated),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.Smartphone,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
          )
        }

        Text(
          text =
            if (hasAnyInstalled) {
              "No Matching Virtual Devices"
            } else {
              "No Android Virtual Devices Yet"
            },
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
          text =
            if (hasAnyInstalled) {
              "Try clearing your search or form factor filter to see all installed emulators."
            } else {
              "Create a Pixel phone, foldable, or tablet emulator in seconds to preview your designs and prototypes."
            },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (hasAnyInstalled) {
          OutlinedButton(onClick = onClearFiltersClick) { Text("Reset Filters") }
        } else {
          Button(onClick = onCreateClick) {
            Icon(
              imageVector = Icons.Default.Add,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create Your First Virtual Device")
          }
        }
      }
    }
  }
}

internal fun formFactorIcon(formFactor: DeviceFormFactor): ImageVector =
  when (formFactor) {
    DeviceFormFactor.PHONE -> Icons.Default.Smartphone
    DeviceFormFactor.FOLDABLE -> Icons.Default.DevicesFold
    DeviceFormFactor.TABLET -> Icons.Default.TabletMac
  }
