# DeepSeek Harness Full Android（官方完整版 + 移动端适配 + 控制桥）

把 **官方 `deepseek-harness` 完整 Node 运行时**装进 Android 手机：
APK 内嵌 Android 版 **Node.js 24 LTS + bash/coreutils 环境**，以及官方仓库的**完整部署目录**。
手机端在本地启动真正的 `dsh web` 服务器，再用 WebView 打开官方 Web UI。

> 官方仓库部署源：`..\dsh-deploy`（对应官方 v0.1.0-rc.5 的完整部署）
> 手机 UI 与桌面版 `dsh web` 是**同一个前端**；本 APK 在其上追加了移动端外壳层与本地控制桥。

---

## 产物

```text
dist\DeepSeekHarness-Full-Android-v0.2.13.apk      (约 115 MB)
```

> v0.2.13 起内嵌的 harness 升到 **`@deepseek-ai/dsh@0.1.5-rc.1`**（与桌面端一致），
> 不再是 v0.1.0-rc.5。APK 体积从 167 MB 降到 115 MB（npm 布局比 pnpm deploy 精简）。

签名：v2/v3，debug keystore。包名 `com.dsh.harness`，
`minSdk 26 / targetSdk 28`（targetSdk 28 是为了允许执行应用私有目录内的 Node 二进制，与 Termux 策略一致）。

## 安装到手机

```powershell
adb install -r "dist\DeepSeekHarness-Full-Android-v0.2.13.apk"
```

或 `.\install-apk.ps1`，也可以把 APK 发到手机直接安装（需允许安装未知来源应用）。

### 首次使用

1. 打开 App → 授予"所有文件访问权限"（Android 11+），用于在手机根目录创建
   `/storage/emulated/0/DHS/`（工作目录，模型读写文件都在这里）。
   `DSH_HOME`（profile/会话存储）位于应用私有目录，以支持官方 symlink 机制。
   未授权时工作目录自动回退到应用私有目录，授权后重启 App 切换回公共 DHS。
2. 允许通知权限（前台服务通知 + 控制桥发通知用）。
3. 首次启动解包 Node 运行时与完整 harness（约 400 MB 解包空间，一分钟左右）。
4. 服务就绪后自动加载 `http://127.0.0.1:3080` 的官方 Web UI。
5. 在官方 UI 的 onboarding/设置里填 DeepSeek API Key。

---

## 本次新增（v0.2.12）

### 一、移动端外壳层（解决"UI 与手机不匹配 / 工具详情看不了 / 工作区指示不明显"）

三个问题的根因都在官方前端的**桌面几何假设**上，逐条对应：

| 现象 | 根因（官方源码） | 处理 |
|---|---|---|
| 手机上仍是三列桌面布局 | `AppFrame` 用内联 `grid-template-columns` 写死三列，窄屏只是把侧栏折成 56px 图标轨 | 外壳层把三列压成单列，侧栏转为**左侧抽屉**、详情列转为**全屏浮层** |
| 看不到/用不了更多工具调用 | `CENTER_MIN = 640`：手机宽度下让步链必然把 `details` 轨道算成 `0` 并裁掉，工具详情、diff、终端输出**在手机上完全打不开** | 详情列改为全屏浮层，出现选中内容时自动浮出，可关闭 |
| 工作区指派不明显 | `WorkspacePicker` 只挂在会话空态；窄屏下工作区浏览器被压在图标轨后面的抽屉里 | 顶栏常驻显示**当前工作区 + 会话标题**，未指派时高亮提示，点击直达工作区选择 |
| 触摸不友好 / 弹层溢出 | 官方按鼠标设计（28px 图标按钮、hover 才出现的控件、无安全区处理） | 触摸目标 ≥44px、输入框 16px 防聚焦缩放、`env(safe-area-inset-*)` 适配、弹层限宽 |

**实现方式：零侵入。** 官方构建产物（`index-*.js` / `index-*.css`）**一字未改**，
外壳层是追加的 `dsh-mobile.css` + `dsh-mobile.js`，由 `index.html` 引入：

```text
mobile-shell/
├── dsh-mobile.css          # 移动端样式（不依赖 CSS Module 哈希类名）
├── dsh-mobile.js           # 移动端行为（抽屉 / 详情浮层 / 顶栏）
├── sync-mobile-shell.ps1   # 幂等注入脚本（-Revert 可完全还原）
└── tests/verify-shell.mjs  # 对官方 DOM 结构的逻辑验证
```

设计要点：

- **不依赖哈希类名**。定位三列只用官方源码里写死的 `data-shell-overlay` 锚点 + DOM 顺序，
  官方前端换版本不会失效；本层自己写的 `data-dsh-*` 属性自成一套选择器。
- **不伪造 React 状态**。抽屉开合直接沿用官方自己的 `data-sidebar-collapsed`
  （官方在窄屏下 `toggleSidebar()` 本就是"展开/收起"语义）；详情开合由面板内容驱动。
- **详情开合的信号**是面板内是否出现 `<section>`（`DetailsPanel` 在有选中项时才渲染输入/输出章节），
  语义标签不受哈希影响。

还原：

```powershell
.\mobile-shell\sync-mobile-shell.ps1 -Revert
```

### 二、本地控制桥（自更新 + 手机操控）

harness 的 bash 工具跑在应用 UID 的 Node 环境里，够不到通知、Intent、剪贴板这些系统能力；
App 想自我更新也需要一个能被 harness 触发的入口。所以加了一个绑定在 **127.0.0.1:3099** 的
小型 HTTP 服务（`HarnessBridge.java`），两端都只认它。

**鉴权**：仅监听回环地址，每个请求必须带 `X-DSH-Token`。token 随机生成并持久化，
通过环境变量交给 harness：

```text
DSH_ANDROID_BRIDGE_URL=http://127.0.0.1:3099/
DSH_ANDROID_BRIDGE_TOKEN=<32 位十六进制>
```

模型侧 `GET /` 就能拿到自描述的 API 清单，例如：

```sh
curl -s -H "X-DSH-Token: $DSH_ANDROID_BRIDGE_TOKEN" "$DSH_ANDROID_BRIDGE_URL"
```

**自更新**

| 端点 | 作用 |
|---|---|
| `POST /update/payload {"zip":"<设备上的zip路径>","runtime":false}` | 用新 payload 替换 harness 程序目录（含前端产物） |
| `POST /service/restart` | 重启 `dsh web`，新前端立即生效 |
| `POST /update/apk {"path":"<设备上的apk路径>"}` | 经 `ApkProvider`（content:// 只读出口）调起系统安装器 |
| `GET /status` | 运行时就绪状态、工作区、harness 地址 |

也就是说：把新的前端/程序 zip 放进手机，模型自己就能完成"替换 → 重启 → 生效"，
不必等新 APK。**APK 本体更新**走 `/update/apk`（需要用户授权"安装未知来源应用"）。

**手机操控**

| 端点 | 作用 |
|---|---|
| `POST /device/open {"url":"https://…"}` 或 `{"package":"com.xxx"}` | 打开链接或拉起指定 App |
| `POST /device/notify {"title":"…","text":"…"}` | 发系统通知 |
| `POST /device/toast {"text":"…"}` | 轻提示 |
| `GET/POST /device/clipboard` | 读写剪贴板 |
| `POST /device/vibrate {"ms":240}` | 震动 |
| `POST /device/share {"text":"…"}` | 调起系统分享 |
| `GET /device/info` · `GET /device/battery` | 机型/系统/工作区 · 电量 |
| `GET /fs/list?path=<dir>` | 列目录 |

**能力边界（有意为之）**：这些都是普通应用权限内可做的事。
`input tap`、`am` 之类 shell 级注入需要 shell UID 或 root，未 root 设备上做不到，因此不提供。
App 私有目录与公共存储的读写等同于应用自身权限。

---

## 实现方式

### 内嵌运行时（ARM64）

来自 Termux 官方源，按依赖闭包打包：

- `nodejs-lts 24.18.0`（满足官方 engines `^22.19 || >=24`）
- `bash`、`coreutils`、`findutils`、`grep`、`sed`、`gawk`、`curl` 等
- 全部动态库：`libc++/openssl/c-ares/icu/sqlite/zlib/ncurses/readline` 等
- CA 证书：`files/usr/etc/tls/cert.pem`，通过 `SSL_CERT_FILE` 注入

### 内嵌官方 harness

用 pnpm 的 injected deploy 从官方仓库生成自包含部署：

```text
dsh-deploy/
├── lib/bin.js               # dsh CLI 入口
├── config/                  # profile 模板
└── node_modules/            # 官方全部 package 与依赖
```

服务启动命令等价于：

```sh
DSH_HOME=<私有目录> \
HOME=<手机 DHS 目录> \
PATH=<prefix>/bin:/system/bin:/system/xbin \
LD_LIBRARY_PATH=<prefix>/lib \
node <dsh>/lib/bin.js web --host 127.0.0.1 --port 3080
```

工作目录 = 手机 DHS 目录，模型 workspace 就在这里。

### Android 适配

| 组件 | 处理方式 |
|---|---|
| `dsh-sandbox-local` | 替换为 **Android 透传沙箱提供方**：保持 `ctx.sandbox` 契约、权限预置、审批流程和 `sandboxMode` 全部不变；Android 无 bwrap/Landlock，shell 进程不再做内核级围栏 |
| `dsh-fs-sandbox` | **保持官方实现**，文件工具仍在应用层做路径围栏 |
| `dsh-user-approval` | **保持官方实现**，工具审批照常 |
| `node-pty` | PTY 桩。官方默认 `dsh web` profile 不挂载终端工具，不影响默认功能 |
| `sharp` | 补充 `@img/sharp-linux-arm64` 原生二进制 |

---

## 构建（不需要 Gradle）

环境：JDK 17 + Android SDK（build-tools 34）+ `E:\android-build\debug.keystore`。

```powershell
cd DHS-Harness-Full
.\build-apk.ps1                 # 完整构建
.\build-apk.ps1 -SkipPayload    # 只编译 Java/资源，快速验证
```

脚本流程：压缩 `..\android-runtime` 与 `..\dsh-deploy` → `aapt2` → `javac` → `d8` →
组装 APK → `zipalign` → `apksigner`。

约定：

- payload 目录、产物目录都按**脚本自身位置**推导，项目整体移动后无需改脚本。
- 产物文件名跟随 `AndroidManifest.xml` 的 `versionName`，避免脚本与清单版本漂移。
- `payload-cache/` 缓存压缩结果；**改了 `dsh-deploy` 或 `android-runtime` 后要删掉对应缓存**
  （如 `payload-cache\dsh.zip`），否则会复用旧包。

### 如何更新前端 / harness

1. 改 `packages/` 或 `apps/web` 源码，构建前端产物；
2. 把新产物覆盖到 `..\dsh-deploy\node_modules\@deepseek-ai\dsh-web-frontend\dist`
   （及其镜像 `...\dsh-root\apps\web\dist`）；
3. 重新执行 `.\build-apk.ps1`。

若只想换前端而不重新出 APK，可用控制桥的 `POST /update/payload` + `POST /service/restart`。

---

## 已验证

- Android 侧全部 Java 编译通过（`build-apk.ps1 -SkipPayload`，exit 0）。
- 外壳层逻辑在**真实官方 DOM 结构**上通过 jsdom 验证，14/14：

  ```text
  node mobile-shell/tests/verify-shell.mjs
  ```

  覆盖：窄屏写入标记 · 三列定位 · 顶栏与遮罩创建 · 抽屉开合跟随官方状态 ·
  详情浮层随内容自动开合并可关闭 · 工作区「有/无」两种文案 · 宽屏不介入。
- 外壳层注入后官方产物零改动，`index.html` 仅追加两行引用。
- `dsh-mobile.css` / `dsh-mobile.js` 在本地 `dsh web` 服务下 HTTP 200 可达。
- 运行时动态依赖已用 `llvm-readelf` 检查，缺库为 0（沿用原构建结论）。

## 已知限制（诚实说明）

1. **内核级 shell 沙箱**：未 root 的 Android 无法使用 bwrap/Landlock，bash 命令不是内核围栏，
   而是应用 UID + 官方审批流程约束；文件工具仍有应用层围栏。
2. **交互式 PTY 终端**：默认 profile 不含终端工具；要启用需为 Android 编译 `node-pty`。
3. **插件安装命令 `dsh plugin`**：部署目录未内嵌 pnpm，命令行插件管理暂不可用；
   Web UI 的插件配置/开关正常。
4. **首次解包需要约 400 MB 手机空间**。
5. 国产 ROM 若隐藏"所有文件访问权限"，会使用应用私有 DHS，不影响其余功能。
6. **移动端外壳层的渲染结论未经真机截图确认**：本机浏览器截图链路不可用
   （agent-browser 与 Edge headless 均无产物），因此外壳层以"源码级推导 + DOM 结构验证 + 静态可达性"
   交付，**首次上机请重点核对**抽屉、详情浮层、顶栏工作区三处。
7. 控制桥未做速率限制；token 泄露等同于本机沙箱内任意代码可触发的手机能力。

## 项目结构

```text
DHS-Harness-Full/
├── app/
│   ├── AndroidManifest.xml
│   ├── res/
│   └── src/com/dsh/harness/
│       ├── MainActivity.java     # WebView 壳 + 状态 + 外壳层兜底注入
│       ├── NodeService.java      # 前台服务，启动/守护 dsh web 与控制桥
│       ├── HarnessRuntime.java   # 解包、DHS 目录、进程启动、bridge token
│       ├── HarnessBridge.java    # 本地控制桥（自更新 + 手机操控）
│       └── ApkProvider.java      # 自更新安装 APK 的 content:// 出口
├── mobile-shell/                 # 移动端外壳层（可独立还原）
├── build-apk.ps1
├── install-apk.ps1
└── dist/
```
