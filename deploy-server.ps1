param(
    [string]$HostName = "8.153.163.104",
    [int]$Port = 22,
    [string]$User = "root",
    [string]$KeyPath = ".deploy/voiceinput_deploy_key",
    [string]$RemoteAppDir = "/opt/voiceinput/server",
    [string]$Version = "1.2.26",
    [string]$ArtifactDir = "release-artifacts/latest"
)

$ErrorActionPreference = "Stop"

function Resolve-RepoPath([string]$RelativePath) {
    return Join-Path $PSScriptRoot $RelativePath
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Update-Asset($Asset, [string]$FilePath) {
    $item = Get-Item -LiteralPath $FilePath
    $Asset.sha256 = Get-Sha256 $FilePath
    $Asset.size = $item.Length
}

function Invoke-Remote([string]$Command) {
    & ssh -i $KeyFullPath -p $Port -o StrictHostKeyChecking=accept-new "$User@$HostName" $Command
    if ($LASTEXITCODE -ne 0) {
        throw "Remote command failed: $Command"
    }
}

function Copy-ToRemote([string]$LocalPath, [string]$RemotePath) {
    & scp -i $KeyFullPath -P $Port -o StrictHostKeyChecking=accept-new -r $LocalPath "$User@$HostName`:$RemotePath"
    if ($LASTEXITCODE -ne 0) {
        throw "Upload failed: $LocalPath -> $RemotePath"
    }
}

function Resolve-ArtifactOrBuildOutput([string]$FileName, [string[]]$BuildOutputCandidates) {
    $artifactPath = Resolve-RepoPath (Join-Path $ArtifactDir $FileName)
    if (Test-Path -LiteralPath $artifactPath) {
        return $artifactPath
    }

    foreach ($candidate in $BuildOutputCandidates) {
        $candidatePath = Resolve-RepoPath $candidate
        if (Test-Path -LiteralPath $candidatePath) {
            return $candidatePath
        }
    }

    throw "Artifact not found: $FileName. Checked $artifactPath and local build outputs."
}

$KeyFullPath = Resolve-RepoPath $KeyPath
if (!(Test-Path -LiteralPath $KeyFullPath)) {
    throw "SSH key not found: $KeyFullPath"
}

$updatesRoot = Resolve-RepoPath "voice-input-server/updates/stable"
$versionDir = Join-Path $updatesRoot $Version
$manifestPath = Join-Path $updatesRoot "manifest.json"
New-Item -ItemType Directory -Force -Path $versionDir | Out-Null

$desktopMsiTarget = Join-Path $versionDir "voiceinput-desktop-windows-x64-v${Version}.msi"
$desktopSetupTarget = Join-Path $versionDir "voiceinput-desktop-windows-x64-v${Version}-setup.exe"
$androidTarget = Join-Path $versionDir "voiceinput-android-v${Version}.apk"

$desktopMsiSource = Resolve-ArtifactOrBuildOutput "voiceinput-desktop-windows-x64-v${Version}.msi" @(
    "voice-input-desktop/src-tauri/target/release/bundle/msi/VoiceInput_${Version}_x64_zh-CN.msi"
)
$desktopSetupSource = Resolve-ArtifactOrBuildOutput "voiceinput-desktop-windows-x64-v${Version}-setup.exe" @(
    "voice-input-desktop/src-tauri/target/release/bundle/nsis/VoiceInput_${Version}_x64-setup.exe"
)
$androidSource = Resolve-ArtifactOrBuildOutput "voiceinput-android-v${Version}.apk" @(
    "VoiceInputApp/app/build/outputs/apk/release/app-release.apk",
    "VoiceInputApp/app/build/outputs/apk/debug/app-debug.apk"
)

Copy-Item -LiteralPath $desktopMsiSource -Destination $desktopMsiTarget -Force
Copy-Item -LiteralPath $desktopSetupSource -Destination $desktopSetupTarget -Force
Copy-Item -LiteralPath $androidSource -Destination $androidTarget -Force

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$manifest.latest_version = $Version
$release = @($manifest.releases | Where-Object { $_.version -eq $Version } | Select-Object -First 1)[0]
if ($null -eq $release) {
    $release = [pscustomobject]@{
        version = $Version
        release_notes = ""
        published_at = ""
        force_update = $false
        assets = @(
            [pscustomobject]@{
                platform = "android"
                arch = "universal"
                file_name = "voiceinput-android-v${Version}.apk"
                sha256 = ""
                size = 0
                mime_type = "application/vnd.android.package-archive"
            },
            [pscustomobject]@{
                platform = "windows"
                arch = "x64"
                file_name = "voiceinput-desktop-windows-x64-v${Version}-setup.exe"
                sha256 = ""
                size = 0
                mime_type = "application/vnd.microsoft.portable-executable"
            },
            [pscustomobject]@{
                platform = "windows"
                arch = "x64"
                file_name = "voiceinput-desktop-windows-x64-v${Version}.msi"
                sha256 = ""
                size = 0
                mime_type = "application/x-msi"
            }
        )
    }
    $manifest.releases = @($release) + @($manifest.releases)
}

$release.published_at = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
$release.release_notes = "发布版本 1.2.26。"

foreach ($asset in $release.assets) {
    if ($asset.file_name -eq "voiceinput-android-v${Version}.apk") {
        Update-Asset $asset $androidTarget
    } elseif ($asset.file_name -eq "voiceinput-desktop-windows-x64-v${Version}-setup.exe") {
        Update-Asset $asset $desktopSetupTarget
    } elseif ($asset.file_name -eq "voiceinput-desktop-windows-x64-v${Version}.msi") {
        Update-Asset $asset $desktopMsiTarget
    }
}

$manifest | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

Invoke-Remote "mkdir -p '$RemoteAppDir/updates'"
Copy-ToRemote (Resolve-RepoPath "voice-input-server/updates/.") "$RemoteAppDir/updates/"

$restartCommand = @(
    "if systemctl list-unit-files | grep -q '^voice-input-server\.service'; then",
    "  systemctl restart voice-input-server",
    "elif systemctl list-unit-files | grep -q '^voiceinput-server\.service'; then",
    "  systemctl restart voiceinput-server",
    "elif pgrep -f voice-input-server >/dev/null; then",
    "  pkill -f voice-input-server || true",
    "  cd '$RemoteAppDir' && nohup ./voice-input-server >/var/log/voice-input-server.log 2>&1 &",
    "else",
    "  true",
    "fi"
) -join "`n"

Invoke-Remote $restartCommand
Invoke-Remote "ls -lh '$RemoteAppDir/updates/stable/$Version' && test -f '$RemoteAppDir/updates/stable/manifest.json'"

Write-Host "Deployed version $Version to $User@$HostName`:$RemoteAppDir"
