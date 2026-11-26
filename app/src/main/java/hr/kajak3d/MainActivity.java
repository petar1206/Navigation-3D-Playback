package hr.kajak3d;

import android.app.Activity;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Prima "Intent.OpenTracks-Dashboard" od OpenTracksa (ikona karte) i prikazuje turu u 3D.
 * Protokol je isti kao kod OSM Dashboarda: lista URI-ja [ture, točke traga, markeri],
 * privremeno pravo čitanja, i obavijesti o novim točkama dok traje snimanje.
 */
public class MainActivity extends Activity {

    private static final String TAG = "Kajak3D";
    private static final String ACTION_DASHBOARD = "Intent.OpenTracks-Dashboard";
    private static final String ACTION_DASHBOARD_PAYLOAD = ACTION_DASHBOARD + ".Payload";
    private static final String EXTRAS_PROTOCOL_VERSION = "PROTOCOL_VERSION";
    private static final String EXTRAS_IS_RECORDING = "EXTRAS_OPENTRACKS_IS_RECORDING_THIS_TRACK";
    private static final String EXTRAS_KEEP_SCREEN_ON = "EXTRAS_SHOULD_KEEP_SCREEN_ON";

    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + ASSET_HOST + "/index.html";
    private static final int REQ_FILE = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private WebView web;
    private boolean pageReady = false;
    private ValueCallback<Uri[]> fileCallback;

    // stanje čitanja iz OpenTracksa (koristi se samo na io dretvi)
    private Uri tracksUri;
    private Uri pointsUri;
    private int protocol = 1;
    private long lastPointId = 0;
    private long lastTrackId = Long.MIN_VALUE;
    private boolean afterPause = false;
    private int session = 0;
    private ContentObserver observer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!ASSET_HOST.equals(u.getHost())) return null; // karta i teren s interneta
                String path = u.getPath() == null ? "index.html" : u.getPath().replaceFirst("^/", "");
                if (path.isEmpty()) path = "index.html";
                try {
                    InputStream in = getAssets().open(path);
                    return new WebResourceResponse(mime(path), "utf-8", in);
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new java.util.HashMap<>(), null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (ASSET_HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!pageReady) {
                    pageReady = true;
                    handleIntent(getIntent());
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Odaberi KMZ, KML ili GPX"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl(START_URL);
    }

    private static String mime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (pageReady) handleIntent(intent);
    }

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent intent) {
        if (intent == null || !ACTION_DASHBOARD.equals(intent.getAction())) {
            return;
        }
        ArrayList<Uri> uris = intent.getParcelableArrayListExtra(ACTION_DASHBOARD_PAYLOAD);
        if (uris == null || uris.size() < 2) {
            js("KJ.error('OpenTracks nije poslao podatke o turi.')");
            return;
        }
        stopObserver();

        final boolean recording = intent.getBooleanExtra(EXTRAS_IS_RECORDING, false);
        boolean keepOn = intent.getBooleanExtra(EXTRAS_KEEP_SCREEN_ON, false);
        if (recording || keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (recording && Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
        }

        final Uri tUri = uris.get(0);
        final Uri pUri = uris.get(1);
        final int proto = intent.getIntExtra(EXTRAS_PROTOCOL_VERSION, 1);
        final int mySession = ++session;

        js("KJ.reset(" + recording + ")");
        io.execute(() -> {
            tracksUri = tUri;
            pointsUri = pUri;
            protocol = proto;
            lastPointId = 0;
            lastTrackId = Long.MIN_VALUE;
            afterPause = false;
            readTrackName(mySession);
            readPoints(true, mySession);
        });

        if (recording) {
            observer = new ContentObserver(main) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    io.execute(() -> readPoints(false, mySession));
                }
            };
            try {
                getContentResolver().registerContentObserver(pUri, false, observer);
            } catch (Exception e) {
                Log.w(TAG, "Ne mogu pratiti nove točke", e);
            }
        }
    }

    private void readTrackName(int mySession) {
        String name = null;
        try (Cursor c = getContentResolver().query(tracksUri, new String[]{"name"}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception e) {
            Log.w(TAG, "Ne mogu pročitati ime ture", e);
        }
        if (name != null && mySession == session) {
            js("KJ.setName(" + JSONObject.quote(name) + ")");
        }
    }

    private void readPoints(boolean first, int mySession) {
        if (mySession != session || pointsUri == null) return;
        String[] projection = protocol >= 2
                ? new String[]{"_id", "trackid", "latitude", "longitude", "time", "type"}
                : new String[]{"_id", "trackid", "latitude", "longitude", "time"};
        String selection = "_id> ?" + (protocol >= 2 ? " AND type IN (-2, -1, 0, 1, 3)" : "");

        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append('[');
        int n = 0;
        try (Cursor c = getContentResolver().query(pointsUri, projection, selection,
                new String[]{String.valueOf(lastPointId)}, null)) {
            if (c != null) {
                int iId = c.getColumnIndexOrThrow("_id");
                int iTrack = c.getColumnIndexOrThrow("trackid");
                int iLat = c.getColumnIndexOrThrow("latitude");
                int iLon = c.getColumnIndexOrThrow("longitude");
                int iTime = c.getColumnIndexOrThrow("time");
                int iType = c.getColumnIndex("type");
                while (c.moveToNext()) {
                    long id = c.getLong(iId);
                    if (id > lastPointId) lastPointId = id;
                    long trackId = c.getLong(iTrack);
                    int type = iType >= 0 && !c.isNull(iType) ? c.getInt(iType) : 0;
                    boolean hasPos = !c.isNull(iLat) && !c.isNull(iLon);
                    double lat = hasPos ? c.getInt(iLat) / 1E6 : 999;
                    double lon = hasPos ? c.getInt(iLon) / 1E6 : 999;
                    boolean valid = Math.abs(lat) <= 90 && Math.abs(lon) <= 180;

                    if (!valid) { // pauza ili točka bez GPS-a
                        if (type == 1 || lat == 100) afterPause = true;
                        continue;
                    }
                    boolean segStart = afterPause || trackId != lastTrackId || type == -2 || type == -1;
                    afterPause = (type == 1);
                    lastTrackId = trackId;

                    if (n++ > 0) sb.append(',');
                    sb.append('[')
                            .append(String.format(Locale.ROOT, "%.6f,%.6f,%.3f", lon, lat, c.getLong(iTime) / 1000.0))
                            .append(',').append(segStart ? 1 : 0).append(']');
                }
            }
        } catch (SecurityException e) {
            js("KJ.error('Nemam dopuštenje za čitanje ture. Otvorite je ponovno iz OpenTracksa.')");
            return;
        } catch (Exception e) {
            Log.w(TAG, "Greška pri čitanju točaka", e);
            if (first) js("KJ.error('Ne mogu pročitati točke ture.')");
            return;
        }
        sb.append(']');
        if (mySession != session) return;
        if (n > 0 || first) {
            js("KJ.addPoints(" + sb + "," + first + ")");
        }
    }

    private void js(String code) {
        main.post(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    private void stopObserver() {
        if (observer != null) {
            try {
                getContentResolver().unregisterContentObserver(observer);
            } catch (Exception ignored) {
            }
            observer = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopObserver();
        session++;
        io.shutdownNow();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
