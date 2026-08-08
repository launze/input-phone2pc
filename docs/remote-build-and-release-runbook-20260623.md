# Remote Build and Release Runbook

Date: 2026-06-23

This document records the repeatable build and upload method. Sensitive passwords are not stored here; local credential notes are in `artifacts/remote-build/remote-build-secrets.local.md`.

## Hosts

Linux ARM64 build host:

- SSH: `user@192.168.12.113`
- Proxy: `http://192.168.12.107:7890`
- OS target: Kylin V10 SP1 / glibc 2.31
- PuTTY host key: `SHA256:fEOPEcRVC5hICq4ay6cVCCVA5y35bMC1IC3j5iJ0LFQ`

Linux AMD64 build host:

- SSH: `custom@10.73.86.199`
- SSH port: `226`
- Proxy: `http://10.73.86.199:7899`
- OS target: Kylin V10 SP1 / glibc 2.31

Download server:

- SSH: `root@8.153.163.104`
- SSH key: `.deploy/voiceinput_deploy_key`
- Remote update root: `/opt/voiceinput/server/updates`
- Public URL root: `http://8.153.163.104:8888/voiceinput-updates`

Known host records are saved in `artifacts/remote-build/known_hosts-qr-gui-builds`.

## QR GUI Linux

Dependencies installed on both Linux build hosts:

```bash
sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y \
  build-essential cmake ninja-build pkg-config git ca-certificates curl \
  tar gzip unzip zip file patchelf binutils \
  libwxgtk3.0-gtk3-dev libopencv-dev libgtk-3-dev xvfb
```

Build and package:

```bash
cd ~/voiceinput-file-qr-gui-build
cmake -S voice-input-file-qr-gui -B build-linux-<arch> -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_EXE_LINKER_FLAGS='-static-libstdc++ -static-libgcc'
cmake --build build-linux-<arch> --parallel
~/package-qr-gui.sh <amd64|arm64> <version>
```

The packaging script is saved at `artifacts/remote-build/package-qr-gui.sh`. It bundles non-glibc runtime `.so` files into `lib/`, sets rpath to `$ORIGIN/lib`, and leaves glibc / the system loader unbundled.

Headless startup test:

```bash
cd ~/voiceinput-file-qr-gui-package-<arch>
xvfb-run -a timeout 8s ./run.sh
```

Exit code `124` is expected: the GUI entered the event loop and stayed running until the timeout.

## QR GUI Windows

Builds are local MSVC/vcpkg static builds. Current successful build directories:

- x64: `build/file-qr-gui-win64-static-v1216/voiceinput-file-qr-gui.exe`
- x86: `build/file-qr-gui-win32-static-v1216/voiceinput-file-qr-gui.exe`

Package as zip with `voiceinput-file-qr-gui.exe` and `README.txt`, then place into:

`voice-input-server/updates/stable/<version>/`

## Desktop Windows

Required local tools:

- Visual Studio 2022 MSVC
- Rust nightly as selected by `voice-input-desktop/src-tauri/rust-toolchain.toml`
- Rust targets: `x86_64-pc-windows-msvc`, `i686-pc-windows-msvc`
- NASM in PATH for x86 release builds of `aws-lc-sys`
- Node/npm and `@tauri-apps/cli`

Install prerequisites:

```powershell
rustup target add x86_64-pc-windows-msvc --toolchain nightly-x86_64-pc-windows-msvc
rustup target add i686-pc-windows-msvc --toolchain nightly-x86_64-pc-windows-msvc
choco install nasm -y --no-progress
```

Build x64:

```powershell
Copy-Item build\voiceinput-c2-reader-bridge-win64-static-v1216\voiceinput_c2_reader_bridge.dll `
  voice-input-desktop\src-tauri\resources\native\voiceinput_c2_reader_bridge.dll -Force
cmd /c 'call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvarsall.bat" x64 && npm run tauri -- build --target x86_64-pc-windows-msvc'
```

Build x86:

```powershell
Copy-Item build\voiceinput-c2-reader-bridge-win32-static-v1216\voiceinput_c2_reader_bridge.dll `
  voice-input-desktop\src-tauri\resources\native\voiceinput_c2_reader_bridge.dll -Force
cmd /c 'call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvarsall.bat" x86 && set "PATH=C:\Program Files\NASM;%PATH%" && npm run tauri -- build --target i686-pc-windows-msvc'
```

Copy outputs into `voice-input-server/updates/stable/<version>/` with stable names:

- `voiceinput-desktop-windows-x64-v<version>-setup.exe`
- `voiceinput-desktop-windows-x64-v<version>.msi`
- `voiceinput-desktop-windows-x86-v<version>-setup.exe`
- `voiceinput-desktop-windows-x86-v<version>.msi`

## Desktop Linux

Current status: not publishable on the Kylin V10 / Ubuntu 20 baseline while the desktop app uses Tauri 2.10.x.

Observed failures:

- ARM64 host: GLib 2.64.6, build requires `glib-2.0 >= 2.70`.
- AMD64 host: GLib/GObject 2.64.2, build requires `glib-2.0 >= 2.70` and `gobject-2.0 >= 2.70`.

Logs:

- `artifacts/remote-build/desktop-linux-arm64-build.log`
- `artifacts/remote-build/desktop-linux-amd64-build.log`

To produce Ubuntu 20 compatible Linux desktop packages, either lower the Tauri/WebKit dependency stack or raise the Linux runtime baseline.

## Manifest And Upload

Update local files under:

`voice-input-server/updates/stable/<version>/`

Update:

`voice-input-server/updates/stable/manifest.json`

Upload:

```powershell
ssh -i .deploy\voiceinput_deploy_key -p 22 root@8.153.163.104 'mkdir -p /opt/voiceinput/server/updates/stable/<version>'
scp -i .deploy\voiceinput_deploy_key -P 22 voice-input-server\updates\stable\<version>\* root@8.153.163.104:/opt/voiceinput/server/updates/stable/<version>/
scp -i .deploy\voiceinput_deploy_key -P 22 voice-input-server\updates\stable\manifest.json root@8.153.163.104:/opt/voiceinput/server/updates/stable/manifest.json
```

Verify:

```powershell
curl.exe -I http://8.153.163.104:8888/voiceinput-updates/stable/<version>/<file>
curl.exe http://8.153.163.104:8888/voiceinput-updates/stable/manifest.json
```
