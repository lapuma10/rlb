# sync-recorder-data.ps1 — install committed recorder data into %USERPROFILE%\.runelite\recorder\
#
# Windows equivalent of scripts/sync-recorder-data.sh. Run this ONCE on a
# fresh PC after `git clone`, or any time you `git pull` new trail / rooftop /
# world data. Safe to re-run: account-scoped files (per-character buy-limits,
# training-plans) are seeded only when absent; pure-data subtrees (trails,
# rooftops, worldmap) are mirrored wholesale.
#
# Run from any working directory:
#     pwsh -File scripts\sync-recorder-data.ps1
# or, if execution policy allows, double-click after right-click → "Run with PowerShell".
# To allow scripts for the current user (one-time):
#     Set-ExecutionPolicy -Scope CurrentUser RemoteSigned

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = Split-Path -Parent $scriptDir
$src       = Join-Path $repoRoot "data\recorder"
$dst       = Join-Path $env:USERPROFILE ".runelite\recorder"

if (-not (Test-Path $src))
{
    Write-Error "error: $src not found — are you in the rlb repo?"
    exit 1
}

New-Item -ItemType Directory -Force -Path $dst | Out-Null

# --- Pure-data subtrees (mirrored wholesale; new commits overwrite) ---
foreach ($dir in @("trails", "rooftops", "worldmap"))
{
    $srcDir = Join-Path $src $dir
    $dstDir = Join-Path $dst $dir
    if (Test-Path $srcDir)
    {
        New-Item -ItemType Directory -Force -Path $dstDir | Out-Null
        Copy-Item -Path (Join-Path $srcDir "*") -Destination $dstDir -Recurse -Force
        $count = (Get-ChildItem -Path $srcDir -Recurse -File).Count
        Write-Host "mirrored:  $dir  ($count files)"
    }
}

# --- Account-scoped seeds (only copied when target is absent) ---
function Seed-IfAbsent
{
    param([string] $rel)
    $srcFile = Join-Path $src $rel
    $dstFile = Join-Path $dst $rel
    if ((Test-Path $srcFile) -and (-not (Test-Path $dstFile)))
    {
        $dstParent = Split-Path -Parent $dstFile
        New-Item -ItemType Directory -Force -Path $dstParent | Out-Null
        Copy-Item -Path $srcFile -Destination $dstFile
        Write-Host "seeded:    $rel"
    }
}

Seed-IfAbsent "buy-limits\default.json"
Seed-IfAbsent "training-plans\default.properties"

Write-Host ""
Write-Host "done. recorder data at $dst"
