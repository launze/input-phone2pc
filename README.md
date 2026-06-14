# 语传：手机输入到电脑

一个跨设备输入系统。用户在手机上用任意输入法输入文字，包含手机语音输入法转换后的文字，然后发送到电脑端并插入到当前光标位置。语传本身不做语音识别。

当前路线：统一 PC 与 App 的历史记录、通知记录和 AI 助手能力，让手机输入、通知和文件记录都能被检索、导出和总结。

## 启动 / 停止项目服务（Windows PowerShell）

在项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\start-project-services.ps1
```

默认会同时启动 `voice-input-server` 中转服务器和 `voice-input-desktop` 桌面端开发应用。日志会写入 `.runtime\logs`。

只启动中转服务器：

```powershell
powershell -ExecutionPolicy Bypass -File .\start-project-services.ps1 -ServerOnly
```

只启动桌面端：

```powershell
powershell -ExecutionPolicy Bypass -File .\start-project-services.ps1 -DesktopOnly
```

停止全部由脚本启动的服务：

```powershell
powershell -ExecutionPolicy Bypass -File .\stop-project-services.ps1
```

只停止中转服务器：

```powershell
powershell -ExecutionPolicy Bypass -File .\stop-project-services.ps1 -ServerOnly
```

只停止桌面端：

```powershell
powershell -ExecutionPolicy Bypass -File .\stop-project-services.ps1 -DesktopOnly
```

## 项目结构

```
voiceinput/
├── voice-input-desktop/     # Rust + Tauri 桌面客户端
├── VoiceInputApp/           # Android 客户端
├── voice-input-server/      # Rust 中转服务器
└── README.md
```

## 核心特性

### 基础功能
- ✅ **局域网自动发现** - UDP 广播机制，手机自动发现电脑
- ✅ **安全配对** - PIN 码验证，首次连接需要确认
- ✅ **实时文本传输** - TCP 可靠传输，文本即时发送到电脑
- ✅ **跨平台键盘模拟** - 使用 enigo 库，支持 Windows/macOS/Linux
- ✅ **历史记录** - 本地会话历史，支持重新编辑发送
- ✅ **自动重连** - 连接断开后自动尝试重连
- ✅ **心跳检测** - 每 5 秒检测连接状态

### 生产力功能
- ✅ **历史记录生产力化** - 搜索、按类型/设备/通道/状态/App 筛选，支持收藏、置顶、标签、批量删除和批量导出
- ✅ **独立通知记录** - 手机通知可常驻监听、过滤、脱敏、转发到电脑，并在 PC/App 中作为独立入口查看
- ✅ **AI 助手** - PC 端基于历史输入、通知、文件/图片 metadata 调用工具回答问题，支持 Skills、ReAct 工具过程、追问、会话记录和 Word 导出
- ✅ **App AI 入口** - 手机端可通过服务器中转向 PC AI 助手提问，接收流式回答和工具调用事件

### 连接与设备
- ✅ **中转服务器** - 解决跨局域网连接问题
- ✅ **服务端接入** - 手动开启，支持远程连接
- ✅ **通知转发** - 手机通知自动转发到电脑
- ✅ **设备管理** - 查看在线设备、默认电脑、上次连接时间，支持重连和取消配对

## 技术栈

### 桌面端
- Rust + Tauri 2.x
- Tokio（异步运行时）
- Enigo（跨平台键盘模拟）
- Serde（JSON 序列化）

### Android 端
- Kotlin
- Jetpack Compose（UI）
- Coroutines（异步）
- OkHttp（WebSocket）
- Gson（JSON 处理）

### 服务器端（新增）
- Rust
- Tokio + Tokio-Tungstenite（WebSocket）
- DashMap（并发哈希表）
- Tracing（日志）

## 快速开始

### 1. 局域网直连模式（默认）

#### 桌面端
```bash
cd voice-input-desktop
npm install
npm run dev
```

#### Android 端
使用 Android Studio 打开 `VoiceInputApp` 目录，然后运行项目。

#### 使用
1. 确保手机和电脑在同一局域网
2. 启动桌面端应用
3. 打开手机应用，点击"连接"
4. 选择电脑，完成配对
5. 开始输入！

### 2. 服务器中转模式（跨局域网）

#### 启动服务器
```bash
cd voice-input-server
cargo build --release
./target/release/voice-input-server
```

服务器将在 `0.0.0.0:7070` 启动。

#### 配置客户端

**Android 端**:
1. 打开应用设置
2. 开启"服务器模式"
3. 输入服务器地址：`wss://nas.smarthome2020.top:7070`
4. 连接成功后查看在线设备
5. 选择设备进行配对

**Desktop 端**:
1. 托盘菜单 → 服务器模式
2. 输入服务器地址
3. 连接并等待配对请求

### 3. 通知转发功能

#### 授予权限
1. 设置 → 通知访问权限
2. 找到"语传"
3. 授予权限

#### 开启转发
1. 应用设置 → 通知转发
2. 开启功能
3. 选择要转发的 App、过滤关键词和转发模式
4. 手机收到的通知将自动记录，并按设置转发到电脑

### 4. AI 助手

1. 在 PC 端打开“AI 助手”Tab
2. 配置模型 API Key、URL 和模型名
3. 直接提问，或从历史记录/通知记录页基于当前筛选发起 AI 总结
4. LLM 会自主选择 Skills 和本地工具，例如查询历史、查询通知、摘要记录、导出 Word

App 端也有独立“AI 助手”入口，可把问题通过服务器中转交给 PC 端执行，并接收流式回答和工具调用状态。

## 网络协议

### 局域网模式
- **UDP 发现**（端口 58888）- 设备发现
- **TCP 数据传输**（端口 58889）- 文本传输

### 服务器模式
- **WebSocket**（端口 7070）- 消息中转
- **消息类型**: 注册、配对、中转、通知转发

## 部署架构

### 局域网直连
```
手机 ←→ 电脑
(UDP + TCP)
```

### 服务器中转
```
手机 ←→ 中转服务器 ←→ 电脑
    (WebSocket)
```

### 推荐部署
```
┌─────────────┐
│   Nginx     │ ← SSL 终止
│   (443)     │
└──────┬──────┘
       │
┌──────▼──────┐
│ 中转服务器   │
│   (7070)    │
└─────────────┘
```

## 开发进度

### v1.0.0 (已完成)
- [x] 项目基础结构
- [x] 网络协议定义
- [x] Android 网络层
- [x] Rust 网络层
- [x] 配对功能
- [x] 文本输入和键盘模拟
- [x] 历史记录
- [x] UI 优化
- [x] 错误处理和重连
- [x] 跨平台测试
- [x] 打包发布

### v1.1.0 (已完成)
- [x] 中转服务器设计
- [x] 服务器实现
- [x] Android 端服务器接入
- [x] Desktop 端服务器接入
- [x] 服务端配对机制
- [x] 通知监听服务
- [x] 通知转发协议
- [x] Desktop 端通知显示
- [x] 文档更新

### 当前路线（进行中）
- [x] PC 主界面拆分为历史记录、AI 助手、通知记录
- [x] App 主入口拆分为历史记录、AI 助手、通知记录
- [x] 历史/通知搜索、筛选、收藏、置顶、标签、批量删除、批量导出
- [x] App 输入框长文本、草稿保存、键盘安全发送、快速粘贴和附件面板
- [x] 通知常驻前台服务、监听权限引导、App 选择、过滤、脱敏和后台转发
- [x] AI 助手 Skills、会话、SSE/等价流式事件、受控 ReAct、工具调用追踪和 Word 导出
- [x] App AI 通过 PC 端同一套 Skills 和工具执行，支持追问、停止和 Markdown 导出

## 文档

- [README.md](README.md) - 项目介绍（本文件）

## 性能指标

### 局域网模式
- 延迟: < 50ms
- 吞吐: 无限制

### 服务器模式
- 并发连接: 10,000+
- 消息延迟: < 100ms
- 消息吞吐: 10,000 msg/s

## 安全性

1. **局域网模式**: PIN 码配对，设备白名单
2. **服务器模式**: WSS 加密，设备认证，配对验证
3. **通知转发**: 仅转发已配对设备

## 已知限制

1. **enigo 库限制** - 在某些应用中可能无法正确输入中文
2. **macOS 权限** - 需要手动授予辅助功能权限
3. **通知权限** - Android 需要授予通知访问权限

## 故障排除

### 局域网连接问题
1. 检查手机和电脑是否在同一网络
2. 检查防火墙设置（端口 58888, 58889）
3. 尝试重启应用

### 服务器连接问题
1. 检查服务器地址是否正确
2. 检查服务器是否运行
3. 检查网络连接
4. 查看服务器日志

### 通知转发问题
1. 检查通知访问权限
2. 确认设备已配对
3. 检查通知转发开关

## 贡献

欢迎提交 Issue 和 Pull Request！

## 许可证

MIT License - 详见 [LICENSE](LICENSE) 文件

## CI/CD 配置

本项目使用 GitHub Actions 进行持续集成和部署，支持自动编译不同操作系统版本的桌面客户端。

### GitHub Actions 工作流

#### 桌面客户端编译

工作流配置将自动编译以下平台的桌面客户端：
- **Windows** (x86_64)
- **macOS** (x86_64, arm64)
- **Linux** (x86_64)

#### 触发条件
- 推送到 `main` 分支
- 手动触发
- 标签发布

### 构建产物

编译完成后，构建产物将作为 GitHub Release 的附件上传，包括：
- Windows: `.exe` 安装包
- macOS: `.dmg` 安装包
- Linux: `.deb` 和 `.AppImage` 安装包

## 致谢

感谢以下开源项目：
- Tauri - 跨平台桌面应用框架
- Enigo - 跨平台键盘模拟
- Jetpack Compose - Android 现代化 UI 框架
- Tokio - Rust 异步运行时
- OkHttp - HTTP/WebSocket 客户端

---

**项目状态**: ✅ v1.1.0 已完成  
**最后更新**: 2026-03-15
