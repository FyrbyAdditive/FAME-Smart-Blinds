package com.fyrbyadditive.famesmartblinds.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fyrbyadditive.famesmartblinds.R
import com.fyrbyadditive.famesmartblinds.data.model.BlindDevice
import com.fyrbyadditive.famesmartblinds.ui.theme.StatusConnected
import com.fyrbyadditive.famesmartblinds.viewmodel.DeviceListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    viewModel: DeviceListViewModel,
    onDeviceClick: (BlindDevice) -> Unit
) {
    val configuredDevices by viewModel.configuredDevices.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val showManualIpDialog by viewModel.showManualIpDialog.collectAsState()
    val manualIp by viewModel.manualIp.collectAsState()

    var showAbout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FAME Smart Blinds") },
                actions = {
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showAbout = true }) {
                        Icon(Icons.Default.Info, contentDescription = "About")
                    }
                }
            )
        }
    ) { padding ->
        if (configuredDevices.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_blinds),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.no_devices_found),
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.no_devices_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { viewModel.showManualIpDialog() }) {
                    Text(stringResource(R.string.enter_ip_manually))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.discovered_devices),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                items(configuredDevices, key = { it.deviceId }) { device ->
                    DeviceCard(
                        device = device,
                        onClick = { onDeviceClick(device) }
                    )
                }
            }
        }
    }

    // Manual IP Dialog
    if (showManualIpDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideManualIpDialog() },
            title = { Text("Enter Device IP") },
            text = {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { viewModel.updateManualIp(it) },
                    label = { Text("192.168.1.x") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.connectToManualIp() }) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideManualIpDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // About Dialog
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun DeviceCard(
    device: BlindDevice,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_blinds),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (device.ipAddress != null) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = StatusConnected
                        )
                        Text(
                            text = device.ipAddress!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusConnected
                        )
                    }

                    if (device.state.displayName != "Unknown") {
                        Text(
                            text = device.state.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
