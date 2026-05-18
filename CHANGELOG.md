# 更新日志

## [1.2.0] - 2024-12-20

### 新增功能
- ✨ Android应用添加对话记录永久保存功能
  - 自动保存所有发送的文本
  - 支持搜索历史记录
  - 支持删除单条或清空所有记录
  - 历史记录持久化存储，应用重启后仍可查看

### 修复
- 🐛 修复Android摄像头自动横屏问题，强制竖屏显示
- 🐛 修复扫码配对时的屏幕方向问题

### 改进
- 🎨 优化历史记录UI，添加搜索和删除按钮
- 🎨 历史记录卡片支持长按删除
- 🎨 所有删除操作添加确认对话框，防止误操作
- 📝 添加详细的功能使用文档

### 开发相关
- 🔧 添加GitHub Actions多平台自动构建工作流
  - 支持Server (Linux AMD64)
  - 支持Android APK自动构建
  - 支持Desktop多平台构建：
    - Windows x86_64
    - macOS Intel/Apple Silicon
    - Linux AMD64/ARM64
- 🔧 清理项目临时文件和测试脚本
- 🔧 优化.gitignore配置

### 技术栈
- Android: Kotlin + Jetpack Compose + Material3
- Desktop: Tauri 2.0 + Rust
- Server: Rust + Tokio + WebSocket

---

## [1.0.0] - 2024-12-01

### 初始版本
- 🎉 首次发布
- ✨ 支持手机到电脑的语音输入
- ✨ 支持局域网直连和服务器中转
- ✨ 支持设备配对和管理
- ✨ 支持通知转发
- ✨ 跨平台支持（Windows、macOS、Linux、Android）
