# GitHub Copilot Instructions - VoiceInput 跨平台输入系统

## 项目概述

局域网跨平台输入系统，支持手机→电脑文本传输（含语音转文字）。包含三个子系统：
- **voice-input-desktop**: Rust + Tauri 2.x 桌面客户端（Windows/macOS/Linux）
- **VoiceInputApp**: Kotlin + Jetpack Compose Android 客户端
- **voice-input-server**: Rust 中转服务器（支持跨局域网连接）

## 核心架构模式

### 通信协议
所有组件使用统一的 JSON 协议（见 `protocol.rs`），消息类型采用 `serde` tag 枚举：
```rust
#[serde(tag = "type")]
pub enum Message {
    #[serde(rename = "DISCOVER")]
    Discover { device_id, device_name, version },
    #[serde(rename = "TEXT_INPUT")]
    TextInput { text, timestamp },
    // ... 其他类型
}
```

### 双模连接架构
1. **直连模式**：UDP 广播发现 + TCP 直连（默认）
2. **中转模式**：WebSocket Secure (WSS) + 服务器中继（跨局域网）

### 加密方案
- AES-GCM 对称加密（`aes-gcm` crate）
- 密钥交换通过 `ENCRYPTION_KEY_EXCHANGE` 消息
- TLS 证书位于各子项目根目录 (`cert.pem`, `key.pem`)

## 关键技术栈

### Rust (桌面端/服务器)
- **异步运行时**: Tokio 1.40 (`full` features)
- **WebSocket**: tokio-tungstenite 0.25 (桌面), 0.21 (服务器)
- **TLS**: rustls 0.23 (桌面), 0.21 (服务器)
- **序列化**: serde + serde_json
- **键盘模拟**: enigo 0.2 (仅桌面端)
- **并发**: dashmap 5.5 (服务器), tokio::sync::Mutex

### Android (Kotlin)
- **UI**: Jetpack Compose + Material 3
- **网络**: OkHttp 4.12 (WebSocket), Gson 2.10
- **异步**: Coroutines + Flow
- **导航**: Navigation Compose
- **二维码**: ZXing (`com.journeyapps:zxing-android-embedded`)

## 开发工作流

### 构建命令

**桌面端 (PowerShell)**:
```powershell
cd voice-input-desktop
npm install
npm run dev      # 开发模式
npm run build    # 生产构建
```

**服务器**:
```bash
cd voice-input-server
cargo build --release
./target/release/voice-input-server  # 监听 0.0.0.0:7070
```

**Android**:
使用 Android Studio 打开 `VoiceInputApp/`，SDK 34, minSdk 24

### 全平台编译
```powershell
.\build.ps1  # 自动编译服务器 + 桌面端
```

### 代码组织约定

**Rust 模块结构**:
```
src/
├── main.rs          # Tauri 命令入口 (桌面) / async main (服务器)
├── network/
│   ├── mod.rs       # 服务启动逻辑
│   ├── protocol.rs  # 消息定义
│   ├── discovery.rs # UDP 广播
│   ├── connection.rs# TCP 服务器
│   └── websocket.rs # WSS 客户端
├── input/           # 键盘模拟 (仅桌面)
├── pairing/         # 配对管理
└── storage/         # 配置持久化
```

**Android 包结构**:
```kotlin
com.voiceinput/
├── MainActivity.kt       # Compose 导航入口
├── viewmodel/
│   └── InputViewModel.kt # 核心业务逻辑
├── network/
│   ├── NetworkManager.kt # 直连模式
│   └── ServerConnection.kt # 中转模式
└── ui/screens/           # Compose 界面
```

## 重要实现细节

### 1. 配对流程
1. 生成 6 位 PIN 码
2. 发送 `PAIR_REQUEST` → 等待 `PAIR_RESPONSE`
3. 成功后存储对方 `device_id` 到本地配置
4. 交换加密密钥 (`ENCRYPTION_KEY_EXCHANGE`)

### 2. 心跳机制
- 每 5 秒发送 `Heartbeat { timestamp }`
- 超时判定：连续 3 次无响应 → 触发重连

### 3. 服务器中继消息路由
```rust
// 服务器维护 DashMap<device_id, TxSender>
RelayMessage { from_device_id, to_device_id, payload }
```

### 4. 配置存储
- 桌面端：`dirs::config_dir()` + `config.json`
- Android: SharedPreferences (通过 `ServerConfig` 封装)
- 服务器：SQLite (`pairings.db` via rusqlite)

## 测试与调试

### 常见问题排查
1. **连接失败**: 检查防火墙是否开放 7070 端口
2. **PIN 码不匹配**: 清除 `pairings.db` 重试
3. **TLS 错误**: 验证 `cert.pem`/`key.pem` 格式 (PEM)

### 日志查看
- Rust: `tracing_subscriber` / `println!`
- Android: `Log.d(TAG, msg)` + Logcat
- 服务器启动时会打印 `info!` 级别日志

## 外部依赖注意事项

| 依赖 | 用途 | 特殊配置 |
|------|------|----------|
| `enigo` | 键盘模拟 | 需要系统辅助功能权限 |
| `tokio-tungstenite` | WebSocket | 启用 `__rustls-tls` feature |
| `rusqlite` | 数据库 | 使用 `bundled` feature 避免系统依赖 |
| `zxing-android-embedded` | 扫码 | 需申请相机权限 |

## 修改指南

### 添加新消息类型
1. 在 `protocol.rs` 扩展枚举
2. 同步更新两端协议定义
3. 更新 `CHANGELOG.md`

### 修改 UI 行为
- 桌面端：编辑 `src/index.html` + `src/main.js`
- Android: 修改 `ui/screens/*.kt` + `InputViewModel.kt`

### 调整网络参数
- UDP 端口：`discovery.rs` 中的 `PORT` 常量
- 心跳间隔：`connection.rs` / `ServerConnection.kt` 中的定时器
