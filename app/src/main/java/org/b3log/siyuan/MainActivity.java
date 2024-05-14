/*
 * SiYuan - 源于思考，饮水思源
 * Copyright (c) 2020-present, b3log.org
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.b3log.siyuan;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.blankj.utilcode.util.AppUtils;
import com.blankj.utilcode.util.KeyboardUtils;
import com.blankj.utilcode.util.StringUtils;
import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.http.AsyncHttpClient;
import com.koushikdutta.async.http.AsyncHttpPost;
import com.koushikdutta.async.http.body.JSONObjectBody;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import com.koushikdutta.async.util.Charsets;
import com.zackratos.ultimatebarx.ultimatebarx.java.UltimateBarX;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import mobile.Mobile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

/**
 * 主程序.
 *
 * @author <a href="https://88250.b3log.org">Liang Ding</a>
 * @version 1.1.0.3, Apr 24, 2024
 * @since 1.0.0
 */
public class MainActivity extends AppCompatActivity implements com.blankj.utilcode.util.Utils.OnAppStatusChangedListener {
    private AsyncHttpServer server;
    private int serverPort = 6906;
    private GeckoView webView;
    private ImageView bootLogo;
    private ProgressBar bootProgressBar;
    private TextView bootDetailsText;
    private String webViewVer;
    private String userAgent;
    private ValueCallback<Uri[]> uploadMessage;
    private static final int REQUEST_SELECT_FILE = 100;
    private static final int REQUEST_CAMERA = 101;
    private static GeckoRuntime sRuntime;
    private GeckoSession session;

    @Override public void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        if (null != session) {
            final String blockURL = intent.getStringExtra("blockURL");
            if (!StringUtils.isEmpty(blockURL)) {
                session.loadUri("javascript:window.openFileByURL('" + blockURL + "')");
            }
        }
    }

    @Override protected void onCreate(final Bundle savedInstanceState) {
        Log.i("boot", "create main activity");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 启动 HTTP Server
        startHttpServer();

        // 初始化 UI 元素
        initUIElements();

        // 拉起内核
        startKernel();

        // 初始化外观资源
        initAppearance();

        AppUtils.registerAppStatusChangedListener(this);

        // 注册工具栏显示/隐藏跟随软键盘状态
        // Fix https://github.com/siyuan-note/siyuan/issues/9765
        Utils.registerSoftKeyboardToolbar(this, session);

        // 沉浸式状态栏设置
        UltimateBarX.statusBarOnly(this).transparent().light(false).color(Color.parseColor("#1e1e1e")).apply();
        ((ViewGroup) webView.getParent()).setPadding(0, UltimateBarX.getStatusBarHeight(), 0, 0);

        // Fix https://github.com/siyuan-note/siyuan/issues/9726
        // KeyboardUtils.fixAndroidBug5497(this);
        AndroidBug5497Workaround.assistActivity(this);
    }

    private void initUIElements() {
        bootLogo = findViewById(R.id.bootLogo);
        bootProgressBar = findViewById(R.id.progressBar);
        bootDetailsText = findViewById(R.id.bootDetails);
        webView = findViewById(R.id.webView);
        webView.setBackgroundColor(Color.parseColor("#1e1e1e"));
        session = new GeckoSession();

        // Workaround for Bug 1758212
        session.setContentDelegate(new GeckoSession.ContentDelegate() {});

        if (sRuntime == null) {
            // GeckoRuntime can only be initialized once per process
            sRuntime = GeckoRuntime.create(this);
        }

        session.setPermissionDelegate(new GeckoViewPermissionDelegate());
        session.open(sRuntime);
        webView.setSession(session);

        webView.setOnDragListener((v, event) -> {
            // 禁用拖拽 https://github.com/siyuan-note/siyuan/issues/6436
            return DragEvent.ACTION_DRAG_ENDED != event.getAction();
        });

        userAgent = GeckoSession.getDefaultUserAgent();
    }

    @SuppressLint("SetJavaScriptEnabled") private void showBootIndex() {
        webView.setVisibility(View.VISIBLE);
        session.loadUri("http://127.0.0.1:6806/appearance/boot/index.html?v=" + Utils.version);

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStop(@NonNull GeckoSession session, boolean success) {
                // 页面停止加载时的处理
                if (success) {
                    // 如果页面加载成功，隐藏启动画面
                    new Handler().postDelayed(() -> {
                        bootLogo.setVisibility(View.GONE);
                        bootProgressBar.setVisibility(View.GONE);
                        bootDetailsText.setVisibility(View.GONE);
                        final ImageView bootLogo = findViewById(R.id.bootLogo);
                        bootLogo.setVisibility(View.GONE);
                    }, 666);
                }
            }
        });

        // 设置 JavaScript 和其他 GeckoSessionSettings
        // GeckoSessionSettings settings = session.getSettings();
        // settings.setAllowJavascript(true);
        // settings.setDomStorageEnabled(true);
        // settings.setCacheMode(GeckoSessionSettings.CACHE_MODE_NO_CACHE);
        // settings.setTextZoom(100);
        // settings.setUseWideViewPort(true);
        // settings.setLoadWithOverviewMode(true);

        // 设置自定义 User-Agent
        // String userAgent = "SiYuan/" + Utils.version + " https://b3log.org/siyuan Android " + settings.getUserAgentOverride();
        // settings.setUserAgentOverride(userAgent);
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @NonNull @Override public GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession geckoSession, @NonNull LoadRequest loadRequest) {

                if (loadRequest.uri.contains("127.0.0.1")) {
                    Log.d("MainActivity", "onLoadRequest() returned: " + loadRequest.uri);
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                }

                if (loadRequest.uri.contains("siyuan://api/system/exit")) {
                    exit();
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                }

                if (Objects.requireNonNull(Uri.parse(loadRequest.uri).getScheme()).toLowerCase().startsWith("http")) {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(loadRequest.uri));
                    startActivity(i);
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                }

                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }

            @Override public void onLocationChange(@NonNull GeckoSession session, String url) {
                // 此方法在 URL 发生变化时被调用
            }

            @Override public void onCanGoBack(@NonNull GeckoSession session, boolean canGoBack) {
                // 可以后退时的处理
            }

            @Override public void onCanGoForward(@NonNull GeckoSession session, boolean canGoForward) {
                // 可以前进时的处理
            }
        });

        waitFotKernelHttpServing();
        new Thread(this::keepLive).start();
    }

    private final Handler bootHandler = new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(final Message msg) {
            final String cmd = msg.getData().getString("cmd");
            if ("startKernel".equals(cmd)) {
                bootKernel();
            } else {
                showBootIndex();
            }
        }
    };

    private void startHttpServer() {
        if (null != server) {
            server.stop();
        }

        try {
            // 解决乱码问题 https://github.com/koush/AndroidAsync/issues/656#issuecomment-523325452
            final Class<Charsets> charsetClass = Charsets.class;
            Field usAscii = charsetClass.getDeclaredField("US_ASCII");
            usAscii.setAccessible(true);
            usAscii.set(Charsets.class, Charsets.UTF_8);
        } catch (final Exception e) {
            Utils.LogError("http", "init charset failed", e);
        }

        server = new AsyncHttpServer();
        server.post("/api/walkDir", (request, response) -> {
            try {
                final long start = System.currentTimeMillis();
                final JSONObject requestJSON = (JSONObject) request.getBody().get();
                final String dir = requestJSON.optString("dir");
                final JSONObject data = new JSONObject();
                final JSONArray files = new JSONArray();
                FileUtils.listFilesAndDirs(new File(dir), TrueFileFilter.INSTANCE, DirectoryFileFilter.DIRECTORY).forEach(file -> {
                    final String path = file.getAbsolutePath();
                    final JSONObject info = new JSONObject();
                    try {
                        info.put("path", path);
                        info.put("name", file.getName());
                        info.put("size", file.length());
                        info.put("updated", file.lastModified());
                        info.put("isDir", file.isDirectory());
                    } catch (final Exception e) {
                        Utils.LogError("http", "walk dir failed", e);
                    }
                    files.put(info);
                });
                data.put("files", files);
                final JSONObject responseJSON = new JSONObject().put("code", 0).put("msg", "").put("data", data);
                response.send(responseJSON);
                Utils.LogInfo("http", "walk dir [" + dir + "] in [" + (System.currentTimeMillis() - start) + "] ms");
            } catch (final Exception e) {
                Utils.LogError("http", "walk dir failed", e);
                try {
                    response.send(new JSONObject().put("code", -1).put("msg", e.getMessage()));
                } catch (final Exception e2) {
                    Utils.LogError("http", "walk dir failed", e2);
                }
            }
        });

        serverPort = getAvailablePort();
        final AsyncServer s = AsyncServer.getDefault();
        if (Utils.isDebugPackageAndMode(this)) {
            // 开发环境绑定所有网卡以便调试
            s.listen(null, serverPort, server.getListenCallback());
        } else {
            // 生产环境绑定 ipv6 回环地址 [::1] 以防止被远程访问
            s.listen(InetAddress.getLoopbackAddress(), serverPort, server.getListenCallback());
        }
        Utils.LogInfo("http", "HTTP server is listening on port [" + serverPort + "]");
    }

    private int getAvailablePort() {
        int ret = 6906;
        try {
            ServerSocket s = new ServerSocket(0);
            ret = s.getLocalPort();
            s.close();
        } catch (final Exception e) {
            Utils.LogError("http", "get available port failed", e);
        }
        return ret;
    }

    private void startKernel() {
        final Bundle b = new Bundle();
        b.putString("cmd", "startKernel");
        final Message msg = new Message();
        msg.setData(b);
        bootHandler.sendMessage(msg);
    }

    private void bootKernel() {
        Mobile.setHttpServerPort(serverPort);
        if (Mobile.isHttpServing()) {
            Utils.LogInfo("boot", "kernel HTTP server is running");
            showBootIndex();
            return;
        }

        final String appDir = getFilesDir().getAbsolutePath() + "/app";
        final Locale locale = LocaleList.getDefault().get(0); // 获取用户的设备首选语言
        final String language = locale.getLanguage().toLowerCase(); // 获取语言代码
        final String script = locale.getScript().toLowerCase(); // 获取脚本代码
        final String country = locale.getCountry().toLowerCase(); // 获取国家代码
        final String workspaceBaseDir = Objects.requireNonNull(getExternalFilesDir(null)).getAbsolutePath();
        final String timezone = TimeZone.getDefault().getID();
        new Thread(() -> {
            final String localIPs = Utils.getIPAddressList();

            String langCode;
            if ("zh".equals(language)) {
                // 检查是否为简体字脚本
                if ("hans".equals(script)) {
                    langCode = "zh_CN"; // 简体中文，使用 zh_CN

                } else if ("hant".equals(script)) {
                    // 对于繁体字脚本，需要进一步检查国家代码
                    if ("tw".equals(country)) {
                        langCode = "zh_CHT"; // 繁体中文对应台湾
                    } else if ("hk".equals(country)) {
                        langCode = "zh_CHT"; // 繁体中文对应香港
                    } else {
                        langCode = "zh_CHT"; // 其他繁体中文情况也使用 zh_CHT
                    }
                } else {
                    langCode = "zh_CN"; // 如果脚本不是简体或繁体，默认为简体中文
                }

            } else {
                // 对于非中文语言，创建一个映射来定义其他语言代码的对应关系
                Map<String, String> otherLangMap = new HashMap<>();
                otherLangMap.put("es", "es_ES"); // 西班牙语使用 es_ES
                otherLangMap.put("fr", "fr_FR"); // 法语使用 fr_FR

                // 使用 getOrDefault 方法从映射中获取语言代码，如果语言不存在则默认为 en_US
                langCode = otherLangMap.getOrDefault(language, "en_US");
            }

            Mobile.startKernel("android", appDir, workspaceBaseDir, timezone, localIPs, langCode, Build.VERSION.RELEASE + "/SDK " + Build.VERSION.SDK_INT + "/WebView " + webViewVer + "/Manufacturer " + android.os.Build.MANUFACTURER + "/Brand " + android.os.Build.BRAND + "/UA " + userAgent);
        }).start();

        final Bundle b = new Bundle();
        b.putString("cmd", "bootIndex");
        final Message msg = new Message();
        msg.setData(b);
        bootHandler.sendMessage(msg);
    }

    /**
     * 通知栏保活。
     */
    private void keepLive() {
        while (true) {
            try {
                final Intent intent = new Intent(MainActivity.this, KeepLiveService.class);
                ContextCompat.startForegroundService(this, intent);
                sleep(45 * 1000);
                stopService(intent);
            } catch (final Throwable ignored) {
            }
        }
    }

    /**
     * 等待内核 HTTP 服务伺服。
     */
    private void waitFotKernelHttpServing() {
        do {
            sleep(10);
        } while (!Mobile.isHttpServing());
    }

    private void initAppearance() {
        if (needUnzipAssets()) {
            bootLogo.setVisibility(View.VISIBLE);
            // 不要进度条更平滑一些
            //bootProgressBar.setVisibility(View.VISIBLE);
            //bootDetailsText.setVisibility(View.VISIBLE);

            final String dataDir = getFilesDir().getAbsolutePath();
            final String appDir = dataDir + "/app";
            final File appVerFile = new File(appDir, "VERSION");

            setBootProgress("Clearing appearance...", 20);
            try {
                FileUtils.deleteDirectory(new File(appDir));
            } catch (final Exception e) {
                Utils.LogError("boot", "delete dir [" + appDir + "] failed, exit application", e);
                exit();
                return;
            }

            setBootProgress("Initializing appearance...", 60);
            Utils.unzipAsset(getAssets(), "app.zip", appDir + "/app");

            try {
                FileUtils.writeStringToFile(appVerFile, Utils.version, StandardCharsets.UTF_8);
            } catch (final Exception e) {
                Utils.LogError("boot", "write version failed", e);
            }

            setBootProgress("Booting kernel...", 80);
        }
    }

    private void setBootProgress(final String text, final int progressPercent) {
        runOnUiThread(() -> {
            bootDetailsText.setText(text);
            bootProgressBar.setProgress(progressPercent);
        });
    }

    private void sleep(final long time) {
        try {
            Thread.sleep(time);
        } catch (final Exception e) {
            Utils.LogError("runtime", "sleep failed", e);
        }
    }

    @Override public void onBackPressed() {
        super.onBackPressed();
        session.loadUri("javascript:window.goBack ? window.goBack() : window.history.back()");
    }

    // 用于保存拍照图片的 uri
    private Uri mCameraUri;

    private boolean hasPermissions(String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
                return;
            }

            Toast.makeText(this, "Permission denied", Toast.LENGTH_LONG).show();
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void openCamera() {
        final Intent captureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (captureIntent.resolveActivity(getPackageManager()) != null) {
            final Uri photoUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new ContentValues());
            mCameraUri = photoUri;
            if (photoUri != null) {
                captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                captureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                startActivityForResult(captureIntent, REQUEST_CAMERA);
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (null == uploadMessage) {
            super.onActivityResult(requestCode, resultCode, intent);
            return;
        }

        if (requestCode == REQUEST_CAMERA) {
            if (RESULT_OK != resultCode) {
                uploadMessage.onReceiveValue(null);
                uploadMessage = null;
                return;
            }

            uploadMessage.onReceiveValue(new Uri[] { mCameraUri });
        } else if (requestCode == REQUEST_SELECT_FILE) {
            // 以下代码参考自 https://github.com/mgks/os-fileup/blob/master/app/src/main/java/mgks/os/fileup/MainActivity.java MIT license

            Uri[] results = null;
            ClipData clipData;
            String stringData;
            try {
                clipData = intent.getClipData();
                stringData = intent.getDataString();
            } catch (Exception e) {
                clipData = null;
                stringData = null;
            }

            if (clipData != null) {
                final int numSelectedFiles = clipData.getItemCount();
                results = new Uri[numSelectedFiles];
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    results[i] = clipData.getItemAt(i).getUri();
                }
            } else {
                try {
                    Bitmap cam_photo = (Bitmap) intent.getExtras().get("data");
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    assert cam_photo != null;
                    cam_photo.compress(Bitmap.CompressFormat.JPEG, 100, bytes);
                    stringData = MediaStore.Images.Media.insertImage(this.getContentResolver(), cam_photo, null, null);
                } catch (Exception ignored) {
                }

                if (!StringUtils.isEmpty(stringData)) {
                    results = new Uri[] { Uri.parse(stringData) };
                }
            }

            uploadMessage.onReceiveValue(results);
        }

        uploadMessage = null;
        super.onActivityResult(requestCode, resultCode, intent);
    }

    private boolean needUnzipAssets() {
        final String dataDir = getFilesDir().getAbsolutePath();
        final String appDir = dataDir + "/app";
        final File appDirFile = new File(appDir);
        appDirFile.mkdirs();

        boolean ret = true;
        if (Utils.isDebugPackageAndMode(this)) {
            Log.i("boot", "always unzip assets in debug mode");
            return ret;
        }

        final File appVerFile = new File(appDir, "VERSION");
        if (appVerFile.exists()) {
            try {
                final String ver = FileUtils.readFileToString(appVerFile, StandardCharsets.UTF_8);
                ret = !ver.equals(Utils.version);
            } catch (final Exception e) {
                Utils.LogError("boot", "check version failed", e);
            }
        }
        return ret;
    }

    @Override protected void onDestroy() {
        Log.i("boot", "destroy main activity");
        super.onDestroy();
        KeyboardUtils.unregisterSoftInputChangedListener(getWindow());
        AppUtils.unregisterAppStatusChangedListener(this);
        if (null != webView) {
            webView.removeAllViews();
            webView.destroyDrawingCache();
        }
        if (null != server) {
            server.stop();
        }
    }

    @Override public void onForeground(Activity activity) {
        startSyncData();
        if (null != webView) {
            session.loadUri("javascript:window.reconnectWebSocket()");
        }
    }

    @Override public void onBackground(Activity activity) {
        startSyncData();
    }

    @Override public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);
    }

    private void exit() {
        finishAffinity();
        finishAndRemoveTask();
    }

    private void checkWebViewVer(final WebSettings ws) {
        // Android check WebView version 75+ https://github.com/siyuan-note/siyuan/issues/7840
        final String ua = ws.getUserAgentString();
        if (ua.contains("Chrome/")) {
            final int minVer = 75;
            try {
                final String chromeVersion = ua.split("Chrome/")[1].split(" ")[0];
                if (chromeVersion.contains(".")) {
                    final String[] chromeVersionParts = chromeVersion.split("\\.");
                    webViewVer = chromeVersionParts[0];
                    if (Integer.parseInt(webViewVer) < minVer) {
                        Toast.makeText(this, "WebView version [" + chromeVersion + "] is too low, please upgrade to " + minVer + "+", Toast.LENGTH_LONG).show();
                    }
                }
            } catch (final Exception e) {
                Utils.LogError("boot", "check webview version failed", e);
                Toast.makeText(this, "Check WebView version failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private static boolean syncing;

    public static void startSyncData() {
        new Thread(MainActivity::syncData).start();
    }

    public static void syncData() {
        try {
            if (syncing) {
                Log.i("sync", "data is syncing...");
                return;
            }
            syncing = true;

            final AsyncHttpPost req = new com.koushikdutta.async.http.AsyncHttpPost("http://127.0.0.1:6806/api/sync/performSync");
            req.setBody(new JSONObjectBody(new JSONObject().put("mobileSwitch", true)));
            AsyncHttpClient.getDefaultInstance().executeJSONObject(req, new com.koushikdutta.async.http.AsyncHttpClient.JSONObjectCallback() {
                @Override public void onCompleted(Exception e, com.koushikdutta.async.http.AsyncHttpResponse source, JSONObject result) {
                    if (null != e) {
                        Utils.LogError("sync", "data sync failed", e);
                    }
                }
            });
        } catch (final Throwable e) {
            Utils.LogError("sync", "data sync failed", e);
        } finally {
            syncing = false;
        }
    }

    private class GeckoViewPermissionDelegate implements GeckoSession.PermissionDelegate {
        @Override public void onMediaPermissionRequest(@NonNull final GeckoSession session, @NonNull final String uri, final MediaSource[] video, final MediaSource[] audio, @NonNull final MediaCallback callback) {
            // Reject permission if Android permission has been previously denied.
            if (video != null && ContextCompat.checkSelfPermission(MainActivity.this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                callback.reject();
                uploadMessage = null;
                return;
            }

            if (Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                // 不支持 Android 10 以下
                Toast.makeText(getApplicationContext(), "Capture is not supported on your device (Android 10+ required)", Toast.LENGTH_LONG).show();
                uploadMessage = null;
                return;
            }

            openCamera();
            callback.reject();
        }
    }
}

