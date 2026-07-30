package com.ucfvpn.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ucfvpn.app.state.ConnectionState
import com.ucfvpn.app.ui.theme.VpnAuthenticating
import com.ucfvpn.app.ui.theme.VpnConnected
import com.ucfvpn.app.ui.theme.VpnConnecting
import com.ucfvpn.app.ui.theme.VpnDisconnected
import com.ucfvpn.app.ui.theme.VpnError
import com.ucfvpn.app.ui.viewmodel.VpnViewModel
import kotlinx.coroutines.delay

data class StackStage(
    val label: String,
    val icon: StackStageIcon
)

enum class StackStageIcon {
    PENDING, IN_PROGRESS, DONE, ERROR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(viewModel: VpnViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val stages = remember {
        listOf(
            StackStage("SSTP", StackStageIcon.PENDING),
            StackStage("Proxy Auth", StackStageIcon.PENDING),
            StackStage("wstunnel", StackStageIcon.PENDING),
            StackStage("WireGuard", StackStageIcon.PENDING),
            StackStage("VPN", StackStageIcon.PENDING)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("VPN Status") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Connection Indicator
            ConnectionIndicator(state = connectionState)

            Spacer(modifier = Modifier.height(8.dp))

            // State name
            Text(
                text = connectionState.displayName,
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Stack stages
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Connection Stack",
                        style = MaterialTheme.typography.titleMedium
                    )
                    stages.forEach { stage ->
                        StackStageRow(stage = stage)
                    }
                }
            }

            // Connected time counter
            if (connectionState is ConnectionState.Connected) {
                ConnectedTimeCounter()
            }

            Spacer(modifier = Modifier.weight(1f))

            // Connect / Disconnect button
            Button(
                onClick = {
                    when (connectionState) {
                        is ConnectionState.Disconnected,
                        is ConnectionState.Error -> viewModel.connect()
                        is ConnectionState.Connected,
                        is ConnectionState.Connecting,
                        is ConnectionState.Authenticating -> viewModel.disconnect()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                val label = when (connectionState) {
                    is ConnectionState.Disconnected,
                    is ConnectionState.Error -> "Connect"
                    is ConnectionState.Connected -> "Disconnect"
                    else -> "Cancel"
                }
                Text(text = label, fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun ConnectionIndicator(state: ConnectionState) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val color = when (state) {
        is ConnectionState.Connected -> VpnConnected
        is ConnectionState.Disconnected -> VpnDisconnected
        is ConnectionState.Connecting -> VpnConnecting
        is ConnectionState.Authenticating -> VpnAuthenticating
        is ConnectionState.Error -> VpnError
    }

    val size = 160.dp
    val strokeWidth = 8.dp

    when (state) {
        is ConnectionState.Connecting -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(VpnConnecting.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val sweepAngle = rotation % 360f
                    drawArc(
                        color = VpnConnecting,
                        startAngle = -90f,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(width = strokeWidth.toPx())
                    )
                }
            }
        }
        is ConnectionState.Authenticating -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(VpnAuthenticating.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val sweepAngle = rotation % 360f
                    drawArc(
                        color = VpnAuthenticating,
                        startAngle = -90f,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(width = strokeWidth.toPx())
                    )
                }
            }
        }
        is ConnectionState.Connected -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                VpnConnected.copy(alpha = 0.3f),
                                VpnConnected.copy(alpha = 0.1f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = VpnConnected,
                        radius = size.toPx() / 2f,
                        style = Stroke(width = strokeWidth.toPx())
                    )
                }
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Connected",
                    tint = VpnConnected,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
        is ConnectionState.Error -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(VpnError.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = VpnError,
                        radius = size.toPx() / 2f,
                        style = Stroke(width = strokeWidth.toPx())
                    )
                }
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Error",
                    tint = VpnError,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
        is ConnectionState.Disconnected -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(VpnDisconnected.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = VpnDisconnected,
                        radius = size.toPx() / 2f,
                        style = Stroke(width = strokeWidth.toPx())
                    )
                }
                Icon(
                    imageVector = Icons.Default.MoreHoriz,
                    contentDescription = "Disconnected",
                    tint = VpnDisconnected,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    }
}

@Composable
fun StackStageRow(stage: StackStage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (icon, tint) = when (stage.icon) {
            StackStageIcon.DONE -> Icons.Default.Check to VpnConnected
            StackStageIcon.IN_PROGRESS -> Icons.Default.MoreHoriz to VpnConnecting
            StackStageIcon.ERROR -> Icons.Default.Close to VpnError
            StackStageIcon.PENDING -> Icons.Default.MoreHoriz to VpnDisconnected
        }
        Icon(
            imageVector = icon,
            contentDescription = stage.icon.name,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stage.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun ConnectedTimeCounter() {
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            elapsedSeconds++
        }
    }

    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60

    Text(
        text = "Connected: %02d:%02d:%02d".format(hours, minutes, seconds),
        style = MaterialTheme.typography.titleMedium,
        color = VpnConnected
    )
}
