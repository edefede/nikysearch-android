package it.nikysearch.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.MotionEvent;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/* App non ufficiale: una WebView che apre nikysearch.it. Le pagine del sito
 * restano nell'app, ogni altro link (risultati, fonti) va nel browser. */
public class MainActivity extends Activity {
    private static final String HOME = "https://nikysearch.it/";
    private static final String HOST = "nikysearch.it";

    private WebView web;
    private PermissionRequest pending;      // richiesta fotocamera/microfono in attesa della risposta di Android
    private static final int REQ_MEDIA = 1;
    private static final int REQ_FILES = 2;
    private ValueCallback<Uri[]> chooser;   // risposta al selettore file della pagina (APRI, APRI CARTELLA)
    private float pullFromY = -1;   // y del tocco iniziale, se la pagina era in cima

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // localStorage: tema e sfondo salvati
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        /* Download: la pagina genera PDF/txt come blob: (nessun URL di rete), che la
         * WebView da sola non salva. Si rilegge il blob dalla pagina, lo si passa a
         * Java in base64 e lo si scrive in Download via MediaStore. */
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void save(String name, String mime, String b64) {
                try {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    try (OutputStream o = getContentResolver().openOutputStream(u)) {
                        o.write(Base64.decode(b64, Base64.DEFAULT));
                    }
                    toast("Salvato in Download: " + name);
                } catch (Exception e) {
                    toast("Salvataggio non riuscito");
                }
            }
        }, "AndroidSave");

        web.setDownloadListener((url, ua, disp, mime, len) -> {
            if (!url.startsWith("blob:")) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); return; }
            String name = URLUtil.guessFileName(url, disp, mime);
            web.evaluateJavascript("fetch(" + org.json.JSONObject.quote(url) + ").then(r=>r.blob()).then(b=>{"
                + "const f=new FileReader();f.onload=()=>AndroidSave.save(" + org.json.JSONObject.quote(name) + ","
                + "b.type||" + org.json.JSONObject.quote(mime) + ",f.result.split(',')[1]);f.readAsDataURL(b);})", null);
        });

        /* Tira-giù-per-aggiornare, senza librerie: se la pagina è in cima e il dito
         * scende di ~100 dp, ricarica. La soglia alta evita falsi scatti mentre si
         * scorre dentro pannelli e risultati. */
        final float threshold = 100 * getResources().getDisplayMetrics().density;
        web.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pullFromY = web.getScrollY() == 0 ? e.getY() : -1;
                    break;
                case MotionEvent.ACTION_UP:
                    if (pullFromY >= 0 && web.getScrollY() == 0 && e.getY() - pullFromY > threshold) {
                        Toast.makeText(this, "Aggiorno…", Toast.LENGTH_SHORT).show();
                        web.reload();
                    }
                    pullFromY = -1;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    pullFromY = -1;
                    break;
            }
            return false;   // il gesto resta anche al WebView (scorrimento normale)
        });

        /* Fotocamera (OrgScan, /orgscan/) e microfono (ricerca vocale, che gira nel
         * browser: l'audio non esce dal dispositivo). Solo per le pagine di
         * nikysearch.it; Android chiede all'utente il permesso la prima volta. */
        web.setWebChromeClient(new WebChromeClient() {
            /* Selettore file per <input type="file"> (APRI, APRI CARTELLA di OrgScan).
             * La WebView non gestisce le cartelle (webkitdirectory): si scelgono più immagini. */
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILES);
                } catch (Exception e) {
                    chooser = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest r) {
                if (!HOST.equals(r.getOrigin().getHost())) { r.deny(); return; }
                List<String> res = new ArrayList<>();
                List<String> need = new ArrayList<>();
                for (String x : r.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(x)) {
                        res.add(x);
                        if (!has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
                    } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(x)) {
                        res.add(x);
                        if (!has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
                    }
                }
                if (res.isEmpty()) { r.deny(); return; }
                if (need.isEmpty()) { r.grant(res.toArray(new String[0])); return; }
                if (pending != null) pending.deny();
                pending = r;
                requestPermissions(need.toArray(new String[0]), REQ_MEDIA);
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, u));
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame())
                    v.loadData("<body style='font-family:sans-serif;text-align:center;margin-top:30vh'>"
                             + "<h3>Nessuna connessione</h3><p><a href='" + HOME + "'>Riprova</a></p></body>",
                               "text/html", "utf-8");
            }
        });

        if (state != null) web.restoreState(state);
        else web.loadUrl(HOME);
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code != REQ_FILES || chooser == null) return;
        chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
        chooser = null;
    }

    private void toast(String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
    }

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_MEDIA || pending == null) return;
        List<String> ok = new ArrayList<>();
        for (String x : pending.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(x) && has(Manifest.permission.CAMERA)) ok.add(x);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(x) && has(Manifest.permission.RECORD_AUDIO)) ok.add(x);
        }
        if (ok.isEmpty()) pending.deny();
        else pending.grant(ok.toArray(new String[0]));
        pending = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
