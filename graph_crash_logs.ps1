# Build zoomable HTML charts for crash_episodes.csv files in pulled_logs/.
# Skips files whose HTML is newer than or equal to the CSV (rebuilds when CSV changes).
# Optional: graphcrash.bat 2026-06-22  (single date only)

param(
    [string]$Date = "",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$DestDir = Join-Path $PSScriptRoot "pulled_logs"
. "$PSScriptRoot\sync_common.ps1"

function Parse-NullableDouble {
    param([string]$Text)
    $t = $Text.Trim()
    if ([string]::IsNullOrEmpty($t)) { return $null }
    return [double]$t
}

function Parse-CrashRow {
    param([string]$Line)
    $parts = $Line.Split(',')
    if ($parts.Count -lt 20) { return $null }
    $ts = $parts[0].Trim()
    if ($ts -eq 'timestamp_ms') { return $null }
    if ($ts -notmatch '^\d+$') { return $null }
    return [PSCustomObject]@{
        TimestampMs = [long]$ts
        EpisodeId = [int]$parts[1]
        MsSinceEpisode = [long]$parts[2]
        CrashKind = $parts[3].Trim()
        EpisodeKind = $parts[4].Trim()
        EpisodeStart = $parts[5].Trim() -eq '1'
        PreIoutA = Parse-NullableDouble $parts[6]
        PreVoutV = Parse-NullableDouble $parts[7]
        PreVinV = Parse-NullableDouble $parts[8]
        PreVtranV = Parse-NullableDouble $parts[9]
        IoutA = Parse-NullableDouble $parts[10]
        VoutV = Parse-NullableDouble $parts[11]
        VinV = Parse-NullableDouble $parts[12]
        VtranV = Parse-NullableDouble $parts[13]
        VinErrorV = Parse-NullableDouble $parts[14]
        CommandIsetA = Parse-NullableDouble $parts[15]
        RidenIsetA = Parse-NullableDouble $parts[16]
        PvMode = $parts[17].Trim()
        RecoveryPhase = $parts[18].Trim()
        PoutW = Parse-NullableDouble $parts[19]
        PinEstW = if ($parts.Count -gt 20) { Parse-NullableDouble $parts[20] } else { $null }
    }
}

function Format-TimeLabel {
    param([long]$TimestampMs)
    return [DateTimeOffset]::FromUnixTimeMilliseconds($TimestampMs).LocalDateTime.ToString("HH:mm:ss.fff")
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

function Escape-Html {
    param([string]$Text)
    return [System.Net.WebUtility]::HtmlEncode($Text)
}

function Build-EpisodeChartsData {
    param($SortedRows)

    $episodeMeta = @{}
    $dayMinTs = [long]::MaxValue
    $dayMaxTs = [long]::MinValue

    foreach ($row in $SortedRows) {
        $id = $row.EpisodeId
        if (-not $episodeMeta.ContainsKey($id)) {
            $episodeMeta[$id] = @{
                Kind = $row.EpisodeKind
                First = $row
                Last = $row
                Count = 0
                PeakIout = $null
                Times = New-Object System.Collections.Generic.List[string]
                Iout = New-Object System.Collections.Generic.List[string]
                Vin = New-Object System.Collections.Generic.List[string]
                Vtran = New-Object System.Collections.Generic.List[string]
                Iset = New-Object System.Collections.Generic.List[string]
            }
        }
        $meta = $episodeMeta[$id]
        $meta.Last = $row
        $meta.Count++

        if ($row.EpisodeStart -and $null -ne $row.PreVinV) {
            $preTs = $row.TimestampMs - 200
            if ($preTs -lt $dayMinTs) { $dayMinTs = $preTs }
            if ($preTs -gt $dayMaxTs) { $dayMaxTs = $preTs }
            [void]$meta.Times.Add("$preTs")
            [void]$meta.Iout.Add((ConvertTo-JsonNumber $row.PreIoutA))
            [void]$meta.Vin.Add((ConvertTo-JsonNumber $row.PreVinV))
            [void]$meta.Vtran.Add((ConvertTo-JsonNumber $row.PreVtranV))
            [void]$meta.Iset.Add((ConvertTo-JsonNumber $row.CommandIsetA))
        }

        if ($row.TimestampMs -lt $dayMinTs) { $dayMinTs = $row.TimestampMs }
        if ($row.TimestampMs -gt $dayMaxTs) { $dayMaxTs = $row.TimestampMs }
        if ($null -ne $row.IoutA) {
            if ($null -eq $meta.PeakIout -or $row.IoutA -gt $meta.PeakIout) {
                $meta.PeakIout = $row.IoutA
            }
        }

        [void]$meta.Times.Add("$($row.TimestampMs)")
        [void]$meta.Iout.Add((ConvertTo-JsonNumber $row.IoutA))
        [void]$meta.Vin.Add((ConvertTo-JsonNumber $row.VinV))
        [void]$meta.Vtran.Add((ConvertTo-JsonNumber $row.VtranV))
        [void]$meta.Iset.Add((ConvertTo-JsonNumber $row.CommandIsetA))
    }

    return @{
        MinTs = $dayMinTs
        MaxTs = $dayMaxTs
        EpisodeMeta = $episodeMeta
    }
}

function Build-EpisodesJson {
    param($EpisodeMeta)

    $parts = New-Object System.Collections.Generic.List[string]
    foreach ($id in ($EpisodeMeta.Keys | Sort-Object { [int]$_ })) {
        $meta = $EpisodeMeta[$id]
        $parts.Add(@"
"$id":{"times":[$( $meta.Times -join ',' )],"iout":[$( $meta.Iout -join ',' )],"vin":[$( $meta.Vin -join ',' )],"vtran":[$( $meta.Vtran -join ',' )],"iset":[$( $meta.Iset -join ',' )]}
"@.Trim())
    }
    return '{ ' + ($parts -join ', ') + ' }'
}

function Read-CrashRows {
    param([string]$CsvPath)
    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($line in [System.IO.File]::ReadLines($CsvPath)) {
        $row = Parse-CrashRow $line
        if ($null -ne $row) {
            [void]$rows.Add($row)
        }
    }
    return $rows
}

function Test-CrashHtmlUpToDate {
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

function Write-CrashHtml {
    param(
        [string]$CsvPath,
        [string]$HtmlPath,
        [string]$DateLabel
    )

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Write-ChartStatus "  reading CSV..." DarkGray
    $rows = Read-CrashRows $CsvPath
    if ($rows.Count -eq 0) {
        Write-Warning "No data rows in $CsvPath"
        return
    }

    Write-ChartStatus "  building series ($($rows.Count) ticks)..." DarkGray
    $sortedRows = [array]($rows | Sort-Object TimestampMs)
    $built = Build-EpisodeChartsData $sortedRows
    $dayMinTs = $built.MinTs
    $dayMaxTs = $built.MaxTs
    $firstLabel = Format-DateTimeLabel $dayMinTs
    $lastLabel = Format-DateTimeLabel $dayMaxTs

    $episodeMeta = @()
    $defaultEpisodeId = $null
    $idx = 0
    foreach ($id in ($built.EpisodeMeta.Keys | Sort-Object { [int]$_ } -Descending)) {
        if ($null -eq $defaultEpisodeId) { $defaultEpisodeId = $id }
        $meta = $built.EpisodeMeta[$id]
        $first = $meta.First
        $last = $meta.Last
        $durationSec = [math]::Round(($last.MsSinceEpisode - $first.MsSinceEpisode) / 1000.0, 1)
        $peak = if ($null -ne $meta.PeakIout) { "{0:N2}" -f $meta.PeakIout } else { "n/a" }
        $kind = Escape-Html $first.EpisodeKind
        $start = Escape-Html (Format-DateTimeLabel $first.TimestampMs)
        $selected = if ($idx -eq 0) { " selected" } else { "" }
        $episodeMeta += "            <option value=`"$id`"$selected>#$id $kind @ $start ($($meta.Count) samples, ${durationSec}s, peak $peak A)</option>"
        $idx++
    }
    $episodesJson = Build-EpisodesJson $built.EpisodeMeta

    $generatedAt = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $csvName = Split-Path $CsvPath -Leaf
    $csvBytes = (Get-Item $CsvPath).Length
    $episodeCount = $built.EpisodeMeta.Count
    $tickCount = $rows.Count
    $episodeOptions = $episodeMeta -join "`n"
    if ($null -eq $defaultEpisodeId) { $defaultEpisodeId = "0" }
    Write-ChartStatus "  writing HTML..." DarkGray
    $utf8 = New-Object System.Text.UTF8Encoding $false
    $writer = New-Object System.IO.StreamWriter($HtmlPath, $false, $utf8)
    try {
        $writer.Write(@"
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <!-- source-csv-bytes:$csvBytes -->
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Crash episodes - $DateLabel</title>
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
            max-width: 1400px;
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
        .card h2 { margin: 0 0 0.75rem; font-size: 1rem; font-weight: 600; }
        .controls {
            display: flex;
            flex-wrap: wrap;
            gap: 0.75rem;
            align-items: center;
            margin-bottom: 0.75rem;
        }
        select, button {
            background: #0f1419;
            color: var(--text);
            border: 1px solid var(--border);
            border-radius: 6px;
            padding: 0.45rem 0.65rem;
            font-size: 0.9rem;
        }
        button { cursor: pointer; }
        button:hover { border-color: #4b6a9b; }
        .chart-wrap { position: relative; height: 480px; }
        .legend-note { color: var(--muted); font-size: 0.85rem; margin: 0 0 0.5rem; }
        .note { color: var(--muted); font-size: 0.85rem; margin-top: 0.5rem; }
    </style>
</head>
<body>
    <header>
        <h1>Crash episodes - $DateLabel</h1>
        <p>One episode per chart. Samples are evenly spaced; tooltip shows real time. One log row per Riden poll.</p>
    </header>
    <main>
        <div class="stats">
            <div class="stat"><div class="label">Episodes</div><div class="value">$episodeCount</div></div>
            <div class="stat"><div class="label">Ticks</div><div class="value">$tickCount</div></div>
            <div class="stat"><div class="label">First crash</div><div class="value">$firstLabel</div></div>
            <div class="stat"><div class="label">Latest crash</div><div class="value">$lastLabel</div></div>
        </div>

        <section class="card">
            <h2>Episode</h2>
            <div class="controls">
                <label for="episodeSelect">Episode:</label>
                <select id="episodeSelect">
$episodeOptions
                </select>
                <button type="button" id="resetZoomBtn">Reset zoom</button>
            </div>
            <p class="legend-note">Left: IOUT + ISET (A). Right: Vin, VTran (V).</p>
            <div class="chart-wrap"><canvas id="crashChart"></canvas></div>
            <p class="note">Source: $csvName - Generated $generatedAt</p>
        </section>
    </main>
    <script>
        const episodes = $episodesJson;
        const defaultEpisodeId = '$defaultEpisodeId';
"@)
        $writer.Write($CrashChartScriptBody)
        $writer.WriteLine('</html>')
    } finally {
        $writer.Close()
    }
    $sw.Stop()
    Write-ChartStatus "  Wrote $HtmlPath ($episodeCount episodes, $tickCount ticks) in $($sw.Elapsed.TotalSeconds.ToString('0.0'))s" Green
}

$CrashChartScriptBody = @'

        let chart = null;
        let episodeTimes = null;

        function formatTimeMs(ms) {
            return new Date(ms).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        }

        function seriesPointsIndexed(count, values) {
            const pts = [];
            for (let i = 0; i < count; i++) {
                const y = values[i];
                pts.push({ x: i, y: (y === null || y === undefined) ? NaN : y });
            }
            return pts;
        }

        function buildLineDatasets(dataPoints, spanGaps) {
            return [
                {
                    label: 'IOUT (A)',
                    data: dataPoints.iout,
                    borderColor: '#f43f5e',
                    yAxisID: 'yAmps',
                    tension: 0.15,
                    pointRadius: 0,
                    borderWidth: 1.5,
                    spanGaps: spanGaps
                },
                {
                    label: 'Command ISET (A)',
                    data: dataPoints.iset,
                    borderColor: '#fb7185',
                    borderDash: [4, 3],
                    yAxisID: 'yAmps',
                    tension: 0.15,
                    pointRadius: 0,
                    borderWidth: 1,
                    spanGaps: spanGaps
                },
                {
                    label: 'Vin (V)',
                    data: dataPoints.vin,
                    borderColor: '#f59e0b',
                    yAxisID: 'yVolts',
                    tension: 0.15,
                    pointRadius: 0,
                    borderWidth: 1.5,
                    spanGaps: spanGaps
                },
                {
                    label: 'VTran (V)',
                    data: dataPoints.vtran,
                    borderColor: '#a78bfa',
                    yAxisID: 'yVolts',
                    tension: 0.15,
                    pointRadius: 0,
                    borderWidth: 1.5,
                    spanGaps: spanGaps
                }
            ];
        }

        function episodePayload(raw) {
            return {
                times: raw.times,
                iout: raw.iout,
                vin: raw.vin,
                vtran: raw.vtran,
                iset: raw.iset,
                count: raw.times.length
            };
        }

        function buildEpisodeDatasets(ep) {
            return buildLineDatasets({
                iout: seriesPointsIndexed(ep.count, ep.iout),
                iset: seriesPointsIndexed(ep.count, ep.iset),
                vin: seriesPointsIndexed(ep.count, ep.vin),
                vtran: seriesPointsIndexed(ep.count, ep.vtran)
            }, true);
        }

        function chartOptionsEpisode(times) {
            const count = times.length;
            return {
                responsive: true,
                maintainAspectRatio: false,
                interaction: { mode: 'index', intersect: false },
                parsing: false,
                plugins: {
                    legend: { labels: { color: '#c5d0e0', usePointStyle: true } },
                    tooltip: {
                        callbacks: {
                            title: function(items) {
                                if (!items.length) return '';
                                const idx = items[0].dataIndex;
                                if (idx >= 0 && idx < times.length) {
                                    return new Date(times[idx]).toLocaleString();
                                }
                                return '';
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
                        zoom: { wheel: { enabled: true }, pinch: { enabled: true }, mode: 'x' }
                    }
                },
                scales: {
                    x: {
                        type: 'linear',
                        min: 0,
                        max: Math.max(0, count - 1),
                        title: { display: true, text: 'Sample (even spacing)', color: '#8b9cb3' },
                        ticks: {
                            color: '#8b9cb3',
                            maxRotation: 45,
                            autoSkip: true,
                            maxTicksLimit: 20,
                            callback: function(value) {
                                const idx = Math.round(value);
                                if (idx >= 0 && idx < times.length) return formatTimeMs(times[idx]);
                                return '';
                            }
                        },
                        grid: { color: 'rgba(42, 54, 72, 0.6)' }
                    },
                    yAmps: {
                        type: 'linear',
                        position: 'left',
                        title: { display: true, text: 'Amps', color: '#f43f5e' },
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
            };
        }

        function focusEpisode(id) {
            if (!chart) return;
            const raw = episodes[id];
            if (!raw || !raw.times || !raw.times.length) return;
            const ep = episodePayload(raw);
            episodeTimes = ep.times;
            chart.data.datasets = buildEpisodeDatasets(ep);
            chart.options = chartOptionsEpisode(ep.times);
            chart.resetZoom();
            chart.update('none');
        }

        chart = new Chart(document.getElementById('crashChart'), {
            type: 'line',
            data: { datasets: [] },
            options: chartOptionsEpisode([])
        });

        const select = document.getElementById('episodeSelect');
        select.addEventListener('change', () => focusEpisode(select.value));
        document.getElementById('resetZoomBtn').addEventListener('click', () => {
            if (chart) chart.resetZoom();
        });
        focusEpisode(select.value || defaultEpisodeId);
    </script>
</body>
'@

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
    $csvFiles += Resolve-PulledLogCsvPath -RootDir $DestDir -Date $Date -Suffix "crash_episodes"
} else {
    $csvFiles += Get-ChildItem -Path $DestDir -Filter "*_crash_episodes.csv" | Sort-Object Name
}

if ($csvFiles.Count -eq 0) {
    Write-Host "No crash_episodes.csv files found in $DestDir"
    exit 0
}

$built = 0
$skipped = 0
Write-ChartStatus "Checking $($csvFiles.Count) crash chart(s)..." DarkGray
foreach ($item in $csvFiles) {
    $csvPath = if ($item -is [string]) { $item } else { $item.FullName }
    if (-not (Test-Path $csvPath)) {
        Write-ChartStatus "Missing: $csvPath" Yellow
        continue
    }
    $htmlPath = $csvPath -replace '\.csv$', '.html'
    $htmlLeaf = Split-Path $htmlPath -Leaf
    $csvFile = Get-Item $csvPath
    if (-not $Force -and (Test-CrashHtmlUpToDate -HtmlPath $htmlPath -CsvPath $csvPath)) {
        Write-ChartStatus "Up to date: $htmlLeaf" DarkGray
        $skipped += 1
        continue
    }
    $dateLabel = $csvFile.BaseName -replace '_crash_episodes$', ''
    Write-ChartStatus "Building: $htmlLeaf ..." Cyan
    Write-CrashHtml -CsvPath $csvPath -HtmlPath $htmlPath -DateLabel $dateLabel
    $built += 1
}

Write-Host ""
Write-ChartStatus "Done: built $built, skipped $skipped (HTML newer than CSV)" Green
