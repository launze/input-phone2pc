# VoiceInput

> 在手机上输入，在电脑上直接落字。

[English README](./README.md)

`VoiceInput` 是一个跨平台输入项目，用来把 Android 手机上输入的文本发送到桌面端，并直接写入当前聚焦的输入位置。

它面向简单直接的跨设备文本输入场景，支持局域网内快速发现与配对，也支持通过可选中转服务实现远程连接。



## 项目亮点

- **手机到桌面的文本输入**
- **局域网自动发现与配对**
- **桌面端文本注入**
- **可选中转服务架构**
- **跨平台桌面支持**：Windows、macOS、Linux
- **原生 Compose Android 客户端**

## 仓库结构

```text
voiceinput/
├── voice-input-desktop/   # 桌面客户端（Rust + Tauri）
├── VoiceInputApp/         # Android 客户端（Kotlin + Jetpack Compose）
├── voice-input-server/    # 中转服务（Rust）
├── README.md
├── README.zh-CN.md
├── CHANGELOG.md
└── LICENSE
```

## 组件说明

### Desktop Client
桌面应用负责：
- 接收移动端发送的文本
- 在局域网中发现设备
- 处理配对与连接状态
- 将收到的文本写入当前桌面输入目标

### Android Client
Android 应用负责：
- 输入并发送文本
- 发现桌面端或通过扫码连接
- 管理连接状态
- 提供轻量的移动端输入体验

### Relay Server
可选的中转服务负责：
- 设备注册
- 消息中继转发
- 配对信息维护
- 支持超出单一局域网的连接场景

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

## 1. Desktop Client

构建：

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

## 2. Android Client

使用 Android Studio 打开 `VoiceInputApp`，然后直接运行。

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

## 3. Relay Server

```bash
cd voice-input-server
cargo build --release
cargo run --release
```

## 使用方式概览

### 局域网模式
1. 启动桌面客户端
2. 启动 Android 客户端
3. 在同一网络下发现或扫码连接桌面端
4. 完成设备配对
5. 开始发送文本

### 中转服务模式
1. 启动 `voice-input-server`
2. 在桌面端和 Android 端配置服务地址
3. 注册并完成配对
4. 通过服务端中继发送文本

## Release 文件说明

项目发布页中可能包含多个平台的安装包，通常采用如下命名：

- `voiceinput-android-v0.0.1.apk`
- `voiceinput-desktop-windows-x64-v0.0.1.msi`
- `voiceinput-desktop-windows-x64-v0.0.1-setup.exe`
- `voiceinput-desktop-macos-x64-v0.0.1.dmg`
- `voiceinput-desktop-macos-arm64-v0.0.1.dmg`
- `voiceinput-desktop-linux-x64-v0.0.1.deb`
- `voiceinput-desktop-linux-x64-v0.0.1.AppImage`

这样做的目的是让用户在 Releases 页面一眼就能看懂每个文件对应的平台和用途。



## 后续方向

后续计划可能包括：
- 更好的配对与首次使用引导
- 更完善的安装包与发布分发
- 更丰富的移动端输入交互
- 更稳定的远程中转支持
- 更完整的配置与部署文档

## 贡献

欢迎提交 Issue 和 Pull Request。

如果你想参与贡献，建议先：
1. 在本地构建相关组件
2. 验证当前行为
3. 提交聚焦、明确的修改

## 许可证

MIT —— 详见 `LICENSE`。
