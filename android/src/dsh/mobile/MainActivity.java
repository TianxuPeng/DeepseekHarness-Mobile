package dsh.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Self-contained DeepSeek Harness shell.
 *
 * The APK carries a Node.js runtime (as native libraries) and the Harness
 * engine (as a zipped asset). On first launch everything is unpacked into the
 * app's private directory, the engine is started on loopback, and its one-time
 * token URL is opened in the embedded WebView.
 *
 * The top bar shows the DeepSeek balance and the current peak/idle tier and
 * opens the platform usage page on tap.
 */
public class MainActivity extends Activity {
    private static final String TAG = "DSH";
    private static final int PORT = 43129;
    private static final String ENGINE_ZIP = "engine.zip";
    private static final String USAGE_URL = "https://platform.deepseek.com/usage";
    private static final String BALANCE_URL = "https://api.deepseek.com/user/balance";
    private static final String PLATFORM_BALANCE_URL =
            "https://platform.deepseek.com/api/v0/users/get_user_summary";
    private static final String PREFS = "dsh";
    private static final String PREF_KEY = "api_key";
    private android.app.Dialog popupDialog;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> engineLog = new ArrayDeque<>();
    private FrameLayout root;
    private WebView web;
    private TextView status;
    private TextView chip;
    private ProgressBar bar;
    private java.lang.Process node;
    private volatile boolean finished;
    private volatile String lastUrl;
    private int reloads;
    private FrameLayout usageOverlay;
    private volatile String balanceText = "余额 —";
    private volatile boolean peak;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        new Thread(this::bootstrap, "dsh-bootstrap").start();
        scheduleBalanceRefresh();
    }

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        column.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        web = makeWebView();
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        column.addView(web, webLp);

        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        int pad = dp(28);
        panel.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setText("正在准备 DeepSeek Harness…");
        status.setTextSize(15f);
        status.setTextColor(Color.parseColor("#1F3864"));
        status.setGravity(Gravity.CENTER);
        panel.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        lp.topMargin = pad / 2;
        panel.addView(bar, lp);

        FrameLayout.LayoutParams center = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        center.gravity = Gravity.CENTER;
        root.addView(panel, center);
        loadingPanel = panel;
        return root;
    }

    private LinearLayout loadingPanel;

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("DeepSeek Harness");
        title.setTextSize(15);
        title.setTextColor(Color.parseColor("#1F3864"));
        title.setPadding(dp(16), 0, 0, 0);
        title.setIncludeFontPadding(false);
        title.setGravity(Gravity.CENTER_VERTICAL);
        try {
            title.setTypeface(android.graphics.Typeface.createFromAsset(
                    getAssets(), "fonts/texgyreheros.otf"));
        } catch (Exception e) {
            Log.w(TAG, "header font unavailable", e);
        }
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        titleLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        header.addView(title, titleLp);

        chip = new TextView(this);
        chip.setText(balanceText);
        chip.setTextSize(12);
        chip.setTextColor(Color.parseColor("#1F3864"));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), dp(5), dp(12), dp(5));
        chip.setBackground(rounded("#F2F4F8", 999));
        chip.setOnClickListener(v -> openUsagePage());
        chip.setOnLongClickListener(v -> {
            showKeyDialog();
            return true;
        });
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        chipLp.rightMargin = dp(12);
        header.addView(chip, chipLp);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#E6E9F0"));
        FrameLayout.LayoutParams divLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divLp.gravity = Gravity.BOTTOM;
        header.addView(divider, divLp);
        return header;
    }

    private GradientDrawable rounded(String color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(color));
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private WebView makeWebView() {
        WebView view = new WebView(this);
        view.setBackgroundColor(Color.WHITE);
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                Log.i(TAG, "popup requested");
                WebView popup = new WebView(MainActivity.this);
                WebSettings ps = popup.getSettings();
                ps.setJavaScriptEnabled(true);
                ps.setDomStorageEnabled(true);
                ps.setSupportMultipleWindows(false);
                popup.setWebViewClient(new WebViewClient());
                popup.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onCloseWindow(WebView w) {
                        if (popupDialog != null) {
                            popupDialog.dismiss();
                            popupDialog = null;
                        }
                    }
                });
                popupDialog = new android.app.Dialog(MainActivity.this,
                        android.R.style.Theme_Material_Light_NoActionBar);
                popupDialog.setContentView(popup, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                popupDialog.setOnDismissListener(d -> popupDialog = null);
                popupDialog.show();
                if (popupDialog.getWindow() != null) {
                    popupDialog.getWindow().setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                }
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                Log.i(TAG, "console " + msg.messageLevel() + ": " + msg.message() + " @" + msg.lineNumber());
                return true;
            }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost();
                String scheme = u.getScheme();
                if (scheme != null && !scheme.startsWith("http")) {
                    openExternal(u);
                    return true;
                }
                if (host != null && !"127.0.0.1".equals(host) && !"localhost".equals(host)
                        && !"platform.deepseek.com".equals(host)) {
                    openExternal(u);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                Log.i(TAG, "page finished: " + url);
                if ("about:blank".equals(url) && lastUrl != null && reloads < 3) {
                    reloads++;
                    Log.w(TAG, "blank page; reloading " + lastUrl);
                    v.loadUrl(lastUrl);
                    return;
                }
                if (url != null && url.startsWith("http://127.0.0.1:")) {
                    Log.i(TAG, "app ui ready");
                    scheduleLoginRefresh();
                }
            }

            @Override
            public void onReceivedError(WebView v, android.webkit.WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                Log.e(TAG, "page error: " + req.getUrl() + " " + err.getErrorCode() + " " + err.getDescription());
            }

            @Override
            public void onReceivedHttpError(WebView v, android.webkit.WebResourceRequest req,
                                            android.webkit.WebResourceResponse res) {
                Log.e(TAG, "http error: " + req.getUrl() + " " + res.getStatusCode());
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                Log.e(TAG, "webview renderer gone; rebuilding");
                if (v.getParent() instanceof ViewGroup) {
                    ((ViewGroup) v.getParent()).removeView(v);
                }
                v.destroy();
                web = makeWebView();
                ViewGroup parent = (ViewGroup) root.getChildAt(0);
                parent.addView(web, 1, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                if (lastUrl != null) web.loadUrl(lastUrl);
                return true;
            }
        });
        view.setVisibility(View.GONE);
        return view;
    }

    private void bootstrap() {
        try {
            File engine = new File(getFilesDir(), "engine");
            if (!new File(engine, ".ready").exists()) {
                say("首次启动：正在解压运行环境，请稍候…");
                deleteRecursive(engine);
                if (!engine.mkdirs() && !engine.isDirectory()) throw new IOException("无法创建 " + engine);
                int entries = unzip(ENGINE_ZIP, engine);
                if (!new File(engine, ".ready").createNewFile()) throw new IOException("无法写入标记文件");
                Log.i(TAG, "engine unpacked: " + entries + " entries");
            }
            say("正在启动 DeepSeek Harness 引擎…");
            startEngine(engine);
        } catch (Throwable t) {
            Log.e(TAG, "bootstrap failed", t);
            fail("启动失败：" + t.getMessage());
        }
    }

    private int unzip(String assetName, File dest) throws IOException {
        AssetManager am = getAssets();
        int count = 0;
        try (InputStream raw = am.open(assetName);
             ZipInputStream zin = new ZipInputStream(raw)) {
            byte[] buf = new byte[64 * 1024];
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(dest.getCanonicalPath())) {
                    throw new IOException("非法路径: " + e.getName());
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                }
                zin.closeEntry();
                count++;
                if (count % 500 == 0) {
                    final int c = count;
                    say("首次启动：正在解压运行环境（已解压 " + c + " 个文件）…");
                }
            }
        }
        return count;
    }

    private void startEngine(File engine) throws Exception {
        File files = getFilesDir();
        File home = dir(files, "home");
        File tmp = dir(files, "tmp");
        File workspace = dir(files, "workspace");
        File lib = dir(files, "lib");
        File bin = dir(files, "bin");
        File ca = installCaCert();

        String nativeDir = getApplicationInfo().nativeLibraryDir;
        link(new File(lib, "libicudata.so.79"), nativeDir + "/libicudata.so");
        link(new File(lib, "libicui18n.so.79"), nativeDir + "/libicui18n.so");
        link(new File(lib, "libicuuc.so.79"), nativeDir + "/libicuuc.so");
        link(new File(lib, "libz.so.1"), nativeDir + "/libz.so");
        link(new File(lib, "libcrypto.so.3"), nativeDir + "/libcrypto.so");
        link(new File(lib, "libssl.so.3"), nativeDir + "/libssl.so");
        link(new File(lib, "libreadline.so.8"), nativeDir + "/libreadline.so");
        link(new File(lib, "libncursesw.so.6"), nativeDir + "/libncursesw.so");
        link(new File(bin, "bash"), nativeDir + "/libbash.so");

        killPrevious();

        File entry = new File(engine, "node_modules/@deepseek-ai/dsh/lib/bin.js");
        if (!entry.isFile()) throw new IOException("引擎文件缺失: " + entry);

        ProcessBuilder pb = new ProcessBuilder(
                nativeDir + "/libnode.so",
                "--expose-internals",
                entry.getAbsolutePath(),
                "web", "--no-open", "--port", "0");
        pb.directory(workspace);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("HOME", home.getAbsolutePath());
        env.put("DSH_HOME", new File(home, ".dsh").getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("PREFIX", files.getAbsolutePath());
        env.put("PATH", bin.getAbsolutePath() + ":/system/bin:/system/xbin");
        env.put("LD_LIBRARY_PATH", nativeDir + ":" + lib.getAbsolutePath());
        env.put("LANG", "en_US.UTF-8");
        env.put("SHELL", new File(bin, "bash").getAbsolutePath());
        env.put("SSL_CERT_FILE", ca.getAbsolutePath());
        env.put("NODE_EXTRA_CA_CERTS", ca.getAbsolutePath());

        node = pb.start();
        Log.i(TAG, "engine started");

        BufferedReader reader = new BufferedReader(new InputStreamReader(node.getInputStream()));
        String line;
        long deadline = System.currentTimeMillis() + 180_000;
        while ((line = reader.readLine()) != null) {
            Log.i(TAG, "engine: " + line);
            remember(line);
            int at = line.indexOf("http://127.0.0.1:");
            int tokenAt = at >= 0 ? line.indexOf("/?token=", at) : -1;
            if (tokenAt > at) {
                openUi(line.substring(at).trim());
            }
            if (System.currentTimeMillis() > deadline && web.getVisibility() != View.VISIBLE) {
                fail("引擎启动超时。\n" + tail());
                return;
            }
        }
        int code = node.waitFor();
        if (!finished && web.getVisibility() != View.VISIBLE) {
            fail("引擎已退出（code=" + code + "）。\n" + tail());
        }
    }

    private void openUi(String url) {
        lastUrl = url;
        ui.post(() -> {
            if (finished) return;
            finished = true;
            status.setText("正在打开界面…");
            web.loadUrl(url);
            web.setVisibility(View.VISIBLE);
            bar.setVisibility(View.GONE);
            status.setVisibility(View.GONE);
        });
    }

    private void fail(String message) {
        ui.post(() -> {
            status.setText(message);
            bar.setVisibility(View.GONE);
        });
    }

    private void say(String text) {
        ui.post(() -> status.setText(text));
    }

    private void remember(String line) {
        synchronized (engineLog) {
            engineLog.addLast(line);
            while (engineLog.size() > 30) engineLog.removeFirst();
        }
    }

    private String tail() {
        StringBuilder sb = new StringBuilder();
        synchronized (engineLog) {
            for (String s : engineLog) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    private static File dir(File base, String name) throws IOException {
        File d = new File(base, name);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("无法创建 " + d);
        return d;
    }

    /**
     * The bundled Node was built for Termux and expects its CA bundle at
     * $PREFIX/etc/tls/cert.pem. Ship the Mozilla bundle and point the engine at
     * it, otherwise every HTTPS request fails certificate verification.
     */
    private File installCaCert() throws IOException {
        File dir = dir(getFilesDir(), "etc/tls");
        File cert = new File(dir, "cert.pem");
        if (!cert.isFile() || cert.length() < 1000) {
            try (InputStream in = getAssets().open("cacert.pem");
                 FileOutputStream out = new FileOutputStream(cert)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        return cert;
    }

    private static void link(File link, String target) {
        try {
            java.nio.file.Files.deleteIfExists(link.toPath());
        } catch (Exception ignored) {
        }
        try {
            Os.symlink(target, link.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "symlink failed " + link, e);
        }
    }

    /** Kill engine processes left behind by an earlier app launch (same uid only). */
    private void killPrevious() {
        String needle = new File(getFilesDir(), "engine").getAbsolutePath();
        File proc = new File("/proc");
        File[] entries = proc.listFiles();
        if (entries == null) return;
        for (File p : entries) {
            String name = p.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            try {
                File cmdFile = new File(p, "cmdline");
                if (!cmdFile.canRead() || cmdFile.length() == 0) continue;
                byte[] raw = new byte[(int) cmdFile.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(cmdFile)) {
                    int off = 0, n;
                    while (off < raw.length && (n = in.read(raw, off, raw.length - off)) > 0) off += n;
                }
                String cmd = new String(raw, "UTF-8").replace('\0', ' ').trim();
                if (!cmd.contains(needle) || !cmd.contains("libnode.so")) continue;
                int pid = Integer.parseInt(name);
                if (pid == android.os.Process.myPid()) continue;
                android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL);
                Log.i(TAG, "killed stale engine pid " + pid);
            } catch (Exception ignored) {
            }
        }
    }

    private void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    // ---------------- balance & tier ----------------

    /** Expand the platform usage page from the chip's corner into a full-screen in-app WebView. */
    private void openUsagePage() {
        if (usageOverlay != null) return;
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(Color.WHITE);
        layer.setClickable(true);

        WebView view = new WebView(this);
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        view.setWebViewClient(new WebViewClient());
        view.setWebChromeClient(new WebChromeClient());
        layer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView close = new TextView(this);
        close.setText("关闭");
        close.setTextSize(12);
        close.setTextColor(Color.parseColor("#1F3864"));
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(12), dp(6), dp(12), dp(6));
        close.setBackground(rounded("#F2F4F8", 999));
        close.setOnClickListener(v -> closeUsageOverlay());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.gravity = Gravity.TOP | Gravity.END;
        closeLp.topMargin = dp(10);
        closeLp.rightMargin = dp(12);
        layer.addView(close, closeLp);

        root.addView(layer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        float w = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
        float h = root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
        layer.setPivotX(w);
        layer.setPivotY(0);
        layer.setScaleX(Math.max(0.04f, (chip != null ? chip.getWidth() : 60f) / w));
        layer.setScaleY(Math.max(0.02f, (chip != null ? chip.getHeight() : 30f) / h));
        layer.setAlpha(0f);
        layer.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();

        usageOverlay = layer;
        view.loadUrl(USAGE_URL);
    }

    private void closeUsageOverlay() {
        if (usageOverlay == null) return;
        final FrameLayout layer = usageOverlay;
        usageOverlay = null;
        layer.animate().scaleX(0.04f).scaleY(0.02f).alpha(0f).setDuration(200)
                .withEndAction(() -> root.removeView(layer)).start();
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            fail("无法打开页面：" + e.getMessage());
        }
    }

    /** Lets the injected window.open shim hand the login URL to the system browser. */
    public class NativeBridge {
        @android.webkit.JavascriptInterface
        public void openExternal(final String url) {
            ui.post(() -> {
                if (url != null && !url.isEmpty()) MainActivity.this.openExternal(Uri.parse(url));
            });
        }
    }

    private void showKeyDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("sk-...");
        input.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_KEY, ""));
        new AlertDialog.Builder(this)
                .setTitle("DeepSeek API Key")
                .setMessage("用于读取余额；留空则清除。登录 Harness 后也会自动使用其凭证。")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(PREF_KEY, input.getText().toString().trim()).apply();
                    refreshBalance();
                })
                .setNeutralButton("打开用量页", (d, w) -> openUsagePage())
                .setNegativeButton("取消", null)
                .show();
    }

    private void scheduleBalanceRefresh() {
        refreshBalance();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshBalance();
                ui.postDelayed(this, 5 * 60_000);
            }
        }, 5 * 60_000);
    }

    /** Re-read credentials shortly after the web UI loads so a fresh login shows the balance. */
    private void scheduleLoginRefresh() {
        for (int delay : new int[]{1500, 5000, 15000, 45000}) {
            ui.postDelayed(this::refreshBalance, delay);
        }
    }

    private void refreshBalance() {
        updateChip();
        new Thread(() -> {
            String accountToken = resolveAccountToken();
            String apiKey = resolveApiKey();
            String text = null;
            if (accountToken != null) {
                text = fetchPlatformBalance(accountToken);
            }
            if (text == null && apiKey != null) {
                text = fetchApiKeyBalance(apiKey);
            }
            if (text == null) text = "余额 —";
            peak = peakAt(new Date());
            balanceText = text;
            ui.post(this::updateChip);
        }, "dsh-balance").start();
    }

    private void updateChip() {
        if (chip == null) return;
        String tier = peak ? "峰时" : "谷时";
        chip.setText(balanceText + " · " + tier);
        chip.setTextColor(Color.parseColor(peak ? "#B24A00" : "#1E6B3A"));
        chip.setBackground(rounded(peak ? "#FFF1E3" : "#E9F5EE", 999));
    }

    /** Account grant stored by the Harness sign-in; the platform summary API accepts it. */
    private String resolveAccountToken() {
        File f = new File(getFilesDir(), "home/.dsh/.credentials.yaml");
        if (!f.isFile()) return null;
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            int at = text.indexOf("deepseek-account-platform/default:");
            if (at >= 0) {
                String rest = text.substring(at);
                Matcher m = Pattern.compile("(?m)^\\s*token:\\s*[\"']?([^\"'\\s#]+)").matcher(rest);
                if (m.find()) return m.group(1);
            }
        } catch (Exception e) {
            Log.w(TAG, "read account token failed", e);
        }
        return null;
    }

    private String resolveApiKey() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String stored = sp.getString(PREF_KEY, "");
        if (stored != null && !stored.isEmpty()) return stored;
        File f = new File(getFilesDir(), "home/.dsh/.credentials.yaml");
        if (!f.isFile()) return null;
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("(?m)^\\s*DEEPSEEK_API_KEY:\\s*[\"']?([^\"'\\s#]+)")
                    .matcher(text);
            if (m.find()) return m.group(1);
        } catch (Exception e) {
            Log.w(TAG, "read credentials failed", e);
        }
        return null;
    }

    /** Wallets from platform.deepseek.com, the same data the platform usage page shows. */
    private String fetchPlatformBalance(String token) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(PLATFORM_BALANCE_URL).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("x-dsh-auth-token", token);
            conn.setRequestProperty("x-client-platform", "web");
            conn.setRequestProperty("x-client-version", "0.2.2");
            conn.setRequestProperty("x-client-locale", "zh_CN");
            conn.setRequestProperty("x-client-timezone-offset", "28800");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "platform balance HTTP " + code);
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject body = new JSONObject(sb.toString());
            JSONObject data = body.optJSONObject("data");
            if (data == null || data.optInt("biz_code", -1) != 0) return null;
            JSONObject biz = data.optJSONObject("biz_data");
            if (biz == null) return null;
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;
            boolean found = false;
            for (String field : new String[]{"normal_wallets", "bonus_wallets"}) {
                JSONArray wallets = biz.optJSONArray(field);
                if (wallets == null) continue;
                for (int i = 0; i < wallets.length(); i++) {
                    JSONObject w = wallets.optJSONObject(i);
                    if (w == null) continue;
                    if (!"CNY".equalsIgnoreCase(w.optString("currency", ""))) continue;
                    try {
                        total = total.add(new java.math.BigDecimal(w.optString("balance", "0")));
                        found = true;
                    } catch (Exception ignored) {
                    }
                }
            }
            if (!found) return null;
            return "余额 ¥" + total.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        } catch (Exception e) {
            Log.w(TAG, "platform balance failed", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String fetchApiKeyBalance(String key) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BALANCE_URL).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "balance HTTP " + code);
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject body = new JSONObject(sb.toString());
            if (!body.optBoolean("is_available", true)) return null;
            JSONArray infos = body.optJSONArray("balance_infos");
            if (infos == null || infos.length() == 0) return null;
            JSONObject info = null;
            for (int i = 0; i < infos.length(); i++) {
                JSONObject it = infos.optJSONObject(i);
                if (it != null && "CNY".equalsIgnoreCase(it.optString("currency"))) {
                    info = it;
                    break;
                }
            }
            if (info == null) info = infos.optJSONObject(0);
            if (info == null) return null;
            String currency = info.optString("currency", "CNY").toUpperCase(Locale.ROOT);
            String total = info.optString("total_balance", "");
            if (total.isEmpty()) return null;
            try {
                java.math.BigDecimal rounded = new java.math.BigDecimal(total)
                        .setScale(2, java.math.RoundingMode.HALF_UP);
                String symbol = "CNY".equals(currency) ? "¥" : ("USD".equals(currency) ? "$" : currency + " ");
                return "余额 " + symbol + rounded.toPlainString();
            } catch (Exception e) {
                return null;
            }
        } catch (Exception e) {
            Log.w(TAG, "balance fetch failed", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Peak on Beijing weekdays 09:00-12:00 and 14:00-18:00, idle otherwise. */
    private boolean peakAt(Date date) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        c.setTime(date);
        int day = c.get(Calendar.DAY_OF_WEEK);
        boolean weekday = day >= Calendar.MONDAY && day <= Calendar.FRIDAY;
        int minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return weekday && ((minutes >= 9 * 60 && minutes < 12 * 60)
                || (minutes >= 14 * 60 && minutes < 18 * 60));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (chip != null) refreshBalance();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && usageOverlay != null) {
            closeUsageOverlay();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (node != null) node.destroy();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
