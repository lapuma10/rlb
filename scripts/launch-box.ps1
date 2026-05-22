# launch-box.ps1 - start the RLB client inside a Sandboxie-Plus box.
#
# Usage:
#   .\scripts\launch-box.ps1 -Box JagexA
#   .\scripts\launch-box.ps1 -Box JagexA -AccountId 1a2b3c4d
#   .\scripts\launch-box.ps1 -Box JagexA -AccountId 1a2b3c4d -NoLogJunction
#
# Per-box plumbing it sets up before launch (idempotent):
#   1. C:\botlogs\<box>\ on the host, junctioned into the sandbox's
#      ~/.runelite/logs/ so you can tail the log from outside the box.
#   2. ~/.runelite/recorder/{trails,rooftops,worldmap} on the host
#      junctioned into the sandbox so shared route data isn't duplicated
#      per box. Account-scoped subdirs (sessions, buy-limits, training-plans,
#      inspect, login-state.json) stay private to each box.
#   3. If -AccountId given: copies the captured credentials.properties from
#      ~/.runelite/rlb/accounts/<id>/ into the sandbox's
#      ~/.runelite/credentials.properties so the Jagex auth flow picks
#      that account on login. Without -AccountId you'll land on the classic
#      login screen inside the sandbox.
#
# Env overrides (optional):
#   $env:JAVA_HOME            - JDK 17 root
#   $env:RLB_JAR              - full path to client-*-shaded.jar
#   $env:SANDBOXIE_START      - path to Sandboxie-Plus Start.exe
#   $env:SANDBOX_ROOT         - sandbox-private root (default C:\Sandbox\<user>)

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $Box,
    [string] $AccountId,
    [switch] $NoLogJunction,
    [switch] $NoRecorderJunction
)

$ErrorActionPreference = "Stop"

function Fail($msg) { Write-Error $msg; exit 1 }

# ---------- locate dependencies ----------

$javaHome = $env:JAVA_HOME
if (-not $javaHome) { $javaHome = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot" }
$jbin = Join-Path $javaHome "bin\java.exe"
if (-not (Test-Path $jbin)) { Fail "java.exe not found at $jbin (set `$env:JAVA_HOME)" }

$repoRoot = Split-Path -Parent $PSScriptRoot
if ($env:RLB_JAR) {
    $jar = $env:RLB_JAR
} else {
    $jar = Get-ChildItem -Path (Join-Path $repoRoot "runelite-client\build\libs\client-*.jar") `
        -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $jar -or -not (Test-Path $jar)) {
    Fail "shadow jar not found - run .\gradlew.bat :client:shadowJar (or set `$env:RLB_JAR)"
}

$startExe = $env:SANDBOXIE_START
if (-not $startExe) { $startExe = "C:\Program Files\Sandboxie-Plus\Start.exe" }
if (-not (Test-Path $startExe)) { Fail "Sandboxie Start.exe not found at $startExe" }

$sandboxRoot = $env:SANDBOX_ROOT
if (-not $sandboxRoot) { $sandboxRoot = Join-Path "C:\Sandbox" $env:USERNAME }
$boxRoot = Join-Path $sandboxRoot $Box
$boxRl   = Join-Path $boxRoot "user\current\.runelite"

# ---------- ensure sandbox dirs exist (one-time per box) ----------

New-Item -ItemType Directory -Force -Path $boxRl | Out-Null

function Ensure-Junction([string] $link, [string] $target) {
    if (-not (Test-Path $target)) {
        Write-Warning "junction target missing, skipping: $target"
        return
    }
    if (Test-Path $link) {
        $item = Get-Item $link -Force
        if ($item.LinkType -eq "Junction") { return }
        if ((Get-ChildItem $link -Force -ErrorAction SilentlyContinue | Measure-Object).Count -gt 0) {
            Write-Warning "skip junction: $link already exists with content"
            return
        }
        Remove-Item $link -Force
    }
    New-Item -ItemType Junction -Path $link -Target $target | Out-Null
    Write-Host ("junction: {0} -> {1}" -f $link, $target)
}

# ---------- logs: junction sandbox/.runelite/logs -> C:\botlogs\<box>\ ----------

if (-not $NoLogJunction) {
    $hostLogDir = Join-Path "C:\botlogs" $Box
    New-Item -ItemType Directory -Force -Path $hostLogDir | Out-Null
    Ensure-Junction -link (Join-Path $boxRl "logs") -target $hostLogDir
    Write-Host ("logs: tail with  Get-Content '{0}\client.log' -Wait -Tail 50" -f $hostLogDir)
}

# ---------- recorder data: junction shared subtrees back to host ----------

if (-not $NoRecorderJunction) {
    $hostRecorder = Join-Path $env:USERPROFILE ".runelite\recorder"
    if (-not (Test-Path $hostRecorder)) {
        Write-Warning "host recorder dir missing: $hostRecorder - run scripts\sync-recorder-data.ps1"
    } else {
        New-Item -ItemType Directory -Force -Path (Join-Path $boxRl "recorder") | Out-Null
        foreach ($sub in @("trails", "rooftops", "worldmap")) {
            Ensure-Junction `
                -link   (Join-Path $boxRl "recorder\$sub") `
                -target (Join-Path $hostRecorder $sub)
        }
    }
}

# ---------- credentials.properties: copy from rlb account store ----------

if ($AccountId) {
    $srcCreds = Join-Path $env:USERPROFILE ".runelite\rlb\accounts\$AccountId\credentials.properties"
    if (-not (Test-Path $srcCreds)) {
        Fail "no credentials for account id '$AccountId' at $srcCreds (capture via AccountLauncher first)"
    }
    $dstCreds = Join-Path $boxRl "credentials.properties"
    Copy-Item -Path $srcCreds -Destination $dstCreds -Force
    Write-Host ("credentials: copied {0} -> {1}" -f $AccountId, $dstCreds)
}

# ---------- launch ----------

Write-Host ""
Write-Host ("==> launching {0} inside box {1}" -f (Split-Path $jar -Leaf), $Box)

& $startExe "/box:$Box" `
    $jbin "-ea" `
    "--add-opens" "java.desktop/com.apple.eawt=ALL-UNNAMED" `
    "--add-opens" "java.desktop/com.apple.eawt.event=ALL-UNNAMED" `
    "--add-opens" "java.base/java.lang=ALL-UNNAMED" `
    "-jar" $jar `
    "--developer-mode"
