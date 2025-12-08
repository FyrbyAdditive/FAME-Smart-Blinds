package com.fyrbyadditive.famesmartblinds.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fyrbyadditive.famesmartblinds.BuildConfig
import com.fyrbyadditive.famesmartblinds.R
import com.fyrbyadditive.famesmartblinds.ui.theme.BluePrimary
import com.fyrbyadditive.famesmartblinds.ui.theme.CyanAccent

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val versionText = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Company Logo
                Image(
                    painter = painterResource(R.drawable.company_logo),
                    contentDescription = "Fyrby Additive Logo",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )

                Spacer(Modifier.height(16.dp))

                // App Name with gradient
                Text(
                    text = "FAME Smart Blinds",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        brush = Brush.linearGradient(
                            colors = listOf(BluePrimary, CyanAccent)
                        )
                    )
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = versionText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(16.dp))

                // Feature badges
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FeatureBadge(text = stringResource(R.string.wifi))
                    FeatureBadge(text = stringResource(R.string.control))
                    FeatureBadge(text = stringResource(R.string.calibrate))
                }

                Spacer(Modifier.height(24.dp))

                HorizontalDivider(modifier = Modifier.padding(horizontal = 40.dp))

                Spacer(Modifier.height(16.dp))

                // Footer
                Text(
                    text = stringResource(R.string.copyright),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.website_url)))
                        context.startActivity(intent)
                    }
                ) {
                    Icon(
                        Icons.Outlined.Language,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.website_label))
                }

                Spacer(Modifier.height(8.dp))

                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.done))
                }
            }
        }
    }
}

@Composable
private fun FeatureBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
