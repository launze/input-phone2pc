# QR GUI Linux remote build - 2026-06-23

## Outputs

- Local artifacts: `artifacts/qr-gui-remote-dev-6/`
- Server update directory: `voice-input-server/updates/stable/1.2.16/`
- Packages:
  - `voiceinput-file-qr-gui-linux-amd64-vdev-6.tar.gz`
  - `voiceinput-file-qr-gui-linux-arm64-vdev-6.tar.gz`

## Build hosts

- linux arm64: `user@192.168.12.113`
  - Proxy used: `http://192.168.12.107:7890`
  - OS: Kylin V10 SP1, aarch64
  - glibc: `2.31`
  - Compiler: GCC/G++ 9.3.0
- linux amd64: `custom@10.73.86.199:226`
  - Proxy used: `http://10.73.86.199:7899`
  - OS: Kylin V10 SP1, x86_64
  - glibc: `2.31`
  - Compiler: GCC/G++ 9.3.0

SSH host keys are saved at `artifacts/remote-build/known_hosts-qr-gui-builds`.
The 8888 deployment key already exists locally at `.deploy/voiceinput_deploy_key`.
Build host passwords are intentionally not written to this document.

## Dependencies installed

Installed from Kylin apt repositories:

- `build-essential`
- `cmake`
- `ninja-build`
- `pkg-config`
- `git`
- `curl`
- `patchelf`
- `binutils`
- `libwxgtk3.0-gtk3-dev`
- `libopencv-dev`
- `libgtk-3-dev`
- `xvfb` for headless launch testing

## Build commands

Source archive uploaded to both hosts:

```bash
~/voiceinput-file-qr-gui-src.tgz
```

Build:

```bash
cd ~/voiceinput-file-qr-gui-build
cmake -S voice-input-file-qr-gui -B build-linux-<arch> -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_EXE_LINKER_FLAGS='-static-libstdc++ -static-libgcc'
cmake --build build-linux-<arch> --parallel
```

Package:

```bash
~/package-qr-gui.sh <amd64|arm64> dev-6
```

The packaging script is saved at `artifacts/remote-build/package-qr-gui.sh`.
It copies wxWidgets/OpenCV and related runtime `.so` files into `lib/`, sets rpath to `$ORIGIN/lib`, and intentionally does not bundle glibc or the system dynamic loader.

## Verification

- `ldd ./voiceinput-file-qr-gui` reported no missing libraries on both build hosts.
- Direct SSH launch without a desktop display failed as expected with `Unable to initialize GTK+, is DISPLAY set properly?`.
- `xvfb-run -a timeout 8s ./run.sh` returned `124` on both hosts, meaning the GUI stayed running until timeout.

Symbol compatibility:

- arm64 executable max GLIBC symbol: `GLIBC_2.27`
- amd64 executable max GLIBC symbol: `GLIBC_2.27`
- bundled library max GLIBC symbol observed: `GLIBC_2.30`
- bundled library max GLIBCXX symbol observed: `GLIBCXX_3.4.28`

This avoids the previous `GLIBC_2.35` / `GLIBC_2.38` and `GLIBCXX_3.4.32` failures.
