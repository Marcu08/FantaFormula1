$src = "$env:TEMP\opencode\f1-circuits\circuits"
$map = @{
    "bahrain" = "bh-2002"; "saudi" = "sa-2021"; "australia" = "au-1953";
    "japan" = "jp-1962"; "china" = "cn-2004"; "miami" = "us-2022";
    "imola" = "it-1953"; "monaco" = "mc-1929"; "canada" = "ca-1978";
    "spain" = "es-2026"; "catalunya" = "es-1991"; "austria" = "at-1969";
    "uk" = "gb-1948"; "hungary" = "hu-1986"; "belgium" = "be-1925";
    "netherlands" = "nl-1948"; "italy" = "it-1914"; "azerbaijan" = "az-2016";
    "singapore" = "sg-2008"; "usa" = "us-2012"; "mexico" = "mx-1962";
    "brazil" = "br-1940"; "vegas" = "us-2023"; "qatar" = "qa-2004";
    "abu_dhabi" = "ae-2009";
}
$entries = foreach ($key in $map.Keys) {
    $file = Join-Path $src ($map[$key] + ".geojson")
    $json = Get-Content $file -Raw | ConvertFrom-Json
    $type = $json.features[0].geometry.type
    $coords = $json.features[0].geometry.coordinates
    if ($type -eq "MultiLineString") {
        # Linea principale = la piu' lunga (la pista vera, non utensili vari).
        $coords = $coords | ForEach-Object { $_ } | Sort-Object { $_.Count } -Descending | Select-Object -First 1
        $coords = $coords | ForEach-Object { $_ }
    }
    $n = @($coords).Count
    $stride = [Math]::Max(1, [Math]::Ceiling($n / 140.0))
    $lons = @(0..($n-1) | ForEach-Object { [double]$coords[$_][0] })
    $lats = @(0..($n-1) | ForEach-Object { [double]$coords[$_][1] })
    $minLon = ($lons | Measure-Object -Minimum).Minimum
    $maxLon = ($lons | Measure-Object -Maximum).Maximum
    $minLat = ($lats | Measure-Object -Minimum).Minimum
    $maxLat = ($lats | Measure-Object -Maximum).Maximum
    $midLatRad = (($minLat + $maxLat) / 2) * [Math]::PI / 180
    $cosLat = [Math]::Max(0.3, [Math]::Cos($midLatRad))
    $dx = [Math]::Max(1e-9, ($maxLon - $minLon) * $cosLat)
    $dy = [Math]::Max(1e-9, $maxLat - $minLat)
    $scale = 1.0 / [Math]::Max($dx, $dy)
    $invariant = [System.Globalization.CultureInfo]::InvariantCulture
    $points = @(0..($n-1) | Where-Object { $_ % $stride -eq 0 } | ForEach-Object {
        $x = ($lons[$_] - $minLon) * $cosLat * $scale
        $y = ($maxLat - $lats[$_]) * $scale
        $xs = $x.ToString('0.000', $invariant)
        $ys = $y.ToString('0.000', $invariant)
        "${xs}f, ${ys}f"
    })
    $line = '        "' + $key + '" to floatArrayOf(' + (($points | Select-Object -First 220) -join ", ") + '),'
    $line
}
$blocks = $entries | Sort-Object
Set-Content -Path "$env:TEMP\opencode\circuits_kotlin.txt" -Value $blocks -Encoding UTF8
"generati: $($blocks.Count)/25"
