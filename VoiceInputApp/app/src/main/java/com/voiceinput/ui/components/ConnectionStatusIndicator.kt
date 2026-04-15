package com.voiceinput.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ConnectionStatusIndicator(
    connected: Boolean,
    deviceName: String?,
    transport: String = "",
    pairedDeviceCount: Int = 0
) {
    val resolvedDeviceName = deviceName
        .orEmpty()
        .removeSuffix(" (局域网)")
        .removeSuffix(" (远程连接)")
    val transportLabel = when (transport) {
        "lan" -> "局域网"
        "server" -> "远程连接"
        else -> ""
    }
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

    val stateLabel = when {
        connected -> "状态：已连接"
        resolvedDeviceName.isNotBlank() -> "状态：电脑离线"
        pairedDeviceCount > 0 -> "状态：待选择设备"
        else -> "状态：未配对"
    }
    val detailLabel = when {
        connected || resolvedDeviceName.isNotBlank() ->
            listOf(resolvedDeviceName, transportLabel)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        pairedDeviceCount > 0 -> "点右上角选择电脑"
        else -> "点右上角扫码配对"
    }
    val indicatorColor = when {
        connected -> Color(0xFF4CAF50).copy(alpha = alpha)
        resolvedDeviceName.isNotBlank() -> Color(0xFFFFB300)
        pairedDeviceCount > 0 -> Color.White.copy(alpha = 0.9f)
        else -> Color.White.copy(alpha = 0.55f)
    }

    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态指示器
        Surface(
            modifier = Modifier.size(12.dp),
            shape = MaterialTheme.shapes.small,
            color = indicatorColor
        ) {}

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stateLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Text(
                text = detailLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.88f)
            )
        }
    }
}
