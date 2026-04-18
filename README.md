# VoiceInput

> Input on your phone, type on your computer.

[简体中文说明](./README.zh-CN.md)

`VoiceInput` is a cross-platform input project that lets you send text from an Android phone to a desktop machine and insert it directly into the current input target.

It is designed for simple cross-device text input, quick pairing on a local network, and an optional relay server for remote connections.

Current public starting release: **`0.0.1`**

## Highlights

- **Phone-to-desktop text input**
- **Local network discovery and pairing**
- **Desktop text injection**
- **Optional relay server architecture**
- **Cross-platform desktop target**: Windows, macOS, Linux
- **Android client with a native Compose UI**

## Repository Structure

```text
voiceinput/
├── voice-input-desktop/   # Desktop client (Rust + Tauri)
├── VoiceInputApp/         # Android client (Kotlin + Jetpack Compose)
├── voice-input-server/    # Relay server (Rust)
├── README.md
├── CHANGELOG.md
└── LICENSE
```

## Components

### Desktop Client
The desktop application is responsible for:
- receiving text from the mobile client
- discovering devices on the local network
- handling pairing and connection state
- inserting incoming text into the current desktop input target

### Android Client
The Android application is responsible for:
- entering and sending text
- discovering or scanning a desktop endpoint
- managing connection state
- providing a lightweight mobile input experience

### Relay Server
The optional relay server is responsible for:
- registering devices
- relaying messages between endpoints
- maintaining pairing information
- supporting connections beyond a single LAN

## Tech Stack

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

## Getting Started

## 1. Desktop Client

Build:

```bash
cd voice-input-desktop
npm install
npm run tauri build
```

Run in development mode:

```bash
cd voice-input-desktop
npm install
npm run tauri dev
```

## 2. Android Client

Open `VoiceInputApp` in Android Studio and run it directly.

CLI build:

```bash
cd VoiceInputApp
./gradlew assembleRelease
```

On Windows:

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

## Usage Overview

### Local Network Mode
1. Start the desktop client
2. Start the Android client
3. Discover or scan the desktop endpoint on the same network
4. Pair the devices
5. Start sending text

### Relay Server Mode
1. Start `voice-input-server`
2. Configure the desktop and Android clients to use the server
3. Register and pair the devices
4. Send text through the relay path

## Release Assets

Project releases may contain several files for different platforms. Typical names follow this pattern:

- `voiceinput-android-v0.0.1.apk`
- `voiceinput-desktop-windows-x64-v0.0.1.msi`
- `voiceinput-desktop-windows-x64-v0.0.1-setup.exe`
- `voiceinput-desktop-macos-x64-v0.0.1.dmg`
- `voiceinput-desktop-macos-arm64-v0.0.1.dmg`
- `voiceinput-desktop-linux-x64-v0.0.1.deb`
- `voiceinput-desktop-linux-x64-v0.0.1.AppImage`

This naming is intended to make each file self-explanatory when viewed directly on the Releases page.

## Project Status

`0.0.1` is the first public starting point of the project.

This version focuses on establishing a clean, buildable, understandable foundation for future iteration rather than presenting a final, feature-complete product.

## Roadmap Direction

Planned future work may include:
- improved pairing and onboarding flow
- better release packaging and distribution
- richer mobile input interactions
- more reliable remote relay support
- stronger configuration and deployment documentation

## Contributing

Issues and pull requests are welcome.

If you want to contribute, a good first step is to:
1. build the relevant component locally
2. verify the current behavior
3. open an issue or submit a focused change

## License

MIT — see `LICENSE` for details.
