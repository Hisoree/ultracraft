# Ultracraft: one click starts ULTRAKILL (hidden, with the UltraBridge plugin) and Minecraft, which becomes V1 as
# soon as ULTRAKILL is ready. Closing Minecraft closes the ULTRAKILL it started. -World picks another save (tests).
# No Steam: the first run asks for the ULTRAKILL folder with a dialog and saves it to %APPDATA%\Ultracraft — the
# same file the Minecraft mod reads, so only one of the two ever asks. -Jdk picks the JDK 21 used to run Minecraft.
param(
    [string]$World = 'Ultracraft',
    [string]$Jdk = ''
)
$ErrorActionPreference = 'Stop'
$root   = Split-Path -Parent $MyInvocation.MyCommand.Path
$fabric = Join-Path $root 'fabric'
$log    = Join-Path $root 'launcher.log'
$ukCfg  = Join-Path $env:APPDATA 'Ultracraft\ultracraft.properties'
function Log([string]$m) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $m" | Add-Content -Path $log }

# --- ULTRAKILL folder: saved once (ukPath= in $ukCfg), asked for otherwise ---------------------------------------
# ULTRAKILL.exe in the folder itself or one level down (some installs wrap the game in a subfolder).
function Get-UkExe([string]$dir) {
    if (-not $dir) { return $null }
    $direct = Join-Path $dir 'ULTRAKILL.exe'
    if (Test-Path -LiteralPath $direct) { return (Get-Item -LiteralPath $direct).FullName }
    $nested = Get-ChildItem -LiteralPath $dir -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'ULTRAKILL.exe' } |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if ($nested) { return (Get-Item -LiteralPath $nested).FullName }
    return $null
}
function Read-UkPath {
    if (-not (Test-Path -LiteralPath $ukCfg)) { return $null }
    $hit = Select-String -LiteralPath $ukCfg -Pattern '^\s*ukPath\s*=\s*(.+?)\s*$' | Select-Object -First 1
    if (-not $hit) { return $null }
    return $hit.Matches[0].Groups[1].Value.Trim('"')
}
function Save-UkPath([string]$dir) {
    $parent = Split-Path -Parent $ukCfg
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent | Out-Null }
    [System.IO.File]::WriteAllText($ukCfg, "ukPath=$dir`r`n", (New-Object System.Text.UTF8Encoding $false))
}
# The saved folder's exe, else a folder dialog (re-asked until a folder with ULTRAKILL.exe is picked, $null on cancel).
function Find-UkExe {
    $exe = Get-UkExe (Read-UkPath)
    if ($exe) { return $exe }
    Add-Type -AssemblyName System.Windows.Forms
    while ($true) {
        $dlg = New-Object System.Windows.Forms.FolderBrowserDialog
        $dlg.Description = 'Choose your ULTRAKILL folder (the one containing ULTRAKILL.exe)'
        $dlg.ShowNewFolderButton = $false
        $saved = Read-UkPath
        if ($saved) { $dlg.SelectedPath = $saved }
        $click = $dlg.ShowDialog()
        $chosen = $dlg.SelectedPath
        $dlg.Dispose()
        if ($click -ne [System.Windows.Forms.DialogResult]::OK) { return $null }
        $exe = Get-UkExe $chosen
        if ($exe) { Save-UkPath $chosen; return $exe }
        [void][System.Windows.Forms.MessageBox]::Show("No ULTRAKILL.exe in:`n$chosen`n`nPick the folder that contains the game.", 'Ultracraft')
    }
}
# A real JDK 21+ (JREs have javaw.exe too, and JAVA_HOME may point at one): the release file knows the version.
function Test-Jdk21([string]$dir) {
    if (-not $dir -or -not (Test-Path -LiteralPath (Join-Path $dir 'bin\javaw.exe'))) { return $false }
    $rel = Join-Path $dir 'release'
    if (Test-Path -LiteralPath $rel) { return [bool](Select-String -LiteralPath $rel -Pattern 'JAVA_VERSION="2[1-9]' -Quiet) }
    return ((& (Join-Path $dir 'bin\java.exe') -version 2>&1 | Out-String) -match 'version "2[1-9]')
}
# --- JDK 21 for Minecraft (-Jdk wins, then JAVA_HOME if it is really 21+, then the usual install places) ----------
function Find-Jdk {
    if ($Jdk) {
        if (-not (Test-Path -LiteralPath (Join-Path $Jdk 'bin\javaw.exe'))) { throw "No bin\javaw.exe in -Jdk '$Jdk'" }
        return $Jdk
    }
    if (Test-Jdk21 $env:JAVA_HOME) { return $env:JAVA_HOME }
    $roots = @(
        'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java', 'C:\Program Files\Microsoft',
        'C:\Program Files\Amazon Corretto', 'C:\Program Files\Zulu', 'C:\Program Files\BellSoft',
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium", "$env:LOCALAPPDATA\Programs\Java", "$env:USERPROFILE\.jdks", "$env:USERPROFILE\tools"
    )
    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        $hit = Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '21' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javaw.exe')) } |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    throw 'JDK 21 not found: install it (Adoptium Temurin 21) or pass -Jdk "<jdk folder>"'
}

try {
    # 1. ULTRAKILL with -ultracraft: it hides its window, loads the Sandbox and waits for Minecraft
    $startedUk = $false
    if (-not (Get-Process ULTRAKILL -ErrorAction SilentlyContinue)) {
        $ukExe = Find-UkExe
        if (-not $ukExe) { throw 'No ULTRAKILL folder chosen' }
        Log "starting ULTRAKILL at $ukExe"
        Start-Process -FilePath $ukExe -ArgumentList '-ultracraft', '-screen-fullscreen', '0', '-screen-width', '1280', '-screen-height', '720' -WorkingDirectory (Split-Path -Parent $ukExe)
        $startedUk = $true
    }

    # 2. Minecraft (Fabric + the ultracraft mod) straight into the Ultracraft world. Java is started directly with
    #    the launch files Loom wrote on the last build, which skips Gradle's start-up; Gradle is the fallback.
    $jdk    = Find-Jdk
    $cfg    = Join-Path $fabric '.gradle\loom-cache\launch.cfg'
    $cpFile = Join-Path $fabric 'build\loom-cache\argFiles\runClient'
    if ((Test-Path $cfg) -and (Test-Path $cpFile)) {
        $mcArgs = @(
            "`"-Dfabric.dli.config=$cfg`"",
            '-Dfabric.dli.env=client',
            '-Dultracraft.noLaunch=true',
            '-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotClient',
            "`"@$cpFile`"",
            'net.fabricmc.devlaunchinjector.Main',
            '--username', 'V1', '--quickPlaySingleplayer', $World
        ) -join ' '
        Log "starting Minecraft with $jdk"
        $mc = Start-Process -FilePath (Join-Path $jdk 'bin\javaw.exe') -ArgumentList $mcArgs -WorkingDirectory (Join-Path $fabric 'run') -PassThru
        $mc.WaitForExit()
        Log "Minecraft exited ($($mc.ExitCode))"
    } else {
        Log 'no Loom launch files yet: starting Minecraft through Gradle'
        $env:JAVA_HOME = $jdk
        Push-Location $fabric
        & .\gradlew.bat runClient
        Pop-Location
    }

    # 3. Minecraft closed: close the ULTRAKILL we started
    if ($startedUk) {
        $uk = Get-Process ULTRAKILL -ErrorAction SilentlyContinue
        if ($uk) {
            Log 'closing ULTRAKILL'
            [void]$uk.CloseMainWindow()
            if (-not $uk.WaitForExit(8000)) { $uk | Stop-Process -Force }
        }
    }
} catch {
    Log "error: $_"
    Add-Type -AssemblyName System.Windows.Forms
    [void][System.Windows.Forms.MessageBox]::Show("Ultracraft couldn't start:`n$_`n`nSee $log", 'Ultracraft')
}
