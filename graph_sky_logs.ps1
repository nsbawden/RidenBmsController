# Build HTML charts for sky_disturbances.csv files in pulled_logs/.
# Skips files whose HTML is newer than or equal to the CSV (rebuilds when CSV changes).
# Optional: graphsky.bat 2026-06-22  (single date only)

param(
    [string]$Date = "",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$DestDir = Join-Path $PSScriptRoot "pulled_logs"

function Parse-NullableDouble {
    param([string]$Text)
    $t = $Text.Trim()
    if ([string]::IsNullOrEmpty($t)) { return $null }
    return [double]$t
}

function Parse-SkyRow {
    param([string]$Line)
    $parts = $Line.Split(',')
    if ($parts.Count -lt 3) { return $null }
    $ts = $parts[0].Trim()
    if ($ts -eq 'timestamp_ms') { return $null }
    if ($ts -notmatch '^\d+$') { return $null }
    return [PSCustomObject]@{
        TimestampMs = [long]$ts
        IoutA = Parse-NullableDouble $parts[1]
        VoutV = Parse-NullableDouble $parts[2]
        VinV = if ($parts.Count -gt 3) { Parse-NullableDouble $parts[3] } else { $null }
        VtranV = if ($parts.Count -gt 4) { Parse-NullableDouble $parts[4] } else { $null }
    }
}

function Format-TimeLabel {
    param([long]$TimestampMs)
    return [DateTimeOffset]::FromUnixTimeMilliseconds($TimestampMs).LocalDateTime.ToString("HH:mm:ss")
}

function Format-DateTimeLabel {
    param([long]$TimestampMs)
    return [DateTimeOffset]::FromUnixTimeMilliseconds($TimestampMs).LocalDateTime.ToString("yyyy-MM-dd HH:mm:ss")
}

function ConvertTo-JsonNumber {
    param($Value)
    if ($null -eq $Value) { return 'null' }
    return ([string]$Value).Replace(',', '.')
}

function Test-SkyHtmlUpToDate {
    param(
        [string]$HtmlPath,
        [string]$CsvPath
    )

    if (-not (Test-Path $HtmlPath)) { return $false }

    $csvBytes = (Get-Item $CsvPath).Length
    $head = @(Get-Content $HtmlPath -TotalCount 12 -ErrorAction SilentlyContinue)
    foreach ($line in $head) {
        if ($line -match 'source-csv-bytes:(\d+)') {
            return [long]$Matches[1] -eq $csvBytes
        }
    }

    return (Get-Item $HtmlPath).LastWriteTime -ge (Get-Item $CsvPath).LastWriteTime
}

function Write-SkyHtml {
    param(
        [string]$CsvPath,
        [string]$HtmlPath,
        [string]$DateLabel
    )

    $rows = @()
    Get-Content $CsvPath | ForEach-Object {
        $row = Parse-SkyRow $_
        if ($null -ne $row) { $rows += $row }
    }
    if ($rows.Count -eq 0) {
        Write-Warning "No data rows in $CsvPath"
        return
    }

    $timesJson = ($rows | ForEach-Object { $_.TimestampMs }) -join ','
    $ioutJson = ($rows | ForEach-Object { ConvertTo-JsonNumber $_.IoutA }) -join ','
    $vinJson = ($rows | ForEach-Object { ConvertTo-JsonNumber $_.VinV }) -join ','
    $vtranJson = ($rows | ForEach-Object { ConvertTo-JsonNumber $_.VtranV }) -join ','

    $firstTs = $rows[0].TimestampMs
    $lastTs = $rows[-1].TimestampMs
    $maxIout = ($rows | Where-Object { $null -ne $_.IoutA } | Measure-Object -Property IoutA -Maximum).Maximum
    $generatedAt = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $csvName = Split-Path $CsvPath -Leaf
    $csvBytes = (Get-Item $CsvPath).Length

    $tableRows = ($rows | ForEach-Object {
        $iout = if ($null -ne $_.IoutA) { "{0:N3}" -f $_.IoutA } else { "" }
        $vout = if ($null -ne $_.VoutV) { "{0:N3}" -f $_.VoutV } else { "" }
        $vin = if ($null -ne $_.VinV) { "{0:N2}" -f $_.VinV } else { "" }
        $vtran = if ($null -ne $_.VtranV) { "{0:N2}" -f $_.VtranV } else { "" }
        @"
        <tr>
            <td>$(Format-DateTimeLabel $_.TimestampMs)</td>
            <td class="num">$iout</td>
            <td class="num">$vout</td>
            <td class="num">$vin</td>
            <td class="num">$vtran</td>
        </tr>
"@
    }) -join "`n"

    $html = @"
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <!-- source-csv-bytes:$csvBytes -->
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Sky disturbances - $DateLabel</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/hammerjs@2.0.8/hammer.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/chartjs-plugin-zoom@2.2.0/dist/chartjs-plugin-zoom.min.js"></script>
    <style>
        :root {
            color-scheme: dark;
            --bg: #0f1419;
            --panel: #1a2332;
            --text: #e7ecf3;
            --muted: #8b9cb3;
            --border: #2a3648;
        }
        * { box-sizing: border-box; }
        body {
            margin: 0;
            font-family: "Segoe UI", system-ui, sans-serif;
            background: var(--bg);
            color: var(--text);
            line-height: 1.45;
        }
        header {
            padding: 1.25rem 1.5rem;
            border-bottom: 1px solid var(--border);
            background: linear-gradient(180deg, #152030, var(--bg));
        }
        header h1 { margin: 0 0 0.35rem; font-size: 1.35rem; }
        header p { margin: 0; color: var(--muted); font-size: 0.95rem; }
        main {
            padding: 1rem 1.5rem 2rem;
            display: grid;
            gap: 1rem;
            max-width: 1200px;
        }
        .stats {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
            gap: 0.75rem;
        }
        .stat {
            background: var(--panel);
            border: 1px solid var(--border);
            border-radius: 8px;
            padding: 0.75rem 1rem;
        }
        .stat .label { color: var(--muted); font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.04em; }
        .stat .value { font-size: 1.25rem; font-weight: 600; margin-top: 0.2rem; }
        .card {
            background: var(--panel);
            border: 1px solid var(--border);
            border-radius: 10px;
            padding: 1rem 1rem 0.75rem;
        }
        .card h2 {
            margin: 0 0 0.75rem;
            font-size: 1rem;
            font-weight: 600;
        }
        .chart-wrap { position: relative; height: 420px; }
        .legend-note { color: var(--muted); font-size: 0.85rem; margin: 0 0 0.5rem; }
        .controls {
            display: flex;
            flex-wrap: wrap;
            gap: 0.75rem;
            align-items: center;
            margin-bottom: 0.75rem;
        }
        button {
            background: #0f1419;
            color: var(--text);
            border: 1px solid var(--border);
            border-radius: 6px;
            padding: 0.45rem 0.65rem;
            font-size: 0.9rem;
            cursor: pointer;
        }
        button:hover { border-color: #4b6a9b; }
        table {
            width: 100%;
            border-collapse: collapse;
            font-size: 0.88rem;
        }
        th, td {
            border-bottom: 1px solid var(--border);
            padding: 0.45rem 0.55rem;
            text-align: left;
        }
        th { color: var(--muted); font-weight: 600; }
        td.num { font-family: Consolas, "Courier New", monospace; text-align: right; }
        tbody tr:hover { background: rgba(59, 130, 246, 0.06); }
        .note { color: var(--muted); font-size: 0.85rem; margin-top: 0.5rem; }
    </style>
</head>
<body>
    <header>
        <h1>Sky disturbances - $DateLabel</h1>
        <p>Unscheduled cloud/shade collapses (pre-crash snapshot per event)</p>
    </header>
    <main>
        <div class="stats">
            <div class="stat"><div class="label">Events</div><div class="value">$($rows.Count)</div></div>
            <div class="stat"><div class="label">First</div><div class="value">$(Format-TimeLabel $firstTs)</div></div>
            <div class="stat"><div class="label">Last</div><div class="value">$(Format-TimeLabel $lastTs)</div></div>
            <div class="stat"><div class="label">Peak IOUT</div><div class="value">$(if ($null -ne $maxIout) { "{0:N2}" -f $maxIout } else { 'n/a' }) A</div></div>
        </div>

        <section class="card">
            <h2>Pre-crash collapse parameters</h2>
            <div class="controls">
                <button type="button" id="resetZoomBtn">Reset zoom</button>
            </div>
            <p class="legend-note">Linear time axis (gaps between events are real). Left: IOUT (A). Right: Vin, VTran (V). Scroll/pinch to zoom.</p>
            <div class="chart-wrap"><canvas id="skyChart"></canvas></div>
        </section>

        <section class="card">
            <h2>Event log</h2>
            <p class="note">Source: $csvName - Generated $generatedAt</p>
            <table>
                <thead>
                    <tr>
                        <th>Time</th>
                        <th>IOUT (A)</th>
                        <th>VOUT (V)</th>
                        <th>Vin (V)</th>
                        <th>VTran (V)</th>
                    </tr>
                </thead>
                <tbody>
$tableRows
                </tbody>
            </table>
        </section>
    </main>
    <script>
        const SKY_GAP_BREAK_MS = 120000;
        const skyTimes = [$timesJson];
        const skyIout = [$ioutJson];
        const skyVin = [$vinJson];
        const skyVtran = [$vtranJson];

        function formatTimeMs(ms) {
            return new Date(ms).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        }

        function seriesPoints(times, values, breakGapMs) {
            const pts = [];
            for (let i = 0; i < times.length; i++) {
                if (breakGapMs && i > 0 && times[i] - times[i - 1] > breakGapMs) {
                    pts.push({ x: NaN, y: NaN });
                }
                const y = values[i];
                pts.push({ x: times[i], y: (y === null || y === undefined) ? NaN : y });
            }
            return pts;
        }

        const skyChart = new Chart(document.getElementById('skyChart'), {
            type: 'line',
            data: {
                datasets: [
                    {
                        label: 'IOUT (A)',
                        data: seriesPoints(skyTimes, skyIout, SKY_GAP_BREAK_MS),
                        borderColor: '#f43f5e',
                        backgroundColor: 'rgba(244, 63, 94, 0.08)',
                        yAxisID: 'yAmps',
                        tension: 0.15,
                        pointRadius: 4,
                        spanGaps: false
                    },
                    {
                        label: 'Vin (V)',
                        data: seriesPoints(skyTimes, skyVin, SKY_GAP_BREAK_MS),
                        borderColor: '#f59e0b',
                        backgroundColor: 'rgba(245, 158, 11, 0.08)',
                        yAxisID: 'yVolts',
                        tension: 0.15,
                        pointRadius: 4,
                        spanGaps: false
                    },
                    {
                        label: 'VTran (V)',
                        data: seriesPoints(skyTimes, skyVtran, SKY_GAP_BREAK_MS),
                        borderColor: '#a78bfa',
                        backgroundColor: 'rgba(167, 139, 250, 0.08)',
                        yAxisID: 'yVolts',
                        tension: 0.15,
                        pointRadius: 4,
                        spanGaps: false
                    }
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: { mode: 'index', intersect: false },
                parsing: false,
                plugins: {
                    legend: {
                        labels: { color: '#c5d0e0', usePointStyle: true }
                    },
                    tooltip: {
                        callbacks: {
                            title: function(items) {
                                if (!items.length) return '';
                                return new Date(items[0].parsed.x).toLocaleString();
                            },
                            label: function(ctx) {
                                const v = ctx.parsed.y;
                                if (v === null || Number.isNaN(v)) return ctx.dataset.label + ': n/a';
                                const unit = ctx.dataset.yAxisID === 'yAmps' ? ' A' : ' V';
                                return ctx.dataset.label + ': ' + v.toFixed(3) + unit;
                            }
                        }
                    },
                    zoom: {
                        pan: { enabled: true, mode: 'x' },
                        zoom: {
                            wheel: { enabled: true },
                            pinch: { enabled: true },
                            mode: 'x'
                        }
                    }
                },
                scales: {
                    x: {
                        type: 'linear',
                        title: { display: true, text: 'Time', color: '#8b9cb3' },
                        ticks: {
                            color: '#8b9cb3',
                            maxRotation: 45,
                            autoSkip: true,
                            maxTicksLimit: 24,
                            callback: function(value) { return formatTimeMs(value); }
                        },
                        grid: { color: 'rgba(42, 54, 72, 0.6)' }
                    },
                    yAmps: {
                        type: 'linear',
                        position: 'left',
                        title: { display: true, text: 'IOUT (A)', color: '#f43f5e' },
                        ticks: { color: '#f43f5e' },
                        grid: { color: 'rgba(42, 54, 72, 0.6)' }
                    },
                    yVolts: {
                        type: 'linear',
                        position: 'right',
                        title: { display: true, text: 'Volts', color: '#f59e0b' },
                        ticks: { color: '#8b9cb3' },
                        grid: { drawOnChartArea: false }
                    }
                }
            }
        });

        document.getElementById('resetZoomBtn').addEventListener('click', () => skyChart.resetZoom());
    </script>
</body>
</html>
"@

    [System.IO.File]::WriteAllText($HtmlPath, $html, [System.Text.UTF8Encoding]::new($false))
    Write-Host "Wrote $HtmlPath ($($rows.Count) events)"
}

if (-not (Test-Path $DestDir)) {
    Write-Error "Directory not found: $DestDir`nRun synclogs.bat first."
    exit 1
}

$csvFiles = @()
if ($Date -ne "") {
    if ($Date -notmatch '^\d{4}-\d{2}-\d{2}$') {
        Write-Error "Use date format YYYY-MM-DD (got: $Date)"
        exit 1
    }
    $csvFiles += Join-Path $DestDir "${Date}_sky_disturbances.csv"
} else {
    $csvFiles += Get-ChildItem -Path $DestDir -Filter "*_sky_disturbances.csv" | Sort-Object Name
}

if ($csvFiles.Count -eq 0) {
    Write-Host "No sky_disturbances.csv files found in $DestDir"
    exit 0
}

$built = 0
$skipped = 0
foreach ($item in $csvFiles) {
    $csvPath = if ($item -is [string]) { $item } else { $item.FullName }
    if (-not (Test-Path $csvPath)) {
        Write-Host "Missing: $csvPath"
        continue
    }
    $htmlPath = $csvPath -replace '\.csv$', '.html'
    $csvFile = Get-Item $csvPath
    if (-not $Force -and (Test-SkyHtmlUpToDate -HtmlPath $htmlPath -CsvPath $csvPath)) {
        Write-Host "Up to date: $(Split-Path $htmlPath -Leaf)"
        $skipped += 1
        continue
    }
    $dateLabel = $csvFile.BaseName -replace '_sky_disturbances$', ''
    Write-SkyHtml -CsvPath $csvPath -HtmlPath $htmlPath -DateLabel $dateLabel
    $built += 1
}

Write-Host ""
Write-Host "Done: built $built, skipped $skipped (HTML newer than CSV)"
