param(
    [switch]$ServerOnly,
    [switch]$DesktopOnly,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RootDir = $PSScriptRoot
$RuntimeDir = Join-Path $RootDir ".runtime"
$LogDir = Join-Path $RuntimeDir "logs"
$StateFile = Join-Path $RuntimeDir "project-services.json"

function Show-Help {
    Write-Host "Start project services"
    Write-Host ""
    Write-Host "Usage:"
    Write-Host "  powershell -ExecutionPolicy Bypass -File .\start-project-services.ps1"
    Write-Host "  powershell -ExecutionPolicy Bypass -File .\start-project-services.ps1 -ServerOnly"
    Write-Host "  powershell -ExecutionPolicy Bypass -File .\start-project-services.ps1 -DesktopOnly"
    Write-Host ""
    Write-Host "Default starts:"
    Write-Host "  1. voice-input-server  relay server"
    Write-Host "  2. voice-input-desktop desktop dev app"
}

function Test-ProcessRunning {
    param([int]$ProcessId)
    return [bool](Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
}

function Get-ServiceState {
    if (-not (Test-Path $StateFile)) {
        return @()
    }

    try {
        $state = Get-Content $StateFile -Raw | ConvertFrom-Json
        if ($null -eq $state.services) {
            return @()
        }
        return @($state.services)
    } catch {
        Write-Warning "Cannot read service state file, recreating: $StateFile"
        return @()
    }
}

function Save-ServiceState {
    param([object[]]$Services)

    if (-not (Test-Path $RuntimeDir)) {
        New-Item -ItemType Directory -Path $RuntimeDir | Out-Null
    }

    [pscustomobject]@{
        updatedAt = (Get-Date).ToString("o")
        services = @($Services)
    } | ConvertTo-Json -Depth 6 | Set-Content -Path $StateFile -Encoding UTF8
}

function Resolve-Tool {
    param([string[]]$Names)

    foreach ($name in $Names) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) {
            return $command.Source
        }
    }

    throw "Command not found: $($Names -join ', ')"
}

function Start-ManagedProcess {
    param(
        [object[]]$Services,
        [string]$Name,
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory
    )

    $existing = @($Services | Where-Object {
        $_.name -eq $Name -and $_.pid -and (Test-ProcessRunning -ProcessId ([int]$_.pid))
    }) | Select-Object -First 1

    if ($existing) {
        Write-Host "$Name is already running, PID: $($existing.pid)"
        return @($Services)
    }

    if (-not (Test-Path $WorkingDirectory)) {
        throw "Working directory does not exist: $WorkingDirectory"
    }

    if (-not (Test-Path $LogDir)) {
        New-Item -ItemType Directory -Path $LogDir | Out-Null
    }

    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $stdout = Join-Path $LogDir "$Name-$timestamp.out.log"
    $stderr = Join-Path $LogDir "$Name-$timestamp.err.log"

    Write-Host "Starting $Name ..."
    $process = Start-Process `
        -FilePath $FilePath `
        -ArgumentList $Arguments `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru

    $entry = [pscustomobject]@{
        name = $Name
        pid = $process.Id
        filePath = $FilePath
        arguments = ($Arguments -join " ")
        workingDirectory = $WorkingDirectory
        stdout = $stdout
        stderr = $stderr
        startedAt = (Get-Date).ToString("o")
    }

    Write-Host "$Name started, PID: $($process.Id)"
    Write-Host "Log: $stdout"
    return @($Services + $entry)
}

if ($Help) {
    Show-Help
    exit 0
}

if ($ServerOnly -and $DesktopOnly) {
    throw "-ServerOnly and -DesktopOnly cannot be used together"
}

$startServer = -not $DesktopOnly
$startDesktop = -not $ServerOnly

$services = @(Get-ServiceState | Where-Object {
    $_.pid -and (Test-ProcessRunning -ProcessId ([int]$_.pid))
})

if ($startServer) {
    $cargo = Resolve-Tool -Names @("cargo.exe", "cargo")
    $serverDir = Join-Path $RootDir "voice-input-server"
    $services = Start-ManagedProcess `
        -Services $services `
        -Name "voice-input-server" `
        -FilePath $cargo `
        -Arguments @("run", "--release") `
        -WorkingDirectory $serverDir
}

if ($startDesktop) {
    $npm = Resolve-Tool -Names @("npm.cmd", "npm")
    $desktopDir = Join-Path $RootDir "voice-input-desktop"
    $services = Start-ManagedProcess `
        -Services $services `
        -Name "voice-input-desktop" `
        -FilePath $npm `
        -Arguments @("run", "dev") `
        -WorkingDirectory $desktopDir
}

Save-ServiceState -Services $services

Write-Host ""
Write-Host "Startup complete. Stop command:"
Write-Host "  powershell -ExecutionPolicy Bypass -File .\stop-project-services.ps1"
