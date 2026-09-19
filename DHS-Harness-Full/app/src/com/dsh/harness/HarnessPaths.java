package com.dsh.harness;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 路径与配置的唯一来源。
 *
 * 这个类存在的唯一目的是：所有关于"东西放在哪"和"用什么参数启动"的知识都集中在一处。
 * 前几版的问题是同类信息散落在多个文件、被反复就地打补丁，最后没人说得清到底生效的是哪一版。
 *
 * 关键约束（都是实测踩出来的，改动前请先读这里的说明）：
 *
 *  1. HOME 与 DSH_HOME 必须位于应用私有目录。公共存储 /storage/emulated/0 是 FUSE，
 *     不支持符号链接，而 dsh 的 profile 引导依赖 symlink —— 放在公共存储上会直接卡死启动。
 *
 *  2. 工作区（用户在文件管理器里看到的那份）仍然是公共 DHS 目录。dsh 以进程的
 *     invoking directory 作为 workspace root，而进程 cwd 设为公共 DHS，所以两者互不影响。
 *
 *  3. 入口路径是 npm 布局：<payload>/node_modules/@deepseek-ai/dsh/lib/bin.js。
 *     早期 pnpm 部署布局是 <payload>/lib/bin.js，这里两种都认。
 */
public final class HarnessPaths {

    private HarnessPaths() {
    }

    /** 本地 Web UI 端口。 */
    public static final int PORT = 3080;
    public static final String URL = "http://127.0.0.1:" + PORT + "/";

    private static final String MARKER_RUNTIME = ".runtime_ready";
    private static final String MARKER_PAYLOAD = ".payload_ready";

    private static final String PREFS = "dsh_prefs";
    private static final String KEY_BRIDGE_TOKEN = "bridge_token";

    // ---------------- 目录布局 ----------------

    public static File filesDir(Context ctx) {
        return ctx.getFilesDir();
    }

    /** Termux 运行时的前缀目录（bin/lib/etc 都在这下面）。 */
    public static File prefix(Context ctx) {
        return new File(filesDir(ctx), "files/usr");
    }

    public static File nodeBinary(Context ctx) {
        return new File(prefix(ctx), "bin/node");
    }

    /** 伪终端包装器；用来让 Node 的 stdout 变成行缓冲（见 HarnessProcess 的说明）。 */
    public static File scriptBinary(Context ctx) {
        return new File(prefix(ctx), "bin/script");
    }

    /** 用于按入口路径精确清理残留 node 进程。 */
    public static File pkillBinary(Context ctx) {
        return new File(prefix(ctx), "bin/pkill");
    }

    /** 解包出来的 harness payload 根目录（等价于旧版的 dsh 目录）。 */
    public static File payloadDir(Context ctx) {
        return new File(filesDir(ctx), "payload");
    }

    /**
     * DSH_HOME 与 HOME 共用同一私有目录。profile、凭据、会话都落在这里。
     * 必须在私有目录 —— 见类注释第 1 条。
     */
    public static File home(Context ctx) {
        return new File(filesDir(ctx), "home");
    }

    public static File tempDir(Context ctx) {
        return new File(ctx.getCacheDir(), "tmp");
    }

    /** 命令行入口；优先 npm 布局，回落 pnpm 部署布局。 */
    public static File entry(Context ctx) {
        File npm = new File(payloadDir(ctx), "node_modules/@deepseek-ai/dsh/lib/bin.js");
        if (npm.isFile()) {
            return npm;
        }
        return new File(payloadDir(ctx), "lib/bin.js");
    }

    // ---------------- 工作区（公共存储） ----------------

    public static boolean hasPublicStorage(Context ctx) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 用户在文件管理器里管理的那份工作区根目录。 */
    public static File workspace(Context ctx) {
        if (hasPublicStorage(ctx)) {
            File pub = new File(Environment.getExternalStorageDirectory(), "DHS");
            if (pub.mkdirs() || pub.isDirectory()) {
                return pub;
            }
        }
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            base = ctx.getFilesDir();
        }
        File fb = new File(base, "DHS");
        fb.mkdirs();
        return fb;
    }

    public static void requestStoragePermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
                return;
            } catch (Exception ignored) {
                // 部分 ROM 没有这个入口，退到全局页
            }
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
                // 都没有就只能靠用户自己去设置了
            }
        } else {
            activity.requestPermissions(new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            }, 1001);
        }
    }

    // ---------------- 控制桥令牌 ----------------

    /** 控制桥令牌：首次生成后固定（稳定比每次轮换更重要，脚本/笔记里会引用它）。 */
    public static String bridgeToken(Context ctx) {
        android.content.SharedPreferences prefs =
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_BRIDGE_TOKEN, null);
        if (token == null || token.isEmpty()) {
            token = UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString(KEY_BRIDGE_TOKEN, token).apply();
        }
        return token;
    }

    // ---------------- 首次解包 ----------------

    public interface Progress {
        void onProgress(int percent, String message);
    }

    public static boolean isExtracted(Context ctx) {
        return new File(filesDir(ctx), MARKER_RUNTIME).exists()
                && new File(filesDir(ctx), MARKER_PAYLOAD).exists()
                && nodeBinary(ctx).isFile()
                && entry(ctx).isFile();
    }

    /**
     * 在 HOME 下暴露一个指向公共工作区的符号链接。
     *
     * 为什么需要：dsh 的"选择工作区目录"对话框以 HOME 为根，用户只能看到
     * profiles / storages 这些私有目录 —— 选不到 /storage/emulated/0/DHS。
     * 于是 Agent 的产出全部落在私有区，用户在文件管理器里既看不到也删不掉。
     *
     * 私有目录在 ext4 上支持符号链接（禁的是硬链接），所以这里建一个软链作为桥梁：
     * 用户在 dsh 里能选中它，实际读写的仍是公共 DHS。
     *
     * @param ctx 上下文。
     */
    public static void ensureWorkspaceLink(Context ctx) {
        File home = home(ctx);
        if (!home.isDirectory() && !home.mkdirs()) {
            return;
        }
        File target = workspace(ctx);
        File link = new File(home, "DHS");
        try {
            if (link.exists()) {
                // 已存在且指向正确就不动；指向别处则重建
                if (link.getCanonicalPath().equals(target.getCanonicalPath())) {
                    return;
                }
                if (link.isDirectory() && !isSymlink(link)) {
                    // 是真实目录：不动用户数据，避免误删
                    return;
                }
                if (!link.delete()) {
                    return;
                }
            }
            Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
        } catch (Exception ignored) {
            // 建链失败不致命：用户仍可通过绝对路径写入公共目录
        }
    }

    private static boolean isSymlink(File file) {
        try {
            File parent = file.getParentFile();
            if (parent == null) {
                return false;
            }
            return !file.getCanonicalPath().equals(
                    new File(parent.getCanonicalFile(), file.getName()).getAbsolutePath());
        } catch (IOException e) {
            return false;
        }
    }

    /** 把 APK 内的 runtime.zip 与 payload.zip 解包到私有目录（幂等）。 */
    public static void ensureExtracted(Context ctx, Progress progress) throws IOException {
        File base = filesDir(ctx);
        if (!base.exists() && !base.mkdirs()) {
            throw new IOException("无法创建应用数据目录");
        }
        if (isExtracted(ctx)) {
            if (progress != null) {
                progress.onProgress(100, "运行时就绪");
            }
            return;
        }
        extract(ctx, "runtime.zip", base, progress, 0, 55, true);
        // payload 同样要补可执行位：npm 包会随包分发原生二进制（@vscode/ripgrep 的 bin/rg、
        // 各平台的 .node 加载器），而 zip 在 Windows 上打包时不带 Unix mode，解出来一律 600，
        // spawn 直接 EACCES —— 表现为 glob/grep "ripgrep provider failure"。
        // 具体补哪些由 isExecutable() 的白名单决定，这里只是允许它执行。
        extract(ctx, "payload.zip", payloadDir(ctx), progress, 55, 99, true);
        writeMarker(base, MARKER_RUNTIME);
        writeMarker(base, MARKER_PAYLOAD);
        if (progress != null) {
            progress.onProgress(100, "运行时就绪");
        }
    }

    private static void writeMarker(File base, String name) throws IOException {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(base, name), false);
            out.write(1);
        } finally {
            close(out);
        }
    }

    private static void extract(Context ctx, String assetName, File targetRoot,
                                Progress progress, int from, int to, boolean chmodBin)
            throws IOException {
        if (progress != null) {
            progress.onProgress(from, "正在解包 " + assetName + " …");
        }
        File zipFile = new File(ctx.getCacheDir(), assetName);
        InputStream in = ctx.getAssets().open(assetName);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(zipFile, false);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            close(in);
            close(out);
        }

        ZipFile zip = null;
        try {
            zip = new ZipFile(zipFile);
            long total = 0;
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                total += Math.max(0, e.nextElement().getSize());
            }
            long done = 0;
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                // Windows 侧生成的 zip 可能用反斜杠作分隔符，Android 上必须归一化
                String name = entry.getName().replace('\\', '/');
                File target = new File(targetRoot, name);
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                    throw new IOException("无法创建目录: " + parent.getAbsolutePath());
                }
                InputStream zin = null;
                FileOutputStream zout = null;
                try {
                    zin = zip.getInputStream(entry);
                    zout = new FileOutputStream(target, false);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = zin.read(buf)) != -1) {
                        zout.write(buf, 0, n);
                    }
                    zout.flush();
                } finally {
                    close(zin);
                    close(zout);
                }
                if (chmodBin && isExecutable(name)) {
                    makeExecutable(target);
                }
                done += Math.max(0, entry.getSize());
                if (progress != null && total > 0) {
                    int pct = from + (int) ((to - from) * done / total);
                    if (done % (16 * 1024 * 1024) < 64 * 1024) {
                        progress.onProgress(pct, "正在解包 " + assetName + " …");
                    }
                }
            }
        } finally {
            close(zip);
        }
        if (!zipFile.delete()) {
            // 删除失败不影响运行
        }
    }

    private static boolean isExecutable(String path) {
        String p = path.replace('\\', '/');
        if (p.contains("/usr/bin/") || p.contains("/usr/libexec/")) {
            return true;
        }
        // npm 包也会随包分发原生二进制（如 @vscode/ripgrep 的 bin/rg、各平台的 .node）。
        // 只认 usr/bin 的旧白名单漏了它们：zip 解包不带可执行位，spawn 时直接 EACCES，
        // 表现为 glob/grep 这类工具"启动失败"。
        //
        // 注意 zip 条目路径是相对路径（node_modules/... ，开头没有斜杠），
        // 所以这里不能用 "/node_modules/" 去匹配 —— 那样永远不成立。
        boolean inNodeModules = p.startsWith("node_modules/") || p.contains("/node_modules/");
        boolean inBin = p.startsWith("bin/") || p.contains("/bin/");
        return inNodeModules && inBin;
    }

    private static void makeExecutable(File file) {
        try {
            Os.chmod(file.getAbsolutePath(), 0755);
        } catch (ErrnoException e) {
            file.setExecutable(true, false);
            file.setReadable(true, false);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
