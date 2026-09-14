package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun SheetContainer(
    isOpen: Boolean,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    sheetHeight: Dp? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = slideInVertically(
            initialOffsetY = { fullHeight -> (fullHeight * 0.22f).toInt() },
            animationSpec = tween(
                durationMillis = 300,
                easing = FastOutSlowInEasing
            )
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = 280,
                easing = LinearOutSlowInEasing
            )
        ),
        exit = slideOutVertically(
            targetOffsetY = { fullHeight -> (fullHeight * 0.22f).toInt() },
            animationSpec = tween(
                durationMillis = 260,
                easing = FastOutLinearInEasing
            )
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = 240,
                easing = LinearEasing
            )
        ),
        modifier = modifier
    ) {
        val configuration = LocalConfiguration.current
        val screenHeight = configuration.screenHeightDp.dp
        // Constant fixed height while open:
        // Leaves upper map area comfortably visible (~52% screen height, fixed range 380-450dp)
        // when no state-specific height override is provided.
        // Search results, typing, loading, or content changes NEVER resize this outer sheet.
        val defaultSheetHeight = remember(screenHeight) {
            (screenHeight * 0.52f).coerceIn(380.dp, 450.dp)
        }
        val actualHeight = sheetHeight ?: defaultSheetHeight

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .widthIn(max = 560.dp)
                .height(actualHeight)
                .testTag("floating_sheet_surface"),
            shape = RoundedCornerShape(28.dp),
            color = DarkSurfaceGlass,
            shadowElevation = 24.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderAccent)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                // Drag Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(44.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(BorderAccent)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Sheet Header with Title and Close Action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedContent(
                        targetState = title,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(200, easing = LinearOutSlowInEasing)) togetherWith
                            fadeOut(animationSpec = tween(150, easing = FastOutLinearInEasing))
                        },
                        label = "sheet_header_title_transition"
                    ) { animatedTitle ->
                        Text(
                            text = animatedTitle,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            letterSpacing = 0.5.sp
                        )
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(DarkSurfaceVariant)
                            .testTag("sheet_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Sheet",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Sheet Content area filling the fixed remaining height with internal scrolling
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    content()
                }
            }
        }
    }
}

