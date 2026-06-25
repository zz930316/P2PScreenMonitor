package com.p2p.monitor.receiver

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.p2p.monitor.ui.common.P2PScreenMonitorTheme
import com.p2p.monitor.network.ConnectionState

class ReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        setContent {
            P2PScreenMonitorTheme {
                ReceiverScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ReceiverScreen(
    viewModel: ReceiverViewModel = viewModel()
) {
    val context = LocalContext.current
    val ipAddress by viewModel.ipAddress.collectAsState()
    val connectionHistory by viewModel.connectionHistory.collectAsState()
    val showHistory by viewModel.showHistory.collectAsState()
    val streamResolution by viewModel.streamResolution.collectAsState()
    val streamW = streamResolution.first
    val streamH = streamResolution.second
    val connectionState by viewModel.connectionState.collectAsState()
    val isFullScreen = connectionState == ConnectionState.CONNECTED

    LaunchedEffect(Unit) {
        viewModel.init(context)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isFullScreen) {
            FullscreenVideoPlayer(
                viewModel = viewModel,
                streamW = streamW,
                streamH = streamH,
                onDisconnect = { viewModel.disconnect() }
            )
        } else {
            NormalLayout(
                viewModel = viewModel,
                connectionState = connectionState,
                ipAddress = ipAddress,
                connectionHistory = connectionHistory,
                showHistory = showHistory
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun NormalLayout(
    viewModel: ReceiverViewModel,
    connectionState: ConnectionState,
    ipAddress: String,
    connectionHistory: List<com.p2p.monitor.util.ConnectionRecord>,
    showHistory: Boolean
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receiver - 屏幕接收端") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { viewModel.updateIpAddress(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("输入被控端IP") },
                        placeholder = { Text("例如：100.88.12.5") },
                        trailingIcon = {
                            IconButton(onClick = { viewModel.toggleHistory() }) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "历史记录"
                                )
                            }
                        },
                        singleLine = true,
                        enabled = connectionState != ConnectionState.CONNECTED
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (connectionState) {
                                            ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                                            ConnectionState.CONNECTING,
                                            ConnectionState.RECONNECTING -> Color(0xFFFF9800)
                                            ConnectionState.DISCONNECTED -> Color.Gray
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (connectionState) {
                                    ConnectionState.DISCONNECTED -> "未连接"
                                    ConnectionState.CONNECTING -> "连接中..."
                                    ConnectionState.CONNECTED -> "已连接"
                                    ConnectionState.RECONNECTING -> "重连中..."
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Button(
                            onClick = {
                                if (connectionState == ConnectionState.CONNECTED) {
                                    viewModel.disconnect()
                                } else {
                                    viewModel.connect()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (connectionState == ConnectionState.CONNECTED)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = if (connectionState == ConnectionState.CONNECTED)
                                    Icons.Default.Close
                                else
                                    Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (connectionState == ConnectionState.CONNECTED) "断开" else "连接"
                            )
                        }
                    }

                    if (showHistory && connectionHistory.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            text = "连接历史",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 200.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(connectionHistory) { record ->
                                Card(
                                    onClick = {
                                        viewModel.updateIpAddress(record.ip)
                                        viewModel.toggleHistory()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = record.ip,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            Text(
                                                text = "连接${record.connectCount}次",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else if (showHistory) {
                        Text(
                            text = "暂无连接记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (connectionState == ConnectionState.DISCONNECTED) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "请输入IP并连接以开始观看",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "支持触控操作",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FullscreenVideoPlayer(
    viewModel: ReceiverViewModel,
    streamW: Int,
    streamH: Int,
    onDisconnect: () -> Unit
) {
    var debugText by remember { mutableStateOf("等待连接...") }
    var frameCountText by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            debugText = viewModel.debugInfo
            frameCountText = viewModel.frameCount
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        VideoPlayer(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = debugText,
                color = Color(0xFFFFEB3B),
                fontSize = 11.sp
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF4CAF50).copy(alpha = 0.85f))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "触控模式",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        FloatingActionButton(
            onClick = onDisconnect,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .size(48.dp),
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
            contentColor = Color.White
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "断开连接",
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VideoPlayer(
    viewModel: ReceiverViewModel,
    modifier: Modifier = Modifier
) {
    var viewWidth by remember { mutableFloatStateOf(1f) }
    var viewHeight by remember { mutableFloatStateOf(1f) }

    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        viewModel.setSurface(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        if (width > 0 && height > 0) {
                            viewModel.onSurfaceChanged(holder.surface, width, height)
                        }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        viewModel.clearSurface()
                    }
                })
            }
        },
        update = { view ->
            viewWidth = view.width.toFloat()
            viewHeight = view.height.toFloat()
        },
        modifier = modifier.pointerInteropFilter { event ->
            viewModel.sendTouchEvent(event, viewWidth, viewHeight)
            true
        }
    )
}
