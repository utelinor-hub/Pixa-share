package com.shivam.pixashare;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
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
import java.net.URLDecoder;
import java.util.ArrayList;

/**
 * PIXA SHARE — the real Android app.
 *
 * A full-screen WebView (part of Android itself, NOT the user's browser)
 * that runs the PIXA SHARE app. Nothing about the app changes — the same
 * page, the same Google Drive protocol — but the phone now treats it as
 * a native application:
 *
 *  - own icon + name on the home screen, opens full-screen (no URL bar,
 *    no tabs, no browser UI of any kind)
 *  - Gallery -> Share -> PIXA SHARE lands here as a REAL app target
 *    (ACTION_SEND / ACTION_SEND_MULTIPLE); photos are copied into the
 *    app cache and handed to the page through a tiny JS bridge that
 *    feeds the page's own hidden file input — the exact same queue,
 *    progress list and Drive upload path as picking by hand
 *  - the page's "Pick photos to send" button opens the Android photo
 *    picker (onShowFileChooser)
 *  - Google sign-in happens inside the app: the WebView has no popup
 *    windows, so the page runs the OAuth as a FULL PAGE — the bridge
 *    (openAuth) loads accounts.google.com here, Google finishes by
 *    redirecting to the storagerelay:// URI (the very one the GIS popup
 *    itself uses), we intercept it, take the access token out of it and
 *    hand it back to the page (__pixaAuthDeliver). v1.4.0.
 *  - service worker + IndexedDB keep working, so offline start and the
 *    update pipeline behave like on Chrome
 */
public class MainActivity extends Activity {

    private static final String APP_URL = "https://utelinor-hub.github.io/Pixa-share/";
    private static final int REQ_PICK = 100;

    private WebView web;
    private ValueCallback<Uri[]> fileCb;

    /* in-app Google sign-in state (v1.4.x) */
    private String pendingAuthJson = null;   /* token/error waiting to reach the page */
    private boolean authStarted = false;     /* a Google auth page is (probably) showing   */
    private boolean authRetry = false;       /* recovery reload for the auth response    */
    private final Handler authWatch = new Handler();
    private int authGen = 0;                 /* cancels the watchdog when superseded     */

    /* shared-photo handoff queue (file names inside cacheDir/shared) */
    private final ArrayList<String> pendingNames = new ArrayList<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0D3B66"));
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);        /* IndexedDB queue + kv store */
        s.setDatabaseEnabled(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);              /* window.open must work */
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        /* plain Chrome mobile UA — Google sign-in refuses embedded WebViews
           that identify themselves as WebView */
        s.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                /* Google OAuth (in-app sign-in, v1.4.0) finishes by
                   redirecting to storagerelay:// — grab the token out of it
                   and take the user back to the app. MUST be checked before
                   the host rules below (storagerelay has no host). */
                if (url != null && url.startsWith("storagerelay://")) {
                    collectAuthResponse(url);
                    return true;
                }
                String h = Uri.parse(url).getHost();
                if (h == null) h = "";
                /* the app itself + Google sign-in/Drive stay inside the app;
                   everything else (help pages etc.) opens outside */
                if (h.equals("utelinor-hub.github.io")
                        || h.endsWith("google.com")
                        || h.endsWith("googleapis.com")
                        || h.endsWith("gstatic.com")
                        || h.endsWith("googleusercontent.com")) {
                    return false;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                drainShared();
                if (url != null && url.startsWith(APP_URL)) {
                    authStarted = false;
                    authRetry = false;
                    authGen++;                       /* cancels the watchdog */
                    if (pendingAuthJson != null) {
                        String j = pendingAuthJson;
                        pendingAuthJson = null;
                        web.evaluateJavascript("window.__pixaAuthDeliver(" + j + ");", null);
                    }
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                /* recover from network drops during sign-in: go back to the
                   app and let the page show its usual "press Sign in again" */
                if (!req.isForMainFrame()) return;
                if (pendingAuthJson != null && !authRetry) {
                    authRetry = true;                       /* token in hand, app reload hiccuped */
                    web.loadUrl(APP_URL);
                } else if (pendingAuthJson == null && authStarted) {
                    authStarted = false;                    /* the Google page itself failed */
                    pendingAuthJson = "{\"error\":\"network\"}";
                    authRetry = true;
                    web.loadUrl(APP_URL);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          Message resultMsg) {
                /* window.open() — route the popup's URL into the main app
                   view (the WebView has no real popup windows) */
                WebView tmp = new WebView(view.getContext());
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v2, String u2) {
                        /* the OAuth response must go through the collector —
                           web.loadUrl() would BYPASS the main client's
                           shouldOverrideUrlLoading and die on the unknown
                           scheme (v1.4.1 fix) */
                        if (u2 != null && u2.startsWith("storagerelay://")) {
                            collectAuthResponse(u2);
                            return true;
                        }
                        web.loadUrl(u2);
                        return true;
                    }
                });
                WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
                t.setWebView(tmp);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("image/*");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(i, "Select photos"), REQ_PICK);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "pixaNative");

        /* the in-app "Download pixa-share.apk" link — a WebView would try to
           render the file inline, so hand downloads to the system (Chrome
           downloads it, then Android offers the install) */
        web.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception ignored) {}
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(APP_URL);
        }

        handleSend(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSend(intent);
    }

    /* ---------- Gallery -> Share -> PIXA SHARE ---------- */

    private void handleSend(Intent i) {
        if (i == null || i.getAction() == null || i.getType() == null) return;
        if (!i.getType().startsWith("image/")) return;

        ArrayList<Uri> uris = new ArrayList<Uri>();
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
            ArrayList<Uri> list = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else {
            return;
        }
        if (uris.isEmpty()) return;

        File dir = new File(getCacheDir(), "shared");
        dir.mkdirs();
        File[] stale = dir.listFiles();
        if (stale != null) for (File f : stale) f.delete();
        pendingNames.clear();

        long stamp = System.currentTimeMillis();
        int n = 0, failed = 0;
        for (Uri u : uris) {
            try {
                String name = queryName(u);
                if (name == null || name.trim().length() == 0) name = "photo-" + stamp + "-" + n + ".jpg";
                name = stamp + "-" + n + "-" + name.replaceAll("[^\\w.\\- ]+", "_");
                if (!name.toLowerCase().matches(".*\\.(jpe?g|png|gif|webp|bmp|avif)$")) name += ".jpg";
                String mime = getTypeSafe(u);
                InputStream in = getContentResolver().openInputStream(u);
                OutputStream os = new FileOutputStream(new File(dir, name));
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                os.close();
                in.close();
                pendingNames.add(name + "\n" + mime);
                n++;
            } catch (Exception e) {
                failed++;
            }
        }
        if (failed > 0) Toast.makeText(this, "Could not read " + failed + " shared photo(s)", Toast.LENGTH_SHORT).show();
        if (n > 0) {
            Toast.makeText(this, "PIXA SHARE — " + n + " photo(s) added to the send queue", Toast.LENGTH_LONG).show();
            drainShared();
        }
    }

    /* ---------- in-app Google sign-in (v1.4.0, full-page OAuth) ----------

       Flow: page -> pixaNative.openAuth(authUrl) -> we load accounts.google.com
       -> user signs in / consents -> Google redirects to
       storagerelay://https/utelinor-hub.github.io?id=...#access_token=...&expires_in=...
       (exactly what the GIS popup would receive) -> shouldOverrideUrlLoading
       catches it -> collectAuthResponse() parses the token -> we load the app
       again -> onPageFinished hands the token to window.__pixaAuthDeliver(). */

    /** openAuth() bridge — the page asks us to show the Google auth page. */
    private void openAuthUrl(String url) {
        if (url == null || !url.startsWith("https://accounts.google.com/o/oauth2/")) return;
        authStarted = true;
        authRetry = false;
        /* watchdog: if Google's pages ever strand the user (a hung silent
           check, a relay page that never navigates — the v1.4.0 blank dark
           screen), bring them back to the app. Silent renew (prompt=none)
           should bounce in seconds; interactive sign-in gets real time. */
        final int gen = ++authGen;
        authWatch.postDelayed(new Runnable() { public void run() {
            if (gen != authGen || !authStarted || web == null) return;
            authStarted = false;
            web.loadUrl(APP_URL);
        }}, url.contains("prompt=none") ? 30000L : 180000L);
        web.loadUrl(url);
    }

    /** Parse the storagerelay:// redirect: token in, JSON payload out. */
    private void collectAuthResponse(String url) {
        String frag = "";
        int hash = url.indexOf('#');
        if (hash >= 0) {
            frag = url.substring(hash + 1);
        } else {
            int q = url.indexOf('?');
            if (q >= 0) {
                frag = url.substring(q + 1);
                int h2 = frag.indexOf('#');
                if (h2 >= 0) frag = frag.substring(0, h2);
            }
        }
        String token = qsParam(frag, "access_token");
        String json;
        if (token != null && token.length() > 0) {
            String exp = qsParam(frag, "expires_in");
            String scope = qsParam(frag, "scope");
            String state = qsParam(frag, "state");
            StringBuilder sb = new StringBuilder("{\"access_token\":").append(jsonEsc(token));
            if (exp != null) {
                String digits = exp.replaceAll("[^0-9]", "");
                if (digits.length() > 0) sb.append(",\"expires_in\":").append(digits);
            }
            if (scope != null) sb.append(",\"scope\":").append(jsonEsc(scope));
            if (state != null) sb.append(",\"state\":").append(jsonEsc(state));
            sb.append('}');
            json = sb.toString();
        } else {
            String err = qsParam(frag, "error");
            json = "{\"error\":" + jsonEsc(err == null || err.length() == 0 ? "login_required" : err) + "}";
        }
        authStarted = false;
        authGen++;                               /* cancels the watchdog */
        pendingAuthJson = json;
        web.loadUrl(APP_URL);   /* back to the app; delivered in onPageFinished */
    }

    /** "a=b&c=d" -> value of "name", URL-decoded; null when absent. */
    private static String qsParam(String qs, String name) {
        for (String kv : qs.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            if (!kv.substring(0, eq).trim().equals(name)) continue;
            try { return URLDecoder.decode(kv.substring(eq + 1), "UTF-8"); }
            catch (Exception e) { return kv.substring(eq + 1); }
        }
        return null;
    }

    /** JSON string literal with quotes (safe for tokens/scope/error text). */
    private static String jsonEsc(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 32) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private String queryName(Uri u) {
        try {
            Cursor c = getContentResolver().query(u, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                String nm = (idx >= 0 && c.moveToFirst()) ? c.getString(idx) : null;
                c.close();
                if (nm != null) return nm;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getTypeSafe(Uri u) {
        try {
            String t = getContentResolver().getType(u);
            if (t != null && t.startsWith("image/")) return t;
        } catch (Exception ignored) {}
        return "image/jpeg";
    }

    /**
     * Push every queued shared photo into the page: a tiny injected loop
     * asks the bridge for one file at a time and feeds it to the page's
     * hidden <input type=file id=file> via DataTransfer — the page's own
     * change handler (onPick) does the rest. Same path as a hand pick.
     */
    private void drainShared() {
        if (pendingNames.isEmpty() || web == null) return;
        web.evaluateJavascript(
            "(function(){if(!window.pixaNative)return;function d(){try{while(true){var raw=window.pixaNative.takeFile();" +
            "if(!raw||raw==='null')break;var o=JSON.parse(raw);var bin=atob(o.b64);" +
            "var u8=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)u8[i]=bin.charCodeAt(i);" +
            "var dt=new DataTransfer();dt.items.add(new File([u8],o.name,{type:o.mime||'image/jpeg'}));" +
            "var inp=document.getElementById('file');if(!inp)break;inp.files=dt.files;" +
            "inp.dispatchEvent(new Event('change',{bubbles:true}));}}catch(e){}}" +
            "if(document.readyState==='complete')d();else window.addEventListener('load',d);})();", null);
    }

    /**
     * JS bridge — one shared photo per call, base64 in JSON.
     * Reads run on the WebView's JavaBridge thread; the file is deleted
     * only after it has been handed over, so nothing is lost if the page
     * reloads mid-transfer.
     */
    private class Bridge {
        @JavascriptInterface
        public void openAuth(final String url) {
            /* full-page Google OAuth for the in-app sign-in (v1.4.0) */
            runOnUiThread(new Runnable() { public void run() { openAuthUrl(url); } });
        }

        @JavascriptInterface
        public String takeFile() {
            String entry;
            synchronized (pendingNames) {
                if (pendingNames.isEmpty()) return "null";
                entry = pendingNames.remove(0);
            }
            int nl = entry.indexOf('\n');
            String name = entry.substring(0, nl);
            String mime = entry.substring(nl + 1);
            try {
                File f = new File(getCacheDir(), "shared/" + name);
                FileInputStream in = new FileInputStream(f);
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) raw.write(buf, 0, r);
                in.close();
                String b64 = Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP);
                f.delete();
                return "{\"name\":" + jsonEsc(name) + ",\"mime\":" + jsonEsc(mime) + ",\"b64\":\"" + b64 + "\"}";
            } catch (Exception e) {
                return "null";
            }
        }
    }

    /* ---------- photo picker result ---------- */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK && fileCb != null) {
            /* parse the picker result by hand — works for single and
               multi selection on every Android version */
            Uri[] out = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData cp = data.getClipData();
                if (cp != null) {
                    ArrayList<Uri> us = new ArrayList<Uri>();
                    for (int i = 0; i < cp.getItemCount(); i++) us.add(cp.getItemAt(i).getUri());
                    out = us.toArray(new Uri[us.size()]);
                } else if (data.getData() != null) {
                    out = new Uri[]{ data.getData() };
                }
            }
            fileCb.onReceiveValue(out);
            fileCb = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /* ---------- lifecycle ---------- */

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
