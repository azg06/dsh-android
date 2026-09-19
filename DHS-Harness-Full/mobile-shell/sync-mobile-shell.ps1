# =============================================================================
# 把移动端外壳层同步进 payload 的前端产物目录。
#
# 零侵入：不修改官方任何构建产物，只复制两个新文件，并在 index.html 追加
# 一行 link + 一行 script。用标记注释保证幂等，-Revert 可完全还原。
#
# 用法：
#   .\sync-mobile-shell.ps1                 # 同步到默认 payload
#   .\sync-mobile-shell.ps1 -Revert         # 从目标移除
#   .\sync-mobile-shell.ps1 -Extra <dir>    # 追加自定义目标
# =============================================================================
[CmdletBinding()]
param(
    [switch]$Revert,
    [string[]]$Extra = @()
)

$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
$srcCss = Join-Path $scriptDir 'dsh-mobile.css'
$srcJs = Join-Path $scriptDir 'dsh-mobile.js'
if (-not (Test-Path $srcCss) -or -not (Test-Path $srcJs)) {
    throw "外壳层源文件缺失（需要 dsh-mobile.css 与 dsh-mobile.js）"
}

# 默认目标是 payload 里的前端产物目录
$targets = @()
$defaultTarget = [IO.Path]::GetFullPath(
    (Join-Path $scriptDir '..\..\dsh-deploy-015\node_modules\@deepseek-ai\dsh-web-frontend\dist'))
if (Test-Path $defaultTarget) { $targets += $defaultTarget }
foreach ($t in $Extra) {
    $full = [IO.Path]::GetFullPath($t)
    if (Test-Path $full) { $targets += $full }
}
if ($targets.Count -eq 0) { throw '没有找到可同步的前端产物目录' }

$marker = '<!-- dsh-mobile-shell -->'
$snippet = @"
    $marker
    <link rel="stylesheet" href="/dsh-mobile.css" />
    <script src="/dsh-mobile.js" defer></script>
"@

foreach ($dir in $targets) {
    $destCss = Join-Path $dir 'dsh-mobile.css'
    $destJs = Join-Path $dir 'dsh-mobile.js'
    $index = Join-Path $dir 'index.html'

    if ($Revert) {
        foreach ($f in @($destCss, $destJs)) {
            if (Test-Path $f) { Remove-Item $f -Force }
        }
        if (Test-Path $index) {
            $html = [IO.File]::ReadAllText($index, [Text.Encoding]::UTF8)
            $pattern = '(?s)\r?\n?\s*' + [Regex]::Escape($marker) + '.*?dsh-mobile\.js"\s*></script>'
            $html = [Regex]::Replace($html, $pattern, '')
            [IO.File]::WriteAllText($index, $html, (New-Object Text.UTF8Encoding($false)))
        }
        Write-Host "  [还原] $dir"
        continue
    }

    Copy-Item $srcCss $destCss -Force
    Copy-Item $srcJs $destJs -Force

    if (-not (Test-Path $index)) { throw "缺少 index.html: $dir" }
    $html = [IO.File]::ReadAllText($index, [Text.Encoding]::UTF8)
    if ($html.IndexOf($marker) -lt 0) {
        $closeIdx = $html.LastIndexOf('</head>')
        if ($closeIdx -lt 0) { throw "index.html 结构异常（找不到 </head>）: $dir" }
        $html = $html.Substring(0, $closeIdx) + $snippet + "`n  " + $html.Substring($closeIdx)
        [IO.File]::WriteAllText($index, $html, (New-Object Text.UTF8Encoding($false)))
        Write-Host "  [注入] $dir"
    }
    else {
        Write-Host "  [跳过] $dir （已注入）"
    }
}

Write-Host ""
Write-Host ("移动端外壳层同步完成，共 {0} 个目标。" -f $targets.Count)
