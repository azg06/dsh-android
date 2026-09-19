<#
  发布到 GitHub：建仓库 → 推送 → 建 Release → 上传 APK 与运行时附件。

  前置：先完成 GitHub 登录（本机凭据可能已失效）
      gh auth login -h github.com

  用法：
      .\publish.ps1                      # 公开仓库，tag 取 manifest 的版本号
      .\publish.ps1 -Visibility private  # 私有仓库
      .\publish.ps1 -SkipBuild           # 复用已有构建产物，不重新打包
#>
[CmdletBinding()]
param(
    [string]$Repo = 'dsh-android',
    [ValidateSet('public', 'private')][string]$Visibility = 'public',
    [string]$Tag = '',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location $root

function Step($n, $text) { Write-Host "`n[$n] $text" -ForegroundColor Cyan }

# ── 0. 登录检查 ──────────────────────────────────────────────
Step 0 '检查 GitHub 登录'
# 注意：gh 会把状态信息写到 stderr，而 PowerShell 把原生命令的 stderr 当作
# NativeCommandError 抛出 —— 在 $ErrorActionPreference='Stop' 下会直接把脚本打断。
# 所以这里临时降级错误策略，并用退避码判断成败，而不是解析输出文本。
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$login = ''
try {
    $login = (& gh api user -q .login 2>$null | Out-String).Trim()
} catch {
    $login = ''
}
$authOk = ($LASTEXITCODE -eq 0) -and $login -and ($login -notmatch 'message|401')
$ErrorActionPreference = $prevEap

if (-not $authOk) {
    throw @"
GitHub 凭据无效或未登录。

请在终端执行（浏览器授权，需 repo 权限）：

    gh auth login -h github.com

注意：网页版或其他客户端的登录状态与 gh CLI 是两套，互不影响。
"@
}
Write-Host "  已登录：$login" -ForegroundColor Green

# ── 1. 版本号 ────────────────────────────────────────────────
$manifest = Join-Path $root 'DHS-Harness-Full\app\AndroidManifest.xml'
$ver = ([regex]'versionName="([^"]+)"').Match((Get-Content $manifest -Raw)).Groups[1].Value
if (-not $Tag) { $Tag = "v$ver" }
Write-Host "  版本 $ver  →  tag $Tag"

# ── 2. 完整打包 ──────────────────────────────────────────────
$apk = Join-Path $root "DHS-Harness-Full\dist\DeepSeekHarness-Full-Android-v$ver.apk"
$rt  = Join-Path $root 'DHS-Harness-Full\build\assets\runtime.zip'
$pl  = Join-Path $root 'DHS-Harness-Full\build\assets\payload.zip'

if ($SkipBuild) {
    Step 2 '跳过打包（-SkipBuild）'
} else {
    Step 2 '完整打包'
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'DHS-Harness-Full\build-apk.ps1')
    if ($LASTEXITCODE -ne 0) { throw '打包失败' }
}
foreach ($f in @($apk, $rt, $pl)) {
    if (-not (Test-Path $f)) { throw "缺少产物：$f" }
    Write-Host ("  {0,-46} {1,8:N1} MB" -f (Split-Path $f -Leaf), ((Get-Item $f).Length / 1MB))
}

# ── 3. 提交本地改动 ──────────────────────────────────────────
Step 3 '提交本地改动'
$dirty = (git status --porcelain 2>&1 | Out-String).Trim()
if ($dirty) {
    git add -A
    git commit -m "chore: 发布 $Tag" | Out-Null
    Write-Host '  已提交' -ForegroundColor Green
} else {
    Write-Host '  工作区干净，无改动'
}

# ── 4. 创建远程仓库并推送 ────────────────────────────────────
Step 4 '创建远程仓库并推送'
$remote = (git remote 2>&1 | Out-String).Trim()
if (-not $remote) {
    gh repo create $Repo --$Visibility --source=. --remote=origin --push
    if ($LASTEXITCODE -ne 0) { throw '创建仓库失败' }
} else {
    git push -u origin HEAD
    if ($LASTEXITCODE -ne 0) { throw '推送失败' }
}

$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$repoUrl = (& gh repo view --json url -q .url 2>$null | Out-String).Trim()
$ErrorActionPreference = $prevEap
Write-Host "  仓库：$repoUrl" -ForegroundColor Green

# ── 5. Release ───────────────────────────────────────────────
Step 5 "创建 Release $Tag"
$notes = @"
## DSH Android $Tag

DeepSeek Harness \`dsh\` $ver 的 Android 自包含封装 —— Node 运行时与业务代码全部打进 APK，
不依赖 Termux 或任何外部安装。

### 附件说明

| 文件 | 用途 |
|---|---|
| \`$(Split-Path $apk -Leaf)\` | **直接安装这个**。首次启动解包运行时（约 10 秒） |
| \`runtime.zip\` | Node 24 运行时（Termux arm64），仅从源码构建时需要 |
| \`payload.zip\` | dsh 0.1.5 及其依赖树（**已含全部 Android 适配补丁**），仅从源码构建时需要 |

### 安装须知

- 首次使用请在应用内授予「所有文件访问权限」，工作区才会落在 \`/storage/emulated/0/DHS\`
- **从旧版本升级**：若已解包过运行时，需先卸载或「设置 → 应用 → 清除数据」再安装，
  否则不会重新解包

构建方法、平台差异清单与已知限制见仓库 README。
"@

$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
(& gh release view $Tag 2>$null | Out-Null)
$releaseExists = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $prevEap

if ($releaseExists) {
    Write-Host "  Release $Tag 已存在，补传附件"
    gh release upload $Tag $apk $rt $pl --clobber
} else {
    gh release create $Tag $apk $rt $pl --title "DSH Android $Tag" --notes $notes
}
if ($LASTEXITCODE -ne 0) { throw '创建 Release 失败' }

Step '完成' "发布成功：$repoUrl/releases/tag/$Tag"
