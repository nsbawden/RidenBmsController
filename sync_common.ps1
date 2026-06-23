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
