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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.StringRes;

import com.blankj.utilcode.util.KeyboardUtils;
import com.blankj.utilcode.util.StringUtils;
import com.blankj.utilcode.util.TimeUtils;

import org.apache.commons.io.FileUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import mobile.Mobile;

/**
 * 工具类.
 *
 * @author <a href="https://88250.b3log.org">Liang Ding</a>
 * @author <a href="https://github.com/wwxiaoqi">Jane Haring</a>
 * @version 1.4.0.1, Apr 6, 2025
 * @since 1.0.0
 */
public final class Utils {

    /**
     * App version.
     */
    public static final String version = BuildConfig.VERSION_NAME;

    /**
     * App version code.
     */
    public static final int versionCode = BuildConfig.VERSION_CODE;

    private static Toast currentToast;

    public static void showToast(final Context context, final String message) {
        if (currentToast != null) {
            currentToast.cancel();
        }
        currentToast = Toast.makeText(context, message, Toast.LENGTH_LONG);
        currentToast.show();
    }

    public static void showToast(final Context context, @StringRes int resId) {
        if (currentToast != null) {
            currentToast.cancel();
        }
        currentToast = Toast.makeText(context, resId, Toast.LENGTH_LONG);
        currentToast.show();
    }

    public static boolean isTablet(String userAgent) {
        if (StringUtils.isEmpty(userAgent)) {
            return false;
        }

        userAgent = userAgent.toLowerCase();
        return userAgent.contains("tablet") || userAgent.contains("pad") ||
                (userAgent.contains("android") && !userAgent.contains("mobile"));
    }


    public static boolean isCnChannel(final PackageManager pm) {
        final String channel = getChannel(pm);
        return channel.contains("cn") || channel.equals("huawei");
    }

    public static String getChannel(final PackageManager pm) {
        // Privacy policy solicitation will no longer pop up when Android starts for the first time
        // https://github.com/siyuan-note/siyuan/issues/10348
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = pm.getApplicationInfo(BuildConfig.APPLICATION_ID, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }

        // 从配置清单获取 CHANNEL 的值，用于判断是哪个渠道包
        return applicationInfo.metaData.getString("CHANNEL");
    }

    private static long lastShowKeyboard = 0;

    public static void registerSoftKeyboardToolbar(final Activity activity, final WebView webView) {
        KeyboardUtils.registerSoftInputChangedListener(activity, height -> {
            if (activity.isInMultiWindowMode()) {
                Utils.logInfo("keyboard", "In multi window mode, do not show keyboard toolbar");
                return;
            }

            final long now = System.currentTimeMillis();
            if (KeyboardUtils.isSoftInputVisible(activity)) {
                webView.evaluateJavascript("javascript:showKeyboardToolbar()", null);
                lastShowKeyboard = now;
                //Utils.logInfo("keyboard", "Show keyboard toolbar");
            } else {
                if (now - lastShowKeyboard < 500) {
                    // 短时间内键盘显示又隐藏，强制再次显示键盘 https://github.com/siyuan-note/siyuan/issues/11098#issuecomment-2273704439
                    KeyboardUtils.showSoftInput(activity);
                    Utils.logInfo("keyboard", "Force show keyboard");
                    return;
                }
                webView.evaluateJavascript("javascript:hideKeyboardToolbar()", null);
                //Utils.logInfo("keyboard", "Hide keyboard toolbar");
            }
        });
    }

    public static void unzipAsset(final AssetManager assetManager, final String zipName, final String targetDirectory) {
        ZipInputStream zis = null;
        try {
            final InputStream zipFile = assetManager.open(zipName);
            zis = new ZipInputStream(new BufferedInputStream(zipFile));
            ZipEntry ze;
            int count;
            byte[] buffer = new byte[1024 * 512];
            while ((ze = zis.getNextEntry()) != null) {
                final File file = new File(targetDirectory, ze.getName());
                try {
                    ensureZipPathSafety(file, targetDirectory);
                } catch (final Exception se) {
                    throw se;
                }

                final File dir = ze.isDirectory() ? file : file.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs())
                    throw new FileNotFoundException("Failed to ensure directory: " + dir.getAbsolutePath());
                if (ze.isDirectory())
                    continue;
                FileOutputStream fout = new FileOutputStream(file);
                try {
                    while ((count = zis.read(buffer)) != -1)
                        fout.write(buffer, 0, count);
                } finally {
                    fout.close();
                }
            /* if time should be restored as well
            long time = ze.getTime();
            if (time > 0)
                file.setLastModified(time);
            */
            }
        } catch (final Exception e) {
            Utils.logError("boot", "unzip asset [from=" + zipName + ", to=" + targetDirectory + "] failed", e);
        } finally {
            if (null != zis) {
                try {
                    zis.close();
                } catch (final Exception e) {
                }
            }
        }
    }

    private static void ensureZipPathSafety(final File outputFile, final String destDirectory) throws Exception {
        final String destDirCanonicalPath = (new File(destDirectory)).getCanonicalPath();
        final String outputFileCanonicalPath = outputFile.getCanonicalPath();
        if (!outputFileCanonicalPath.startsWith(destDirCanonicalPath)) {
            throw new Exception(String.format("Found Zip Path Traversal Vulnerability with %s", outputFileCanonicalPath));
        }
    }

    public static String getIPAddressList() {
        final List<String> list = new ArrayList<>();
        try {
            for (final Enumeration<NetworkInterface> enNetI = NetworkInterface.getNetworkInterfaces(); enNetI.hasMoreElements(); ) {
                final NetworkInterface netI = enNetI.nextElement();
                for (final Enumeration<InetAddress> enumIpAddr = netI.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    final InetAddress inetAddress = enumIpAddr.nextElement();
                    if (inetAddress instanceof Inet4Address && !inetAddress.isLoopbackAddress()) {
                        list.add(inetAddress.getHostAddress());
                    }
                }
            }
        } catch (final Exception e) {
            logError("network", "get IP list failed, returns 127.0.0.1", e);
        }
        list.add("127.0.0.1");
        return TextUtils.join(",", list);
    }

    public static void logError(final String tag, final String msg) {
        logError(tag, msg, null);
    }

    public static void logError(final String tag, final String msg, final Throwable e) {
        synchronized (Utils.class) {
            if (null != e) {
                Log.e(tag, msg, e);
            } else {
                Log.e(tag, msg);
            }
            try {
                final String workspacePath = Mobile.getCurrentWorkspacePath();
                if (StringUtils.isEmpty(workspacePath)) {
                    return;
                }

                final String mobileLogPath = workspacePath + "/temp/mobile.log";
                final File logFile = new File(mobileLogPath);
                if (logFile.exists() && 1024 * 1024 * 8 < logFile.length()) {
                    FileUtils.deleteQuietly(logFile);
                }

                final FileWriter writer = new FileWriter(logFile, true);
                final String time = TimeUtils.millis2String(System.currentTimeMillis(), "yyyy-MM-dd HH:mm:ss");
                writer.write("E " + time + " " + tag + " " + msg + "\n");
                if (null != e) {
                    writer.write(Log.getStackTraceString(e) + "\n");
                }
                writer.flush();
                writer.close();
            } catch (final Exception ex) {
                Log.e("logging", "Write mobile log failed", ex);
            }
        }
    }

    public static void logInfo(final String tag, final String msg) {
        synchronized (Utils.class) {
            Log.i(tag, msg);
            try {
                final String workspacePath = Mobile.getCurrentWorkspacePath();
                if (StringUtils.isEmpty(workspacePath)) {
                    return;
                }

                final String mobileLogPath = workspacePath + "/temp/mobile.log";
                final File logFile = new File(mobileLogPath);
                if (logFile.exists() && 1024 * 1024 * 8 < logFile.length()) {
                    FileUtils.deleteQuietly(logFile);
                }

                final FileWriter writer = new FileWriter(logFile, true);
                final String time = TimeUtils.millis2String(System.currentTimeMillis(), "yyyy-MM-dd HH:mm:ss");
                writer.write("I " + time + " " + tag + " " + msg + "\n");
                writer.flush();
                writer.close();
            } catch (final Exception ex) {
                Log.e("logging", "Write mobile log failed", ex);
            }
        }
    }

    /**
     * Checks if the current package name contains ".debug" and if debug mode is enabled.
     *
     * @param context The Android context used to retrieve the package information.
     * @return true if the package name contains ".debug" and debug mode is enabled, false otherwise.
     */
    public static boolean isDebugPackageAndMode(Context context) {
        PackageManager packageManager = context.getPackageManager();
        ApplicationInfo appInfo = null;
        try {
            appInfo = packageManager.getApplicationInfo(context.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        // Check if the package name contains ".debug"
        boolean isDebugPackage = context.getPackageName() != null && context.getPackageName().contains(".debug");
        boolean isDebugMode = appInfo != null && (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        return isDebugPackage && isDebugMode;
    }

    /**
     * Checks if the given string is a URL.
     *
     * @param str the string to check
     * @return <code>true</code> if the string is a URL, <code>false</code> otherwise
     */
    public static boolean isURL(String str) {
        try {
            new java.net.URL(str).toURI();
            return true;
        } catch (final Exception e) {
            return false;
        }
    }

    public static void openByDefaultBrowser(String url, final Activity activity) {
        if (StringUtils.isEmpty(url)) {
            return;
        }

        if (url.startsWith("#")) {
            return;
        }

        if (url.startsWith("/")) {
            url = "http://127.0.0.1:6806" + url;
        }

        if (url.startsWith("assets/")) {
            url = "http://127.0.0.1:6806/" + url;
        }

        // https://developer.android.google.cn/training/app-links/verify-android-applinks?hl=zh-cn
        // 从 Android 12 开始，经过验证的链接现在会自动在相应的应用中打开，以获得更简化、更快速的用户体验。谷歌还更改了未经Android应用链接验证或用户手动批准的链接的默认处理方式。谷歌表示，Android 12将始终在默认浏览器中打开此类未经验证的链接，而不是向您显示应用程序选择对话框。
        final Uri uri = Uri.parse(url);
        final Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
        activity.startActivity(browserIntent);
    }
}
