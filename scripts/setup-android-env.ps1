<#
.SYNOPSIS
  Setup automatico dell'ambiente di build Android per FantaFormula 1 (Windows).

.DESCRIPTION
  Idempotente: puo' essere rieseguita quante volte vuoi.
  - Verifica/installa Temurin JDK 21 LTS (winget) se assente (il JDK di sistema 27 NON e' compatibile con AGP)
  - Scarica Android cmdline-tools da dl.google.com se assenti
  - Installa platform-tools, platforms;android-35, build-tools;35.0.0
  - Accetta le licenze Android SDK
  - Scrive local.properties (sdk.dir); la JDK e' selezionata dal toolchain
    Gradle (jvmToolchain(21) in app/build.gradle.kts), quindi NON serve
    org.gradle.java.home in gradle.properties.

.USAGE
  powershell -ExecutionPolicy Bypass -File .\scripts\setup-android-env.ps1
#>

[CmdletBinding()]
param(
    # Sovrascrive la posizione dell'Android SDK (default: %LOCALAPPDATA%\Android\Sdk)
    [string]$SdkRoot = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    # Sovrascrive la JDK 21 da usare
    [string]$JavaHome = ''
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Write-Step([string]$msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "    OK  $msg" -ForegroundColor Green }
function Write-Warn2([string]$msg){ Write-Host "    --  $msg" -ForegroundColor Yellow }

$ProjectRoot = Split-Path -Parent $PSScriptRoot

# ---------------------------------------------------------------- JDK 21 ----
function Find-Jdk21 {
    param([string]$Override)
    if ($Override -and (Test-Path (Join-Path $Override 'bin\javac.exe'))) { return $Override }

    $candidates = @()
    # Installazioni note (winget / msi Temurin, scoop, manuali)
    foreach ($base in @('C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java', "$env:LOCALAPPDATA\Eclipse Adoptium", "$env:USERPROFILE\.jdks")) {
        if (Test-Path $base) {
            $candidates += (Get-ChildItem -Path $base -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
        }
    }
    # JAVA_HOME corrente se e' gi una 21
    if ($env:JAVA_HOME) { $candidates = @($env:JAVA_HOME) + $candidates }

    foreach ($c in $candidates) {
        $javac = Join-Path $c 'bin\javac.exe'
        if (-not (Test-Path $javac)) { continue }
        try {
            $v = & $javac -version 2>&1 | Out-String
            if ($v -match 'javac 21\.') { return $c }
        } catch { }
    }
    return ''
}

Write-Step 'Ricerca JDK 21 LTS (Temurin)'
$Jdk21 = Find-Jdk21 -Override $JavaHome

if (-not $Jdk21) {
    $winget = Get-Command winget -ErrorAction SilentlyContinue
    if (-not $winget) { throw 'winget non disponibile: installa Temurin JDK 21 manualmente e rilancia con -JavaHome <percorso>.' }
    Write-Warn2 'JDK 21 assente: installo Eclipse Temurin 21 via winget...'
    & winget install --id EclipseAdoptium.Temurin.21.JDK --exact --silent `
        --accept-package-agreements --accept-source-agreements --disable-interactivity | Out-Null
    $Jdk21 = Find-Jdk21 -Override $JavaHome
    if (-not $Jdk21) { throw 'Installazione JDK 21 non riuscita: installala manualmente e rilancia con -JavaHome <percorso>.' }
}
Write-Ok "JDK 21: $Jdk21"
$env:JAVA_HOME = $Jdk21
$env:Path = "$Jdk21\bin;" + $env:Path

# ------------------------------------------------------- Android cmdline ----
$CmdlineDir = Join-Path $SdkRoot 'cmdline-tools\latest'
$SdkManager = Join-Path $CmdlineDir 'bin\sdkmanager.bat'

if (Test-Path $SdkManager) {
    Write-Ok "cmdline-tools gia' presenti: $CmdlineDir"
} else {
    Write-Step 'Download Android cmdline-tools'
    New-Item -ItemType Directory -Force -Path $SdkRoot | Out-Null
    $zip = Join-Path $env:TEMP 'cmdline-tools.zip'
    # Elenco candidati (dal piu' recente): l'ultimo disponibile viene usato
    $candidates = @(
        'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip',
        'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip',
        'https://dl.google.com/android/repository/commandlinetools-win-10406996_latest.zip'
    )
    $downloaded = $false
    foreach ($u in $candidates) {
        try {
            Write-Warn2 "Download $u"
            Invoke-WebRequest -Uri $u -OutFile $zip -TimeoutSec 900 -UseBasicParsing
            $downloaded = $true
            break
        } catch {
            Write-Warn2 "Fallito ($($_.Exception.Message)) - provo il candidato successivo"
        }
    }
    if (-not $downloaded) { throw 'Impossibile scaricare Android cmdline-tools.' }

    $tmp = Join-Path $env:TEMP 'cmdline-tools-extract'
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    # Il zip contiene una cartella "cmdline-tools": va rinominata in "latest"
    if (Test-Path $CmdlineDir) { Remove-Item -Recurse -Force $CmdlineDir }
    New-Item -ItemType Directory -Force -Path (Join-Path $SdkRoot 'cmdline-tools') | Out-Null
    Move-Item -Path (Join-Path $tmp 'cmdline-tools') -Destination $CmdlineDir
    Remove-Item -Recurse -Force $tmp, $zip -ErrorAction SilentlyContinue
    if (-not (Test-Path $SdkManager)) { throw "sdkmanager non trovato dopo l'estrazione: $SdkManager" }
    Write-Ok "cmdline-tools installati: $CmdlineDir"
}

# ------------------------------------------------------- Pacchetti SDK ------
$requiredPackages = @(
    'platform-tools',
    'platforms;android-35',
    'platforms;android-36',
    'build-tools;35.0.0'
)

# Verifica quali pacchetti mancano davvero (per rendere lo script idempotente)
$installed = & $SdkManager --list_installed 2>$null | Out-String
$missing = @()
foreach ($p in $requiredPackages) {
    # formato atteso: "path | version | description"
    $name = ($p -split ';')[0] -replace '^platforms$', 'platforms'
    $found = $false
    foreach ($line in ($installed -split "`n")) {
        if ($line -match [regex]::Escape($p)) { $found = $true; break }
    }
    if (-not $found) { $missing += $p }
}

if ($missing.Count -eq 0) {
    Write-Ok "Pacchetti SDK gia presenti"
} else {
    Write-Step "Installazione pacchetti SDK: $($missing -join ', ')"
    # Licenze: si incolano un paio di decine di "y" sullo stdin di sdkmanager.
    # Se le licenze sono gia' accettate il comando puo' uscire con codice != 0: non e' un errore bloccante.
    Write-Warn2 'Accetto le licenze Android SDK...'
    $yesInput = (('y' + [Environment]::NewLine) * 40)
    try {
        $yesInput | & $SdkManager --licenses --sdk_root="$SdkRoot" 2>&1 | Out-Null
    } catch {
        Write-Warn2 'Accettazione licenze: ignoro errore non bloccante'
    }

    foreach ($p in $missing) {
        Write-Warn2 "Installazione $p"
        & $SdkManager $p --sdk_root="$SdkRoot" 2>&1 | ForEach-Object { "$_" } | Select-Object -Last 3
        if ($LASTEXITCODE -ne 0) { throw "Installazione fallita: $p (exit $LASTEXITCODE)" }
    }
    Write-Ok 'Pacchetti SDK installati'
}

# Verifica finale
$final = & $SdkManager --list_installed 2>$null | Out-String
foreach ($p in $requiredPackages) {
    if ($final -notmatch [regex]::Escape($p)) { throw "Pacchetto SDK ancora mancante: $p" }
}
Write-Ok 'Piattaforma android-35 disponibile'

# ------------------------------------------------- local.properties ---------
Write-Step 'Scrittura local.properties'
$localProps = "sdk.dir=$($SdkRoot.Replace('\', '\\'))`n"
Set-Content -Path (Join-Path $ProjectRoot 'local.properties') -Value $localProps -Encoding ASCII

# NOTA (R1): non si scrive piu' org.gradle.java.home in gradle.properties.
# Il file e' portabile: la JDK 21 e' scelta dal toolchain Gradle
# (jvmToolchain(21) in app/build.gradle.kts), che la trova o la scarica da
# sola. JAVA_HOME resta esportato qui sotto per i tool esterni.
Write-Ok "sdk.dir=$SdkRoot"

Write-Host ''
Write-Host 'Ambiente Android pronto.' -ForegroundColor Green
Write-Host "  JAVA_HOME : $Jdk21"
Write-Host "  Android SDK: $SdkRoot"
