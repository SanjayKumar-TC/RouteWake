package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.Destination
import com.example.engine.AlarmAudioEngine
import com.example.engine.VibrationEngine
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ArrivalDialog(
    destination: Destination,
    distanceMeters: Double,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var isDismissing by remember { mutableStateOf(false) }
    var isExiting by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulsingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    // Animated visual feedback properties when dismissing
    val activeRadarScale by animateFloatAsState(
        targetValue = if (isDismissing) 1.10f else pulsingScale,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "activeRadarScale"
    )

    val dismissButtonScale by animateFloatAsState(
        targetValue = if (isDismissing) 1.02f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "dismissButtonScale"
    )

    val dismissButtonColor by animateColorAsState(
        targetValue = if (isDismissing) EmeraldSuccess else AmberAction,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "dismissButtonColor"
    )

    val dismissContentColor by animateColorAsState(
        targetValue = if (isDismissing) Color.White else DarkBackground,
        animationSpec = tween(durationMillis = 350),
        label = "dismissContentColor"
    )

    val accentColor by animateColorAsState(
        targetValue = if (isDismissing) EmeraldSuccess else AmberAction,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "accentColor"
    )

    val dialogBorderColor by animateColorAsState(
        targetValue = if (isDismissing) EmeraldSuccess else AmberAction,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "dialogBorderColor"
    )

    val snoozeAlpha by animateFloatAsState(
        targetValue = if (isDismissing) 0f else 1f,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "snoozeAlpha"
    )

    // Exit transition animations for a graceful, smooth card departure
    val dialogAlpha by animateFloatAsState(
        targetValue = if (isExiting) 0f else 1f,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "dialogAlpha"
    )

    val dialogScale by animateFloatAsState(
        targetValue = if (isExiting) 0.90f else 1f,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "dialogScale"
    )

    val dialogTranslationY by animateFloatAsState(
        targetValue = if (isExiting) 36f else 0f,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "dialogTranslationY"
    )

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .graphicsLayer {
                    alpha = dialogAlpha
                    scaleX = dialogScale
                    scaleY = dialogScale
                    translationY = dialogTranslationY
                },
            shape = RoundedCornerShape(28.dp),
            color = DarkSurfaceGlass,
            shadowElevation = 32.dp,
            border = androidx.compose.foundation.BorderStroke(2.dp, dialogBorderColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Pulsing Alarm Radar Icon (Morphs to confirmed state on dismiss)
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(activeRadarScale)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(accentColor.copy(alpha = 0.4f), accentColor.copy(alpha = 0.1f))
                            )
                        )
                        .border(2.dp, accentColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = isDismissing,
                        transitionSpec = {
                            (fadeIn(tween(350, easing = FastOutSlowInEasing)) + scaleIn(tween(350), initialScale = 0.8f))
                                .togetherWith(fadeOut(tween(220)) + scaleOut(tween(220), targetScale = 1.1f))
                        },
                        label = "radarIcon"
                    ) { dismissing ->
                        if (dismissing) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Alarm Deactivated",
                                tint = EmeraldSuccess,
                                modifier = Modifier.size(48.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Alarm,
                                contentDescription = "Alarm Active",
                                tint = AmberAction,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                AnimatedContent(
                    targetState = isDismissing,
                    transitionSpec = {
                        (fadeIn(tween(300, easing = FastOutSlowInEasing)) + slideInVertically(tween(300)) { it / 2 })
                            .togetherWith(fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 2 })
                    },
                    label = "headerText"
                ) { dismissing ->
                    Text(
                        text = if (dismissing) "ALARM DEACTIVATED" else "YOU'RE HERE",
                        fontSize = if (dismissing) 22.sp else 28.sp,
                        fontWeight = FontWeight.Black,
                        color = accentColor,
                        letterSpacing = 1.2.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = destination.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )

                if (destination.address.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = destination.address,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DarkSurfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isDismissing) EmeraldSuccess.copy(alpha = 0.4f) else BorderAccent
                    )
                ) {
                    AnimatedContent(
                        targetState = isDismissing,
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                        label = "statusBadge"
                    ) { dismissing ->
                        Text(
                            text = if (dismissing) "✓ Alarm silenced • Finishing trip" else "Within arrival radius (${formatDistance(distanceMeters)})",
                            fontSize = 12.sp,
                            color = if (dismissing) EmeraldSuccess else ElectricBlue,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Action Controls: DISMISS & SNOOZE 1 MIN
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onSnooze,
                        enabled = !isDismissing,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .graphicsLayer { alpha = snoozeAlpha }
                            .testTag("arrival_snooze_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextPrimary,
                            disabledContentColor = TextMuted
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (!isDismissing) BorderAccent else BorderSubtle
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Snooze,
                            contentDescription = "Snooze",
                            tint = if (!isDismissing) TextSecondary else TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SNOOZE 1M",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }

                    Button(
                        onClick = {
                            if (!isDismissing) {
                                isDismissing = true
                                // Immediate tactile confirmation
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                // Immediate acoustic and haptic silence for instantaneous responsiveness
                                AlarmAudioEngine.stopAll()
                                VibrationEngine.stopAll(context)
                                // Allow visual confirmation animation to play smoothly before completing
                                scope.launch {
                                    // Generous time for deactivation animation to be seen and appreciated (~750ms)
                                    delay(750L)
                                    // Initiate smooth fade-out and scale-out exit transition
                                    isExiting = true
                                    // Allow smooth exit animation to complete (~380ms)
                                    delay(380L)
                                    onDismiss()
                                }
                            }
                        },
                        enabled = true,
                        modifier = Modifier
                            .weight(1.3f)
                            .height(52.dp)
                            .scale(dismissButtonScale)
                            .testTag("arrival_dismiss_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = dismissButtonColor,
                            contentColor = dismissContentColor
                        )
                    ) {
                        AnimatedContent(
                            targetState = isDismissing,
                            transitionSpec = {
                                (fadeIn(tween(300, easing = FastOutSlowInEasing)) + scaleIn(tween(300), initialScale = 0.85f))
                                    .togetherWith(fadeOut(tween(180)))
                            },
                            label = "dismissButtonContent"
                        ) { dismissing ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = if (dismissing) Icons.Default.Check else Icons.Default.CheckCircle,
                                    contentDescription = if (dismissing) "Deactivated" else "Dismiss",
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (dismissing) "ALARM DISMISSED" else "DISMISS",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 14.sp,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
