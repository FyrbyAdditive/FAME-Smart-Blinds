package com.fyrbyadditive.famesmartblinds.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fyrbyadditive.famesmartblinds.viewmodel.CalibrationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    viewModel: CalibrationViewModel,
    onDismiss: () -> Unit
) {
    val device by viewModel.device.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // Collect calibration state as StateFlow for proper recomposition
    val calibrationState by viewModel.calibrationState.collectAsState()
    val cumulativePosition by viewModel.cumulativePosition.collectAsState()
    val isCalibrated by viewModel.isCalibrated.collectAsState()

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibration") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.cancelAndDismiss()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status indicator
            CalibrationStatusView(
                calibrationState = calibrationState,
                isLoading = isLoading
            )

            Spacer(Modifier.weight(0.5f))

            // Instructions
            InstructionsView(calibrationState = calibrationState)

            Spacer(Modifier.weight(0.5f))

            // Controls based on state
            ControlsView(
                calibrationState = calibrationState,
                isLoading = isLoading,
                onStartCalibration = { viewModel.startCalibration() },
                onCancelCalibration = { viewModel.cancelCalibration() },
                onMoveOpen = { viewModel.moveOpen() },
                onMoveClose = { viewModel.moveClose() },
                onStop = { viewModel.stopMovement() },
                onSetBottom = { viewModel.setBottom() },
                onDone = { onDismiss() }
            )

            // Position display
            if (calibrationState == "at_home") {
                Spacer(Modifier.height(24.dp))
                PositionDisplay(position = cumulativePosition)
            }
        }
    }
}

@Composable
private fun CalibrationStatusView(
    calibrationState: String,
    isLoading: Boolean
) {
    val (statusColor, statusIcon, statusTitle, statusSubtitle) = remember(calibrationState) {
        when (calibrationState) {
            "idle" -> StatusInfo(
                Color(0xFF2196F3), // Blue
                Icons.Default.Settings,
                "Ready to Calibrate",
                "Press Start to begin the calibration process"
            )
            "finding_home" -> StatusInfo(
                Color(0xFFFF9800), // Orange
                Icons.Default.ArrowUpward,
                "Finding Home...",
                "Moving blind up to find the magnet sensor"
            )
            "at_home" -> StatusInfo(
                Color(0xFF4CAF50), // Green
                Icons.Default.Home,
                "At Home Position",
                "Move the blind down to the lowest desired point"
            )
            "complete" -> StatusInfo(
                Color(0xFF4CAF50), // Green
                Icons.Default.CheckCircle,
                "Calibration Complete",
                "Your blind is now calibrated and ready to use"
            )
            else -> StatusInfo(
                Color.Gray,
                Icons.Default.QuestionMark,
                "Unknown",
                ""
            )
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(statusColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading || calibrationState == "finding_home") {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = statusColor
                )
            } else {
                Icon(
                    statusIcon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = statusColor
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = statusTitle,
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = statusSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private data class StatusInfo(
    val color: Color,
    val icon: ImageVector,
    val title: String,
    val subtitle: String
)

@Composable
private fun InstructionsView(calibrationState: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (calibrationState) {
            "idle" -> {
                InstructionRow(number = 1, text = "Press 'Start Calibration' to begin", isActive = true)
                InstructionRow(number = 2, text = "Blind will move up to find home position")
                InstructionRow(number = 3, text = "Use controls to move blind to bottom")
                InstructionRow(number = 4, text = "Press 'Set Bottom' to complete")
            }
            "finding_home" -> {
                InstructionRow(number = 1, text = "Started calibration", isCompleted = true)
                InstructionRow(number = 2, text = "Finding home position...", isActive = true)
                InstructionRow(number = 3, text = "Use controls to move blind to bottom")
                InstructionRow(number = 4, text = "Press 'Set Bottom' to complete")
            }
            "at_home" -> {
                InstructionRow(number = 1, text = "Started calibration", isCompleted = true)
                InstructionRow(number = 2, text = "Home position found!", isCompleted = true)
                InstructionRow(number = 3, text = "Move blind down to desired lowest point", isActive = true)
                InstructionRow(number = 4, text = "Press 'Set Bottom' when ready")
            }
            "complete" -> {
                InstructionRow(number = 1, text = "Started calibration", isCompleted = true)
                InstructionRow(number = 2, text = "Home position found!", isCompleted = true)
                InstructionRow(number = 3, text = "Bottom position set", isCompleted = true)
                InstructionRow(number = 4, text = "Calibration complete!", isCompleted = true)
            }
            else -> {
                Text(
                    text = "Unknown state: $calibrationState",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InstructionRow(
    number: Int,
    text: String,
    isActive: Boolean = false,
    isCompleted: Boolean = false
) {
    val circleColor = when {
        isCompleted -> Color(0xFF4CAF50) // Green
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = when {
        isCompleted || isActive -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(circleColor),
            contentAlignment = Alignment.Center
        ) {
            if (isCompleted) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.White
                )
            } else {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ControlsView(
    calibrationState: String,
    isLoading: Boolean,
    onStartCalibration: () -> Unit,
    onCancelCalibration: () -> Unit,
    onMoveOpen: () -> Unit,
    onMoveClose: () -> Unit,
    onStop: () -> Unit,
    onSetBottom: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (calibrationState) {
            "idle" -> {
                Button(
                    onClick = onStartCalibration,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Calibration")
                }
            }

            "finding_home" -> {
                OutlinedButton(
                    onClick = onCancelCalibration,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Cancel")
                }
            }

            "at_home" -> {
                // Movement controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    OutlinedButton(
                        onClick = onMoveOpen,
                        enabled = !isLoading,
                        modifier = Modifier.size(60.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = "Move Up",
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    OutlinedButton(
                        onClick = onStop,
                        enabled = !isLoading,
                        modifier = Modifier.size(60.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFFF9800)
                        )
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop",
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    OutlinedButton(
                        onClick = onMoveClose,
                        enabled = !isLoading,
                        modifier = Modifier.size(60.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Move Down",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = onSetBottom,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Set Bottom Position")
                }

                OutlinedButton(
                    onClick = onCancelCalibration,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Cancel")
                }
            }

            "complete" -> {
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun PositionDisplay(position: Int) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Current Position",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = position.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}
