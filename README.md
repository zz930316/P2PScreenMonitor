# P2P Screen Monitor

实时 Android 屏幕投屏与控制工具，支持通过 TCP 网络在设备间进行低延迟的视频流传输和触控反向控制。

## 功能特性

- 📱 **实时屏幕采集** — 基于 `MediaProjection` API 实现高质量屏幕捕获
- 🎬 **H.264 硬编码** — 使用 Android `MediaCodec` 进行硬件视频编码，降低 CPU 占用
- 🔄 **TCP 网络传输** — 自定义二进制协议，低开销传输视频帧与触控事件
- 👆 **触控反向控制** — 接收端可在大屏上操作发送端设备
- 🎨 **Jetpack Compose UI** — 现代化的 Material Design 3 界面
- ⚡ **自适应码率** — 实时 FPS 和码率统计，支持动态调整
- 🔗 **断线重连** — 指数退避自动重连机制，最多 5 次
- 📜 **连接历史** — 自动记录 IP 地址，快速回连

## 项目架构

```
P2PScreenMonitor/
├── app/                 # 共享模块（协议、编解码、网络、UI 组件）
│   └── com.p2p.monitor/
│       ├── capture/     # 屏幕采集 (MediaProjection + VirtualDisplay)
│       ├── codec/       # 视频编解码 (H.264 MediaCodec)
│       ├── model/       # 通信协议定义
│       ├── network/     # TCP 客户端与服务端
│       ├── ui/          # Compose 主题与公共组件
│       └── util/        # 工具类（日志、IP、连接历史）
├── sender/              # 发送端应用（屏幕采集 + 流媒体服务）
│   └── CaptureService   # 前台服务，持续采集并推流
├── receiver/            # 接收端应用（视频播放 + 触控反馈）
│   └── ReceiverActivity # SurfaceView 解码渲染 + 触控注入
└── main.cpp             # C++ 参考实现
```

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 1.9.22 |
| UI | Jetpack Compose + Material Design 3 |
| 视频编码 | MediaCodec (H.264/AVC) |
| 网络 | Java TCP Socket + 自定义二进制协议 |
| 并发 | Kotlin Coroutines + ExecutorService |
| 构建 | Gradle 8.2, AGP 8.2.0 |
| 最低 SDK | Android 8.0 (API 26) |

## 通信协议

自定义二进制数据包格式：

| 字段 | 大小 | 说明 |
|------|------|------|
| Length | 4 bytes | 负载长度 (Big-Endian) |
| Type | 1 byte | 数据包类型 |
| Data | Variable | 负载数据 |

**数据包类型：**

| 值 | 类型 | 说明 |
|----|------|------|
| 0 | FRAME | H.264 视频帧数据 |
| 1 | CONFIG | SPS/PPS 编解码器配置 |
| 2 | TOUCH | 触控事件 (action, x, y, pointerId) |
| 3 | RESOLUTION | 分辨率通知 (width, height) |

默认端口：**8888**

## 快速开始

### 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17+
- Android SDK 34

### 构建步骤

1. 用 Android Studio 打开项目
2. 同步 Gradle 依赖
3. 分别编译 `sender` 和 `receiver` 模块
4. 将两个 APK 安装到两台 Android 设备

### 使用流程

**发送端 (Sender):**
1. 打开 Sender 应用
2. 点击「开始投屏」，授予屏幕录制权限
3. 点击「开始服务」启动前台采集服务
4. 记录显示的本地 IP 地址

**接收端 (Receiver):**
1. 打开 Receiver 应用
2. 输入发送端的 IP 地址
3. 点击「连接」
4. 开始观看屏幕，支持触控操作

**流媒体参数预设:**
- 720p @ 3Mbps, 25fps（推荐）
- 1080p @ 5Mbps, 30fps
- 480p @ 1.5Mbps, 20fps
- 360p @ 800kbps, 15fps

## 核心模块说明

### ScreenCapture (`app/capture`)
使用 `MediaProjection.createVirtualDisplay()` 创建虚拟显示，将屏幕画面输出到 Surface。

### VideoEncoder (`app/codec`)
基于 `MediaCodec` 的 H.264 硬件编码器，配置 1280×720 / 3Mbps / 25fps 默认参数，分离 SPS/PPS 配置帧与视频帧。

### VideoDecoder (`app/codec`)
`MediaCodec` 软解码器，从 TCP 流中接收 H.264 数据并输出 YUV 帧供 SurfaceView 渲染。

### TcpServer (`app/network`)
接收端的服务端实现，监听 8888 端口，处理视频帧接收与触控事件转发。

### TcpClient (`app/network`)
发送端的客户端实现，建立 TCP 连接后持续推送编码后的视频帧，支持断线自动重连。

### CaptureService (`sender`)
前台服务，保持屏幕采集在后台持续运行，支持触控事件注入到发送端设备。

## 构建配置

```groovy
// 版本汇总
AGP: 8.2.0
Kotlin: 1.9.22
Compose BOM: 2024.02.00
Compile SDK: 34
Min SDK: 26
```

## License

MIT

## 联系方式

GitHub: [zz930316/P2PScreenMonitor](https://github.com/zz930316/P2PScreenMonitor)
