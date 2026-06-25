package com.p2p.monitor.sender

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p2p.monitor.model.TouchEvent
import com.p2p.monitor.ui.common.P2PScreenMonitorTheme
import com.p2p.monitor.util.IpUtils
import com.p2p.monitor.util.Logger

class SenderActivity : ComponentActivity() {
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptureService.setProjectionResult(result.resultCode, result.data!!)
            startCaptureService()
        }
    }

    private var inputManager: Any? = null
    private var injectMethod: java.lang.reflect.Method? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initInputManager()

        CaptureService.onTouchReceived = { touchEvent ->
            injectTouchEvent(touchEvent)
        }

        CaptureService.onError = { message ->
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }

        setContent {
            P2PScreenMonitorTheme {
                var isCapturing by remember { mutableStateOf(CaptureService.isCapturing) }
                var isClientConnected by remember { mutableStateOf(CaptureService.isClientConnected) }
                var currentFps by remember { mutableIntStateOf(0) }
                var currentBitrate by remember { mutableLongStateOf(0L) }
                var showSettings by remember { mutableStateOf(false) }

                DisposableEffect(Unit) {
                    val callback = {
                        isCapturing = CaptureService.isCapturing
                        isClientConnected = CaptureService.isClientConnected
                    }
                    val statsCallback = { fps: Int, bitrate: Long ->
                        currentFps = fps
                        currentBitrate = bitrate
                    }
                    CaptureService.onStateChanged = callback
                    CaptureService.onStatsUpdate = statsCallback
                    callback()
                    onDispose {
                        CaptureService.onStateChanged = null
                        CaptureService.onStatsUpdate = null
                    }
                }

                if (showSettings) {
                    SettingsDialog(
                        currentWidth = CaptureService.streamWidth,
                        currentHeight = CaptureService.streamHeight,
                        currentBitrate = CaptureService.streamBitrate,
                        currentFps = CaptureService.streamFps,
                        onDismiss = { showSettings = false },
                        onSave = { width, height, bitrate, fps ->
                            CaptureService.updateSettings(width, height, bitrate, fps)
                            showSettings = false
                        }
                    )
                }

                SenderScreen(
                    isCapturing = isCapturing,
                    isClientConnected = isClientConnected,
                    currentFps = currentFps,
                    currentBitrate = currentBitrate,
                    onShowSettings = { showSettings = true },
                    onStartCapture = { requestProjectionPermission() },
                    onStopCapture = { stopCaptureService() }
                )
            }
        }
    }

    private fun initInputManager() {
        try {
            val clazz = Class.forName("android.view.InputManager")
            val getMethod = clazz.getMethod("getInstance")
            inputManager = getMethod.invoke(null)
            injectMethod = clazz.getMethod(
                "injectInputEvent",
                MotionEvent::class.java,
                Int::class.javaPrimitiveType
            )
        } catch (e: Exception) {
            Logger.e("Failed to get InputManager via reflection", e)
        }
    }

    private fun injectTouchEvent(touchEvent: com.p2p.monitor.model.TouchEvent) {
        val now = System.currentTimeMillis()
        val pointerProperties = MotionEvent.PointerProperties().apply {
            id = touchEvent.pointerId
        }
        val pointerCoords = MotionEvent.PointerCoords().apply {
            x = touchEvent.x
            y = touchEvent.y
            pressure = 1.0f
            size = 1.0f
        }

        val action = when (touchEvent.action) {
            TouchEvent.ACTION_DOWN -> MotionEvent.ACTION_DOWN
            TouchEvent.ACTION_UP -> MotionEvent.ACTION_UP
            TouchEvent.ACTION_MOVE -> MotionEvent.ACTION_MOVE
            TouchEvent.ACTION_POINTER_DOWN -> MotionEvent.ACTION_POINTER_DOWN
            TouchEvent.ACTION_POINTER_UP -> MotionEvent.ACTION_POINTER_UP
            TouchEvent.ACTION_CANCEL -> MotionEvent.ACTION_CANCEL
            else -> MotionEvent.ACTION_DOWN
        }

        val event = MotionEvent.obtain(
            now, now, action, 1,
            arrayOf(pointerProperties),
            arrayOf(pointerCoords),
            0, 0, 1.0f, 1.0f, 0, 0, 0, 0
        )

        try {
            injectMethod?.invoke(inputManager, event, 0)
        } catch (e: Exception) {
            Logger.e("Failed to inject touch event", e)
        } finally {
            event.recycle()
        }
    }

    private fun requestProjectionPermission() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService() {
        val serviceIntent = Intent(this, CaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopCaptureService() {
        val serviceIntent = Intent(this, CaptureService::class.java)
        stopService(serviceIntent)
    }

    override fun onDestroy() {
        CaptureService.onStateChanged = null
        CaptureService.onStatsUpdate = null
        CaptureService.onTouchReceived = null
        CaptureService.onError = null
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderScreen(
    isCapturing: Boolean,
    isClientConnected: Boolean,
    currentFps: Int,
    currentBitrate: Long,
    onShowSettings: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit
) {
    val allIps = remember { IpUtils.getAllIpAddresses() }
    val vpnIp = IpUtils.getTailscaleIp()

    val statusText = remember {
        mutableStateOf("等待连接...")
    }

    LaunchedEffect(isCapturing, isClientConnected) {
        statusText.value = when {
            isCapturing && isClientConnected -> "正在推流..."
            isClientConnected -> "已连接，等待开始投屏"
            else -> "等待连接..."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sender - 屏幕发送端") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = onShowSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "请在主控端输入以下IP：",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (vpnIp != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "VPN/虚拟网络IP",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = vpnIp,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (allIps.size > 1) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "所有网络接口",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        allIps.forEach { ip ->
                            Text(
                                text = ip,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "端口：8888",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isCapturing && isClientConnected -> Color(0xFF4CAF50)
                        isClientConnected -> Color(0xFF66BB6A)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCapturing -> Color(0xFF4CAF50)
                                    isClientConnected -> Color(0xFF4CAF50)
                                    else -> Color.Gray
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = statusText.value,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isCapturing || isClientConnected) Color.White else Color.Unspecified
                    )
                }
            }

            if (isCapturing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "推流统计",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${currentFps}",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "FPS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${currentBitrate / 1000}",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "kbps",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (isCapturing) onStopCapture() else onStartCapture()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCapturing)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = if (isCapturing) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isCapturing) "停止投屏" else "开始投屏",
                    fontSize = 18.sp
                )
            }
        }
    }
}

@Composable
fun SettingsDialog(
    currentWidth: Int,
    currentHeight: Int,
    currentBitrate: Int,
    currentFps: Int,
    onDismiss: () -> Unit,
    onSave: (width: Int, height: Int, bitrate: Int, fps: Int) -> Unit
) {
    var width by remember { mutableStateOf(currentWidth.toString()) }
    var height by remember { mutableStateOf(currentHeight.toString()) }
    var bitrate by remember { mutableStateOf((currentBitrate / 1000).toString()) }
    var fps by remember { mutableStateOf(currentFps.toString()) }

    val presets = listOf(
        "720p" to Triple(1280, 720, 3000),
        "1080p" to Triple(1920, 1080, 5000),
        "480p" to Triple(854, 480, 1500),
        "360p" to Triple(640, 360, 800)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("推流设置") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "预设",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { (name, preset) ->
                        FilterChip(
                            selected = width.toIntOrNull() == preset.first &&
                                    height.toIntOrNull() == preset.second,
                            onClick = {
                                width = preset.first.toString()
                                height = preset.second.toString()
                                bitrate = preset.third.toString()
                            },
                            label = { Text(name) }
                        )
                    }
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = width,
                    onValueChange = { width = it.filter { c -> c.isDigit() } },
                    label = { Text("宽度") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it.filter { c -> c.isDigit() } },
                    label = { Text("高度") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = bitrate,
                    onValueChange = { bitrate = it.filter { c -> c.isDigit() } },
                    label = { Text("码率 (kbps)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = fps,
                    onValueChange = { fps = it.filter { c -> c.isDigit() } },
                    label = { Text("帧率 (FPS)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val w = width.toIntOrNull() ?: 1280
                    val h = height.toIntOrNull() ?: 720
                    val br = (bitrate.toIntOrNull() ?: 3000) * 1000
                    val f = fps.toIntOrNull() ?: 25
                    onSave(w, h, br, f)
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
