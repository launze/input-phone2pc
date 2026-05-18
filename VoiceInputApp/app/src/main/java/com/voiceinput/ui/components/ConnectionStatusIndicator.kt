package com.voiceinput.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ConnectionStatusIndicator(
    connected: Boolean,
    deviceName: String?
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 状态指示器
        Surface(
            modifier = Modifier.size(12.dp),
            shape = MaterialTheme.shapes.small,
            color = if (connected) {
                Color(0xFF4CAF50).copy(alpha = if (connected) alpha else 1f)
            } else {
                Color(0xFFF44336)
            }
        ) {}

        // 状态文本
        Text(
            text = if (connected && !deviceName.isNullOrBlank()) {
                "已连接到「$deviceName」"
            } else if (!deviceName.isNullOrBlank()) {
                "已选择「$deviceName」(离线)"
            } else {
                "未连接"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}
