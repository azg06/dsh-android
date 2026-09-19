# 构建并安装 DeepSeek Harness Full Android
[CmdletBinding()]
param(
    [string]$SdkPath = $env:ANDROID_HOME
)
if ([string]::IsNullOrWhiteSpace($SdkPath)) { $SdkPath = 'E:\android-sdk' }
$adb = Join-Path $SdkPath 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "找不到 adb: $adb" }

& (Join-Path $PSScriptRoot 'build-apk.ps1') -Install -SdkPath $SdkPath
