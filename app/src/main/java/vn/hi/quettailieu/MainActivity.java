package vn.hi.quettailieu;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_CAM = 1, REQ_FILE = 2;
    private WebView web;
    private PermissionRequest pendingPerm;
    private ValueCallback<Uri[]> fileCb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        // Phục vụ file trong assets qua https (ngữ cảnh an toàn -> cho phép camera), chạy hoàn toàn offline
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(req.getResources());
                    } else {
                        pendingPerm = req;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(Intent.createChooser(i, "Chọn ảnh"), REQ_FILE); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
        }
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_CAM) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingPerm != null) {
                if (ok) pendingPerm.grant(pendingPerm.getResources()); else pendingPerm.deny();
                pendingPerm = null;
            } else if (ok) {
                web.reload();
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_FILE || fileCb == null) return;
        Uri[] uris = null;
        if (result == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                uris = new Uri[n];
                for (int k = 0; k < n; k++) uris[k] = data.getClipData().getItemAt(k).getUri();
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
        }
        fileCb.onReceiveValue(uris);
        fileCb = null;
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.onAndroidBack && window.onAndroidBack()) ? 'y' : 'n'", v -> {
            if (v == null || !v.contains("y")) MainActivity.super.onBackPressed();
        });
    }

    /** Cầu nối JS: lưu file vào thư mục Download/QuetTaiLieu (nhận theo từng phần để không tràn bộ nhớ) */
    class Bridge {
        private final java.util.HashMap<String, File> tmpFiles = new java.util.HashMap<>();
        private final java.util.HashMap<String, String[]> meta = new java.util.HashMap<>();
        private int seq = 0;

        @JavascriptInterface
        public synchronized String beginFile(String name, String mime) {
            try {
                String id = "f" + (++seq);
                File t = new File(getCacheDir(), "up_" + id + ".tmp");
                if (t.exists()) t.delete();
                tmpFiles.put(id, t);
                meta.put(id, new String[]{name, mime});
                return id;
            } catch (Throwable e) { return "ERR: " + e; }
        }

        @JavascriptInterface
        public synchronized String appendChunk(String id, String b64) {
            try {
                File t = tmpFiles.get(id);
                if (t == null) return "ERR: không có file";
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                try (FileOutputStream os = new FileOutputStream(t, true)) { os.write(bytes); }
                return "OK";
            } catch (Throwable e) { return "ERR: " + e; }
        }

        @JavascriptInterface
        public synchronized String finishFile(String id) {
            File t = tmpFiles.remove(id);
            String[] m = meta.remove(id);
            if (t == null || m == null) return "ERR: không có file";
            try {
                return writeOut(m[0], m[1], t);
            } catch (Throwable e) {
                return "ERR: " + e;
            } finally {
                t.delete();
            }
        }

        /** cách cũ (file nhỏ) - giữ để tương thích */
        @JavascriptInterface
        public String saveFile(String name, String mime, String b64) {
            try {
                String id = beginFile(name, mime);
                String r = appendChunk(id, b64);
                if (r.startsWith("ERR")) return r;
                return finishFile(id);
            } catch (Throwable e) { return "ERR: " + e; }
        }

        private String writeOut(String name, String mime, File src) throws Exception {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/QuetTaiLieu");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return "ERR: không tạo được file";
                try (OutputStream os = getContentResolver().openOutputStream(uri);
                     java.io.FileInputStream in = new java.io.FileInputStream(src)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            } else {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 3));
                    return "ERR: cần cấp quyền bộ nhớ, bấm Lưu lại";
                }
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "QuetTaiLieu");
                dir.mkdirs();
                try (FileOutputStream os = new FileOutputStream(new File(dir, name));
                     java.io.FileInputStream in = new java.io.FileInputStream(src)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
            return "Download/QuetTaiLieu/" + name;
        }
    }
}
