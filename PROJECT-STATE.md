# DSH Android —— 项目状态快照

> 用途：跨会话交接。读这一份就能接上，不必翻对话历史。
> 逐步排查过程见 `E:\工作目录\.workbuddy\memory\2026-09-19.md`。
> 最后更新：v0.4.0（服务完全可用，进入 UI 打磨）

---

## 1. 目标与现状

把 **DeepSeek Harness (dsh) 0.1.5** 完整跑在 Android 上，并做移动端 UI 适配。
**纯离线 App，不依赖 Termux 安装** —— Node 运行时与 payload 全部打包进 APK。

**现状**：服务链路完整可用（模型正常回复、会话正常跑完）。四个 Android 平台限制全部解决。
剩余工作都是 UI 打磨。

---

## 2. 目录结构

```
E:\工作目录\DSH android\
├── android-runtime/           Termux Node 24 运行时（打包时压成 runtime.zip, 58MB）
├── dsh-deploy-015/            0.1.5 payload 部署目录（压成 payload.zip, 73.6MB）
├── DHS-Harness-Full/          构建工程
│   ├── app/
│   │   ├── AndroidManifest.xml
│   │   ├── res/               colors.xml / styles.xml / layout ...
│   │   └── src/com/dsh/harness/*.java
│   ├── mobile-shell/          前端外壳层（零侵入注入到官方前端产物）
│   │   ├── dsh-mobile.css
│   │   ├── dsh-mobile.js
│   │   ├── sync-mobile-shell.ps1
│   │   └── tests/probe-*.mjs  Playwright 探针
│   ├── build-apk.ps1          打包脚本（-SkipPayload 只编译 Java）
│   └── dist/                  产物 APK
└── DSH-backup-20260919/       重写前的旧源码备份

E:\DSH\dsh-0.1.5\              本地调试用 0.1.5（探针跑在这上面）
E:\DSH\deepseek-harness\       上游源码仓库（含 playwright-core）
```

**已删除**（重写时清理，约 2.5GB）：`deepseek-harness-mobile`(1.46G)、`dsh-deploy`(rc.5)、
`dist`/`build`/`payload-cache`、`pnpm-patched`、`_probe`。
> 遗留：`deepseek-harness-mobile` 因批量删除护栏未删掉，需手动清：
> `Remove-Item -Recurse -Force 'E:\工作目录\DSH android\deepseek-harness-mobile'`

---

## 3. Java 侧六个类（各司其职，配置集中）

| 文件 | 职责 |
|---|---|
| `HarnessPaths.java` | **路径与配置的唯一来源**，所有约束写在类注释里 |
| `HarnessProcess.java` | 进程启动 / 端口探测 / 残留回收（四个坑固化成注释） |
| `HarnessService.java` | 状态机（EXTRACTING→STARTING→READY）+ 守护 + 有限次自动重启 |
| `MainActivity.java` | 壳：就绪后原生 UI 全隐藏；含 `DownloadBridge`、`onShowFileChooser` |
| `ControlBridge.java` | 本地控制桥，端口 **3099**，`X-DSH-Token` 鉴权，自更新 + 手机操控 |
| `ApkInstallProvider.java` | 安装 APK 的 content:// 出口 |

**设计要点**
- 就绪后**完全隐藏**原生界面（早期版本常驻状态条被用户明确吐槽"违和"）
- WebView `LOAD_NO_CACHE`（否则改了前端还加载旧页）
- `@JavascriptInterface` 方法跑在 WebView 的 JS 线程，弹 Toast 必须 `runOnUiThread`
- Java 里自建 `notify(String,String)` 会与 `Object.notify()` 冲突 → 已改名 `updateNotification`

---

## 4. 启动链（每个参数都是实测结论）

```text
Java → script(伪终端) → node --expose-internals <entry> web --no-open --host 127.0.0.1 --port 3080
```

| 项 | 值 / 原因 |
|---|---|
| `--no-open` | dsh web 默认调系统浏览器打开 UI，Android 上不存在该命令 → 进程会"优雅退出"(code 0) |
| `--expose-internals` | `cordis-plugin-hmr` 构造时硬检查 `ctx.loader.internal`，0.1.5 的 web profile 默认挂 HMR |
| `script -q -e -c ... /dev/null` | Node stdout 在管道下**块缓冲**，卡住时缓冲区不 flush → 日志一片空白。套伪终端变行缓冲。**代价**：启动链多一层，回收必须按特征清理 |
| `HOME` / `DSH_HOME` | **必须在应用私有目录**。公共存储是 FUSE，不支持 symlink，而 profile 引导依赖 symlink → 会卡死启动 |
| cwd | 设为**公共 DHS 工作区**（dsh 以 invoking directory 作 workspace root） |
| `PREFIX` / `PATH` / `LD_LIBRARY_PATH` | 指向 `files/files/usr` |
| 入口 | `<payload>/node_modules/@deepseek-ai/dsh/lib/bin.js`（无则回落 `lib/bin.js`） |

**就绪判据**：端口给出 **任意 HTTP 响应**（200/401/403）。**不能只认 200** —— dsh 服务要 token，
无 token 请求返回 401，只认 200 会导致永远探测不到就绪。
**WebView 必须用服务打印的带 token 地址**（`captureAuthenticatedUrl` 从 stdout 抓），裸地址会被 401 拒绝。

**进程回收**：`pkill -9 -f "deepseek-ai/dsh/lib/bin.js"` 按入口特征清理；
旧代码只 `Process.destroy()` 会杀掉 script 而留下 node 孤儿占端口 → 重启必撞 EADDRINUSE。

---

## 5. 四个 Android 平台限制（全部已解，改动点勿回退）

| # | 现象 | 位置 | 修法 |
|---|---|---|---|
| 1 | `Cannot find the native Koffi module` | payload | 补装 `@koromix/koffi-android-arm64`。**注意 npm `--os=android` 会 prune 掉 linux 平台包，而 Android 的 Node 是 Linux ABI，两者都要** |
| 2 | `flock is not supported on android-arm64` | `node-addon-system/lib/flock.js` | 原判据只认 `'linux'`/`'darwin'` → 把 `android` 当 `linux` |
| 3 | `dlopen failed: cannot locate symbol "__errno_location"` | 同上 | musl/glibc 两份预编译产物在 bionic 上都加载不了 → **android 直接放行（不加锁）**。单进程无并发写，权衡可接受 |
| 4 | `EACCES: permission denied, link ...` | `dsh-session-persistence-jsonl` | Android 10+ **禁止硬链接**（SELinux）→ `link` 换 `rename`。共 3 处：`lib/index.js` 的 `publishCurrentExclusive` 与 `materializePosix`、`lib/worker.cjs` 的 `publishCurrentExclusive` |

> `dsh-app-boot` 用 **symlinkSync**（符号链接）—— **Android 禁的是硬链接，不是符号链接**，那处不用动。

**排查方向**：Android 上任何"预编译原生模块"都要确认能在 bionic 上 dlopen。
按 `process.platform` 分派会漏，按 libc 家族分派也会漏。

---

## 6. 前端外壳层（mobile-shell）

**零侵入**：官方产物一字不改，只追加 `dsh-mobile.{css,js}` 并在 index.html 注入两行。
`sync-mobile-shell.ps1 -Revert` 可完全还原。

**定位只认官方 slot 契约** `data-slot="<name>"`（跨版本稳定），列元素 = slot 的父元素。
0.1.5 的列名：`main` / `rightbar`（旧版是 `conversation` / `details`，做候选回退）。

**三个关键实现细节**
1. **中间列必须显式 `grid-column: 2/3`** —— 侧栏右栏改 fixed 后脱离 grid 流，
   自动放置会把中间列塞进宽度为 0 的第 1 条轨道 → 整页空白。
2. **侧栏/右栏用 `left`/`top` 偏移，绝不用 `transform`** —— `transform` 创建包含块，
   会让子树里的 `position: fixed` 元素（设置面板挂在 `sidebar.settings` 下）跟着被推出屏幕。
3. **遮罩只由用户主动点顶栏菜单触发** —— 跟随 `data-sidebar-collapsed` 会在官方自行展开侧栏时误盖界面。

**设置面板**：官方是 panel(flex-row) 内含 `<nav>`，宽 334 时导航吃 188px、内容只剩 146px。
JS 打标（导航含"通用设置/General"才认定）→ CSS 翻成顶部横向 tab 栏 + 全宽内容。
**只改 nav 不够，里层 navList 也要改 row**（否则导航高 201px）。
官方自带一个"关闭"按钮（**无 aria-label，只有可见文字**）。

**导出落盘**：官方"导出日志"走 `blob:` URL，WebView 的 `DownloadListener` 收不到。
JS 捕获阶段拦截 `a[download]` → fetch → base64 → `window.DSHDownload.save()` → 写入 Download 目录。

---

## 7. 三条铁律（UI 层已三次踩坑，别再犯）

> **本层三次"全屏遮挡"事故，根因相同：用推测的状态驱动显隐。**

1. 右侧浮层"有内容就自动弹" → 重启后误弹纯色浮层。**已去掉自动行为。**
2. 设置面板跟随 `transform` 被推出屏幕。
3. 打标条件过宽（只判"有 nav"）→ 接管了工作区浏览器，整屏纯色。

**原则**：只在①有官方明确的用户意图信号，或②干脆不做自动行为，这两种情况下做显隐。

**其余 UI 约定**
- 状态栏/导航栏 `#121316`（原品牌蓝 `#3538CD` 与手机不搭）
- 顶栏只剩「打开导航」+ 会话标题；工作区指示、详情、新建会话三个元素已按需求移除
- 顶栏配色由 JS 读官方元素背景后**按亮度自动选前景色**（`readableOn()`），不沿用官方 color

---

## 8. 工作流与命令

```powershell
# 1) 改外壳层后同步进 payload 前端（dsh-deploy-015）
& 'E:\工作目录\DSH android\DHS-Harness-Full\mobile-shell\sync-mobile-shell.ps1'
#    调试时可 -Extra 'E:\DSH\dsh-0.1.5\node_modules\@deepseek-ai\dsh-web-frontend\dist'

# 2) 编译验证（只编 Java，30s）
& '...\build-apk.ps1' -SkipPayload

# 3) 完整打包
& '...\build-apk.ps1'          # 默认 DeployDir = ..\dsh-deploy-015

# 4) 改版本号：DHS-Harness-Full\app\AndroidManifest.xml 的 versionCode/versionName

# 5) 探针（需本地服务）
#    启服务：cd E:\DSH\dsh-0.1.5 && $env:DSH_HOME='...\.dsh-home'; $env:NO_PROXY='127.0.0.1,localhost'
#            node node_modules\@deepseek-ai\dsh\lib\bin.js web --no-open --host 127.0.0.1 --port 3083
node '...\mobile-shell\tests\probe-interact.mjs' "http://127.0.0.1:3083/?token=XXX"   # 11 项交互回归
node '...\mobile-shell\tests\probe-panel.mjs'    "..."   # 设置面板结构
node '...\mobile-shell\tests\probe-overlay.mjs'  "..."   # 全屏覆盖元素扫描
```

**打包注意**
- 改了 `android-runtime/` → 必须清 `payload-cache\runtime.zip`，否则复用旧包
- 改了 `dsh-deploy-015/` → 必须清 `payload-cache\payload.zip`
- 批量删文件有护栏（>50 个文件会被整体拦下），构建脚本别用 `rmtree`，改用「复用缓存 + 精准覆盖」

**环境注意**
- PowerShell 工具 stdout 常整个不回传 → 要读的输出一律 `Set-Content` 落盘再 Read
- curl/浏览器访问本地服务必须 `NO_PROXY=127.0.0.1,localhost`
- Playwright 复用 `E:/DSH/deepseek-harness/node_modules/.pnpm/playwright-core@1.61.1/...`，
  Chromium 用 `channel: 'msedge'` 免下载

---

## 8.5 发布（GitHub）

- **仓库**：https://github.com/azg06/dsh-android
- **发版方式**：`.\publish.ps1`
  （登录检查 → 读版本号 → 完整打包 → 提交 → 建仓库/推送 → 建 Release → 传三个附件）
- **仓库只放源码**（38 文件 / 0.45MB），**大文件走 Release 附件**：
  APK（131.8MB，超 GitHub 单文件 100MB 硬限）+ `runtime.zip`（58MB）+ `payload.zip`（73.6MB）
- **`.gitignore` 排除**：`android-runtime/`、`dsh-deploy-015/`、`_probe/`、`build/`、`dist/`、
  `payload-cache/`、签名材料

### 发版时容易踩的两个坑
1. **`build-apk.ps1` 会复用 `payload-cache/` 里的 zip** —— 改完 payload 代码后必须先删对应缓存，
   否则打出来的包不含改动。v0.4.8 就是这样把"附件功能坏掉"的版本发了出去。
2. **GitHub 登录**：`gh auth login` 的浏览器流程在国内网络下容易超时/EOF。
   最可靠的是写配置文件（**不联网**）：
   ```powershell
   $t = "token"
   $y = "github.com:`n    user: azg06`n    oauth_token: $t`n    git_protocol: https`n"
   [IO.File]::WriteAllText("$env:APPDATA\GitHub CLI\hosts.yml", $y, (New-Object Text.UTF8Encoding($false)))
   ```
   **必须用 `[IO.File]::WriteAllText` + 无 BOM 的 UTF8Encoding** ——
   PS 5.1 的 `Set-Content -Encoding UTF8` 会写 BOM，`gh` 解析 YAML 会失败。
   另：`publish.ps1` 的登录检查**只读本地 token、不联网**，避免网络波动打断发布。

## 9. 待办

- [ ] **模型选择看不出当前模型** —— 需要探针定位官方模型选择元素，然后在顶栏显示（用户明确提过）
- [ ] **顶栏两条三横线** —— 一条是本层的抽屉入口，一条是官方的（在"标准模式"那行右侧），
      功能重叠。**待用户决定是否去掉一条**
- [ ] `deepseek-harness-mobile` 目录待手动删除（1.46GB）

## 10. 已确认可用的验收项

- 服务启动 → 就绪进界面 → 模型正常回复、会话完整跑完
- 交互探针 11/11（抽屉开合、设置面板标记/全屏/关闭、横向导航、无页面错误）
- 设置面板可正常查看与操作
- 导出日志落到 `/storage/emulated/0/Download/`
- 附件（回形针）按钮拉起系统文件选择器
