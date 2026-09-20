# DSH Android

把 **[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（`dsh`）0.1.5** 完整封装成一个
自包含的 Android 应用 —— **不依赖 Termux，不依赖任何外部安装**，Node 运行时与业务代码全部打进 APK。

装上、点开、用。服务只监听本机回环，不出网。

---

## 它是什么

`dsh` 是一个跑在 Node.js 上的 AI 编码工作台，自带 Web UI。
本项目做的事是：**把 Node 24 运行时 + dsh 及其完整依赖树塞进一个 APK**，
用一个前台服务在应用私有目录里把它拉起来，再用 WebView 指向本地服务。

```
┌─────────────────────── Android App ─────────────────────────┐
│  MainActivity (WebView)                                     │
│        │  加载 http://127.0.0.1:3080/?token=…               │
│        ▼                                                     │
│  HarnessService (前台服务)                                   │
│        │  exec                                              │
│        ▼                                                     │
│  script ──► node --expose-internals <dsh>/lib/bin.js web …   │
│                     ▲                                        │
│                     └── files/files/usr/bin/node (Termux 24)  │
│                                                              │
│  ControlBridge (127.0.0.1:3099)  ← 自更新 / 手机操控          │
└──────────────────────────────────────────────────────────────┘
```

---

## 快速开始

1. 到本仓库的 **Releases** 页下载最新的 `DeepSeekHarness-Full-Android-vX.Y.Z.apk`
2. 安装（首次需允许「安装未知应用」）
3. 首次启动会解包运行时（约 10 秒），随后进入 Web UI
4. 首次使用请在应用内授予「所有文件访问权限」，工作区才能落在 `/storage/emulated/0/DHS`

> **升级请注意**：若旧版本已解包过运行时，新版本不会重新解包。
> 涉及运行时 / payload 的更新，请先卸载或「设置 → 应用 → 清除数据」再安装。

---

## 从零构建

### 0. 前置

- Windows + PowerShell
- JDK（含 `javac`）、Android SDK（`build-tools` 含 `aapt2` / `d8` / `zipalign` / `apksigner`）
- Node.js（用于准备 payload）

脚本里默认 SDK 路径为 `E:\android-sdk`，签名密钥为 `E:\android-build\debug.keystore`，
按需在 `build-apk.ps1` 顶部修改。

### 1. 准备运行时（`android-runtime/`）

取自 **Termux** 的 Node 24，解包后是一个标准的 `usr/` 前缀：

```
android-runtime/
└── files/usr/{bin,lib,etc}/…
```

必须包含 `bin/node`、`bin/bash`、`bin/script`（伪终端包装）、`bin/pkill`，
以及 `etc/tls/cert.pem`（HTTPS 根证书）。

### 2. 准备 payload（`dsh-deploy-015/`）

```bash
mkdir dsh-deploy-015 && cd dsh-deploy-015
npm init -y
npm install @deepseek-ai/dsh@0.1.5-rc.1
```

安装后请**确认平台相关的可选依赖都在**（见下方「平台差异清单」的 A1），
然后**应用第 A 节的全部补丁** —— 这一步不做，App 能装但服务起不来。

### 3. 注入移动端外壳层

```powershell
cd DHS-Harness-Full
.\mobile-shell\sync-mobile-shell.ps1            # 注入到 payload 的前端目录
.\mobile-shell\sync-mobile-shell.ps1 -Revert    # 完全还原
```

### 4. 打包

```powershell
.\build-apk.ps1 -SkipPayload    # 只编译 Java（约 30 秒，日常验证用）
.\build-apk.ps1                 # 完整打包（重新压 runtime / payload）
```

**注意**：改了 `android-runtime/` 或 `dsh-deploy-015/` 之后，必须删掉
`payload-cache/` 下对应的 zip，否则会复用旧包。

### 5. 移植自检（已接入构建流程）

```powershell
node ..\verify-android-port.mjs dsh-deploy-015
```

逐条核对 12 项平台补丁是否在位，**任何一项不合格即退出码 1**。
`build-apk.ps1` 已在压缩 payload 之前自动调用它 —— **漏改会直接中止打包**。

> **为什么必须有这个**：这个移植靠"就地替换 `node_modules` 里的若干文件"实现，
> 而它的失败模式是**静默的**。本项目为此栽过两次：
>
> | 版本 | 事故 | 后果 |
> |---|---|---|
> | v0.4.9 | 改的是 `lib/types/commands.js`，但运行时加载的是 `lib/index.js`（bundle） | 改动完全未生效，现象与"没修"一模一样 |
> | v0.4.11 | 把 `link` 改成 `rename`，漏了紧随其后的 `unlink` 清理 | 附件仍然全线失败，且磁盘状态会误导排查方向 |
>
> 两次都不是"想不到"，而是**没有机械化手段确认改动真的在位**。
> 靠人眼核对 12 处锚点不可靠，靠注释里写"已实测"更不可靠。

### 6. 体积优化（已做）

以下内容在 Android 上**永远无法加载或不会被使用**，已从 payload 中移除：

| 路径 | 体积 | 为什么可以删 |
|---|---|---|
| `node-pty.original/` | 25.6 MB | 含 Windows ConPTY 的 `.dll`/`.node`，纯死重 |
| `@img/sharp-libvips-linux-arm64/` | 17.4 MB | glibc 版，Bionic 结构性不可用 |
| `@img/sharp-libvips-linuxmusl-arm64/` | 17.8 MB | musl 版，同样不可用 |
| `@img/sharp-linux-arm64/`、`linuxmusl-arm64/` | 0.8 MB | 同上 |

**实测收益**：`payload.zip` 73.6 MB → **51.0 MB**，APK 131.8 MB → **109.3 MB**。

> 删除前已核对 sharp 加载器：它按平台名 `switch`，**Android 无对应 case**，
> 因而根本不会 `require` 这些包，直接落到 WASM 兜底 —— 删除零风险。
> `@img/sharp-wasm32`（8.7 MB）必须保留。

### 7. 运行时侧的裁剪：移除"存在但静默失败"的命令

`android-runtime/` 是一套 **Termux bootstrap**。它自带一整套 Android 集成命令
（`am` / `pkg` / `termux-open` / `termux-wake-lock` / `termux-backup` …），
而这些命令依赖 **Termux 自己的 Java 组件**（`TermuxService` / `TermuxOpenReceiver` / am socket）——
本应用**没有移植**它们。于是这些命令全部处于一种很危险的状态：

> **存在 · `--help` 正常返回 · 实际什么都不做**

**危害不是"没用"，而是"误导"**：模型看到命令存在会以为可用，而失败是**静默的**
（`am --version` 返回 rc=0、零输出）—— 它会基于错误前提继续往下做。
**一个不存在的命令，比一个假装能用的命令安全得多。**

已移除（共 17 个命令 + `am.apk`）：

| 类别 | 命令 |
|---|---|
| **数据破坏风险** | `termux-reset`（含 `rm -rf`）· `termux-fix-shebang`（把用户脚本 shebang 改成不存在的解释器） |
| **依赖缺失的 Java 组件** | `am` · `pkg` · `termux-am` · `termux-am-socket` · `termux-open` · `termux-open-url` · `termux-wake-lock` · `termux-wake-unlock` · `termux-reload-settings` · `termux-setup-storage` · `termux-setup-package-manager` · `termux-change-repo` · `termux-info` · `termux-backup` · `termux-restore` |

**必须保留**（被 `etc/profile` 调用或支撑 shebang）：
`termux-apps-info-*` · `termux-scoped-env-variable*` · `termux-exec-*`

**同时修正的路径问题**：`etc/profile`、`etc/bash.bashrc` 原本硬编码 Termux 默认前缀
（`/data/data/com.termux/...`），在本应用里**恒不命中** —— 于是 `profile.pre`、
`profile.d/*.sh`、`bash_completion` 从未被加载。改用 `$PREFIX`（启动时已正确设置）。

> ⚠️ **一个容易忽略的连带风险**：修好 `profile` 的路径后，`profile.d/*.sh` 会
> **第一次真正被执行** —— 原先"因为路径错所以从未加载"的脚本会突然生效。
> 实测就抓到一个 `init-termux-properties.sh`（Termux UI 配置，路径全错且每次都会
> `mkdir` 失败报错），已一并移除。**修复动作本身会引入新风险，改完必须验证。**

这两类检查（命令清单 + `etc/` 全域活代码路径）都已纳入
`verify-android-port.mjs`，构建时自动拦截。

---

## Android 平台差异清单

**这一节是本项目最核心的部分。** Android 与桌面 Linux 的差异会以各种形式让服务起不来，
下面每一条都是实测踩出来的，改动前请先读原因。

### A. 打包期（payload 侧代码补丁）

| # | 现象 | 位置 | 处理 |
|---|---|---|---|
| A1 | `Cannot find the native Koffi module` | 依赖安装 | 补装 `@koromix/koffi-android-arm64`。**npm 的 `--os=android` 会 prune 掉 linux 平台包，而 Android 的 Node 是 Linux ABI，两者都要保留** |
| A2 | `flock is not supported on android-arm64` | `@deepseek-ai/node-addon-system/lib/flock.js` | 原判据只认 `'linux'`/`'darwin'` → 把 `android` 按 `linux` 处理 |
| A3 | `dlopen failed: cannot locate symbol "__errno_location"` | 同上 | musl / glibc 两份预编译产物**在 bionic 上都加载不了** → android 下直接放行（不加文件锁）。单进程无并发写，权衡可接受 |
| A4 | `EACCES: permission denied, link …` | `@deepseek-ai/dsh-session-persistence-jsonl/lib/{index.js,worker.cjs}` | **Android 10+ 禁止普通应用创建硬链接** → `link` 换 `rename`。共 3 处：两个 `publishCurrentExclusive` + 一个 `materializePosix` |
| A5 | `EACCES: permission denied, link …tmpdir…` | `@deepseek-ai/dsh-fs-local/lib/index.js` | 同上。**文件工具的「新建文件」路径**也走硬链接，android 下先用 `lstat` 复现 `EEXIST` 语义（保住 `createIfAbsent` 约定），再 `rename` |
| A6 | `glob` / `grep` 报 `ripgrep provider failure` | `@deepseek-ai/dsh-tool-fs-search/lib/index.js` | `@vscode/ripgrep` 按 `process.platform` 拼包名（无 android 变体）→ 直接解析 `@vscode/ripgrep-linux-<arch>/bin/rg`，并在返回前补一次 `chmod 0755` |
| A7 | 工作区选择器被锁在私有目录 | `@deepseek-ai/dsh-host-directory-picker-browse/lib/index.js` | 起点恒为 `homedir()`（私有目录），用户选不到公共目录 → 支持 `DSH_PICKER_ROOT` 环境变量覆盖起点 |
| A8 | **发送附件报 `prompt rejected (session/agent-busy)`** | `@deepseek-ai/dsh-attachment-local/lib/index.js` | 同上，**附件持久化也走硬链接**，共两处：`publishImmutableAlias`（给已存在对象挂别名 → 用 `copyFile`，不能 `rename`，那会移走源）与 `publishStagedObject`（发布临时文件 → `rename` 等价）。**症状极易误判**：`link` 抛的 `EACCES` 不是 `AttachmentError`，被上游兜底 catch 归成 `agent-busy`，表面看像"会话忙"，实际是**所有类型的附件都发不出去** |
| A9 | **附件报 `ATTACHMENT_WRITE_FAILED`（对象其实已落盘）** | 同上，`publishStagedObject` 的清理行 | **A8 的配套陷阱**：把提交从 `link` 改成 `rename` 后，源目录项已被**移走**，但紧随其后的 `await unlink(staged.path)` 没跟着改 → 必然 `ENOENT` → 被外层包成 `ATTACHMENT_WRITE_FAILED`。**磁盘上的表现极具误导性**：对象已按内容摘要正确写入、`tmp/` 为空、操作却报失败（且 `chmod 0400` 与目录 fsync 被一并跳过）。修法是换成同文件的 `removeTemporary()`（只吞 `ENOENT`，其余照抛）。**改 `link`→`rename` 时必须同步检查配套清理** |
| A10 | 附件报 `EACCES: open '/data/user/0'` | 同上，`ensureDurableHome` | 它用 `parse(home).root`（即 `/`）作上溯边界，为"崩溃可恢复"一路 fsync 所有祖先。桌面 Linux 合法，但 **`/data/user/0` 是全部应用的父目录，普通应用无权访问** → 附件保存失败。Android 下把边界收到应用包目录（`dirname(dirname(home))`） |

> **A2 / A3 / A6 同源**：Android 上任何「按平台名或 libc 家族分派」的逻辑都会漏。
> 更可靠的做法是在真机上做一次最小加载测试，而不是靠平台名推断。

### B. 运行期（App 侧启动参数）

| 参数 | 为什么必须 |
|---|---|
| `--no-open` | `dsh web` 默认调系统浏览器打开 UI，Android 上不存在该命令，进程会「优雅退出」（exit 0） |
| `--expose-internals` | `cordis-plugin-hmr` 构造时硬检查 `ctx.loader.internal`，而 0.1.5 的 web profile 默认挂载 HMR，缺参数会让整棵插件树装载失败（exit 1） |
| `script -q -e -c … /dev/null` | Node 的 stdout 在管道下是**块缓冲**，进程卡住时缓冲区永不 flush，日志里只剩自己的 `[boot]` 行。套一层伪终端变行缓冲。**代价**：启动链变成 `Java → script → node`，回收必须按特征清理 |
| `HOME` / `DSH_HOME` → 应用私有目录 | 公共存储是 **FUSE，不支持符号链接**，而 dsh 的 profile 引导依赖 symlink，放在公共存储上会直接卡死启动 |
| cwd → 公共工作区 | dsh 以进程的 invoking directory 作为 workspace root，这样模型读写落在用户可见的位置 |
| `DSH_PICKER_ROOT` | 见 A7，令工作区选择器从公共目录起步 |

### C. 其它坑位

- **就绪判据是「端口给出任意 HTTP 响应」，不是「返回 200」** —— dsh 的服务要 token，
  无 token 请求返回 401。只认 200 会导致永远探测不到就绪，界面无限停在「正在启动」。
- **WebView 必须加载带 token 的地址**（服务启动时会打印），裸地址会被 401 拒绝。
- **进程回收要按入口特征 `pkill -f "deepseek-ai/dsh/lib/bin.js"`** ——
  启动链多了一层 `script`，`Process.destroy()` 只杀得掉直接子进程，node 会变成孤儿占住端口。
- **解压必须给可执行文件补权限位**：zip 在 Windows 上打包不带 Unix mode（`external_attr=0`），
  解出来全是 600。补的范围要覆盖 `usr/bin/`、`usr/libexec/` **以及 `node_modules/**/bin/**`**
  （`rg` 属于最后一类）。
- **Manifest 用 `launchMode="singleTop"`**：`singleTask` 的 Activity 是 task 根，
  从它发起 `startActivityForResult` 时结果回调可能不触发 —— 表现为系统文件选择器
  「选好了、点了确定，但什么都没发生」。
- **图标必须走自适应图标**（`mipmap-anydpi-v26/`）。minSdk 26 故全设备支持；
  用传统 PNG 图标时 Android 12+ 会自动套一层白色圆底。

---

## 移动端 UI 适配

`mobile-shell/` 把官方的桌面三列 Shell 重新解释成手机形态：侧边栏 → 左侧抽屉，
右栏（工具详情）→ 全屏浮层，顶栏 → 会话标题。

三条硬约束（都踩过）：

1. **定位只认官方 `data-slot` 契约**，不依赖 CSS Module 哈希类名。
2. **中间列必须显式 `grid-column: 2/3`** —— 侧栏与右栏改成 fixed 后脱离 grid 流，
   自动放置算法会把中间列塞进宽度为 0 的第 1 条轨道，整页空白。
3. **容器显隐用 `left`/`top` 偏移，绝不用 `transform`** —— `transform` 会创建新的包含块，
   让子树里的 `position: fixed` 元素（设置面板就挂在侧栏子树下）跟着被推出屏幕。

外加一条设计原则：**不要用推测的状态驱动显隐**。本项目三次「全屏遮挡」事故
（浮层自动弹出 / 面板被 transform 推出 / 打标条件过宽接管了工作区浏览器）根因都是这个。
只在①有官方明确的用户意图信号，或②干脆不做自动行为，这两种情况下做自动行为。

---

## 控制桥

App 内置一个只监听 `127.0.0.1:3099` 的 HTTP 控制桥，令牌经环境变量交给 harness：

| 端点 | 用途 |
|---|---|
| `GET /status` | 运行时状态 / 工作区路径 |
| `POST /update/payload` | 替换 harness 程序目录（可带 `sha256`，给出即严格校验） |
| `POST /service/restart` | 重启 dsh web |
| `POST /update/apk` | 调起系统安装器 |
| `GET /device/info` · `/battery` | 机型 / 电量 |
| `POST /device/open` · `/notify` · `/toast` · `/clipboard` · `/vibrate` · `/share` | 手机操控 |
| `GET /fs/list?path=` | 列目录 |

所有请求需带 `X-DSH-Token`。

---

## 已知限制

- **仅 Android**。HarmonyOS NEXT 走不了原生路线（内核已移除 Linux ABI，Node 二进制无法运行），
  卓易通容器则明确不支持「后台常驻」与「高权限应用」，与本项目的两条硬需求冲突。
- **未做现代安全基线**：使用调试签名、`targetSdk 28`、明文流量全开。
  作为自用 / 内部分发够用，**不适合上架应用商店**。
- **无开机自启**，进程被杀后需手动打开。
- **工作区仍在应用私有目录下管理**（`DSH_HOME/storages/<id>`），
  通过 `DSH_PICKER_ROOT` 让选择器从公共目录起步来缓解，而非真正改变 dsh 的存储模型。

---

## 许可证

本项目是 [deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) 的 Android 封装。
上游与全部依赖均为 **MIT**（`node-addon-system` 系为 BSD-3-Clause），本项目同样以 **MIT** 发布。
