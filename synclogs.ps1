# Sync ops_logs from connected phone to pulled_logs/.
# Today's enabled logs -> pulled_logs/ (root). Past days -> pulled_logs/archive/, then removed from phone.
# Root is pruned to match what the phone currently has for today (logging toggles off = file gone locally).

param(
    [switch]$SkipLock
)

$ErrorActionPreference = "Stop"

. "$PSScriptRoot\sync_common.ps1"

$Package = "com.example.ridenbmscontroller"
$RemoteDir = "files/ops_logs"
$DestDir = Join-Path $PSScriptRoot "pulled_logs"
$ArchiveDir = Get-PulledLogsArchiveDir $DestDir
$Today = Get-Date -Format "yyyy-MM-dd"
$DailyLogPattern = '^(?<date>\d{4}-\d{2}-\d{2})_(?<rest>.+\.csv)$'
$SkyLogPattern = '_sky_disturbances\.csv$'
$CrashLogPattern = '_crash_episodes\.csv$'

function Write-Step($Message) {
    Write-Host $Message
}

function Get-LogKindLabel {
    param([string]$FileName)
    if ($FileName -match $SkyLogPattern) { return "sky" }
    if ($FileName -match $CrashLogPattern) { return "crash" }
    if ($FileName -match '_events\.csv$') { return "events" }
    if ($FileName -match '_telemetry') { return "telemetry" }
    return "log"
}

function Invoke-RunAsShell {
    param([string]$Command)
    adb shell "run-as $Package $Command" 2>&1
}

function Get-RemoteLogFiles {
    $raw = Invoke-RunAsShell "ls $RemoteDir"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to list $RemoteDir on phone. Is the app installed and the device connected?`n$raw"
    }

    $files = @()
    foreach ($line in @($raw)) {
        $name = ($line -split '\s+')[-1].Trim()
        if ($name -match $DailyLogPattern) {
            $files += [PSCustomObject]@{
                Name = $name
                Date = $Matches.date
            }
        }
    }
    return $files | Sort-Object Date, Name
}

function Pull-RemoteFile {
    param([string]$RemoteName, [string]$LocalPath)

    $parent = Split-Path $LocalPath -Parent
    if ($parent -and -not (Test-Path $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "adb"
    $psi.Arguments = "exec-out run-as $Package cat `"$RemoteDir/$RemoteName`""
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $proc = [System.Diagnostics.Process]::Start($psi)
    if ($null -eq $proc) {
        return $false
    }

    try {
        $fs = [System.IO.File]::Create($LocalPath)
        try {
            $proc.StandardOutput.BaseStream.CopyTo($fs)
        } finally {
            $fs.Close()
            $fs.Dispose()
        }
        $proc.WaitForExit()
    } finally {
        if (-not $proc.HasExited) {
            $proc.Kill()
        }
        $proc.Dispose()
    }

    return (Test-Path $LocalPath) -and ((Get-Item $LocalPath).Length -gt 0)
}

function Get-RemoteFileStat {
    param([string]$RemoteName)

    $path = "$RemoteDir/$RemoteName"
    $sizeText = (Invoke-RunAsShell "stat -c %s `"$path`" 2>/dev/null") | Select-Object -Last 1
    $mtimeText = (Invoke-RunAsShell "stat -c %Y `"$path`" 2>/dev/null") | Select-Object -Last 1

    $size = $null
    $modifiedUtc = $null
    if ($sizeText -match '^\d+$') {
        $size = [long]$sizeText.Trim()
    }
    if ($mtimeText -match '^\d+$') {
        $modifiedUtc = [DateTimeOffset]::FromUnixTimeSeconds([long]$mtimeText.Trim()).UtcDateTime
    }

    if ($null -ne $size) {
        return [PSCustomObject]@{
            Name = $RemoteName
            Size = $size
            ModifiedUtc = $modifiedUtc
        }
    }

    $lsLine = (Invoke-RunAsShell "ls -l `"$path`" 2>/dev/null") | Select-Object -Last 1
    if ($lsLine -match '^\S+\s+\d+\s+\S+\s+\S+\s+(\d+)\s+') {
        return [PSCustomObject]@{
            Name = $RemoteName
            Size = [long]$Matches[1]
            ModifiedUtc = $modifiedUtc
        }
    }

    return $null
}

function Format-RemoteStamp {
    param([DateTime]$UtcTime)
    if ($null -eq $UtcTime) { return "unknown time" }
    return $UtcTime.ToLocalTime().ToString("yyyy-MM-dd HH:mm")
}

function Ensure-LocalArchiveMatchesPhone {
    param(
        [string]$RemoteName,
        [string]$LocalPath,
        [string]$KindTag
    )

    $legacyPath = Join-Path $DestDir $RemoteName
    if ((Split-Path $LocalPath -Parent) -ne $DestDir -and (Test-Path $legacyPath)) {
        Move-PulledLogFileToArchive -SourcePath $legacyPath -ArchiveDir $ArchiveDir
    }

    $remote = Get-RemoteFileStat -RemoteName $RemoteName
    if ($null -eq $remote) {
        Write-Step "  -> cannot read phone size for $RemoteName; re-pulling before delete ..."
        if (-not (Pull-RemoteFile -RemoteName $RemoteName -LocalPath $LocalPath)) {
            Write-Step "  -> re-pull failed; not deleting from phone"
            return [PSCustomObject]@{ Ready = $false; Pulled = $false }
        }
        $size = (Get-Item $LocalPath).Length
        Write-Step "  -> re-pulled ($size bytes) -> archive"
        return [PSCustomObject]@{ Ready = $true; Pulled = $true }
    }

    $remoteStamp = Format-RemoteStamp $remote.ModifiedUtc
    $existingPath = Find-PulledLogLocalPath -RootDir $DestDir -FileName $RemoteName
    if (-not (Test-Path $existingPath)) {
        Write-Step "Pulling $RemoteName ($KindTag past day -> archive, phone $($remote.Size) bytes @ $remoteStamp) ..."
        if (-not (Pull-RemoteFile -RemoteName $RemoteName -LocalPath $LocalPath)) {
            Write-Step "  -> failed or empty; not deleting from phone"
            return [PSCustomObject]@{ Ready = $false; Pulled = $false }
        }
        $size = (Get-Item $LocalPath).Length
        Write-Step "  -> saved to archive ($size bytes)"
        return [PSCustomObject]@{ Ready = $true; Pulled = $true }
    }

    if ($existingPath -ne $LocalPath) {
        Move-PulledLogFileToArchive -SourcePath $existingPath -ArchiveDir $ArchiveDir
    }

    $local = Get-Item $LocalPath
    $localStamp = $local.LastWriteTime.ToString("yyyy-MM-dd HH:mm")
    if ($local.Length -eq $remote.Size) {
        Write-Step "Verified $RemoteName ($KindTag archive $($local.Length) bytes @ $localStamp matches phone $($remote.Size) bytes @ $remoteStamp)"
        return [PSCustomObject]@{ Ready = $true; Pulled = $false }
    }

    Write-Step "Updating $RemoteName ($KindTag archive $($local.Length) bytes @ $localStamp, phone $($remote.Size) bytes @ $remoteStamp) ..."
    if (-not (Pull-RemoteFile -RemoteName $RemoteName -LocalPath $LocalPath)) {
        Write-Step "  -> re-pull failed; not deleting from phone"
        return [PSCustomObject]@{ Ready = $false; Pulled = $false }
    }
    $size = (Get-Item $LocalPath).Length
    if ($size -ne $remote.Size) {
        Write-Step "  -> warning: after re-pull local is $size bytes but phone reports $($remote.Size); not deleting from phone"
        return [PSCustomObject]@{ Ready = $false; Pulled = $false }
    }
    Write-Step "  -> re-pulled to archive ($size bytes, matches phone)"
    return [PSCustomObject]@{ Ready = $true; Pulled = $true }
}

function Remove-RemoteFile {
    param([string]$RemoteName)
    Invoke-RunAsShell "rm $RemoteDir/$RemoteName" | Out-Null
    return $LASTEXITCODE -eq 0
}

function Sync-OtherPhoneLogs {
    param([string]$RemoteName)

    $localPath = Join-Path $DestDir $RemoteName
    Write-Step "Pulling $RemoteName (today, overwrite) ..."
    if (Pull-RemoteFile -RemoteName $RemoteName -LocalPath $localPath) {
        $size = (Get-Item $localPath).Length
        Write-Step "  -> saved ($size bytes), kept on phone"
    } elseif (Test-Path $localPath) {
        Remove-Item $localPath -Force
        Write-Step "  -> removed local copy (not on phone / logging off)"
    } else {
        Write-Step "  -> skip (missing or empty on phone)"
    }
}

if (-not $SkipLock) {
    if (-not (Enter-SyncLock)) {
        exit 2
    }
}

try {
Initialize-PulledLogsLayout -RootDir $DestDir -Today $Today

$adbReport = Get-AdbDeviceReport
if (-not $adbReport.HasDevice) {
    Show-NoPhoneConnected -Report $adbReport
    exit 1
}

Write-Banner -Title "SYNClogs - phone connected" -Color Green
foreach ($id in $adbReport.Connected) {
    Write-Host "  Device: $id" -ForegroundColor Green
}
Write-Host ""

Write-Step "synclogs: today=$Today active=$DestDir archive=$ArchiveDir"
Write-Step ""

$remoteFiles = Get-RemoteLogFiles
$remoteNames = @($remoteFiles | ForEach-Object { $_.Name })
$skyPulled = 0
$skyCleared = 0
$crashPulled = 0
$crashCleared = 0
if ($remoteFiles.Count -eq 0) {
    Write-Step "No daily ops_logs/*.csv files found on phone (telemetry, events, sky_disturbances, crash_episodes)."
} else {
    foreach ($file in $remoteFiles) {
        $localPath = Resolve-PulledLogLocalPath -RootDir $DestDir -FileName $file.Name -FileDate $file.Date -Today $Today
        $isToday = $file.Date -eq $Today
        $kind = Get-LogKindLabel $file.Name
        $kindTag = switch ($kind) {
            "sky" { "sky disturbance, " }
            "crash" { "crash episode, " }
            default { "" }
        }

        if ($isToday) {
            Write-Step "Pulling $($file.Name) ($kindTag today, overwrite) ..."
            if (Pull-RemoteFile -RemoteName $file.Name -LocalPath $localPath) {
                $size = (Get-Item $localPath).Length
                Write-Step "  -> saved ($size bytes), kept on phone"
                if ($kind -eq "sky") { $skyPulled += 1 }
                if ($kind -eq "crash") { $crashPulled += 1 }
            } else {
                Remove-PulledLogSiblingHtml $localPath
                if (Test-Path $localPath) {
                    Remove-Item $localPath -Force
                }
                Write-Step "  -> removed local copy (empty or missing on phone)"
            }
            continue
        }

        $archive = Ensure-LocalArchiveMatchesPhone -RemoteName $file.Name -LocalPath $localPath -KindTag $kindTag
        if (-not $archive.Ready) {
            continue
        }
        if ($archive.Pulled -and $kind -eq "sky") {
            $skyPulled += 1
        }
        if ($archive.Pulled -and $kind -eq "crash") {
            $crashPulled += 1
        }

        if (Remove-RemoteFile -RemoteName $file.Name) {
            Write-Step "  -> removed from phone"
            if ($kind -eq "sky") { $skyCleared += 1 }
            if ($kind -eq "crash") { $crashCleared += 1 }
        } else {
            Write-Step "  -> warning: could not remove from phone"
        }
    }
}

Write-Step ""
Write-Step "Sky disturbance logs: pulled/updated $skyPulled, cleared from phone $skyCleared"
Write-Step "Crash episode logs: pulled/updated $crashPulled, cleared from phone $crashCleared"
Write-Step ""
Sync-OtherPhoneLogs -RemoteName "controller_history.csv"
if ((Get-RemoteFileStat -RemoteName "controller_history.csv") -ne $null) {
    if ($remoteNames -notcontains "controller_history.csv") {
        $remoteNames += "controller_history.csv"
    }
} else {
    $remoteNames = @($remoteNames | Where-Object { $_ -ne "controller_history.csv" })
}
Remove-PulledLogsTodayNotOnPhone -RootDir $DestDir -Today $Today -RemoteNames $remoteNames
Move-PulledLogsStaleRootToArchive -RootDir $DestDir -Today $Today

Write-Step ""
Write-Banner -Title "SYNClogs SUCCEEDED" -Color Green
Write-Host "Today's logs: $DestDir" -ForegroundColor Green
Write-Host "Archive:      $ArchiveDir" -ForegroundColor Green
Write-Host ""
}
finally {
    if (-not $SkipLock) {
        Exit-SyncLock
    }
}
