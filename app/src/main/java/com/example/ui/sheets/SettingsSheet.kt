package com.example.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.AppSettings
import com.example.data.model.AlarmTone
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    onUpdateEcoMode: (Boolean) -> Unit,
    onUpdateAlarmTone: (AlarmTone) -> Unit,
    onUpdateVibration: (Boolean) -> Unit,
    onUpdateDefaultRadius: (Int) -> Unit,
    onUpdateSatellite: (Boolean) -> Unit,
    onUpdateTrafficEnabled: (Boolean) -> Unit = {},
    onTestAlarmSound: (AlarmTone) -> Unit
) {
    var isTestingSound by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 6.dp)
    ) {
        // Battery & Tracking Performance
        item {
            Text(
                text = "TRACKING & BATTERY",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 1.sp
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DarkSurfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Eco Mode", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text(
                                "Adaptive GPS interval when far (>10km: 35s, near: 1.5s) to save battery without sacrificing arrival accuracy.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = settings.ecoMode,
                            onCheckedChange = onUpdateEcoMode,
                            modifier = Modifier.testTag("setting_eco_mode_switch")
                        )
                    }
                }
            }
        }

        // Alarm & Alert Settings
        item {
            Text(
                text = "ALARM & ALERTS",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 1.sp
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DarkSurfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Alarm Tone", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Synthesized audio with 3-second volume ramp", fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(10.dp))

                    AlarmTone.entries.forEach { tone ->
                        val isSelected = settings.alarmTone == tone
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) BorderAccent else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { onUpdateAlarmTone(tone) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tone.title,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) AmberAction else TextPrimary
                            )
                            if (isSelected) {
                                IconButton(
                                    onClick = { onTestAlarmSound(tone) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.VolumeUp,
                                        contentDescription = "Test Tone",
                                        tint = AmberAction,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = DividerColor)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Arrival Vibration", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("400ms-150ms-400ms-150ms-800ms rhythmic alert", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = settings.vibrationEnabled,
                            onCheckedChange = onUpdateVibration
                        )
                    }
                }
            }
        }

        // Map Preferences
        item {
            Text(
                text = "MAP & DISPLAY",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 1.sp
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DarkSurfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Satellite Imagery Layer", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Toggle high-resolution satellite tiles", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = settings.satelliteMap,
                            onCheckedChange = onUpdateSatellite
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = BorderSubtle
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Traffic & Incidents Layer", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Color-coded route congestion and incident markers", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = settings.trafficEnabled,
                            onCheckedChange = onUpdateTrafficEnabled,
                            modifier = Modifier.testTag("setting_traffic_switch")
                        )
                    }
                }
            }
        }

        // Background Location & Platform Truthfulness
        item {
            Text(
                text = "BACKGROUND RELIABILITY",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 1.sp
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DarkSurfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Android Platform Notes", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "• Screen Off / Locked: Tracking runs reliably via Android Foreground Location Service.\n" +
                        "• WakeLock is maintained for CPU continuity.\n" +
                        "• Device Powered Off: Tracking cannot run while device is fully turned off.\n" +
                        "• Battery Optimization: For longest trips, exclude RouteWake from vendor aggressive sleep killers.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
