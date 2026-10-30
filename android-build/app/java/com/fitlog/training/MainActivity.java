package com.fitlog.training;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

public class MainActivity extends Activity {

    static final int REQ_FILE = 1001;
    static final int REQ_BACKUP = 1002;

    WebView web;
    ValueCallback<Uri[]> pendingChooser;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        Window win = getWindow();
        win.setStatusBarColor(Color.parseColor("#F5F6F8"));
        win.setNavigationBarColor(Color.parseColor("#FFFFFF"));
        if (Build.VERSION.SDK_INT >= 23) {
            win.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setBackgroundColor(Color.parseColor("#F5F6F8"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new PlainClient());
        web.setWebChromeClient(new FileChooserClient(this));
        web.addJavascriptInterface(new Bridge(this), "AndroidBridge");

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    /* ---------------- 返回键：子页面回日历，日历页退出 ---------------- */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            web.evaluateJavascript(
                "(function(){try{return (typeof route!=='undefined'&&route&&route.name)?route.name:''}catch(e){return ''}})()",
                new BackCallback(this));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    void goCalendarOrExit(String rawName) {
        String name = rawName == null ? "" : rawName.replace("\"", "");
        if (name.isEmpty() || "calendar".equals(name)) {
            finish();
        } else {
            web.evaluateJavascript("go('calendar')", null);
        }
    }

    /* ---------------- 文件读写 ---------------- */

    File autoFile() {
        return new File(getFilesDir(), "autosave.json");
    }

    void writeText(File f, String txt) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(txt.getBytes(Charset.forName("UTF-8")));
        fo.close();
    }

    String readText(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            in.close();
            return new String(bo.toByteArray(), Charset.forName("UTF-8"));
        } catch (Exception e) {
            return "";
        }
    }

    String readUri(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            in.close();
            return new String(bo.toByteArray(), Charset.forName("UTF-8"));
        } catch (Exception e) {
            return "";
        }
    }

    void doAutoSave(String json) {
        if (json == null || json.length() < 10) {
            return;
        }
        try {
            writeText(autoFile(), json);
        } catch (Exception e) {
        }
    }

    void doSaveBackup(String name, String json) {
        String safe = (name == null || name.isEmpty()) ? "training-log-backup.json" : name;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) {
                    throw new Exception("insert failed");
                }
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(json.getBytes(Charset.forName("UTF-8")));
                os.close();
                toast("备份已存到「下载」文件夹：" + safe);
                return;
            }
            File f = new File(getExternalFilesDir(null), safe);
            writeText(f, json);
            toast("备份已保存：" + f.getAbsolutePath());
        } catch (Exception e) {
            try {
                File f = new File(getFilesDir(), safe);
                writeText(f, json);
                toast("备份已保存到应用目录：" + f.getAbsolutePath());
            } catch (Exception e2) {
                toast("备份保存失败：" + e2.getMessage());
            }
        }
    }

    void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /* ---------------- 静态嵌套类（避免非静态内部类） ---------------- */

    static class PlainClient extends WebViewClient {
    }

    static class FileChooserClient extends WebChromeClient {
        private final MainActivity act;

        FileChooserClient(MainActivity a) {
            act = a;
        }

        /* WebView 默认不弹 JS 对话框，不接管的话 confirm() 会直接当作「取消」，
           导入备份、删除计划这些确认都会被静默吞掉。 */
        @Override
        public boolean onJsAlert(WebView v, String url, String message, final JsResult result) {
            new AlertDialog.Builder(act)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("知道了", (d, w) -> result.confirm())
                .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView v, String url, String message, final JsResult result) {
            new AlertDialog.Builder(act)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("确定", (d, w) -> result.confirm())
                .setNegativeButton("取消", (d, w) -> result.cancel())
                .show();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (act.pendingChooser != null) {
                act.pendingChooser.onReceiveValue(null);
            }
            act.pendingChooser = cb;
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                act.startActivityForResult(i, REQ_FILE);
            } catch (Exception e) {
                act.pendingChooser = null;
                return false;
            }
            return true;
        }
    }

    static class BackCallback implements ValueCallback<String> {
        private final MainActivity act;

        BackCallback(MainActivity a) {
            act = a;
        }

        @Override
        public void onReceiveValue(String v) {
            act.goCalendarOrExit(v);
        }
    }

    public static class Bridge {
        private final MainActivity act;

        Bridge(MainActivity a) {
            act = a;
        }

        @JavascriptInterface
        public void autoSave(String json) {
            act.doAutoSave(json);
        }

        @JavascriptInterface
        public boolean hasAutosave() {
            File f = act.autoFile();
            return f.exists() && f.length() > 10;
        }

        @JavascriptInterface
        public String getAutosave() {
            return act.readText(act.autoFile());
        }

        @JavascriptInterface
        public void saveBackup(final String name, final String json) {
            final MainActivity a = act;
            a.runOnUiThread(() -> a.doSaveBackup(name, json));
        }

        @JavascriptInterface
        public void pickBackup() {
            final MainActivity a = act;
            a.runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    a.startActivityForResult(i, REQ_BACKUP);
                } catch (Exception e) {
                    a.toast("打不开文件选择器");
                }
            });
        }

        @JavascriptInterface
        public void toast(final String msg) {
            final MainActivity a = act;
            a.runOnUiThread(() -> a.toast(msg));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null && data.getData() != null) {
                out = new Uri[]{ data.getData() };
            }
            if (pendingChooser != null) {
                pendingChooser.onReceiveValue(out);
                pendingChooser = null;
            }
            return;
        }
        if (req == REQ_BACKUP) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                String txt = readUri(data.getData());
                if (txt.length() < 2) {
                    toast("读不到文件内容");
                    return;
                }
                String js = "window.__applyBackup && window.__applyBackup(" + org.json.JSONObject.quote(txt) + ")";
                web.evaluateJavascript(js, null);
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }
}
