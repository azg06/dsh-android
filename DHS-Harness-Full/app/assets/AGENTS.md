# 你运行在一台 Android 手机上

这个工作区不是普通电脑 —— 你宿主在一台真实的 Android 设备上（应用包名 `com.dsh.harness`），
并且**你可以操控它**：打开应用、发通知、读写剪贴板、震动、分享、查机型电量。

这些能力通过一个**本地 HTTP 控制桥**暴露。桥只监听 `127.0.0.1`，不出网，也不接受外部连接。

## 第一步永远是这个

```sh
curl -s -H "X-DSH-Token: $DSH_ANDROID_BRIDGE_TOKEN" "$DSH_ANDROID_BRIDGE_URL/help"
```

它会返回**当前可用的全部端点**（比我在这里列的可能更新）。先跑它，再决定用什么。

两个环境变量在启动时自动注入，直接引用即可：

| 变量 | 含义 |
|---|---|
| `DSH_ANDROID_BRIDGE_URL` | 形如 `http://127.0.0.1:3099/` |
| `DSH_ANDROID_BRIDGE_TOKEN` | 必需，放进 `X-DSH-Token` 请求头 |

**每个请求都必须带这个头**，否则返回 401。

## 打开手机上的应用

**`/device/open` 需要的是包名，不是应用名。** 包名不能靠猜 —— 用 `/device/apps` 查：

```sh
# 1. 按名称搜，拿到包名
curl -s -H "X-DSH-Token: $DSH_ANDROID_BRIDGE_TOKEN" \
     "$DSH_ANDROID_BRIDGE_URL/device/apps?q=微信"

# 返回： {"count":1,"apps":[{"package":"com.tencent.mm","label":"微信"}]}

# 2. 用包名打开
curl -s -X POST -H "X-DSH-Token: $DSH_ANDROID_BRIDGE_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"package":"com.tencent.mm"}' \
     "$DSH_ANDROID_BRIDGE_URL/device/open"
```

不带 `q` 就列出全部已安装应用（可能有上百条，建议先搜）。

也可以直接开链接，不需要包名：

```sh
-d '{"url":"https://example.com"}'
```

## 其余常用能力

| 端点 | 用途 |
|---|---|
| `POST /device/notify` | 发系统通知 `{"title":"…","text":"…"}` |
| `POST /device/toast` | 屏幕上一闪而过的轻提示 `{"text":"…"}` |
| `GET /device/clipboard` · `POST /device/clipboard` | 读 / 写剪贴板 `{"text":"…"}` |
| `POST /device/vibrate` | 震动 `{"ms":240}` |
| `POST /device/share` | 调起系统分享 `{"text":"…"}` |
| `GET /device/info` | 机型 / 系统版本 / 工作区路径 |
| `GET /device/battery` | 电量与充电状态 |
| `GET /fs/list?path=<dir>` | 列目录（省略 path 则为工作区） |

## 关于这台设备的一些事实

- **存储**：工作区在公共存储（`/storage/emulated/0/…`），文件管理器里能看到；
  应用私有目录在 `/data/user/0/com.dsh.harness/files/`，**你读写不了它之外的地方**。
- **没有 root**，也没有 `am` / `pm` 之类的系统命令 —— 想操作 Android 就走这座桥。
- **shell 环境是裁剪过的 Termux**：常用命令未必齐全（例如没有 `ls` 之外的很多工具），
  缺什么可以用 Node 或桥来替代，不要假设 `apt` / `pkg` 能用。
- **控制设备是有副作用的操作**：打开应用会把用户界面切走。除非用户要求，
  不要主动去开应用或震动手机。
