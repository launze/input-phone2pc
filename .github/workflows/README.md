# GitHub Actions 工作流说明

本目录中的 workflow 用于项目当前版本的自动构建与发布。



## 当前用途

- 代码推送后的自动构建
- 多端构建验证
- 基于 tag 的发布流程

## 发布建议

发布新版本时，统一同步以下位置：

- `voice-input-server/Cargo.toml`
- `voice-input-desktop/package.json`
- `voice-input-desktop/src-tauri/Cargo.toml`
- `voice-input-desktop/src-tauri/tauri.conf.json`
- `VoiceInputApp/app/build.gradle.kts`
- `CHANGELOG.md`

## 说明

如果 workflow 发生调整，以仓库中的实际 YAML 配置为准。
