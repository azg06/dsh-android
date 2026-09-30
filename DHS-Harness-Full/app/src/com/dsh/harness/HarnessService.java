package com.dsh.harness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 前台服务：负责 dsh web 的整条生命周期。
 *
 * 状态机：INIT → EXTRACTING → STARTING → READY ⇄ STOPPED
 * 每个状态都会广播出去，界面只做展示，不参与判断。
 *
 * 关于自动重启：dsh 退出码为 0 时通常是"服务没能开始监听"（优雅退出而非崩溃），
 * 重试往往能跨过去；但重试必须先把端口和残留进程清干净，否则必然撞 EADDRINUSE。
 * 重试有上限，耗尽后如实上报原始退出码，不掩盖问题。
 */
public class HarnessService extends Service {

    public static final String ACTION_START = "com.dsh.harness.START";
    public static final String ACTION_STOP = "com.dsh.harness.STOP";
    public static final String ACTION_RESTART = "com.dsh.harness.RESTART";

    public static final String ACTION_STATE = "com.dsh.harness.STATE";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_LOG = "log";

    public static final String STATE_EXTRACTING = "EXTRACTING";
    public static final String STATE_STARTING = "STARTING";
    public static final String STATE_READY = "READY";
    public static final String STATE_STOPPED = "STOPPED";

    private static final String CHANNEL_ID = "dsh";
    /**
     * 前台服务常驻通知。
     *
     * ★ 这条**绝不能**带 `miui.focus.param`：小米把带该参数的通知当作"焦点通知"处理，
     * 未获超级岛授权的应用会被系统**整条静默丢弃** —— 连通知栏都看不到。
     * 前台服务通知消失还会让系统认为服务无通知而回收进程。所以它必须保持纯净。
     */
    private static final int NOTIFICATION_ID = 1;
    /**
     * 状态 / 超级岛通知。
     *
     * 带 `miui.focus.param`，在**已获授权**的设备上会被渲染成超级岛 + 状态栏芯片；
     * 未授权设备上被系统丢弃 —— 这是可接受的，因为状态同时也写进了上面那条常驻通知。
     */
    private static final int STATUS_NOTIFICATION_ID = 2;
    private static final int MAX_RESTARTS = 3;

    private volatile Process process;
    private volatile Thread worker;
    private volatile boolean shuttingDown;
    private volatile int restarts;
    /** 正在解包/启动中。不能用 worker.isAlive() 代替 —— 见 onStartCommand 的注释。 */
    private volatile boolean booting;

    /**
     * 服务打印出来的带 token 的访问地址。
     * WebView 必须用它 —— 裸地址 http://127.0.0.1:3080/ 会被服务以 401 拒绝。
     */
    private static volatile String authenticatedUrl;

    /** 供界面取用；尚未捕获到时返回 null。 */
    public static String authenticatedUrl() {
        return authenticatedUrl;
    }
    private ControlBridge bridge;

    /** 当前存活的服务实例，供控制桥推送状态用；未运行时为 null。 */
    private static volatile HarnessService instance;

    /**
     * 由控制桥调用，更新通知里的 Agent 工作状态。
     *
     * 做成静态入口是因为桥在另一个类里，拿不到 Service 引用；
     * 而状态展示必须落到那条**已经存在**的前台通知上 —— 另发一条会是两条通知，
     * 且前台服务通知才是系统允许提升为 Live Update / 岛的那一条。
     *
     * @param title 标题。
     * @param text 正文。
     * @param chip 状态栏芯片文案，可为空。
     * @return 是否成功投递（服务未运行时为 false）。
     */
    public static boolean updateStatus(String title, String text, String chip, String mode) {
        HarnessService s = instance;
        if (s == null) {
            return false;
        }
        // mode 语义：
        //   auto（默认）—— 双通道：① 前台通知改成同样文案（不带岛参数，任何设备保证可见）
        //                          ② 另发一条带岛参数的通知（授权设备上升格为超级岛/状态栏芯片）
        //   normal      —— 只更新前台通知，并清掉岛通知（已知未授权 / 不希望出现岛时用）
        //   focus       —— 只发岛通知（授权设备上通知栏更干净；未授权时**完全不可见**）
        //
        // 为什么默认是 auto 而不是 focus：只发带岛参数的那条，在未授权设备上会被系统
        // 整条丢弃 —— 用户既看不到岛也看不到通知，而桥仍返回 ok:true。本项目踩过这个坑，
        // 是最难排查的一种失败模式。
        boolean wantNormal = !"focus".equals(mode);
        boolean wantIsland = !"normal".equals(mode);

        if (wantNormal) {
            s.updateNotification(title, text);
        }
        if (wantIsland) {
            s.updateIslandNotification(title, text, chip);
        } else {
            NotificationManager nm = (NotificationManager) s.getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(STATUS_NOTIFICATION_ID);
            }
        }
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        startForegroundCompat(buildNotification("正在初始化", "准备 DeepSeek Harness…"));
        try {
            bridge = new ControlBridge(this, HarnessPaths.bridgeToken(this));
            bridge.start();
        } catch (Exception e) {
            log("[bridge] 启动失败: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shuttingDown = true;
            HarnessProcess.stop(this, process);
            process = null;
            broadcast(STATE_STOPPED, "服务已停止", -1, null);
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    shutdownCurrent();
                    HarnessProcess.killStale(HarnessService.this);
                    waitPortFree(16);
                    shuttingDown = false;
                    restarts = 0;
                    boot();
                }
            }, "dsh-restart").start();
            return START_STICKY;
        }
        // 幂等启动：worker 线程在 boot() 返回后就结束了（服务本体由 probe 线程守护），
        // 所以 worker.isAlive() 不能代表"是否已在运行"。用它会让每次 onStartCommand
        // 都再拉起一份，日志里出现重复的 [boot] 段，端口也会互撞。
        if (booting) {
            log("[start] 正在启动中，忽略重复请求");
            return START_STICKY;
        }
        if (process != null && process.isAlive()) {
            log("[start] 服务已在运行，忽略重复请求");
            return START_STICKY;
        }
        booting = true;
        shuttingDown = false;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                boot();
            }
        }, "dsh-boot");
        worker.start();
        return START_STICKY;
    }

    /** 解包 → 启动 → 守护。 */
    private void boot() {
        try {
            broadcast(STATE_EXTRACTING, "正在解包运行时…", 0, null);
            HarnessPaths.ensureExtracted(this, new HarnessPaths.Progress() {
                @Override
                public void onProgress(int percent, String message) {
                    broadcast(STATE_EXTRACTING, message, percent, null);
                    updateNotification("正在初始化", message);
                }
            });
            if (shuttingDown) {
                return;
            }
            // 让 dsh 的工作区选择器能看到公共 DHS 目录（否则只能在私有目录里选）
            HarnessPaths.ensureWorkspaceLink(this);
            launch();
        } catch (Exception e) {
            broadcast(STATE_STOPPED, "初始化失败: " + e.getMessage(), -1, null);
            updateNotification("启动失败", String.valueOf(e.getMessage()));
        } finally {
            // 启动流程走完即清除；此后靠 process.isAlive() 判断是否在运行
            booting = false;
        }
    }

    private void launch() {
        broadcast(STATE_STARTING, "正在启动 dsh web…", 100, null);
        updateNotification("DeepSeek Harness", "正在启动 dsh web…");

        // 记录实际使用的配置：出问题时这几行是唯一的现场证据
        log("[boot] node   = " + HarnessPaths.nodeBinary(this).getAbsolutePath());
        log("[boot] entry  = " + HarnessPaths.entry(this).getAbsolutePath()
                + " (exists=" + HarnessPaths.entry(this).isFile() + ")");
        log("[boot] HOME   = " + HarnessPaths.home(this).getAbsolutePath());
        log("[boot] cwd    = " + HarnessPaths.workspace(this).getAbsolutePath());
        log("[boot] args   = node --expose-internals <entry> web --no-open --host 127.0.0.1 --port "
                + HarnessPaths.PORT);

        // 清掉上一轮可能残留的 node（script 包装会留下孤儿占端口）
        HarnessProcess.killStale(this);
        waitPortFree(16);

        try {
            process = HarnessProcess.start(this);
        } catch (Exception e) {
            broadcast(STATE_STOPPED, "启动失败: " + e.getMessage(), -1, null);
            updateNotification("启动失败", String.valueOf(e.getMessage()));
            return;
        }

        final Process p = process;
        // stdout 已合并 stderr；逐行落日志并广播，界面实时可见
        new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log(line);
                        captureAuthenticatedUrl(line);
                        broadcast(null, null, -1, line);
                    }
                } catch (IOException ignored) {
                    // 进程退出时管道关闭，正常现象
                } finally {
                    try {
                        if (reader != null) {
                            reader.close();
                        }
                    } catch (IOException ignored) {
                        // 忽略
                    }
                    onProcessGone(p);
                }
            }
        }, "dsh-stdout").start();

        probeUntilReady();
    }

    /** 轮询端口直到就绪；期间进程若已退出则由 onProcessGone 处理。 */
    private void probeUntilReady() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 500ms 一轮 × 240 轮 = 最长等 120 秒；探测更密，服务就绪后能更快进界面
                for (int i = 0; i < 240; i++) {
                    if (shuttingDown) {
                        return;
                    }
                    // 每满一秒上报一次等待进度：dsh 冷启动要几秒，界面若一直停在同一句话，
                    // 用户会以为应用卡死了。
                    if (i > 0 && i % 2 == 0) {
                        broadcast(STATE_STARTING,
                                "正在启动 dsh web…（已等待 " + (i / 2) + " 秒）", -1, null);
                    }
                    if (HarnessProcess.probe()) {
                        restarts = 0;
                        String url = authenticatedUrl != null ? authenticatedUrl : HarnessPaths.URL;
                        broadcast(STATE_READY, url, 100, null);
                        updateNotification("DeepSeek Harness 已就绪", url);
                        return;
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
                Process p = process;
                boolean alive = p != null && p.isAlive();
                log("[probe] 端口等待超时：进程 alive=" + alive);
                broadcast(STATE_STOPPED, alive
                        ? "等待 dsh web 端口超时（进程仍在运行但未监听）"
                        : "等待 dsh web 端口超时（进程已退出）", -1, null);
            }
        }, "dsh-probe").start();
    }

    /** 进程结束后的收尾与重试。 */
    private void onProcessGone(Process p) {
        if (shuttingDown) {
            return;
        }
        int code = -1;
        try {
            code = p.exitValue();
        } catch (IllegalThreadStateException e) {
            try {
                p.waitFor();
                code = p.exitValue();
            } catch (InterruptedException ignored) {
                return;
            }
        }
        log("[exit] dsh 进程退出，code=" + code);

        if (restarts < MAX_RESTARTS) {
            restarts++;
            log("[exit] 自动重启 " + restarts + "/" + MAX_RESTARTS);
            broadcast(STATE_STARTING,
                    "服务已退出，正在自动重启（" + restarts + "/" + MAX_RESTARTS + "）…", -1, null);
            HarnessProcess.killStale(this);
            waitPortFree(16);
            if (!shuttingDown) {
                launch();
            }
            return;
        }
        broadcast(STATE_STOPPED, "dsh 进程已退出（code " + code + "）", -1, null);
        updateNotification("DeepSeek Harness 已停止", "退出码 " + code);
    }

    /** 等待端口释放，maxTicks × 500ms。子进程退出与 socket 回收之间有空档。 */
    private void waitPortFree(int maxTicks) {
        for (int i = 0; i < maxTicks && HarnessProcess.probe(); i++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void shutdownCurrent() {
        shuttingDown = true;
        HarnessProcess.stop(this, process);
        process = null;
    }

    /**
     * 从服务输出里捕获带 token 的访问地址。
     * 形如 {@code dsh web: http://127.0.0.1:3080/?token=XXXX}，后面可能还跟 "(LAN: …)"。
     * @param line 服务的一行输出。
     */
    private void captureAuthenticatedUrl(String line) {
        int at = line.indexOf("dsh web: http://");
        if (at < 0) {
            return;
        }
        String url = line.substring(at + "dsh web: ".length()).trim();
        int space = url.indexOf(' ');
        if (space > 0) {
            url = url.substring(0, space);
        }
        if (!url.isEmpty()) {
            authenticatedUrl = url;
        }
    }

    // ---------------- 日志 ----------------

    /**
     * 脱敏：dsh 启动时会打印带 token 的完整访问地址，原样落盘等于明文留存凭据。
     * 只作用于写日志这条路径 —— 界面与 captureAuthenticatedUrl 仍用原始行（点击要能直接用）。
     */
    private static String redact(String line) {
        if (line == null || line.indexOf("token=") < 0) {
            return line;
        }
        return line.replaceAll("(token=)[A-Za-z0-9_\\-]+", "$1<redacted>");
    }

    /** 追加一行到 filesDir/dsh.log；界面上的"日志"读的就是它。 */
    private void log(String line) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(getFilesDir(), "dsh.log"), true);
            out.write(redact(line).getBytes(StandardCharsets.UTF_8));
            out.write('\n');
        } catch (IOException ignored) {
            // 写日志失败不影响服务
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        }
    }

    // ---------------- 广播 / 通知 ----------------

    private void broadcast(String state, String message, int progress, String logLine) {
        Intent intent = new Intent(ACTION_STATE);
        intent.setPackage(getPackageName());
        if (state != null) {
            intent.putExtra(EXTRA_STATE, state);
        }
        if (message != null) {
            intent.putExtra(EXTRA_MESSAGE, message);
        }
        intent.putExtra(EXTRA_PROGRESS, progress);
        if (logLine != null) {
            intent.putExtra(EXTRA_LOG, logLine);
        }
        sendBroadcast(intent);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                // IMPORTANCE_DEFAULT 而非 LOW：Android 16 的 Live Updates 明确要求
                // 通道不能是 IMPORTANCE_MIN；小米的焦点通知同样不会在低优先级通道上生效。
                // 这条通道承载"Agent 正在干什么"，属于用户想一眼看到的状态。
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "DeepSeek Harness", NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("本地 dsh web 服务状态与 Agent 工作进度");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String title, String text) {
        return buildNotification(title, text, "", false);
    }

    /**
     * 构造一条通知。
     *
     * @param title 标题，同时作为大岛标题。
     * @param text 正文。
     * @param chip 状态栏芯片文案；空字符串则回退为 text。
     * @param withIsland 是否附带小米焦点通知参数。
     *        **前台服务通知必须传 false** —— 小米把带该参数的通知当作"焦点通知"，
     *        未获授权的应用会被系统**整条静默丢弃**；前台通知消失还会导致服务被回收。
     */
    private Notification buildNotification(String title, String text, String chip, boolean withIsland) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 100,
                new Intent(this, HarnessService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent restart = PendingIntent.getService(this, 101,
                new Intent(this, HarnessService.class).setAction(ACTION_RESTART),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "停止", stop).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_rotate, "重启", restart).build());

        // 这里**不要** setProgress(0, 0, true)。
        // 它是不确定进度条，会在通知栏渲染成一条永远转不完的加载条 ——
        // 而这条件通知是常驻的，用户会一直看到一个"正在进行"的假象，
        // 误以为后台在下载或处理什么。它也没能起到预期作用：
        // 上岛靠的是 miui.focus.param，与 setProgress 无关，所以这是纯多余的一行。
        //
        // 想表达"正在工作"请用 text 文案（"分析中…"），不要用进度条。

        Notification n = builder.build();

        if (withIsland) {
            // 小米超级岛 / 焦点通知：走 extras 里的 JSON，不需要任何新 SDK（compileSdk 34 可编译）。
            //
            // ⚠️ 实测结论（2026-09-30，HyperOS + Android 17）：
            // 带这个 key 的通知在**未获超级岛授权**的设备上会被系统**整条丢弃**，
            // 并不是"岛的参数被忽略、通知照常显示"。所以它只能挂在专用通知上，
            // 绝不能挂前台服务通知 —— 否则连通知栏都看不到，且前台通知消失会导致服务被回收。
            try {
                n.extras.putString("miui.focus.param", buildMiuiFocusParam(title, text, chip));
            } catch (Throwable ignored) {
                // 参数构造失败只影响岛的展示，不该让通知本身发不出去
            }
        }
        return n;
    }

    /**
     * 构造小米超级岛参数（澎湃 OS 的"灵动岛"）。
     *
     * 接入方式是纯数据：把 JSON 塞进 `notification.extras` 的 `miui.focus.param`，
     * 支持的设备会据此渲染成岛，不支持的设备当作普通通知 —— 所以可以无条件附带。
     *
     * 字段含义（据澎湃 OS 开发者文档）：
     *   · ticker       —— 状态栏焦点文案，OS2 起用于状态栏常驻显示
     *   · islandProperty 1=信息展示为主、2=操作为主
     *   · aodTitle     —— 息屏显示文案
     *
     * 已知限制：小米对焦点通知有应用白名单，未上白名单时系统可能直接忽略该参数，
     * 退化为普通通知。这是系统侧策略，应用无法绕过。
     *
     * @param title 大岛标题。
     * @param text 大岛正文。
     * @param chip 状态栏芯片文案；为空则回退到 text。
     * @return JSON 字符串。
     */
    private static String buildMiuiFocusParam(String title, String text, String chip) {
        JSONObject island = new JSONObject();
        try {
            island.put("islandProperty", 1);
            island.put("islandTimeout", 24 * 60 * 60);

            JSONObject paramIsland = new JSONObject();
            JSONObject big = new JSONObject();
            big.put("title", title);
            big.put("content", text);
            JSONObject small = new JSONObject();
            small.put("title", title);
            paramIsland.put("bigIslandArea", big);
            paramIsland.put("smallIslandArea", small);

            JSONObject v2 = new JSONObject();
            v2.put("param_island", paramIsland);
            v2.put("ticker", chip == null || chip.isEmpty() ? text : chip);
            v2.put("aodTitle", chip == null || chip.isEmpty() ? text : chip);

            JSONObject root = new JSONObject();
            root.put("param_v2", v2);
            return root.toString();
        } catch (JSONException e) {
            return "";
        }
    }

    /**
     * 更新前台服务通知（**不带**岛参数）。
     *
     * 名字不能叫 notify —— 那会与 Object.notify() 冲突。
     * 这条通知承载"服务在跑 + 当前状态"，是整个应用唯一的可见性保证，
     * 因此永远不带 miui.focus.param（理由见 NOTIFICATION_ID 的注释）。
     */
    private void updateNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(title, text, "", false));
        }
    }

    /**
     * 发布「状态 / 超级岛」通知。
     *
     * 与前台服务通知使用**不同的 ID**：带 `miui.focus.param` 的通知在未授权设备上
     * 会被系统整条丢弃，必须与"必须可见"的前台通知解耦，否则一损俱损。
     */
    private void updateIslandNotification(String title, String text, String chip) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(STATUS_NOTIFICATION_ID, buildNotification(title, text, chip, true));
        }
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        shuttingDown = true;
        HarnessProcess.stop(this, process);
        process = null;
        if (bridge != null) {
            bridge.stop();
            bridge = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 供界面按需触发重启。 */
    public static void restart(Context ctx) {
        Intent intent = new Intent(ctx, HarnessService.class).setAction(ACTION_RESTART);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }
}
