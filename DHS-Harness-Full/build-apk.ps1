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

# 本机 ANSI 代码页是 GBK（ACP=gb2312），而项目路径含中文（E:\工作目录\…）。
# PowerShell 5.1 在调用原生 exe 时默认按 ANSI 转换命令行参数，于是 aapt2 / d8 / javac
# 收到的是"UTF-8 被当 GBK 解码"的乱码路径，表现为：
#   E:\宸ヤ綔鐩綍\…\app\res: error: failed to open directory
# 显式把输出编码钉成 UTF-8，避免这层有损转换。
$OutputEncoding = New-Object Text.UTF8Encoding($false)
try { [Console]::OutputEncoding = New-Object Text.UTF8Encoding($false) } catch { }

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

# ── 切到项目目录，并让后续所有路径都走"相对形式" ─────────────
# 原因（实测，非推测）：本机 ANSI 代码页是 GBK，项目路径含中文（E:\工作目录\…）。
# PowerShell 5.1 把参数传给原生 exe 时按 ANSI 转换，于是 aapt2 / javac / d8 收到的是
# "UTF-8 被当 GBK 解码"的乱码路径，报：
#     E:\宸ヤ綔鐩綍\…\app\res: error: failed to open directory  (2)
# 验证过的事实：
#   · chcp 65001        —— 无效（不改 PowerShell 传参所用的 ACP）
#   · Start-Process     —— 无效
#   · 设 $OutputEncoding —— 无效
#   · **相对路径        —— 有效**（路径不含中文字节，转换无损）
# 所以这里 cd 进项目根，项目内的路径一律用相对形式；只有 SDK / JDK / keystore
# 这类本身不含中文的路径才用绝对形式。
Push-Location $projectRoot

$buildDir = 'build'
$classesDir = 'build\classes'
$genDir = 'build\gen'
$dexDir = 'build\dex'
$assetsDir = 'build\assets'
$outDirRel = 'dist'
if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }
New-Item -ItemType Directory -Path $classesDir, $genDir, $dexDir, $assetsDir, $outDirRel -Force | Out-Null

$manifest = Join-Path $projectRoot 'app\AndroidManifest.xml'
$resDir = Join-Path $projectRoot 'app\res'
$srcDir = Join-Path $projectRoot 'app\src'
# 传给 aapt2 / javac / d8 的版本必须是相对路径
$manifestRel = 'app\AndroidManifest.xml'
$resDirRel = 'app\res'
$srcDirRel = 'app\src'
$compiledRes = Join-Path $projectRoot 'build\compiled-res.zip'
$compiledResRel = 'build\compiled-res.zip'
# 每对路径都准备"绝对版"和"相对版"：
#   绝对版 → 给 .NET API（ZipFile.Open / Get-Item）与 publish.ps1 取用；
#   相对版 → 给外部 exe（aapt2 / javac / d8 / zipalign / apksigner / adb），
#            必须相对，否则中文绝对路径经 ANSI 转换后变成乱码（见文件开头说明）。
# ★ 关键区别：Push-Location 只改 PowerShell 的 $PWD，**不改 [Environment]::CurrentDirectory**，
# 而 .NET API（[IO.File] / [ZipFile] / Get-Item）用的是后者。所以：
#   给 .NET 的必须是绝对路径（用 $projectRoot 拼）
#   给外部 exe 的必须是相对路径（避开中文的 ANSI 转换）
$unsignedApk = Join-Path $projectRoot 'build\app.unsigned.apk'
$alignedApk = Join-Path $projectRoot 'build\app.aligned.apk'
$unsignedApkRel = 'build\app.unsigned.apk'
$alignedApkRel = 'build\app.aligned.apk'
# 产物文件名以 AndroidManifest.xml 的 versionName 为准，避免脚本与清单版本漂移
$manifestText = [IO.File]::ReadAllText($manifest, [Text.Encoding]::UTF8)
$versionMatch = [Regex]::Match($manifestText, 'android:versionName="([^"]+)"')
$versionName = '0.0.0'
if ($versionMatch.Success) { $versionName = $versionMatch.Groups[1].Value }
$finalApk = Join-Path $OutDir ('DeepSeekHarness-Full-Android-v{0}.apk' -f $versionName)
$finalApkRel = 'dist\DeepSeekHarness-Full-Android-v{0}.apk' -f $versionName

# ---------- 0. 移植自检（有补丁缺失即中止）----------
# 这个移植靠"就地替换 node_modules 里的若干文件"实现，历史上两次栽在"以为改了其实没改"：
#   · v0.4.9  改的是 lib/types/commands.js，而运行时加载的是 lib/index.js（bundle）
#   · v0.4.11 把 link 改成 rename，却漏了紧随其后的 unlink 清理，附件仍然全线失败
# 两次都不是想不到，而是没有机械化手段确认改动真的在位。所以在压缩 payload 之前
# 先逐条核对补丁锚点 —— 不通过就不该继续打包。
if (-not $SkipPayload) {
    $verifyScript = Join-Path (Split-Path $projectRoot -Parent) 'verify-android-port.mjs'
    if (Test-Path $verifyScript) {
        Write-Host '移植自检 ...'
        $nodeExe = (Get-Command node -ErrorAction SilentlyContinue).Source
        if (-not $nodeExe) { $nodeExe = 'node' }
        & $nodeExe $verifyScript $DeployDir
        if ($LASTEXITCODE -ne 0) {
            throw '移植自检未通过：有平台补丁缺失，已中止打包（详见上方输出）'
        }
    } else {
        Write-Host "  [warn] 未找到 $verifyScript，跳过自检"
    }
}

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

$runtimeZip = Join-Path $projectRoot 'build\assets\runtime.zip'
$payloadZip = Join-Path $projectRoot 'build\assets\payload.zip'
if (-not $SkipPayload) {
    New-PayloadZip $RuntimeDir $runtimeZip 'Android 运行时'
    New-PayloadZip $DeployDir $payloadZip 'deepseek-harness 部署'
}

# ---------- 1. 资源 ----------
Write-Host '[1/5] 编译资源 ...'
& $aapt2 compile --dir $resDirRel -o $compiledResRel
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }
# -A 指定 Android 的 assets 目录。
# 之前漏了这个参数，app/assets/ 下的文件从未进过 APK —— 而且极难发现：构建一路成功、
# APK 能装能跑，只有运行时读 assets 才抛 FileNotFoundException，表现为"功能莫名缺失"。
# 注意别与 $assetsDir 混淆：那是 build/assets，装 runtime.zip 与 payload.zip 的中间目录。
# -o / --manifest / --java / -A 一律用相对路径（同文件开头的 ACP 说明）；
# 只有 $androidJar 是绝对路径，但它在 E:\android-sdk 下、不含中文，不受影响。
$aapt2Link = @(
    'link', '-o', $unsignedApkRel, '-I', $androidJar,
    '--manifest', $manifestRel, '--java', $genDir,
    '--min-sdk-version', '26', '--target-sdk-version', '28'
)
if (Test-Path 'app\assets') {
    $aapt2Link += @('-A', 'app\assets')
    Write-Host '  assets: app\assets'
}
$aapt2Link += $compiledResRel
& $aapt2 @aapt2Link
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

# ---------- 2. javac ----------
Write-Host '[2/5] 编译 Java ...'
# 源文件路径也必须是相对形式：cwd 已是项目根，Resolve-Path -Relative 给出的
# 就是项目内相对路径，从而避开中文绝对路径的 ANSI 转换问题。
$sourceFiles = @()
$sourceFiles += Get-ChildItem $srcDirRel -Recurse -Filter '*.java' | ForEach-Object { Resolve-Path -Relative $_.FullName }
$sourceFiles += Get-ChildItem $genDir -Recurse -Filter '*.java' | ForEach-Object { Resolve-Path -Relative $_.FullName }
$javacArgs = @(
    '-encoding', 'UTF-8', '-source', '1.8', '-target', '1.8', '-Xlint:-options', '-nowarn',
    '-classpath', "$androidJar;$genDir", '-d', $classesDir
) + $sourceFiles
& $javacExe @javacArgs
if ($LASTEXITCODE -ne 0) { throw 'javac 编译失败' }

# ---------- 3. d8 ----------
Write-Host '[3/5] 生成 dex ...'
$classesJar = 'build\classes.jar'
Push-Location $buildDir
try {
    # cwd 已是 build\，这里全部用相对名，避免中文绝对路径进入命令行
    & (Join-Path $javaHome 'bin\jar.exe') cf 'classes.jar' -C 'classes' .
    & $d8 --lib $androidJar --min-api 26 --release --output 'dex' 'classes.jar'
    if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }
} finally {
    Pop-Location
}

# ---------- 4. 组装 APK ----------
Write-Host '[4/5] 组装 APK（dex + payload） ...'
$zip = [System.IO.Compression.ZipFile]::Open($unsignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $dexFile = Join-Path $projectRoot 'build\dex\classes.dex'
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
& $zipalign -f -p 4 $unsignedApkRel $alignedApkRel
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }
& $apksigner sign --ks $Keystore --ks-key-alias androiddebugkey --ks-pass 'pass:android' `
    --key-pass 'pass:android' --out $finalApkRel $alignedApkRel
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败' }
& $apksigner verify --verbose $finalApkRel | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'APK 校验失败' }

$mb = [math]::Round((Get-Item $finalApk).Length / 1MB, 1)
Write-Host ''
Write-Host "构建成功: $finalApk ($mb MB)"

if ($Install) {
    $adb = Join-Path $SdkPath 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) { throw '找不到 adb' }
    & $adb install -r $finalApkRel
    if ($LASTEXITCODE -ne 0) { throw 'adb install 失败' }
}

Pop-Location
