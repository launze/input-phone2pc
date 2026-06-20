# 语传文件二维码生成工具 native GUI

这是 HTML 文件二维码生成工具的 wxWidgets + C++ 落地版本。目标是只保留一个功能：

- 选择文件
- 使用和现有 HTML 工具一致的 libcimbar 编码链生成动态二维码
- 在原生窗口中用 CPU 位图显示动态帧

它不使用 WebView、WebGL、OpenGL、浏览器内核，也不提供保存图片、压缩包导出等功能。

## 编码兼容性

当前实现对齐现有 HTML sender 的默认路径：

- `cimbar::Config::update(68)`，界面默认显示为 `C2`
- zstd 压缩等级 `16`
- 文件名写入 zstd skippable header
- `fountain_encoder_stream`
- `Encoder::encode_next`
- 初始 encode id 为 `109`，每次切换文件递增
- 显示帧率默认 `45 fps`
- 帧率使用和 HTML 工具一致的预设按钮：`15 / 20 / 25 / 30 / 40 / 45 / 50 / 60`

## 构建依赖

需要：

- CMake 3.16+
- C++17 编译器
- wxWidgets 3.2
- OpenCV core/imgproc/imgcodecs/photo

Windows 7 兼容建议使用：

- wxWidgets 3.2
- MSVC v141/v142 toolset 或 MinGW-w64
- 不要使用需要 Windows 10 API 的运行时打包方式

Linux amd64 / arm64 建议在目标架构上原生编译，或者用对应 sysroot 交叉编译。

## 本地构建示例

```powershell
cmake -S voice-input-file-qr-gui -B build/file-qr-gui -DCMAKE_BUILD_TYPE=Release
cmake --build build/file-qr-gui --config Release
```

如果 wxWidgets 或 OpenCV 不在默认路径，需要传入对应的 CMake 变量，例如：

```powershell
cmake -S voice-input-file-qr-gui -B build/file-qr-gui `
  -DwxWidgets_ROOT_DIR=C:\deps\wxWidgets `
  -DOpenCV_DIR=C:\deps\opencv\build
```

## 后续发布目标

建议产物命名：

- `voiceinput-file-qr-gui-windows-x86-v<version>.zip`
- `voiceinput-file-qr-gui-windows-x64-v<version>.zip`
- `voiceinput-file-qr-gui-linux-x64-v<version>.tar.gz`
- `voiceinput-file-qr-gui-linux-arm64-v<version>.tar.gz`

Linux 如果要单文件分发，可以后续加 AppImage；Windows 可以先发布 zip 便携包。

## GitHub Actions 多平台构建

已添加 `.github/workflows/build-file-qr-gui.yml`，支持：

- Windows x64：`x64-windows-static`
- Windows x86：`x86-windows-static`
- Linux amd64：`ubuntu-24.04`
- Linux arm64：`ubuntu-24.04-arm`

手动运行 workflow 会把四个平台的包上传为 Actions artifacts；推送 `v*` tag 时还会把这些包上传到 GitHub Release。
