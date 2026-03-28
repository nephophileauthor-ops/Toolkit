param(
    [string]$ApiLevel = "24",
    [string]$GoBinary = "go",
    [string]$NdkHome = $env:ANDROID_NDK_HOME
)

if (-not $NdkHome) {
    $workspaceRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..\..\..\..")
    $localProperties = Join-Path $workspaceRoot "local.properties"
    if (Test-Path $localProperties) {
        $raw = (Get-Content $localProperties | Select-String '^sdk.dir=').ToString().Split('=')[1]
        $sdkDir = $raw -replace '\\\\', '\' -replace '^([A-Za-z])\\:', '$1:'
        $preferredNdk = Join-Path $sdkDir "ndk\25.2.9519653"
        if (Test-Path $preferredNdk) {
            $NdkHome = $preferredNdk
        }
    }
}

if (-not $NdkHome) {
    throw "ANDROID_NDK_HOME is not set and no local NDK was found from local.properties."
}

$moduleDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$toolchain = Join-Path $NdkHome "toolchains\llvm\prebuilt\windows-x86_64\bin"
$outputRoot = Join-Path $moduleDir "build\android"

$targets = @(
    @{
        Abi = "arm64-v8a"
        GoArch = "arm64"
        Clang = "aarch64-linux-android$ApiLevel-clang"
    },
    @{
        Abi = "x86_64"
        GoArch = "amd64"
        Clang = "x86_64-linux-android$ApiLevel-clang"
    }
)

Push-Location $moduleDir
try {
    foreach ($target in $targets) {
        $abiOut = Join-Path $outputRoot $target.Abi
        New-Item -ItemType Directory -Force -Path $abiOut | Out-Null

        $env:CGO_ENABLED = "1"
        $env:GOOS = "android"
        $env:GOARCH = $target.GoArch
        $env:CC = Join-Path $toolchain $target.Clang
        if ($target.GoArch -eq "amd64") {
            $env:GOAMD64 = "v1"
        } else {
            Remove-Item Env:GOAMD64 -ErrorAction SilentlyContinue
        }

        & $GoBinary build `
            -trimpath `
            -buildmode=c-shared `
            -ldflags="-s -w" `
            -o (Join-Path $abiOut "libtunbridge.so") `
            .

        if ($LASTEXITCODE -ne 0) {
            throw "Go build failed for $($target.Abi)"
        }
    }
}
finally {
    Pop-Location
}
