# GitHub Actions 工作流说明

## 工作流概述

本项目包含两个主要的 GitHub Actions 工作流：

### 1. Build All Platforms (`build-all.yml`)

完整的多平台构建工作流，用于发布版本。

**触发条件：**
- 推送到 `master` 或 `main` 分支
- 创建以 `v` 开头的标签（如 `v1.0.0`）
- Pull Request 到 `master` 或 `main` 分支
- 手动触发

**构建内容：**

#### Server
- **平台**: Linux AMD64
- **输出**: `voice-input-server` 二进制文件

#### Android APP
- **平台**: Android (ARM64-v8a, ARMv7, x86, x86_64)
- **输出**: Release APK (已签名)
- **版本**: 1.2.0 (versionCode: 2)

#### Desktop 客户端
支持以下平台和架构：

| 平台 | 架构 | 输出格式 |
|------|------|----------|
| Windows | x86_64 | MSI, NSIS Installer |
| macOS | x86_64 (Intel) | DMG |
| macOS | aarch64 (Apple Silicon) | DMG |
| Linux | x86_64 (AMD64) | DEB, AppImage |
| Linux | aarch64 (ARM64) | DEB, AppImage |

**构建产物：**
所有构建产物会上传为 GitHub Actions Artifacts，可在 Actions 页面下载。

**自动发布：**
当推送标签时（如 `v1.0.0`），会自动创建 GitHub Release 并上传所有构建产物。

### 2. Quick Build (`quick-build.yml`)

快速构建工作流，用于日常开发测试。

**触发条件：**
- 推送到 `dev` 或 `develop` 分支
- Pull Request 到 `dev` 或 `develop` 分支
- 手动触发

**构建内容：**
- Server: Debug 构建 + 运行测试
- Android: Debug APK
- Desktop: Debug 构建（仅 Linux）

## 使用方法

### 发布新版本

1. 更新版本号：
   - Server: `voice-input-server/Cargo.toml`
   - Android: `VoiceInputApp/app/build.gradle.kts`
   - Desktop: `voice-input-desktop/src-tauri/Cargo.toml` 和 `tauri.conf.json`

2. 提交更改：
   ```bash
   git add .
   git commit -m "chore: bump version to 1.2.0"
   git push
   ```

3. 创建并推送标签：
   ```bash
   git tag v1.2.0
   git push origin v1.2.0
   ```

4. GitHub Actions 会自动：
   - 构建所有平台
   - 创建 GitHub Release
   - 上传所有构建产物

### 手动触发构建

1. 访问 GitHub 仓库的 Actions 页面
2. 选择要运行的工作流
3. 点击 "Run workflow" 按钮
4. 选择分支并确认

### 下载构建产物

**从 Actions 页面：**
1. 访问 Actions 页面
2. 选择对应的工作流运行
3. 在 "Artifacts" 部分下载需要的文件

**从 Releases 页面：**
1. 访问 Releases 页面
2. 选择对应的版本
3. 在 "Assets" 部分下载需要的文件

## 构建产物命名规则

- Server: `voice-input-server-linux-amd64`
- Android: `voice-input-android-apk`
- Desktop Windows: `voice-input-desktop-windows-x86_64`
- Desktop macOS Intel: `voice-input-desktop-macos-x86_64`
- Desktop macOS ARM: `voice-input-desktop-macos-aarch64`
- Desktop Linux AMD64: `voice-input-desktop-linux-x86_64`
- Desktop Linux ARM64: `voice-input-desktop-linux-aarch64`

## 注意事项

### Android 签名
- 工作流中使用临时生成的 keystore
- 生产环境应使用 GitHub Secrets 存储真实的签名密钥
- 配置方法：
  1. 将 keystore 文件转换为 base64: `base64 voiceinput.keystore > keystore.txt`
  2. 在 GitHub 仓库设置中添加 Secrets:
     - `KEYSTORE_BASE64`: keystore 的 base64 内容
     - `KEYSTORE_PASSWORD`: keystore 密码
     - `KEY_ALIAS`: 密钥别名
     - `KEY_PASSWORD`: 密钥密码

### macOS 签名和公证
- 当前构建未签名
- 生产环境需要 Apple Developer 账号
- 需要配置代码签名证书和公证凭据

### Linux ARM64 交叉编译
- 使用 `gcc-aarch64-linux-gnu` 进行交叉编译
- 某些依赖可能需要额外配置

## 故障排查

### 构建失败
1. 检查 Actions 日志中的错误信息
2. 确认所有依赖版本兼容
3. 验证 Rust、Node.js、Java 版本

### 产物缺失
1. 检查构建日志中的警告
2. 确认文件路径正确
3. 验证 `if-no-files-found` 设置

### 缓存问题
1. 手动清除 Actions 缓存
2. 在工作流中添加 `cache-version` 键

## 性能优化

- 使用 GitHub Actions 缓存加速构建
- 并行构建多个平台
- 仅在标签推送时创建 Release

## 相关链接

- [GitHub Actions 文档](https://docs.github.com/en/actions)
- [Tauri 构建指南](https://tauri.app/v1/guides/building/)
- [Android Gradle 插件](https://developer.android.com/studio/build)
- [Rust 交叉编译](https://rust-lang.github.io/rustup/cross-compilation.html)
