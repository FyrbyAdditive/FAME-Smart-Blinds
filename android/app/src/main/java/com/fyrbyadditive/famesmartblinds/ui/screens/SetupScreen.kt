package com.fyrbyadditive.famesmartblinds.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fyrbyadditive.famesmartblinds.data.model.BlindDevice
import com.fyrbyadditive.famesmartblinds.data.model.DeviceOrientation
import com.fyrbyadditive.famesmartblinds.service.BleManager
import com.fyrbyadditive.famesmartblinds.viewmodel.SetupStep
import com.fyrbyadditive.famesmartblinds.viewmodel.SetupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onDismiss: () -> Unit
) {
    val setupStep by viewModel.setupStep.collectAsState()
    val selectedDeviceId by viewModel.selectedDeviceId.collectAsState()
    val availableDevices by viewModel.availableDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isPoweredOn by viewModel.isPoweredOn.collectAsState()

    // WiFi config states
    val wifiSsid by viewModel.wifiSsid.collectAsState()
    val wifiPassword by viewModel.wifiPassword.collectAsState()
    val wifiConnecting by viewModel.wifiConnecting.collectAsState()
    val wifiStatus by viewModel.wifiStatus.collectAsState()
    val wifiFailed by viewModel.wifiFailed.collectAsState()

    // Device name states
    val deviceName by viewModel.deviceName.collectAsState()
    val isSavingName by viewModel.isSavingName.collectAsState()

    // Orientation states
    val orientation by viewModel.orientation.collectAsState()
    val isSavingOrientation by viewModel.isSavingOrientation.collectAsState()

    // Password states
    val devicePassword by viewModel.devicePassword.collectAsState()
    val confirmPassword by viewModel.confirmPassword.collectAsState()
    val isSavingPassword by viewModel.isSavingPassword.collectAsState()

    // Finish states
    val isFinishing by viewModel.isFinishing.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Setup") },
                navigationIcon = {
                    if (setupStep != SetupStep.SELECT_DEVICE) {
                        IconButton(onClick = { viewModel.cancelSetup(); onDismiss() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    }
                },
                actions = {
                    if (setupStep == SetupStep.SELECT_DEVICE) {
                        IconButton(
                            onClick = { viewModel.startScanning() },
                            enabled = !isScanning
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Scan")
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Progress indicator
            LinearProgressIndicator(
                progress = { viewModel.progressValue },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(16.dp))

            when (setupStep) {
                SetupStep.SELECT_DEVICE -> SelectDeviceStep(
                    availableDevices = availableDevices,
                    selectedDeviceId = selectedDeviceId,
                    isScanning = isScanning,
                    isPoweredOn = isPoweredOn,
                    onDeviceSelect = { viewModel.selectDevice(it) },
                    onContinue = { viewModel.continueToConnect() },
                    onStartScan = { viewModel.startScanning() }
                )

                SetupStep.CONNECT_BLE -> ConnectingStep(
                    deviceName = viewModel.selectedDevice?.name ?: "device"
                )

                SetupStep.CONFIGURE_WIFI -> WiFiConfigStep(
                    ssid = wifiSsid,
                    password = wifiPassword,
                    isConnecting = wifiConnecting,
                    status = wifiStatus,
                    failed = wifiFailed,
                    onSsidChange = { viewModel.updateWifiSsid(it) },
                    onPasswordChange = { viewModel.updateWifiPassword(it) },
                    onConnect = { viewModel.configureWifi() }
                )

                SetupStep.CONFIGURE_NAME -> DeviceNameConfigStep(
                    deviceName = deviceName,
                    isSaving = isSavingName,
                    onNameChange = { viewModel.updateDeviceName(it) },
                    onContinue = { viewModel.configureDeviceName() }
                )

                SetupStep.CONFIGURE_ORIENTATION -> OrientationConfigStep(
                    selectedOrientation = orientation,
                    isSaving = isSavingOrientation,
                    onOrientationSelect = { viewModel.updateOrientation(it) },
                    onContinue = { viewModel.configureOrientation() }
                )

                SetupStep.CONFIGURE_PASSWORD -> PasswordConfigStep(
                    password = devicePassword,
                    confirmPassword = confirmPassword,
                    passwordsMatch = viewModel.passwordsMatch,
                    isSaving = isSavingPassword,
                    onPasswordChange = { viewModel.updateDevicePassword(it) },
                    onConfirmChange = { viewModel.updateConfirmPassword(it) },
                    onContinue = { viewModel.configurePassword() },
                    onSkip = { viewModel.skipPassword() }
                )

                SetupStep.COMPLETE -> SetupCompleteStep(
                    isFinishing = isFinishing,
                    onDone = {
                        if (viewModel.finishSetup()) {
                            onDismiss()
                        }
                    }
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startScanning()
    }
}

@Composable
private fun SelectDeviceStep(
    availableDevices: List<BlindDevice>,
    selectedDeviceId: String?,
    isScanning: Boolean,
    isPoweredOn: Boolean,
    onDeviceSelect: (String) -> Unit,
    onContinue: () -> Unit,
    onStartScan: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.5f))

        Icon(
            Icons.Default.Bluetooth,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Select a Device",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Choose a FAME Smart Blinds device to set up",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        if (!isPoweredOn) {
            Text(
                text = "Please enable Bluetooth to scan for devices",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        } else if (availableDevices.isEmpty()) {
            if (isScanning) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Scanning for devices...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "No unconfigured devices found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onStartScan) {
                    Text("Scan for Devices")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(availableDevices, key = { it.deviceId }) { device ->
                    DeviceSelectionCard(
                        device = device,
                        isSelected = selectedDeviceId == device.deviceId,
                        onClick = { onDeviceSelect(device.deviceId) }
                    )
                }
            }
        }

        Spacer(Modifier.weight(0.5f))

        Button(
            onClick = onContinue,
            enabled = selectedDeviceId != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun DeviceSelectionCard(
    device: BlindDevice,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (isSelected) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium
                )
                if (device.rssi != 0) {
                    Text(
                        text = "Signal: ${device.rssi} dBm",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ConnectingStep(deviceName: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Connecting...",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Connecting to $deviceName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WiFiConfigStep(
    ssid: String,
    password: String,
    isConnecting: Boolean,
    status: String,
    failed: Boolean,
    onSsidChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Wifi,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = if (failed) "Connection Failed" else "Configure WiFi",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = if (failed) {
                "Could not connect to the WiFi network. Please check your credentials and try again."
            } else {
                "Enter your WiFi credentials to connect the device to your network"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = ssid,
            onValueChange = onSsidChange,
            label = { Text("WiFi Network Name (SSID)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
        )

        if (status.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onConnect,
            enabled = ssid.isNotEmpty() && !isConnecting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (isConnecting) "Connecting..." else if (failed) "Retry" else "Connect"
            )
        }
    }
}

@Composable
private fun DeviceNameConfigStep(
    deviceName: String,
    isSaving: Boolean,
    onNameChange: (String) -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Label,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Name Your Device",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Give your blind controller a friendly name to identify it easily",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = deviceName,
            onValueChange = onNameChange,
            label = { Text("Device Name (e.g., Living Room Blinds)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "This name will be shown in the app and Home Assistant",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            enabled = deviceName.isNotEmpty() && !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isSaving) "Saving..." else "Save & Continue")
        }
    }
}

@Composable
private fun OrientationConfigStep(
    selectedOrientation: DeviceOrientation,
    isSaving: Boolean,
    onOrientationSelect: (DeviceOrientation) -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.SwapHoriz,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Device Orientation",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Which side of the window is the servo mounted on? This affects the direction of the open/close controls.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        DeviceOrientation.entries.forEach { orientation ->
            OrientationOption(
                orientation = orientation,
                isSelected = selectedOrientation == orientation,
                onClick = { onOrientationSelect(orientation) }
            )
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isSaving) "Saving..." else "Save & Continue")
        }
    }
}

@Composable
private fun OrientationOption(
    orientation: DeviceOrientation,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = orientation.displayName,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = orientation.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isSelected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PasswordConfigStep(
    password: String,
    confirmPassword: String,
    passwordsMatch: Boolean,
    isSaving: Boolean,
    onPasswordChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Set Device Password",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Optionally set a password to secure access to your device",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = confirmPassword,
            onValueChange = onConfirmChange,
            label = { Text("Confirm Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            isError = password.isNotEmpty() && confirmPassword.isNotEmpty() && !passwordsMatch
        )

        if (password.isNotEmpty() && confirmPassword.isNotEmpty() && !passwordsMatch) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Passwords don't match",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Leave blank to allow open access",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            enabled = (password.isEmpty() || passwordsMatch) && !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isSaving) "Saving..." else "Save & Continue")
        }

        Spacer(Modifier.height(12.dp))

        TextButton(onClick = onSkip) {
            Text("Skip - No Password")
        }
    }
}

@Composable
private fun SetupCompleteStep(
    isFinishing: Boolean,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isFinishing) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp)
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Restarting Device...",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "The device is restarting with its new settings. It will appear in your device list shortly.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        } else {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Setup Complete!",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Your FAME Smart Blinds device is now configured. Tap Done to restart the device and apply all settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = onDone,
                enabled = !isFinishing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isFinishing) "Please wait..." else "Done")
            }
        }
    }
}
