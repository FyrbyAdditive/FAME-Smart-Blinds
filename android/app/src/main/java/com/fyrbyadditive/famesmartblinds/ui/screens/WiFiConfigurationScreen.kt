package com.fyrbyadditive.famesmartblinds.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fyrbyadditive.famesmartblinds.viewmodel.WiFiConfigurationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WiFiConfigurationScreen(
    viewModel: WiFiConfigurationViewModel,
    onNavigateBack: () -> Unit
) {
    val device by viewModel.device.collectAsState()
    val wifiSsid by viewModel.wifiSsid.collectAsState()
    val wifiPassword by viewModel.wifiPassword.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val showRestartDialog by viewModel.showRestartDialog.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val successMessage by viewModel.successMessage.collectAsState()

    var wifiPasswordVisible by remember { mutableStateOf(false) }

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
            onDismissRequest = {
                viewModel.clearSuccess()
                onNavigateBack()
            },
            title = { Text("Success") },
            text = { Text(successMessage ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearSuccess()
                    onNavigateBack()
                }) {
                    Text("OK")
                }
            }
        )
    }

    // Restart confirmation dialog
    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideRestartDialog() },
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
                TextButton(onClick = { viewModel.hideRestartDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi") },
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
            SettingsSectionHeader(title = "WiFi Configuration")

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
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
                    onClick = { viewModel.showRestartDialog() },
                    enabled = wifiSsid.isNotEmpty() && !isSaving && device?.ipAddress != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSaving) {
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
                    text = "Enter the WiFi network name and password. Changing WiFi settings will restart the device.\n\nMake sure the credentials are correct and you're connected to the same network, or you may lose access to the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
