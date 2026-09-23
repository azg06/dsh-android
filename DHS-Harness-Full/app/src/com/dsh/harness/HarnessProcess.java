package com.dsh.harness;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * dsh web 子进程的启动、探测与回收。
 *
 * 每一个启动细节都是实测结论，改动前请读对应注释：
 *
 *   · {@code --no-open}：dsh web 默认会调用系统默认浏览器打开 UI。Android 上不存在
 *     这条命令，该步骤失败后进程会直接退出（观察到 exit code 0，即"优雅退出而非崩溃"）。
 *
 *   · {@code --expose-internals}：cordis-plugin-hmr 的构造函数里有硬检查
 *     {@code if (!ctx.loader.internal) throw ...}，而 0.1.5 的 web profile 默认挂载 HMR。
 *     缺这个参数会让整棵插件树装载失败、进程以 code 1 退出。
 *
 *   · {@code script} 包装：Node 的 stdout 在管道下是块缓冲，进程卡住时缓冲区永不 flush，
 *     日志里只剩我们自己的 [boot] 行，完全看不到它停在哪一步 —— 这是排查困难的根源。
 *     套一层伪终端让 stdout 变成行缓冲，输出实时可见。代价是启动链变成
 *     Java → script → node，因此回收必须按特征清理（见 {@link #killStale}）。
 *
 *   · 环境变量：HOME 必须指向私有目录（FUSE 不支持 symlink）；工作区靠进程 cwd 决定。
 */
public final class HarnessProcess {

    private HarnessProcess() {
    }

    /** 用于 pkill 匹配的入口特征串；越独特越安全。 */
    private static final String ENTRY_MARKER = "deepseek-ai/dsh/lib/bin.js";

    /**
     * 把 assets/AGENTS.md 投放到工作区根目录。
     *
     * dsh 的 dsh-agent-instructions 从 cwd 向上找项目根（标记是 .git），
     * 一路到文件系统根都没找到就退回 cwd —— 所以放在工作区根即可被读到，
     * 然后作为 workspace instructions 注入模型上下文。
     *
     * 仅在内容与应用内置版本不一致时覆盖：这份说明必须以应用为准，
     * 用户改坏了会让模型按错误的端点用法去调控制桥。
     * 投放失败不阻断启动（最坏情况只是模型少一份能力说明）。
     *
     * @param ctx 上下文，用于读 assets。
     * @param workspace 公共工作区目录。
     */
    private static void deployAgentInstructions(Context ctx, File workspace) {
        final String name = "AGENTS.md";
        try {
            File dst = new File(workspace, name);
            byte[] want = readAllBytes(ctx.getAssets().open(name));
            if (dst.isFile()) {
                byte[] have = readAllBytes(new FileInputStream(dst));
                if (java.util.Arrays.equals(have, want)) {
                    return;
                }
            }
            try (OutputStream out = new FileOutputStream(dst)) {
                out.write(want);
            }
        } catch (Exception ignored) {
            // 静默：说明文件缺失只影响模型对控制桥的认知，不该阻断服务启动
        }
    }

    /** 读到 EOF。用于几 KB 的说明文件，不需要缓冲调优。 */
    private static byte[] readAllBytes(InputStream in) throws IOException {
        try (InputStream src = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = src.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        }
    }

    /**
     * 启动 dsh web。
     * @param ctx 上下文。
     * @return 已启动的进程（其 stdout 已合并 stderr）。
     * @throws IOException 启动失败。
     */
    public static Process start(Context ctx) throws IOException {
        File home = HarnessPaths.home(ctx);
        File workspace = HarnessPaths.workspace(ctx);
        File tmp = HarnessPaths.tempDir(ctx);
        File prefix = HarnessPaths.prefix(ctx);
        mkdirs(home);
        mkdirs(tmp);

        String node = HarnessPaths.nodeBinary(ctx).getAbsolutePath();
        String entry = HarnessPaths.entry(ctx).getAbsolutePath();
        // 入口路径带引号：payload 路径可能含空格（应用目录名不保证），且这里要过一层 shell
        String app = node + " --expose-internals \"" + entry + "\""
                + " web --no-open --host 127.0.0.1 --port " + HarnessPaths.PORT;

        List<String> cmd = new ArrayList<>();
        File script = HarnessPaths.scriptBinary(ctx);
        if (script.isFile()) {
            cmd.add(script.getAbsolutePath());
            cmd.add("-q");
            cmd.add("-e");
            cmd.add("-c");
            cmd.add(app);
            cmd.add("/dev/null");
        } else {
            // 没有 script 就直连；日志可见性会变差，但功能不受影响
            cmd.add(node);
            cmd.add("--expose-internals");
            cmd.add(entry);
            cmd.add("web");
            cmd.add("--no-open");
            cmd.add("--host");
            cmd.add("127.0.0.1");
            cmd.add("--port");
            cmd.add(String.valueOf(HarnessPaths.PORT));
        }

        // 把能力说明投放进工作区。
        // dsh 会读工作区根目录的 AGENTS.md（候选还有 CLAUDE.md），内容作为
        // workspace instructions 进入模型上下文 —— 这是模型**唯一**能知道
        // "这座控制桥存在、怎么调用"的途径。没有它，桥的所有端点都存在但无人调用：
        // 能力齐全、可发现性为零，表现出来就是"模型不会控制手机"。
        deployAgentInstructions(ctx, workspace);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // cwd = 公共工作区：dsh 以 invoking directory 作为 workspace root，
        // 这样模型读写文件仍落在用户能在文件管理器里看到的地方。
        pb.directory(workspace);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        // HOME / DSH_HOME 必须在应用私有目录：公共存储是 FUSE，不支持 symlink，
        // profile 引导依赖 symlink —— 放公共存储上会直接卡死启动。
        env.put("HOME", home.getAbsolutePath());
        env.put("DSH_HOME", home.getAbsolutePath());
        // dsh 的"选择工作区目录"以 homedir() 为起点。HOME 在 Android 上必须指向私有目录，
        // 于是选择器被锁在私有区 —— 用户看不到公共目录，停在起点直接点"打开"还会把
        // 工作区设成私有 HOME 本身，产出永远拿不到。这个变量让起点改指公共工作区。
        env.put("DSH_PICKER_ROOT", workspace.getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("PREFIX", prefix.getAbsolutePath());
        env.put("PATH", prefix.getAbsolutePath() + "/bin:/system/bin:/system/xbin");
        env.put("LD_LIBRARY_PATH", prefix.getAbsolutePath() + "/lib");
        env.put("SHELL", prefix.getAbsolutePath() + "/bin/bash");
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C");
        env.put("SSL_CERT_FILE", prefix.getAbsolutePath() + "/etc/tls/cert.pem");
        env.put("NO_COLOR", "1");
        // 控制桥：让模型能通过本地 HTTP 做自更新与手机操控
        env.put("DSH_ANDROID_BRIDGE_URL", "http://127.0.0.1:" + ControlBridge.PORT + "/");
        env.put("DSH_ANDROID_BRIDGE_TOKEN", HarnessPaths.bridgeToken(ctx));
        return pb.start();
    }

    /**
     * 结束服务：先结束直接子进程，再按入口特征清理可能残留的 node。
     * 直接子进程是 script，真正的 node 在它下面，只 destroy 会留下孤儿占着端口。
     * @param ctx 上下文。
     * @param process 当前进程，可为 null。
     */
    public static void stop(Context ctx, Process process) {
        if (process != null) {
            try {
                process.destroy();
            } catch (Exception ignored) {
                // 进程可能已退出
            }
        }
        killStale(ctx);
    }

    /**
     * 杀掉所有跑着本 payload 入口的 node 进程。
     * 按入口路径匹配而非进程名，避免误伤设备上其它 node。
     * @param ctx 上下文。
     */
    public static void killStale(Context ctx) {
        File pkill = HarnessPaths.pkillBinary(ctx);
        if (!pkill.isFile()) {
            return;
        }
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    pkill.getAbsolutePath(), "-9", "-f", ENTRY_MARKER);
            pb.redirectErrorStream(true);
            p = pb.start();
            p.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 清理失败不致命；若仍有残留，下次启动会以 EADDRINUSE 明确暴露
        } finally {
            if (p != null && p.isAlive()) {
                p.destroy();
            }
        }
    }

    /**
     * 服务是否已在监听。
     *
     * 判据是"端口能给出 HTTP 响应"，而不是"返回 200"。dsh 的本地服务要求 token，
     * 无 token 请求返回的是 401 —— 只认 200 会导致永远探测不到就绪，界面一直停在
     * "正在启动"，即使服务其实早就跑起来了。
     * @return true 表示服务已监听（含 401/403 这类需要鉴权的正常响应）。
     */
    public static boolean probe() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(HarnessPaths.URL).openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code == 200 || code == 401 || code == 403;
        } catch (Exception e) {
            // 连接被拒/超时：服务还没起来
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IOException("无法创建目录: " + dir.getAbsolutePath());
        }
    }
}
