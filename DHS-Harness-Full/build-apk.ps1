# =============================================================================
# DeepSeek Harness Full Android 打包脚本
#
# 把:
#   E:\工作目录\android-runtime   (Android Node.js 24 LTS + bash/coreutils)
#   E:\工作目录\dsh-deploy       (官方 deepseek-harness 完整部署)
# 打进 APK。应用首次启动时解包到私有目录，并在前台服务里启动官方 `dsh web`。
# =============================================================================
[CmdletBinding()]
param(
    # 路径类参数留空即按脚本位置推导：param 默认值求值时 $PSScriptRoot 尚不可用
    [string]$OutDir,
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$Keystore = 'E:\android-build\debug.keystore',
    [string]$RuntimeDir,
    [string]$DeployDir,
    [switch]$SkipPayload,
    [switch]$Install
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot

# payload 目录按项目位置推导（项目此前被移动过，硬编码绝对路径会失效）
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $projectRoot 'dist' }
if ([string]::IsNullOrWhiteSpace($RuntimeDir)) { $RuntimeDir = Join-Path (Split-Path $projectRoot -Parent) 'android-runtime' }
if ([string]::IsNullOrWhiteSpace($DeployDir)) { $DeployDir = Join-Path (Split-Path $projectRoot -Parent) 'dsh-deploy-015' }

if ([string]::IsNullOrWhiteSpace($SdkPath)) { $SdkPath = 'E:\android-sdk' }
if (-not (Test-Path $SdkPath)) { throw "找不到 Android SDK: $SdkPath" }
if (-not (Test-Path $RuntimeDir)) { throw "找不到运行时目录: $RuntimeDir" }
if (-not (Test-Path $DeployDir)) { throw "找不到 dsh 部署目录: $DeployDir" }

$buildToolsRoot = Join-Path $SdkPath 'build-tools'
$platformsRoot = Join-Path $SdkPath 'platforms'
$buildTools = Get-ChildItem $buildToolsRoot -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
$platform = Get-ChildItem $platformsRoot -Directory | Sort-Object { [int](($_.Name -replace 'android-','')) } -Descending | Select-Object -First 1
if (-not $buildTools) { throw '没有可用 build-tools' }
if (-not $platform) { throw '没有可用 platform' }
$androidJar = Join-Path $platform.FullName 'android.jar'

$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    $javac = (Get-Command javac.exe -ErrorAction SilentlyContinue).Source
    if (-not $javac) { throw '找不到 JDK 17' }
    $javaHome = Split-Path (Split-Path $javac -Parent) -Parent
}
$javacExe = Join-Path $javaHome 'bin\javac.exe'

$aapt2 = Join-Path $buildTools.FullName 'aapt2.exe'
$d8 = Join-Path $buildTools.FullName 'd8.bat'
$zipalign = Join-Path $buildTools.FullName 'zipalign.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
foreach ($tool in @($aapt2, $d8, $zipalign, $apksigner, $javacExe)) {
    if (-not (Test-Path $tool)) { throw "缺少工具: $tool" }
}
if (-not (Test-Path $Keystore)) { throw "找不到密钥库: $Keystore" }

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $SdkPath

Write-Host '=============================================='
Write-Host ' DeepSeek Harness Full Android 构建'
Write-Host " SDK: $SdkPath / $($platform.Name)"
Write-Host " Runtime: $RuntimeDir"
Write-Host " Deploy : $DeployDir"
Write-Host '=============================================='

$buildDir = Join-Path $projectRoot 'build'
$classesDir = Join-Path $buildDir 'classes'
$genDir = Join-Path $buildDir 'gen'
$dexDir = Join-Path $buildDir 'dex'
$assetsDir = Join-Path $buildDir 'assets'
if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }
New-Item -ItemType Directory -Path $classesDir, $genDir, $dexDir, $assetsDir, $OutDir -Force | Out-Null

$manifest = Join-Path $projectRoot 'app\AndroidManifest.xml'
$resDir = Join-Path $projectRoot 'app\res'
$srcDir = Join-Path $projectRoot 'app\src'
$compiledRes = Join-Path $buildDir 'compiled-res.zip'
$unsignedApk = Join-Path $buildDir 'app.unsigned.apk'
$alignedApk = Join-Path $buildDir 'app.aligned.apk'
# 产物文件名以 AndroidManifest.xml 的 versionName 为准，避免脚本与清单版本漂移
$manifestText = [IO.File]::ReadAllText($manifest, [Text.Encoding]::UTF8)
$versionMatch = [Regex]::Match($manifestText, 'android:versionName="([^"]+)"')
$versionName = '0.0.0'
if ($versionMatch.Success) { $versionName = $versionMatch.Groups[1].Value }
$finalApk = Join-Path $OutDir ('DeepSeekHarness-Full-Android-v{0}.apk' -f $versionName)

# ---------- 0. 打包运行时与 dsh 部署为 zip ----------
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function New-PayloadZip([string]$SrcDir, [string]$ZipPath, [string]$Label) {
    $cacheDir = Join-Path $projectRoot 'payload-cache'
    New-Item -ItemType Directory $cacheDir -Force | Out-Null
    $cacheFile = Join-Path $cacheDir ([IO.Path]::GetFileName($ZipPath))
    if (Test-Path $cacheFile) {
        Write-Host "复用缓存 $Label ..."
        Copy-Item $cacheFile $ZipPath -Force
        return
    }
    if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
    $tmp = "$ZipPath.tmp"
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    Write-Host "压缩 $Label ..."
    [System.IO.Compression.ZipFile]::CreateFromDirectory($SrcDir, $tmp, [System.IO.Compression.CompressionLevel]::Optimal, $false)
    Move-Item $tmp $ZipPath
    Copy-Item $ZipPath $cacheFile -Force
    $mb = [math]::Round((Get-Item $ZipPath).Length / 1MB, 1)
    Write-Host "  -> $ZipPath ($mb MB)"
}

$runtimeZip = Join-Path $assetsDir 'runtime.zip'
$payloadZip = Join-Path $assetsDir 'payload.zip'
if (-not $SkipPayload) {
    New-PayloadZip $RuntimeDir $runtimeZip 'Android 运行时'
    New-PayloadZip $DeployDir $payloadZip 'deepseek-harness 部署'
}

# ---------- 1. 资源 ----------
Write-Host '[1/5] 编译资源 ...'
& $aapt2 compile --dir $resDir -o $compiledRes
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }
& $aapt2 link -o $unsignedApk -I $androidJar --manifest $manifest --java $genDir `
    --min-sdk-version 26 --target-sdk-version 28 $compiledRes
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

# ---------- 2. javac ----------
Write-Host '[2/5] 编译 Java ...'
$sourceFiles = @()
$sourceFiles += Get-ChildItem $srcDir -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$sourceFiles += Get-ChildItem $genDir -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$javacArgs = @(
    '-encoding', 'UTF-8', '-source', '1.8', '-target', '1.8', '-Xlint:-options', '-nowarn',
    '-classpath', "$androidJar;$genDir", '-d', $classesDir
) + $sourceFiles
& $javacExe @javacArgs
if ($LASTEXITCODE -ne 0) { throw 'javac 编译失败' }

# ---------- 3. d8 ----------
Write-Host '[3/5] 生成 dex ...'
$classesJar = Join-Path $buildDir 'classes.jar'
Push-Location $buildDir
try {
    & (Join-Path $javaHome 'bin\jar.exe') cf $classesJar -C $classesDir .
    & $d8 --lib $androidJar --min-api 26 --release --output $dexDir $classesJar
    if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }
} finally {
    Pop-Location
}

# ---------- 4. 组装 APK ----------
Write-Host '[4/5] 组装 APK（dex + payload） ...'
$zip = [System.IO.Compression.ZipFile]::Open($unsignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $dexFile = Join-Path $dexDir 'classes.dex'
    $entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
    $es = $entry.Open(); try { $b=[IO.File]::ReadAllBytes($dexFile); $es.Write($b,0,$b.Length) } finally { $es.Dispose() }

    foreach ($asset in @($runtimeZip, $payloadZip)) {
        if (-not (Test-Path $asset)) { continue }
        $name = 'assets/' + [IO.Path]::GetFileName($asset)
        $entry = $zip.CreateEntry($name, [System.IO.Compression.CompressionLevel]::NoCompression)
        $es = $entry.Open()
        try {
            $fs = [IO.File]::OpenRead($asset)
            try { $fs.CopyTo($es) } finally { $fs.Dispose() }
        } finally { $es.Dispose() }
    }
} finally {
    $zip.Dispose()
}

# ---------- 5. 对齐签名 ----------
Write-Host '[5/5] 对齐并签名 ...'
& $zipalign -f -p 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }
& $apksigner sign --ks $Keystore --ks-key-alias androiddebugkey --ks-pass 'pass:android' `
    --key-pass 'pass:android' --out $finalApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败' }
& $apksigner verify --verbose $finalApk | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'APK 校验失败' }

$mb = [math]::Round((Get-Item $finalApk).Length / 1MB, 1)
Write-Host ''
Write-Host "构建成功: $finalApk ($mb MB)"

if ($Install) {
    $adb = Join-Path $SdkPath 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) { throw '找不到 adb' }
    & $adb install -r $finalApk
    if ($LASTEXITCODE -ne 0) { throw 'adb install 失败' }
}
