package com.dsh.harness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 本地控制桥：把手机里的 harness（Node 侧）与 Android 系统能力连起来。
 *
 * 两条主线：
 *   1) 自更新 —— 用新的 payload 包替换程序目录并热重启；也支持调起 APK 安装。
 *   2) 手机操控 —— 打开链接/应用、通知、剪贴板、震动、分享、设备与电量信息。
 *
 * 安全：只监听 127.0.0.1，每个请求必须带 X-DSH-Token。令牌随机生成后固定，
 * 通过环境变量交给 harness，模型只能从自己的进程环境里读到。
 */
public class ControlBridge {

    public static final int PORT = 3099;
    public static final String HEADER_TOKEN = "X-DSH-Token";
    private static final String CHANNEL_ID = "dsh-bridge";
    private static final int MAX_BODY = 8 * 1024 * 1024;

    private final Context ctx;
    private final String token;
    private volatile ServerSocket server;
    private volatile boolean running;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public ControlBridge(Context ctx, String token) {
        this.ctx = ctx.getApplicationContext();
        this.token = token;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "dsh-bridge").start();
    }

    public void stop() {
        running = false;
        ServerSocket s = server;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 关闭即可
            }
        }
        server = null;
    }

    private void acceptLoop() {
        try {
            ServerSocket s = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
            server = s;
            while (running) {
                final Socket client = s.accept();
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        serve(client);
                    }
                });
            }
        } catch (IOException e) {
            // 端口占用或 socket 关闭：桥不可用时 harness 侧会拿到连接错误，不影响主服务
        }
    }

    // ---------------- HTTP 骨架 ----------------

    private void serve(Socket client) {
        try {
            client.setSoTimeout(20000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            String path = parts[1];

            int contentLength = 0;
            String headerToken = null;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if ("Content-Length".equalsIgnoreCase(name)) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        contentLength = 0;
                    }
                } else if (HEADER_TOKEN.equalsIgnoreCase(name)) {
                    headerToken = value;
                }
            }

            if (!token.equals(headerToken)) {
                write(client, 401, error("缺少或错误的 " + HEADER_TOKEN));
                return;
            }

            byte[] body = new byte[0];
            if (contentLength > 0) {
                if (contentLength > MAX_BODY) {
                    write(client, 413, error("请求体过大"));
                    return;
                }
                body = readBody(in, contentLength);
            }

            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }

            String result = route(method, path, query, new String(body, StandardCharsets.UTF_8));
            if (result == null) {
                write(client, 404, error("未知端点: " + path));
            } else {
                write(client, 200, result);
            }
        } catch (Exception e) {
            try {
                write(client, 500, error(String.valueOf(e.getMessage())));
            } catch (IOException ignored) {
                // 连接已断
            }
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    private String route(String method, String path, String query, String body) {
        try {
            JSONObject req = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            if ("/".equals(path) || "/help".equals(path)) return help();
            if ("/status".equals(path)) return status();
            if ("/update/payload".equals(path)) return updatePayload(req);
            if ("/service/restart".equals(path)) return restartService();
            if ("/update/apk".equals(path)) return installApk(req);
            if ("/device/info".equals(path)) return deviceInfo();
            if ("/device/battery".equals(path)) return battery();
            if ("/device/apps".equals(path)) return listApps(query);
            if ("/device/open".equals(path)) return open(req);
            if ("/device/notify".equals(path)) return notifyUser(req);
            if ("/device/toast".equals(path)) return toast(req);
            if ("/device/clipboard".equals(path)) return "GET".equals(method) ? clipboardRead() : clipboardWrite(req);
            if ("/device/vibrate".equals(path)) return vibrate(req);
            if ("/device/share".equals(path)) return share(req);
            if ("/fs/list".equals(path)) return listDir(query);
        } catch (Exception e) {
            return error("处理失败: " + e.getMessage());
        }
        return null;
    }

    // ---------------- 自更新 ----------------

    private String updatePayload(JSONObject req) throws Exception {
        String zipPath = req.optString("zip", "");
        if (zipPath.isEmpty()) {
            return error("缺少 zip 字段（设备上的 payload zip 路径）");
        }
        File zip = new File(zipPath);
        if (!zip.isFile()) {
            return error("找不到文件: " + zipPath);
        }

        // 载荷完整性校验：调用方给出 sha256 时严格比对，不匹配一律拒绝解包。
        // 本通道原先只验 token —— token 一旦泄露或被误用，任意 zip 都能覆盖程序目录，
        // 且替换后无法察觉。无论是否校验，实际哈希都会回传，便于调用方核对。
        String actualSha = sha256Of(zip);
        String expectedSha = req.optString("sha256", "");
        if (!expectedSha.isEmpty() && !expectedSha.equalsIgnoreCase(actualSha)) {
            JSONObject bad = new JSONObject();
            bad.put("ok", false);
            bad.put("error", "payload 哈希不匹配，已拒绝替换");
            bad.put("expected", expectedSha.toLowerCase(Locale.US));
            bad.put("actual", actualSha);
            return bad.toString();
        }

        File target = HarnessPaths.payloadDir(ctx);
        if (!target.exists() && !target.mkdirs() && !target.exists()) {
            return error("无法创建目标目录");
        }
        int files = unzipInto(zip, target);
        JSONObject ok = new JSONObject();
        ok.put("ok", true);
        ok.put("files", files);
        ok.put("target", target.getAbsolutePath());
        ok.put("sha256", actualSha);
        ok.put("note", "payload 已替换；调用 POST /service/restart 生效");
        return ok.toString();
    }

    /** 计算文件的 SHA-256，返回小写十六进制。 */
    private static String sha256Of(File file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(file);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private int unzipInto(File zip, File destRoot) throws IOException {
        int count = 0;
        ZipInputStream zin = null;
        FileInputStream fin = null;
        try {
            fin = new FileInputStream(zip);
            zin = new ZipInputStream(fin);
            String destPath = destRoot.getCanonicalPath();
            ZipEntry entry;
            byte[] buf = new byte[64 * 1024];
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                File out = new File(destRoot, name);
                // 防目录穿越
                if (!out.getCanonicalPath().startsWith(destPath)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(out, false);
                    int n;
                    while ((n = zin.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                } finally {
                    if (fos != null) {
                        try {
                            fos.close();
                        } catch (IOException ignored) {
                            // 忽略
                        }
                    }
                }
                count++;
            }
        } finally {
            if (zin != null) {
                try {
                    zin.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            } else if (fin != null) {
                try {
                    fin.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        }
        return count;
    }

    private String restartService() throws Exception {
        HarnessService.restart(ctx);
        JSONObject ok = new JSONObject();
        ok.put("ok", true);
        ok.put("note", "已请求重启 dsh web");
        return ok.toString();
    }

    private String installApk(JSONObject req) throws Exception {
        String path = req.optString("path", "");
        if (path.isEmpty()) {
            return error("缺少 path 字段（设备上的 .apk 路径）");
        }
        File apk = new File(path);
        if (!apk.isFile()) {
            return error("找不到文件: " + path);
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(ApkInstallProvider.uriFor(ctx, apk),
                "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.startActivity(intent);
        JSONObject ok = new JSONObject();
        ok.put("ok", true);
        ok.put("note", "已调起系统安装器");
        return ok.toString();
    }

    // ---------------- 手机操控 ----------------

    private String deviceInfo() throws Exception {
        JSONObject o = new JSONObject();
        o.put("manufacturer", Build.MANUFACTURER);
        o.put("model", Build.MODEL);
        o.put("android", Build.VERSION.RELEASE);
        o.put("sdk", Build.VERSION.SDK_INT);
        o.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?");
        o.put("workspace", HarnessPaths.workspace(ctx).getAbsolutePath());
        o.put("publicStorageGranted", HarnessPaths.hasPublicStorage(ctx));
        o.put("bridge", "http://127.0.0.1:" + PORT + "/");
        return o.toString();
    }

    private String battery() throws Exception {
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        JSONObject o = new JSONObject();
        if (bm != null) {
            o.put("level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            o.put("charging", bm.isCharging());
        }
        return o.toString();
    }

    /**
     * 列出已安装的、带桌面入口的应用。
     *
     * 存在的理由：/device/open 需要**包名**，而包名无法从应用名推断 ——
     * 模型知道「微信」却不知道 com.tencent.mm。没有这个端点它只能靠猜，
     * 猜错的表现是「未找到应用」，看起来像权限问题，实则是能力缺失。
     *
     * @param query 原始 query string，可含 q=<关键词>（按应用名或包名做子串匹配）
     * @return {"count":n,"apps":[{"package":"…","label":"…"}]}
     */
    private String listApps(String query) throws Exception {
        String q = "";
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && "q".equals(kv.substring(0, eq))) {
                q = Uri.decode(kv.substring(eq + 1)).trim().toLowerCase(Locale.ROOT);
            }
        }
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        // 本应用 targetSdk 28，不受 Android 11 的包可见性限制，可直接枚举；
        // 即便将来提升 targetSdk，Manifest 的 <queries> 也已声明了同样的 intent。
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        JSONArray arr = new JSONArray();
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            CharSequence labelCs = ri.loadLabel(pm);
            String label = labelCs == null ? "" : labelCs.toString();
            if (!q.isEmpty()
                    && !label.toLowerCase(Locale.ROOT).contains(q)
                    && !pkg.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("package", pkg);
            o.put("label", label);
            arr.put(o);
        }
        JSONObject out = new JSONObject();
        out.put("count", arr.length());
        out.put("apps", arr);
        return out.toString();
    }

    private String open(JSONObject req) throws Exception {
        String url = req.optString("url", "");
        String pkg = req.optString("package", "");
        Intent intent;
        if (!pkg.isEmpty()) {
            intent = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) {
                return error("未找到应用: " + pkg);
            }
        } else if (!url.isEmpty()) {
            intent = new Intent(Intent.ACTION_VIEW).setData(Uri.parse(url));
        } else {
            return error("需要 url 或 package 之一");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
        JSONObject ok = new JSONObject();
        ok.put("ok", true);
        return ok.toString();
    }

    private String notifyUser(JSONObject req) throws Exception {
        String title = req.optString("title", "DeepSeek Harness");
        String text = req.optString("text", "");
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "Harness 消息", NotificationManager.IMPORTANCE_DEFAULT));
            }
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return error("通知服务不可用");
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true);
        nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), b.build());
        JSONObject ok = new JSONObject();
        ok.put("ok", true);
        return ok.toString();
    }

    private String toast(final JSONObject req) throws Exception {
        final String text = req.optString("text", "");
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
            }
        });
        JSONObject ok = new JSONObject();
        ok.put("ok", true);
        return ok.toString();
    }

    private String clipboardRead() throws Exception {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        JSONObject o = new JSONObject();
        String text = "";
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence t = clip.getItemAt(0).coerceToText(ctx);
                text = t == null ? "" : t.toString();
            }
        }
        o.put("text", text);
        return o.toString();
    }

    private String clipboardWrite(JSONObject req) throws Exception {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return error("剪贴板不可用");
        }
        cm.setPrimaryClip(ClipData.newPlainText("dsh", req.optString("text", "")));
        JSONObject ok = new JSONObject();
        ok.put("ok", true);
        return ok.toString();
    }

    private String vibrate(JSONObject req) throws Exception {
        long ms = req.optLong("ms", 240);
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) {
                vm.getDefaultVibrator().vibrate(
                        VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } else {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(ms);
                }
            }
        }
        JSONObject ok = new JSONObject();
        ok.put("ok", true);
        return ok.toString();
    }

    private String share(JSONObject req) throws Exception {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, req.optString("text", ""));
        Intent chooser = Intent.createChooser(send, req.optString("title", "分享"));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(chooser);
        JSONObject ok = new JSONObject();
        ok.put("ok", true);
        return ok.toString();
    }

    private String listDir(String query) throws Exception {
        String p = "";
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && "path".equals(kv.substring(0, eq))) {
                p = Uri.decode(kv.substring(eq + 1));
            }
        }
        File dir = p.isEmpty() ? HarnessPaths.workspace(ctx) : new File(p);
        JSONArray arr = new JSONArray();
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File f : kids) {
                JSONObject o = new JSONObject();
                o.put("name", f.getName());
                o.put("dir", f.isDirectory());
                o.put("size", f.isFile() ? f.length() : 0);
                arr.put(o);
            }
        }
        JSONObject o = new JSONObject();
        o.put("path", dir.getAbsolutePath());
        o.put("entries", arr);
        return o.toString();
    }

    // ---------------- 自描述 ----------------

    private String help() throws Exception {
        JSONObject o = new JSONObject();
        o.put("service", "DeepSeek Harness Android Bridge");
        o.put("base", "http://127.0.0.1:" + PORT + "/");
        o.put("auth", "所有请求需带 header " + HEADER_TOKEN
                + ": <token>（token 在环境变量 DSH_ANDROID_BRIDGE_TOKEN 中）");
        o.put("update", new JSONArray()
                .put("POST /update/payload {\"zip\":\"…\",\"sha256\":\"…\"} —— 替换 harness 程序目录；"
                        + "sha256 可选，给出则严格校验（不匹配拒绝替换），响应始终回传实际哈希")
                .put("POST /service/restart —— 重启 dsh web")
                .put("POST /update/apk {\"path\":\"<设备上的apk路径>\"} —— 调起系统安装器"));
        o.put("device", new JSONArray()
                .put("GET  /device/info —— 机型/系统/工作区")
                .put("GET  /device/battery —— 电量")
                .put("GET  /device/apps?q=<关键词> —— 列出已安装应用的包名（open 之前先用它查）")
                .put("POST /device/open {\"url\":\"…\"} 或 {\"package\":\"…\"}")
                .put("POST /device/notify {\"title\":\"…\",\"text\":\"…\"}")
                .put("POST /device/toast {\"text\":\"…\"}")
                .put("GET  /device/clipboard · POST /device/clipboard {\"text\":\"…\"}")
                .put("POST /device/vibrate {\"ms\":240}")
                .put("POST /device/share {\"text\":\"…\"}"));
        o.put("fs", new JSONArray().put("GET /fs/list?path=<dir>"));
        return o.toString();
    }

    private String status() throws Exception {
        JSONObject o = new JSONObject();
        o.put("runtimeReady", HarnessPaths.isExtracted(ctx));
        o.put("harnessUrl", HarnessPaths.URL);
        o.put("payloadDir", HarnessPaths.payloadDir(ctx).getAbsolutePath());
        o.put("workspace", HarnessPaths.workspace(ctx).getAbsolutePath());
        o.put("publicStorageGranted", HarnessPaths.hasPublicStorage(ctx));
        o.put("bridge", "http://127.0.0.1:" + PORT + "/");
        return o.toString();
    }

    // ---------------- 小工具 ----------------

    private static String error(String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", message);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    private static byte[] readBody(BufferedReader in, int n) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(n);
        char[] buf = new char[4096];
        int remaining = n;
        while (remaining > 0) {
            int read = in.read(buf, 0, Math.min(buf.length, remaining));
            if (read < 0) {
                break;
            }
            byte[] chunk = new String(buf, 0, read).getBytes(StandardCharsets.UTF_8);
            bos.write(chunk, 0, chunk.length);
            remaining -= read;
        }
        return bos.toByteArray();
    }

    private static void write(Socket client, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
                .append("Content-Type: application/json; charset=utf-8\r\n")
                .append("Content-Length: ").append(bytes.length).append("\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Connection: close\r\n\r\n");
        OutputStream out = client.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            default: return "Internal Server Error";
        }
    }
}
