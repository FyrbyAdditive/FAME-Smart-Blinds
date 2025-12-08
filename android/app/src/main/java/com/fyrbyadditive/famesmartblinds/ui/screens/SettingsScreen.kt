package com.fyrbyadditive.famesmartblinds.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fyrbyadditive.famesmartblinds.data.model.DeviceOrientation
import com.fyrbyadditive.famesmartblinds.util.Constants
import com.fyrbyadditive.famesmartblinds.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    val device by viewModel.device.collectAsState()
    val firmwareVersion by viewModel.firmwareVersion.collectAsState()
    val currentOrientation by viewModel.currentOrientation.collectAsState()
    val currentSpeed by viewModel.currentSpeed.collectAsState()
    val isLoadingInfo by viewModel.isLoadingInfo.collectAsState()
    val isSavingOrientation by viewModel.isSavingOrientation.collectAsState()
    val isSavingSpeed by viewModel.isSavingSpeed.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val isResetting by viewModel.isResetting.collectAsState()
    val showResetConfirmation by viewModel.showResetConfirmation.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val successMessage by viewModel.successMessage.collectAsState()

    val context = LocalContext.current

    // File picker for firmware update
    val firmwarePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                val bytes = stream.readBytes()
                viewModel.uploadFirmware(bytes)
            }
        }
    }

    // Error dialog
    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("Error") },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text("OK")
                }
            }
        )
    }

    // Success dialog
    if (successMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearSuccess() },
            title = { Text("Success") },
            text = { Text(successMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearSuccess() }) {
                    Text("OK")
                }
            }
        )
    }

    // Factory reset confirmation
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.hideResetConfirmation() },
            title = { Text("Factory Reset") },
            text = {
                Text(
                    "Are you sure you want to factory reset this device?\n\n" +
                    "This will erase ALL settings including:\n" +
                    "• WiFi credentials\n" +
                    "• Device name\n" +
                    "• Calibration data\n" +
                    "• MQTT configuration\n\n" +
                    "The device will need to be set up again from scratch."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (viewModel.performFactoryReset()) {
                            onNavigateBack()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideResetConfirmation() }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Device Information Section
            SettingsSectionHeader(title = "Device Information")

            SettingsRow(
                label = "Name",
                value = device?.name ?: "Unknown"
            )

            SettingsRow(
                label = "IP Address",
                value = device?.ipAddress ?: "Not connected"
            )

            SettingsRow(
                label = "Device ID",
                value = device?.deviceId?.takeIf { it.isNotEmpty() } ?: "Unknown"
            )

            if (!device?.macAddress.isNullOrEmpty()) {
                SettingsRow(
                    label = "MAC Address",
                    value = device?.macAddress ?: ""
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Orientation Section
            SettingsSectionHeader(title = "Orientation")

            OrientationPicker(
                currentOrientation = currentOrientation,
                isSaving = isSavingOrientation,
                enabled = device?.ipAddress != null,
                onOrientationChange = { viewModel.setOrientation(it) }
            )

            Text(
                text = "Select which side of the window the servo is mounted on. This affects the direction of open/close controls.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Speed Section
            SettingsSectionHeader(title = "Speed")

            SpeedSlider(
                currentSpeed = currentSpeed,
                isSaving = isSavingSpeed,
                enabled = device?.ipAddress != null,
                onSpeedChange = { viewModel.setSpeed(it) }
            )

            Text(
                text = "Adjust how fast the blind moves. Lower values are slower, higher values are faster.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Firmware Section
            SettingsSectionHeader(title = "Firmware")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Current Version",
                    style = MaterialTheme.typography.bodyLarge
                )
                if (isLoadingInfo) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = firmwareVersion,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Firmware update button
            TextButton(
                onClick = { firmwarePicker.launch(arrayOf("application/octet-stream")) },
                enabled = device?.ipAddress != null && !isUploading,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.Upload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Update Firmware")
                if (isUploading) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            if (isUploading) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "Uploading firmware...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { uploadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${(uploadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Actions Section
            SettingsSectionHeader(title = "Actions")

            TextButton(
                onClick = onNavigateToLogs,
                enabled = device?.ipAddress != null,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.Article, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("View Logs")
            }

            TextButton(
                onClick = { viewModel.restartDevice() },
                enabled = device?.ipAddress != null,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Restart Device")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Danger Zone Section
            SettingsSectionHeader(title = "Danger Zone")

            TextButton(
                onClick = { viewModel.showResetConfirmation() },
                enabled = device?.ipAddress != null && !isResetting,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Factory Reset")
                if (isResetting) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Text(
                text = "Factory reset will erase all settings including WiFi credentials, calibration data, and device name. The device will need to be set up again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingsRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrientationPicker(
    currentOrientation: DeviceOrientation,
    isSaving: Boolean,
    enabled: Boolean,
    onOrientationChange: (DeviceOrientation) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Mounting Side",
            style = MaterialTheme.typography.bodyLarge
        )

        Box {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { if (enabled && !isSaving) expanded = it }
            ) {
                Row(
                    modifier = Modifier.menuAnchor(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentOrientation.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isSaving) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DeviceOrientation.entries.forEach { orientation ->
                        DropdownMenuItem(
                            text = { Text(orientation.displayName) },
                            onClick = {
                                onOrientationChange(orientation)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedSlider(
    currentSpeed: Float,
    isSaving: Boolean,
    enabled: Boolean,
    onSpeedChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Servo Speed",
                style = MaterialTheme.typography.bodyLarge
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = currentSpeed.toInt().toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Slider(
            value = currentSpeed,
            onValueChange = onSpeedChange,
            valueRange = Constants.Speed.MIN.toFloat()..Constants.Speed.MAX.toFloat(),
            steps = ((Constants.Speed.MAX - Constants.Speed.MIN) / 50) - 1,
            enabled = enabled && !isSaving
        )
    }
}
