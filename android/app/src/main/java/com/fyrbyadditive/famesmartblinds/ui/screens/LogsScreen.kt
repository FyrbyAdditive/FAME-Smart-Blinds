package com.fyrbyadditive.famesmartblinds.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fyrbyadditive.famesmartblinds.viewmodel.LogsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    viewModel: LogsViewModel,
    onNavigateBack: () -> Unit
) {
    val logs by viewModel.logs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val sseConnected by viewModel.sseConnected.collectAsState()

    val listState = rememberLazyListState()

    // Lifecycle observer to reconnect SSE when app resumes from sleep
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Reconnect SSE when returning from sleep
                viewModel.reconnectSSEIfNeeded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    // Error dialog
    if (error != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("Error") },
            text = { Text(error ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Logs") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // SSE connection indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (sseConnected) Color.Green else Color.Red,
                                    shape = MaterialTheme.shapes.small
                                )
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (sseConnected) "Live" else "Disconnected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Clear button
                    IconButton(
                        onClick = { viewModel.clearLogs() },
                        enabled = !isLoading && logs.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Clear logs",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading && logs.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                logs.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No logs available",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        itemsIndexed(logs) { _, log ->
                            Text(
                                text = log,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = getLogColor(log),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun getLogColor(log: String): Color {
    return when {
        log.contains("[ERROR]") -> Color.Red
        log.contains("[WIFI]") -> Color.Blue
        log.contains("[BLE]") -> Color(0xFF9C27B0) // Purple
        log.contains("[HTTP]") -> Color(0xFF4CAF50) // Green
        log.contains("[SERVO]") -> Color(0xFFFF9800) // Orange
        log.contains("[HALL]") -> Color.Cyan
        log.contains("[BOOT]") -> Color(0xFFFFC107) // Amber/Yellow
        log.contains("[MQTT]") -> Color(0xFF00BCD4) // Teal
        log.contains("[NVS]") -> Color(0xFF795548) // Brown
        else -> MaterialTheme.colorScheme.onSurface
    }
}
