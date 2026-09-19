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
    private static final int NOTIFICATION_ID = 1;
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

    @Override
    public void onCreate() {
        super.onCreate();
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
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "DeepSeek Harness", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("本地 dsh web 服务状态");
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String title, String text) {
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
        return builder.build();
    }

    /** 更新前台通知。名字不能叫 notify —— 那会与 Object.notify() 冲突。 */
    private void updateNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(title, text));
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
