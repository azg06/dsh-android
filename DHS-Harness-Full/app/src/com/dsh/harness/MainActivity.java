package com.dsh.harness;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 应用外壳：服务就绪后只留一个全屏 WebView。
 *
 * 设计取舍：原生界面只在"还没就绪"和"出错"时出现。就绪后它整块隐藏 ——
 * 常驻的状态条会一直占着屏幕顶部，既挤掉内容又让人分不清这是 App 还是网页。
 * 出错时才把日志入口露出来，那是用户唯一需要操作原生界面的时刻。
 */
public class MainActivity extends Activity {

    /**
     * 供前端调用的下载落盘桥。
     *
     * 官方的"导出日志"走的是 blob: URL，WebView 的 DownloadListener 收不到这类下载，
     * 必须由页面侧读出内容再交回原生落盘。落到公共 Download 目录，用户能用文件管理器直接找到。
     */
    public class DownloadBridge {
        /** 单次写入上限，防止超大导出把内存吃穿。 */
        private static final int MAX_BYTES = 64 * 1024 * 1024;

        /**
         * 保存文件到公共 Download 目录。
         * @param filename 建议的文件名（会做安全清洗）。
         * @param base64Data 文件内容的 base64（不含 data: 前缀）。
         * @return 成功返回绝对路径，失败返回以 "ERR:" 开头的说明。
         */
        @android.webkit.JavascriptInterface
        public String save(String filename, String base64Data) {
            try {
                byte[] data = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
                if (data.length > MAX_BYTES) {
                    return "ERR:文件过大（" + data.length + " 字节）";
                }
                File dir = new File(android.os.Environment.getExternalStorageDirectory(), "Download");
                if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
                    return "ERR:无法创建 Download 目录";
                }
                // 清洗文件名：去掉路径分隔符，避免跳出目标目录
                String safe = (filename == null || filename.isEmpty() ? "dsh-export" : filename)
                        .replaceAll("[/\\\\:*?\"<>|]", "_");
                File out = new File(dir, safe);
                java.io.FileOutputStream fos = null;
                try {
                    fos = new java.io.FileOutputStream(out, false);
                    fos.write(data);
                    fos.flush();
                } finally {
                    if (fos != null) {
                        try {
                            fos.close();
                        } catch (java.io.IOException ignored) {
                            // 忽略
                        }
                    }
                }
                final String saved = out.getName();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this,
                                "已保存到 Download/" + saved, Toast.LENGTH_LONG).show();
                    }
                });
                return out.getAbsolutePath();
            } catch (Exception e) {
                return "ERR:" + e.getMessage();
            }
        }
    }

    /** 外壳层兜底注入：正常应由前端产物自带，这里只在它没生效时补一次。 */
    private static final String SHELL_BOOTSTRAP =
            "(function(){if(window.__dshMobileShell)return;"
            + "if(!document.getElementById('dsh-m-shell-css')){"
            + "var l=document.createElement('link');l.id='dsh-m-shell-css';"
            + "l.rel='stylesheet';l.href='/dsh-mobile.css';document.head.appendChild(l);}"
            + "var j=document.createElement('script');j.src='/dsh-mobile.js';"
            + "document.head.appendChild(j);})();";

    private WebView webView;
    private LinearLayout statusPanel;
    private TextView tvTitle;
    private TextView tvDetail;
    private TextView tvLog;
    private Button btnPermission;
    private boolean ready;
    private boolean lastStorageOk;
    /** 品牌强调色，与 Web UI 主色一致。 */
    private static final int ACCENT = 0xFF4D6BFE;
    /** 启动进度环。 */
    private ProgressBar spinner;

    private int loadAttempts;
    /** 文件选择器回调：在选择返回前必须持有引用，否则 WebView 会一直等待。 */
    private ValueCallback<Uri[]> pendingFileCallback;
    private static final int REQ_FILE_CHOOSER = 2001;
    private final Runnable loadUiRetry = new Runnable() {
        @Override
        public void run() {
            loadUi();
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!HarnessService.ACTION_STATE.equals(intent.getAction())) {
                return;
            }
            String state = intent.getStringExtra(HarnessService.EXTRA_STATE);
            String message = intent.getStringExtra(HarnessService.EXTRA_MESSAGE);
            String logLine = intent.getStringExtra(HarnessService.EXTRA_LOG);

            if (logLine != null && !logLine.isEmpty()) {
                appendLog(logLine);
            }
            if (message != null && !message.isEmpty()) {
                tvDetail.setText(message);
            }
            if (HarnessService.STATE_READY.equals(state)) {
                ready = true;
                statusPanel.setVisibility(View.GONE);
                loadUi();
            } else if (HarnessService.STATE_STOPPED.equals(state)) {
                ready = false;
                statusPanel.setVisibility(View.VISIBLE);
                tvDetail.setText(message != null && !message.isEmpty()
                        ? "服务未运行 · " + message
                        : "服务未运行");
            } else if (state != null) {
                ready = false;
                statusPanel.setVisibility(View.VISIBLE);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        configureWebView();
        lastStorageOk = HarnessPaths.hasPublicStorage(this);
        updatePermissionButton();
        promptStorageIfNeeded();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1002);
        }

        IntentFilter filter = new IntentFilter(HarnessService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }

        Intent start = new Intent(this, HarnessService.class).setAction(HarnessService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(start);
        } else {
            startService(start);
        }
        // 标题固定为品牌名，状态一律走副标题 —— 避免标题在"正在准备/服务未运行"之间跳变
        tvTitle.setText("DeepSeek Harness");
        tvDetail.setText(HarnessPaths.isExtracted(this) ? "正在启动…" : "首次启动，正在解包运行时…");
    }

    // ---------------- 界面 ----------------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#121316"));

        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 启动要等十秒左右，这个界面不可避免，那就把它做克制一点：
        // 整体垂直居中、一个细进度环给出"在动"的反馈、操作项降级成文字按钮。
        // 之前标题左对齐而内容居中、两个系统默认样式的浅色按钮在深色底上非常跳。
        statusPanel = new LinearLayout(this);
        statusPanel.setOrientation(LinearLayout.VERTICAL);
        statusPanel.setGravity(Gravity.CENTER);
        statusPanel.setBackgroundColor(Color.parseColor("#121316"));
        int pad = dp(32);
        statusPanel.setPadding(pad, pad, pad, pad);

        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        spinner.setLayoutParams(spinnerLp);
        statusPanel.addView(spinner);

        tvTitle = new TextView(this);
        tvTitle.setTextColor(Color.parseColor("#EDEEF0"));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvTitle.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(22);
        tvTitle.setLayoutParams(titleLp);
        statusPanel.addView(tvTitle);

        tvDetail = new TextView(this);
        tvDetail.setTextColor(Color.parseColor("#8A9099"));
        tvDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = dp(8);
        tvDetail.setLayoutParams(detailLp);
        statusPanel.addView(tvDetail);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsLp.topMargin = dp(18);
        buttons.setLayoutParams(buttonsLp);
        buttons.addView(button("重启服务", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tvDetail.setText("正在重启…");
                HarnessService.restart(MainActivity.this);
            }
        }));
        buttons.addView(button("查看日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLog();
            }
        }));
        btnPermission = button("授予文件权限", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HarnessPaths.requestStoragePermission(MainActivity.this);
            }
        });
        buttons.addView(btnPermission);
        statusPanel.addView(buttons);

        tvLog = new TextView(this);
        tvLog.setTextColor(Color.parseColor("#7F858C"));
        tvLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvLog.setTypeface(Typeface.MONOSPACE);
        ScrollView logScroll = new ScrollView(this);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        logLp.topMargin = dp(20);
        logScroll.setLayoutParams(logLp);
        logScroll.addView(tvLog);
        // 日志默认收起：启动界面只留进度与状态，细节按需从"查看日志"里读
        logScroll.setVisibility(View.GONE);
        statusPanel.addView(logScroll);

        root.addView(statusPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    /**
     * 无边框文字按钮。
     * 系统默认 Button 在深色底上是两块浅色方块，比正文还抢眼 —— 这里去掉底色与阴影，
     * 只留文字和按下的水波纹，权重降下来。
     */
    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(Color.parseColor("#9AA6C8"));
        b.setBackground(null);
        b.setStateListAnimator(null);
        b.setMinimumWidth(0);
        b.setMinimumHeight(dp(40));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void appendLog(String line) {
        tvLog.append(line + "\n");
        View parent = (View) tvLog.getParent();
        if (parent instanceof ScrollView) {
            final ScrollView sv = (ScrollView) parent;
            sv.post(new Runnable() {
                @Override
                public void run() {
                    sv.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    // ---------------- WebView ----------------

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        // 不缓存：前端资源随 payload 更新，缓存会导致"改了却还加载旧页面"
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 兜底确认移动端外壳层在运行；正常情况下它随前端产物一起加载
                view.evaluateJavascript(SHELL_BOOTSTRAP, null);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                tvTitle.setText("页面加载失败");
                tvDetail.setText(description);
                statusPanel.setVisibility(View.VISIBLE);
            }
        });
        // WebView 默认不处理 <input type="file">，不接管的话页面上的"添加附件"按钮
        // 在手机上点下去没有任何反应（桌面上浏览器自己会弹文件框，所以只在手机暴露）。
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (callback == null) {
                    return false;
                }
                if (pendingFileCallback != null) {
                    // 上一次选择未收尾就再次触发：先把它取消，否则 WebView 会一直等
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    // CATEGORY_OPENABLE 确保返回的是可直接读取的文档 URI，
                    // 而不是某些 provider 给出的、WebView 打不开的中间形态
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    pendingFileCallback = null;
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });
        // 导出类下载的落盘桥；页面侧通过 window.DSHDownload.save(...) 调用
        webView.addJavascriptInterface(new DownloadBridge(), "DSHDownload");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            ValueCallback<Uri[]> callback = pendingFileCallback;
            pendingFileCallback = null;
            if (callback == null) {
                return;
            }
            if (resultCode != RESULT_OK || data == null) {
                // 用户取消：必须回一个 null 收尾，否则 WebView 会一直等这个回调
                callback.onReceiveValue(null);
                return;
            }
            // 自己解析，不用 FileChooserParams.parseResult ——
            // 后者依赖 Intent 的标准形态，部分 ROM / 文档提供方会返回 null，
            // 表现出来就是"文件选好了、点了确定，但什么都没发生"。
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            android.content.ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri uri = clip.getItemAt(i).getUri();
                    if (uri != null) {
                        uris.add(uri);
                    }
                }
            }
            if (uris.isEmpty() && data.getData() != null) {
                uris.add(data.getData());
            }
            callback.onReceiveValue(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * 载入 Web UI。
     * 必须用服务打印出来的带 token 地址 —— 裸地址会被服务以 401 拒绝，
     * 页面只会显示一行 "dsh web authentication required"。
     * 极少数时序下地址还没捕获到，短暂重试若干次再回落。
     */
    private void loadUi() {
        String url = HarnessService.authenticatedUrl();
        if (url == null && loadAttempts < 25) {
            loadAttempts++;
            webView.postDelayed(loadUiRetry, 400);
            return;
        }
        webView.loadUrl(url != null ? url : HarnessPaths.URL);
    }

    // ---------------- 权限与生命周期 ----------------

    private void updatePermissionButton() {
        boolean ok = HarnessPaths.hasPublicStorage(this);
        if (btnPermission != null) {
            btnPermission.setVisibility(ok ? View.GONE : View.VISIBLE);
        }
    }

    private void promptStorageIfNeeded() {
        if (HarnessPaths.hasPublicStorage(this)) {
            return;
        }
        android.content.SharedPreferences prefs = getSharedPreferences("dsh_prefs", MODE_PRIVATE);
        if (prefs.getBoolean("storage_prompted", false)) {
            return;
        }
        prefs.edit().putBoolean("storage_prompted", true).apply();

        new AlertDialog.Builder(this)
                .setTitle("需要文件权限")
                .setMessage("工作区需要建在手机存储的 DHS 目录下（/storage/emulated/0/DHS），"
                        + "这样你才能用文件管理器直接管理模型读写的文件。\n\n"
                        + "请点击「去授权」，在系统页面开启「所有文件访问权限」。")
                .setPositiveButton("去授权", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        HarnessPaths.requestStoragePermission(MainActivity.this);
                    }
                })
                .setNegativeButton("暂不", null)
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean nowOk = HarnessPaths.hasPublicStorage(this);
        if (nowOk != lastStorageOk) {
            lastStorageOk = nowOk;
            if (nowOk) {
                Toast.makeText(this, "文件权限已授予，正在切换到 DHS 工作区…", Toast.LENGTH_LONG).show();
                HarnessService.restart(this);
            }
        }
        updatePermissionButton();
        if (ready) {
            statusPanel.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
            // 未注册时忽略
        }
        super.onDestroy();
    }

    private void showLog() {
        StringBuilder sb = new StringBuilder();
        File file = new File(getFilesDir(), "dsh.log");
        if (file.exists()) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(
                        new FileInputStream(file), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } catch (IOException e) {
                sb.append("读取日志失败: ").append(e.getMessage());
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                        // 忽略
                    }
                }
            }
        }
        if (sb.length() == 0) {
            sb.append("（暂无日志）");
        }
        if (sb.length() > 40000) {
            sb = new StringBuilder("…[仅显示最近 40KB]…\n").append(sb.substring(sb.length() - 40000));
        }
        final String text = sb.toString();

        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(10.5f);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);

        new AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }
}
