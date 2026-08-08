# VoiceInput 1.2.16 PC and QR GUI Release

Date: 2026-06-23

## Uploaded downloads

Base URL:

`http://8.153.163.104:8888/voiceinput-updates/stable/1.2.16/`

| File | Size | SHA256 |
| --- | ---: | --- |
| voiceinput-android-v1.2.16.apk | 33406654 | 124520cc16322afa5d309b3ef5d57f151df5729d540ff9fd37aa715468e9e940 |
| voiceinput-desktop-windows-x64-v1.2.16-setup.exe | 8318676 | 1ecc6d1031b8f19bff0c21d391bed432e9366f34f46f297ccf4a63d6ddd0b3f7 |
| voiceinput-desktop-windows-x64-v1.2.16.msi | 11972608 | 3ae579d65cfb9959135185bd49b824a942cc62d4bf8c0a601c61980b51a26dd5 |
| voiceinput-desktop-windows-x86-v1.2.16-setup.exe | 7269144 | 1f0182b720a916aae797c8e8744fac9857dcbad85930c7f32a5096ba6bb7da5c |
| voiceinput-desktop-windows-x86-v1.2.16.msi | 10227712 | 9867a4d37ee353b9e8b14de37094a59fda3f59ff627674cfc4bf63bcd027d80b |
| voiceinput-file-qr-gui-windows-x64-v1.2.16.zip | 2708272 | 67f276179448819b25fe55d659ddd78a92c9389cb93296bf00408d552c0fa277 |
| voiceinput-file-qr-gui-windows-x86-v1.2.16.zip | 2360230 | 741729404654e350b12da1b14141616ccdd51250ca62806e0e0d23b795193b58 |
| voiceinput-file-qr-gui-linux-amd64-v1.2.16.tar.gz | 20081350 | 0c0e17496e59c5ddd713947ca5fa874b8109c7081b73cd8d82ee493e415744ae |
| voiceinput-file-qr-gui-linux-arm64-v1.2.16.tar.gz | 18454650 | 859f68475956b99e9c0d270c1ed9f58f03603d66093ede7ac47507325f6e794b |

All files above were verified with HTTP 200 from the 8888 service. The remote manifest at
`/opt/voiceinput/server/updates/stable/manifest.json` was updated from local
`voice-input-server/updates/stable/manifest.json`.

## Build notes

- QR GUI Linux amd64/arm64 was built on Kylin V10 hosts with glibc 2.31 and packaged with bundled wxWidgets/OpenCV runtime libraries.
- QR GUI Linux startup was verified under `xvfb-run`; the process stayed in the GUI event loop until the test timeout.
- QR GUI Windows x64/x86 was built locally with MSVC and static vcpkg dependencies.
- Desktop Windows x64/x86 was built locally with Tauri 2.10.1. The matching `voiceinput_c2_reader_bridge.dll` was copied before each architecture build.
- Local NASM was installed with Chocolatey because the Windows x86 Rust dependency `aws-lc-sys` requires NASM for release builds.

## Linux desktop blocker

No Linux desktop Tauri package was published for 1.2.16.

The current desktop app uses Tauri 2.10.x. Real builds on the Kylin V10 hosts failed because the systems have GLib/GObject 2.64.x, while the Linux dependency chain requires `glib-2.0 >= 2.70` and `gobject-2.0 >= 2.70`. The Kylin V10/Ubuntu 20 era hosts also only provide WebKitGTK 4.0/libsoup2, while current Tauri 2 Linux builds require the newer WebKitGTK/libsoup stack. This makes a Ubuntu 20 compatible Linux desktop package unavailable without either lowering the desktop Tauri/WebKit dependency stack or raising the Linux baseline.

Saved logs:

- `artifacts/remote-build/desktop-linux-arm64-build.log`
- `artifacts/remote-build/desktop-linux-amd64-build.log`
