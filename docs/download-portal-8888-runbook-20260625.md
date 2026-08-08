# 8888 Download Portal Runbook

Date: 2026-06-25

This document records the 8888 download portal deployment method and naming rules.
Do not paste private key contents into this file.

## Server

- Public URL: `http://8.153.163.104:8888/`
- SSH: `root@8.153.163.104`
- SSH key: `.deploy/voiceinput_deploy_key`
- systemd service: `download-portal-8888.service`
- Binary: `/opt/download-portal/download-portal`
- Remote source copy: `/opt/download-portal/source/`
- Local source copy: `.deploy/download-portal/`

The service starts with:

```bash
/opt/download-portal/download-portal \
  -addr :8888 \
  -voiceinput-manifest /opt/voiceinput/server/updates/stable/manifest.json \
  -voiceinput-updates /opt/voiceinput/server/updates \
  -termsync-downloads /opt/termsync/downloads \
  -micserver-public /opt/micserver/public
```

## Download Roots

- VoiceInput: `/opt/voiceinput/server/updates`
- VoiceInput stable manifest: `/opt/voiceinput/server/updates/stable/manifest.json`
- TermSync: `/opt/termsync/downloads`
- MicServer: `/opt/micserver/public`

## Product Labels

The portal uses user-facing labels instead of raw file names.

- `VoiceInput 语传`: phone input and file transfer assistant.
- `TermSync 远程终端`: cross-platform remote terminal sync.
- `MicServer 远程麦克风`: remote microphone and virtual audio tool.

Version display rules:

- VoiceInput uses the stable manifest `latest_version`.
- TermSync uses the newest version found in Android/desktop assets.
- TermSync server may show `最新版` when only a `latest` binary exists.
- MicServer uses the date-style version `2026.06.14` until a real semantic version is added.

## Normalized File Names

VoiceInput and TermSync versioned assets are already canonical and are selected by pattern.

MicServer keeps the old names for compatibility and adds normalized aliases:

- `micserver-remote-mic-windows-x64-v2026.06.14.exe`
- `micserver-remote-mic-linux-amd64-v2026.06.14`
- `micserver-remote-mic-linux-arm64-v2026.06.14`
- `micserver-vbcable-driver-windows-v45.zip`

TermSync server normalized alias:

- `termsync-server-linux-amd64-latest`

Alias creation commands:

```bash
cd /opt/micserver/public
cp -pn remotemic.exe micserver-remote-mic-windows-x64-v2026.06.14.exe
cp -pn remotemic-linux-amd64 micserver-remote-mic-linux-amd64-v2026.06.14
cp -pn remotemic-linux-arm64 micserver-remote-mic-linux-arm64-v2026.06.14
cp -pn VBCABLE_Driver_Pack45.zip micserver-vbcable-driver-windows-v45.zip

cd /opt/termsync/downloads
cp -pn TermSync-Server-Linux-latest termsync-server-linux-amd64-latest
```

## Build And Deploy

Build from Windows:

```powershell
cd E:\Work\Code\voiceinput\.deploy\download-portal
gofmt -w main.go
go test ./...
$env:CGO_ENABLED='0'
$env:GOOS='linux'
$env:GOARCH='amd64'
go build -trimpath -ldflags '-s -w' -o download-portal-linux-amd64 .
```

Upload and test on a temporary port:

```powershell
scp -i E:\Work\Code\voiceinput\.deploy\voiceinput_deploy_key `
  E:\Work\Code\voiceinput\.deploy\download-portal\download-portal-linux-amd64 `
  root@8.153.163.104:/tmp/download-portal.new

ssh -i E:\Work\Code\voiceinput\.deploy\voiceinput_deploy_key root@8.153.163.104 `
  "/tmp/download-portal.new -addr :18888 -voiceinput-manifest /opt/voiceinput/server/updates/stable/manifest.json -voiceinput-updates /opt/voiceinput/server/updates -termsync-downloads /opt/termsync/downloads -micserver-public /opt/micserver/public"
```

Deploy to 8888:

```bash
stamp=$(date +%Y%m%d%H%M%S)
cp -a /opt/download-portal/download-portal /opt/download-portal/download-portal.bak.$stamp
systemctl stop download-portal-8888.service
cp -f /tmp/download-portal.new /opt/download-portal/download-portal
chmod +x /opt/download-portal/download-portal
systemctl start download-portal-8888.service
systemctl status download-portal-8888.service --no-pager -l
```

Sync source to the server:

```powershell
scp -i E:\Work\Code\voiceinput\.deploy\voiceinput_deploy_key `
  E:\Work\Code\voiceinput\.deploy\download-portal\go.mod `
  E:\Work\Code\voiceinput\.deploy\download-portal\main.go `
  root@8.153.163.104:/opt/download-portal/source/
```

## Verification

```powershell
curl.exe -s http://8.153.163.104:8888/
curl.exe -s http://8.153.163.104:8888/downloads-manifest.json
curl.exe -I http://8.153.163.104:8888/voiceinput-updates/stable/1.2.17/voiceinput-android-v1.2.17.apk
curl.exe -I http://8.153.163.104:8888/termsync-downloads/termsync-android-release-v0.1.10.apk
curl.exe -I http://8.153.163.104:8888/micserver-downloads/micserver-remote-mic-windows-x64-v2026.06.14.exe
```
