package com.fyrbyadditive.famesmartblinds.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fyrbyadditive.famesmartblinds.R
import com.fyrbyadditive.famesmartblinds.data.model.BlindCommand
import com.fyrbyadditive.famesmartblinds.data.model.BlindState
import com.fyrbyadditive.famesmartblinds.ui.theme.StatusConnected
import com.fyrbyadditive.famesmartblinds.ui.theme.StatusWarning
import com.fyrbyadditive.famesmartblinds.viewmodel.DeviceControlViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceControlScreen(
    viewModel: DeviceControlViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToCalibration: (String) -> Unit,
    onNavigateToSettings: (String) -> Unit
) {
    val device by viewModel.device.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val showRenameDialog by viewModel.showRenameDialog.collectAsState()
    val newDeviceName by viewModel.newDeviceName.collectAsState()
    val isRestarting by viewModel.isRestarting.collectAsState()
    val calibrationNagDismissed by viewModel.calibrationNagDismissed.collectAsState()

    var showMenu by remember { mutableStateOf(false) }

    // Lifecycle observer to reconnect SSE when returning from sleep/background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Reconnect SSE when returning from sleep/background
                viewModel.reconnectSSEIfNeeded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopPolling()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device?.name ?: "Device") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (device?.isCalibrated == true) "Recalibrate" else "Calibrate")
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Straighten, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    device?.deviceId?.let { onNavigateToCalibration(it) }
                                },
                                enabled = device?.ipAddress != null
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename_device)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    viewModel.showRenameDialog()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    device?.deviceId?.let { onNavigateToSettings(it) }
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                // Calibration nag banner
                if (device?.isCalibrated == false && !calibrationNagDismissed && device?.ipAddress != null) {
                    CalibrationNagBanner(
                        onCalibrate = { device?.deviceId?.let { onNavigateToCalibration(it) } },
                        onDismiss = { viewModel.dismissCalibrationNag() }
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Status Card
                    StatusCard(device = device)

                    // Control Buttons
                    ControlButtons(
                        state = device?.state ?: BlindState.UNKNOWN,
                        isLoading = isLoading,
                        enabled = device?.ipAddress != null,
                        onCommand = { viewModel.sendCommand(it) }
                    )

                }
            }

            // Restarting overlay
            if (isRestarting) {
                RestartingOverlay()
            }
        }
    }

    // Error dialog
    errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text(stringResource(R.string.error)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    // Rename dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideRenameDialog() },
            title = { Text(stringResource(R.string.rename_device)) },
            text = {
                OutlinedTextField(
                    value = newDeviceName,
                    onValueChange = { viewModel.updateNewDeviceName(it) },
                    label = { Text(stringResource(R.string.device_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.renameDevice() },
                    enabled = newDeviceName.isNotBlank()
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideRenameDialog() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun StatusCard(device: com.fyrbyadditive.famesmartblinds.data.model.BlindDevice?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // WiFi status header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = "WiFi",
                    modifier = Modifier.size(14.dp),
                    tint = if (device?.wifiConnected == true) StatusConnected
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = device?.ipAddress ?: "Not connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Main content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_blinds),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = device?.state?.displayName ?: "Unknown",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )

                // Position with progress bar (only if calibrated)
                if (device?.isCalibrated == true && (device.maxPosition) > 0) {
                    Spacer(Modifier.height(16.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.position),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${device.cumulativePosition} / ${device.maxPosition}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = {
                                if (device.maxPosition > 0) {
                                    device.cumulativePosition.toFloat() / device.maxPosition.toFloat()
                                } else 0f
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlButtons(
    state: BlindState,
    isLoading: Boolean,
    enabled: Boolean,
    onCommand: (BlindCommand) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Up Button
        ArrowButton(
            isUp = true,
            isActive = state == BlindState.OPENING,
            isLoading = isLoading && state == BlindState.OPENING,
            enabled = enabled && !isLoading,
            onClick = { onCommand(BlindCommand.OPEN) }
        )

        // Down Button
        ArrowButton(
            isUp = false,
            isActive = state == BlindState.CLOSING,
            isLoading = isLoading && state == BlindState.CLOSING,
            enabled = enabled && !isLoading,
            onClick = { onCommand(BlindCommand.CLOSE) }
        )

        // Stop Button
        Button(
            onClick = { onCommand(BlindCommand.STOP) },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            enabled = enabled && !isLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ),
            shape = RoundedCornerShape(25.dp)
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("STOP", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ArrowButton(
    isUp: Boolean,
    isActive: Boolean,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isActive) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.primary
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = if (isActive) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp
            )
        } else {
            Icon(
                if (isUp) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
private fun CalibrationNagBanner(
    onCalibrate: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = StatusWarning.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = StatusWarning,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Calibration Required",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Calibrate your blind to enable position limits",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = onCalibrate,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Calibrate", style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun RestartingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.padding(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Restarting Device...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Please wait while the device restarts with its new name",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
