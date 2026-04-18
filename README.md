# VoiceInput

一个从手机向电脑发送文本内容的跨平台输入项目。

`VoiceInput` 的目标很简单：
- 在手机上输入文字
- 通过局域网或中转服务连接电脑
- 让电脑端自动接收并写入当前输入位置



## 项目组成

```text
voiceinput/
├── voice-input-desktop/   # 桌面端，Rust + Tauri
├── VoiceInputApp/         # Android 客户端，Kotlin + Compose
├── voice-input-server/    # 可选中转服务，Rust
└── README.md
```

## 当前版本能力

### 桌面端
- 接收移动端发送的文本
- 局域网发现与连接
- 基础配对能力
- 桌面输入模拟
- 基础配置存储

### Android 端
- 输入文本并发送到桌面端
- 扫码/发现连接
- 基础连接状态展示
- 历史输入记录
- 服务器模式入口

### 服务端
- 设备注册
- 消息中转
- 配对关系维护
- 基础连接管理

## 技术栈

### Desktop
- Rust
- Tauri 2
- Tokio

### Android
- Kotlin
- Jetpack Compose
- OkHttp

### Server
- Rust
- Tokio
- WebSocket

## 快速开始

## 1. 桌面端

```bash
cd voice-input-desktop
npm install
npm run tauri build
```

开发模式：

```bash
cd voice-input-desktop
npm install
npm run tauri dev
```

## 2. Android 端

使用 Android Studio 打开 `VoiceInputApp`，然后直接运行或构建 APK。

命令行构建：

```bash
cd VoiceInputApp
./gradlew assembleRelease
```

Windows 下：

```bash
cd VoiceInputApp
gradlew.bat assembleRelease
```

## 3. 服务端

```bash
cd voice-input-server
cargo build --release
cargo run --release
```

## 使用说明

### 局域网模式
1. 启动桌面端
2. 启动手机端
3. 在同一网络下发现或扫码连接
4. 建立配对后开始输入

### 中转服务模式
1. 先启动 `voice-input-server`
2. 在桌面端和移动端分别配置服务端地址
3. 完成注册与配对
4. 开始发送输入内容

## 目录说明

### `voice-input-desktop`
桌面客户端，负责：
- 网络连接
- 配对流程
- 文本接收
- 本地输入模拟

### `VoiceInputApp`
Android 客户端，负责：
- 用户输入
- 设备发现
- 连接与配对
- 文本发送

### `voice-input-server`
中转服务，负责：
- 设备在线管理
- 配对关系管理
- 消息转发



## 许可证

MIT
