param(
    [switch]$ServerOnly,
    [switch]$DesktopOnly,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RootDir = $PSScriptRoot
$RuntimeDir = Join-Path $RootDir ".runtime"
$StateFile = Join-Path $RuntimeDir "project-services.json"

function Show-Help {
    Write-Host "Stop project services"
    Write-Host ""
    Write-Host "Usage:"
    Write-Host "  powershell -ExecutionPolicy Bypass -File .\stop-project-services.ps1"
    Write-Host "  powershell -ExecutionPolicy Bypass -File .\stop-project-services.ps1 -ServerOnly"
    Write-Host "  powershell -ExecutionPolicy Bypass -File .\stop-project-services.ps1 -DesktopOnly"
}

function Test-ProcessRunning {
    param([int]$ProcessId)
    return [bool](Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
}

function Stop-ProcessTree {
    param([int]$ProcessId)

    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId = $ProcessId" -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        Stop-ProcessTree -ProcessId ([int]$child.ProcessId)
    }

    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($process) {
        Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
    }
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
        Write-Warning "Cannot read service state file: $StateFile"
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

if ($Help) {
    Show-Help
    exit 0
}

if ($ServerOnly -and $DesktopOnly) {
    throw "-ServerOnly and -DesktopOnly cannot be used together"
}

$targetNames = @()
if (-not $DesktopOnly) {
    $targetNames += "voice-input-server"
}
if (-not $ServerOnly) {
    $targetNames += "voice-input-desktop"
}

$services = @(Get-ServiceState)
if ($services.Count -eq 0) {
    Write-Host "No running services recorded by the start script."
    exit 0
}

$remaining = @()
$stopped = 0

foreach ($service in $services) {
    $name = [string]$service.name
    $pidValue = [int]$service.pid

    if ($targetNames -contains $name) {
        if (Test-ProcessRunning -ProcessId $pidValue) {
            Write-Host ("Stopping {0}, PID: {1} ..." -f $name, $pidValue)
            Stop-ProcessTree -ProcessId $pidValue
            $stopped += 1
            Write-Host "$name stopped"
        } else {
            Write-Host "$name is not running, clearing record"
        }
        continue
    }

    if (Test-ProcessRunning -ProcessId $pidValue) {
        $remaining += $service
    }
}

Save-ServiceState -Services $remaining

if ($stopped -eq 0) {
    Write-Host "No running services need to be stopped."
} else {
    Write-Host "Stop complete."
}
