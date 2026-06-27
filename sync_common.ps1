# Shared helpers for synclogs.ps1 and syncall.ps1

$script:SyncLockPath = Join-Path $env:TEMP "RidenBmsController_sync.lock"

function Write-Banner {
    param(
        [string]$Title,
        [string]$Color = "White"
    )
    $line = "=" * 60
    Write-Host ""
    Write-Host $line -ForegroundColor $Color
    Write-Host "  $Title" -ForegroundColor $Color
    Write-Host $line -ForegroundColor $Color
    Write-Host ""
}

function Get-AdbDeviceReport {
    $raw = @(adb devices 2>&1 | ForEach-Object { "$_" })
    $connected = @()
    $unauthorized = @()
    $offline = @()

    foreach ($line in $raw) {
        if ($line -match '^\s*([^\s]+)\s+device\s*$') {
            $connected += $Matches[1]
        } elseif ($line -match '^\s*([^\s]+)\s+unauthorized\s*$') {
            $unauthorized += $Matches[1]
        } elseif ($line -match '^\s*([^\s]+)\s+offline\s*$') {
            $offline += $Matches[1]
        }
    }

    return [PSCustomObject]@{
        RawLines = $raw
        Connected = $connected
        Unauthorized = $unauthorized
        Offline = $offline
        HasDevice = ($connected.Count -gt 0)
    }
}

function Show-NoPhoneConnected {
    param([object]$Report)

    Write-Banner -Title "PHONE NOT CONNECTED" -Color Red
    Write-Host "adb devices output:" -ForegroundColor Yellow
    foreach ($line in $Report.RawLines) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            Write-Host "  (empty line)"
        } else {
            Write-Host "  $line"
        }
    }
    if ($Report.Unauthorized.Count -gt 0) {
        Write-Host ""
        Write-Host "Device(s) found but NOT authorized. Accept the USB debugging prompt on the phone:" -ForegroundColor Yellow
        foreach ($id in $Report.Unauthorized) {
            Write-Host "  $id  unauthorized"
        }
    }
    if ($Report.Offline.Count -gt 0) {
        Write-Host ""
        Write-Host "Device(s) offline. Reconnect USB or re-pair wireless debugging:" -ForegroundColor Yellow
        foreach ($id in $Report.Offline) {
            Write-Host "  $id  offline"
        }
    }
    if ($Report.Connected.Count -eq 0 -and $Report.Unauthorized.Count -eq 0 -and $Report.Offline.Count -eq 0) {
        Write-Host ""
        Write-Host "No phone is attached to ADB." -ForegroundColor Yellow
        Write-Host "  - USB: plug in and enable USB debugging"
        Write-Host "  - Wireless: enable wireless debugging and run adb connect <ip>:<port>"
    }
    Write-Host ""
    Write-Host "Then verify with:  adb devices" -ForegroundColor Cyan
    Write-Host "You need a line ending in ""device"" before sync can pull logs." -ForegroundColor Cyan
    Write-Host ""
    Write-Banner -Title "Nothing was pulled from the phone." -Color Red
}

function Test-AdbDevice {
    return (Get-AdbDeviceReport).HasDevice
}

function Enter-SyncLock {
    if (Test-Path $script:SyncLockPath) {
        $oldPidText = (Get-Content $script:SyncLockPath -Raw -ErrorAction SilentlyContinue).Trim()
        $stillRunning = $false
        if ($oldPidText -match '^\d+$') {
            $oldPid = [int]$oldPidText
            $proc = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
            if ($null -ne $proc) {
                $stillRunning = $true
            }
        }
        if ($stillRunning) {
            Write-Host ""
            Write-Banner -Title "SYNC ALREADY RUNNING" -Color Yellow
            Write-Host "Another sync is in progress (PID $oldPidText)." -ForegroundColor Yellow
            Write-Host "Wait for it to finish or close that window, then try again." -ForegroundColor Yellow
            Write-Host ""
            return $false
        }
        Remove-Item $script:SyncLockPath -Force -ErrorAction SilentlyContinue
    }

    Set-Content -Path $script:SyncLockPath -Value $PID -NoNewline
    return $true
}

function Exit-SyncLock {
    if (Test-Path $script:SyncLockPath) {
        Remove-Item $script:SyncLockPath -Force -ErrorAction SilentlyContinue
    }
}

function Write-ChartStatus {
    param(
        [Parameter(Mandatory)][string]$Message,
        [ConsoleColor]$Color = [ConsoleColor]::Gray
    )
    Write-Host $Message -ForegroundColor $Color
    [Console]::Out.Flush()
}

# pulled_logs layout: today's active logs in root; older logs in pulled_logs/archive/
$script:DailyLogNamePattern = '^(?<date>\d{4}-\d{2}-\d{2})_(?<rest>.+)$'

function Get-PulledLogsArchiveDir {
    param([Parameter(Mandatory)][string]$RootDir)
    return Join-Path $RootDir "archive"
}

function Initialize-PulledLogsLayout {
    param(
        [Parameter(Mandatory)][string]$RootDir,
        [string]$Today = (Get-Date -Format "yyyy-MM-dd")
    )

    $archiveDir = Get-PulledLogsArchiveDir $RootDir
    New-Item -ItemType Directory -Force -Path $RootDir, $archiveDir | Out-Null
    Move-PulledLogsStaleRootToArchive -RootDir $RootDir -Today $Today
}

function Move-PulledLogFileToArchive {
    param(
        [Parameter(Mandatory)][string]$SourcePath,
        [Parameter(Mandatory)][string]$ArchiveDir
    )

    if (-not (Test-Path $SourcePath)) { return }

    New-Item -ItemType Directory -Force -Path $ArchiveDir | Out-Null
    $name = Split-Path $SourcePath -Leaf
    $dest = Join-Path $ArchiveDir $name
    if (Test-Path $dest) {
        $srcLen = (Get-Item $SourcePath).Length
        $destLen = (Get-Item $dest).Length
        if ($srcLen -le $destLen) {
            Remove-Item $SourcePath -Force
            return
        }
        Remove-Item $dest -Force
    }
    Move-Item -Force $SourcePath $dest
}

function Move-PulledLogsStaleRootToArchive {
    param(
        [Parameter(Mandatory)][string]$RootDir,
        [Parameter(Mandatory)][string]$Today
    )

    $archiveDir = Get-PulledLogsArchiveDir $RootDir
    Get-ChildItem -Path $RootDir -File | ForEach-Object {
        if ($_.Name -eq "controller_history.csv") { return }
        if ($_.Name -notmatch $script:DailyLogNamePattern) { return }
        if ($Matches.date -eq $Today) { return }
        Move-PulledLogFileToArchive -SourcePath $_.FullName -ArchiveDir $archiveDir
    }
}

function Resolve-PulledLogLocalPath {
    param(
        [Parameter(Mandatory)][string]$RootDir,
        [Parameter(Mandatory)][string]$FileName,
        [Parameter(Mandatory)][string]$FileDate,
        [Parameter(Mandatory)][string]$Today
    )

    if ($FileDate -eq $Today) {
        return Join-Path $RootDir $FileName
    }
    return Join-Path (Get-PulledLogsArchiveDir $RootDir) $FileName
}

function Find-PulledLogLocalPath {
    param(
        [Parameter(Mandatory)][string]$RootDir,
        [Parameter(Mandatory)][string]$FileName
    )

    $archivePath = Join-Path (Get-PulledLogsArchiveDir $RootDir) $FileName
    if (Test-Path $archivePath) { return $archivePath }

    $rootPath = Join-Path $RootDir $FileName
    if (Test-Path $rootPath) { return $rootPath }

    return $archivePath
}

function Remove-PulledLogSiblingHtml {
    param([Parameter(Mandatory)][string]$CsvPath)

    $htmlPath = $CsvPath -replace '\.csv$', '.html'
    if (Test-Path $htmlPath) {
        Remove-Item $htmlPath -Force
    }
}

function Resolve-PulledLogCsvPath {
    param(
        [Parameter(Mandatory)][string]$RootDir,
        [Parameter(Mandatory)][string]$Date,
        [Parameter(Mandatory)][string]$Suffix
    )

    $fileName = "${Date}_${Suffix}.csv"
    $rootPath = Join-Path $RootDir $fileName
    if (Test-Path $rootPath) { return $rootPath }

    $archivePath = Join-Path (Get-PulledLogsArchiveDir $RootDir) $fileName
    if (Test-Path $archivePath) { return $archivePath }

    return $rootPath
}

function Remove-PulledLogsTodayNotOnPhone {
    param(
        [Parameter(Mandatory)][string]$RootDir,
        [Parameter(Mandatory)][string]$Today,
        [Parameter(Mandatory)][string[]]$RemoteNames
    )

    $remoteSet = [System.Collections.Generic.HashSet[string]]::new([string[]]$RemoteNames)
    Get-ChildItem -Path $RootDir -File | ForEach-Object {
        if ($_.Name -eq "controller_history.csv") {
            if (-not $remoteSet.Contains($_.Name)) {
                Remove-Item $_.FullName -Force
            }
            return
        }
        if ($_.Name -notmatch "^(?<date>$([regex]::Escape($Today)))_(?<rest>.+\.(csv|html))$") {
            return
        }
        if ($_.Extension -eq ".html") { return }
        if (-not $remoteSet.Contains($_.Name)) {
            Remove-PulledLogSiblingHtml $_.FullName
            Remove-Item $_.FullName -Force
        }
    }
}
