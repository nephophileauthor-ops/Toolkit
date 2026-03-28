param(
    [string]$ModuleDir = (Split-Path -Parent $MyInvocation.MyCommand.Path)
)

$sourceRoot = Join-Path $ModuleDir "build\android"
$workspaceRoot = Resolve-Path (Join-Path $ModuleDir "..\..\..\..\..")
$jniLibsRoot = Join-Path $workspaceRoot "app\src\main\jniLibs"
$headerTarget = Join-Path $workspaceRoot "app\src\main\cpp\include\tunbridge.h"

$abis = @("arm64-v8a", "x86_64")

foreach ($abi in $abis) {
    $abiSource = Join-Path $sourceRoot $abi
    $soSource = Join-Path $abiSource "libtunbridge.so"

    if (-not (Test-Path $soSource)) {
        throw "Missing $soSource. Build the Go bridge first."
    }

    $abiTarget = Join-Path $jniLibsRoot $abi
    New-Item -ItemType Directory -Force -Path $abiTarget | Out-Null
    Copy-Item $soSource (Join-Path $abiTarget "libtunbridge.so") -Force
}

$headerSource = Join-Path (Join-Path $sourceRoot "arm64-v8a") "libtunbridge.h"
if (-not (Test-Path $headerSource)) {
    throw "Missing $headerSource. Build the Go bridge first."
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $headerTarget) | Out-Null
Copy-Item $headerSource $headerTarget -Force

Write-Output "Synced libtunbridge.so into app/src/main/jniLibs and updated cpp/include/tunbridge.h"
