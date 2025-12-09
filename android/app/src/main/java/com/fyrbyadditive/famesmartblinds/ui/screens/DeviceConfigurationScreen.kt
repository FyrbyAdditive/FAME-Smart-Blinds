package com.fyrbyadditive.famesmartblinds.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fyrbyadditive.famesmartblinds.viewmodel.DeviceConfigurationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConfigurationScreen(
    viewModel: DeviceConfigurationViewModel,
    onNavigateBack: () -> Unit
) {
    val device by viewModel.device.collectAsState()

    // WiFi states
    val wifiSsid by viewModel.wifiSsid.collectAsState()
    val wifiPassword by viewModel.wifiPassword.collectAsState()
    val isSavingWifi by viewModel.isSavingWifi.collectAsState()
    val showWifiRestartDialog by viewModel.showWifiRestartDialog.collectAsState()

    // MQTT states
    val mqttBroker by viewModel.mqttBroker.collectAsState()
    val mqttPort by viewModel.mqttPort.collectAsState()
    val mqttUser by viewModel.mqttUser.collectAsState()
    val mqttPassword by viewModel.mqttPassword.collectAsState()
    val isSavingMqtt by viewModel.isSavingMqtt.collectAsState()

    // Password states
    val devicePassword by viewModel.devicePassword.collectAsState()
    val confirmPassword by viewModel.confirmPassword.collectAsState()
    val isSavingPassword by viewModel.isSavingPassword.collectAsState()

    // Messages
    val errorMessage by viewModel.errorMessage.collectAsState()
    val successMessage by viewModel.successMessage.collectAsState()

    // Password visibility toggles
    var wifiPasswordVisible by remember { mutableStateOf(false) }
    var mqttPasswordVisible by remember { mutableStateOf(false) }
    var devicePasswordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

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

    // WiFi restart confirmation dialog
    if (showWifiRestartDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideWifiRestartDialog() },
            title = { Text("Restart Required") },
            text = {
                Text(
                    "Changing WiFi settings will restart the device. It will reconnect using the new WiFi credentials.\n\n" +
                    "Make sure you're connected to the same network, or you may lose access to the device."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.saveWifiConfig() }) {
                    Text("Save & Restart")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideWifiRestartDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuration") },
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
            // WiFi Configuration Section
            SettingsSectionHeader(title = "WiFi Configuration")

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = wifiSsid,
                    onValueChange = { viewModel.updateWifiSsid(it) },
                    label = { Text("WiFi Network Name (SSID)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    )
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = wifiPassword,
                    onValueChange = { viewModel.updateWifiPassword(it) },
                    label = { Text("WiFi Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (wifiPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { wifiPasswordVisible = !wifiPasswordVisible }) {
                            Icon(
                                if (wifiPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (wifiPasswordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    )
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { viewModel.showWifiRestartDialog() },
                    enabled = wifiSsid.isNotEmpty() && !isSavingWifi && device?.ipAddress != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSavingWifi) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Save WiFi Settings")
                }

                Text(
                    text = "Changing WiFi settings will restart the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // MQTT Configuration Section
            SettingsSectionHeader(title = "MQTT / Home Assistant")

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = mqttBroker,
                    onValueChange = { viewModel.updateMqttBroker(it) },
                    label = { Text("Broker Address (e.g., 192.168.1.50)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    )
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = mqttPort,
                    onValueChange = { viewModel.updateMqttPort(it) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    )
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = mqttUser,
                    onValueChange = { viewModel.updateMqttUser(it) },
                    label = { Text("Username (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    )
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = mqttPassword,
                    onValueChange = { viewModel.updateMqttPassword(it) },
                    label = { Text("Password (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (mqttPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { mqttPasswordVisible = !mqttPasswordVisible }) {
                            Icon(
                                if (mqttPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (mqttPasswordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    )
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { viewModel.saveMqttConfig() },
                    enabled = mqttBroker.isNotEmpty() && !isSavingMqtt && device?.ipAddress != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSavingMqtt) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Save MQTT Settings")
                }

                Text(
                    text = "Username and password are optional if your broker doesn't require authentication.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Device Password Section
            SettingsSectionHeader(title = "Device Password")

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = devicePassword,
                    onValueChange = { viewModel.updateDevicePassword(it) },
                    label = { Text("New Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (devicePasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { devicePasswordVisible = !devicePasswordVisible }) {
                            Icon(
                                if (devicePasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (devicePasswordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    )
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { viewModel.updateConfirmPassword(it) },
                    label = { Text("Confirm Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                            Icon(
                                if (confirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (confirmPasswordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    isError = devicePassword.isNotEmpty() && confirmPassword.isNotEmpty() && !viewModel.passwordsMatch,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    )
                )

                if (devicePassword.isNotEmpty() && confirmPassword.isNotEmpty() && !viewModel.passwordsMatch) {
                    Text(
                        text = "Passwords don't match",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { viewModel.saveDevicePassword() },
                    enabled = (devicePassword.isEmpty() || viewModel.passwordsMatch) && !isSavingPassword && device?.ipAddress != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSavingPassword) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Save Password")
                }

                Text(
                    text = "Leave empty to remove password protection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
