package com.gymregistro.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.JavascriptInterface;

import org.json.JSONException;
import org.json.JSONObject;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.text.TextUtils;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.view.HapticFeedbackConstants;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import java.util.Collections;
import java.util.List;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    static final String PREFS = "gym_registro";
    static final String CLAVE_URL = "app_url";

    private WebView web;
    private ProgressBar progreso;
    private LinearLayout sinConexion, configurar;
    private ImageButton escanear;
    private String urlBase = "";
    private long ultimoAtras = 0;
    private boolean errorPrincipal = false;
    /** Código escaneado esperando a que la página lo recoja (GymApp.tomarCodigo). */
    private volatile String codigoPendiente = "";
    private View panelEscaner, lineaEscaner;
    private BarcodeView barcode;
    private ObjectAnimator animLinea;
    private boolean botonQRQuerido = true;
    private static final int PERMISO_CAMARA = 78;
    /** Botón atrás pendiente de que la página lo atienda (GymApp.tomarAtras). */
    private volatile boolean atrasPendiente = false;
    private volatile long ultimaConsultaAtras = 0;
    private final android.os.Handler manejador = new android.os.Handler(android.os.Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.web);
        progreso = findViewById(R.id.progreso);
        sinConexion = findViewById(R.id.sinConexion);
        configurar = findViewById(R.id.configurar);
        escanear = findViewById(R.id.escanear);
        panelEscaner = findViewById(R.id.panelEscaner);
        barcode = findViewById(R.id.barcode);
        lineaEscaner = findViewById(R.id.lineaEscaner);
        barcode.setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        findViewById(R.id.cancelarEscaner).setOnClickListener(v -> cerrarEscaner());

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(true);
        // Google trata distinto a los "WebView"; nos presentamos como el navegador Chrome normal del teléfono.
        String ua = s.getUserAgentString();
        if (ua != null) s.setUserAgentString(ua.replace("; wv)", ")").replace(" Version/4.0", ""));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new ClienteWeb());
        web.setWebChromeClient(new ClienteChrome());
        // Puente para que la web active las notificaciones de pago en este móvil
        web.addJavascriptInterface(new PuenteApp(), "GymApp");
        Avisos.crearCanal(this);
        if (Avisos.hayDispositivo(this)) Avisos.programar(this);

        findViewById(R.id.reintentar).setOnClickListener(v -> cargarInicio());
        ((Button) findViewById(R.id.guardarEnlace)).setOnClickListener(v -> guardarEnlace());
        escanear.setOnClickListener(v -> abrirEscaner());
        escanear.setOnLongClickListener(v -> { pedirCambioEnlace(); return true; });

        urlBase = leerUrlBase();
        if (TextUtils.isEmpty(urlBase)) {
            mostrarConfigurar();
        } else if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else if (!abrirDesdeNotificacion(getIntent())) {
            cargarInicio();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        abrirDesdeNotificacion(intent);
    }

    /** Al tocar "Ver perfil" en una notificación se abre directamente el perfil del cliente. */
    private boolean abrirDesdeNotificacion(Intent intent) {
        if (intent == null) return false;
        String gym = intent.getStringExtra(Avisos.EXTRA_GYM), codigo = intent.getStringExtra(Avisos.EXTRA_CODIGO);
        if (TextUtils.isEmpty(gym) || TextUtils.isEmpty(codigo) || TextUtils.isEmpty(urlBase)) return false;
        intent.removeExtra(Avisos.EXTRA_CODIGO);
        cargar(urlBase + "?g=" + Uri.encode(gym) + "&c=" + Uri.encode(codigo));
        return true;
    }

    /* ---------- Puente JavaScript ↔ Android ---------- */
    private class PuenteApp {
        @JavascriptInterface
        public boolean tieneDispositivo(String id) {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            return id != null && id.equals(p.getString(Avisos.DEV_ID, "")) && !TextUtils.isEmpty(p.getString(Avisos.DEV_CLAVE, ""));
        }

        @JavascriptInterface
        public String claveActual() {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString(Avisos.DEV_CLAVE, "");
        }

        @JavascriptInterface
        public void guardarDispositivo(String json) {
            try {
                JSONObject o = new JSONObject(json);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(Avisos.DEV_ID, o.optString("id"))
                        .putString(Avisos.DEV_GYM, o.optString("gym"))
                        .putString(Avisos.DEV_CLAVE, o.optString("clave"))
                        .putString(Avisos.DEV_NOMBRE, o.optString("nombre"))
                        .apply();
            } catch (JSONException e) {
                return;
            }
            runOnUiThread(() -> {
                pedirPermisoNotificaciones();
                Avisos.programar(MainActivity.this);
                Avisos.revisarAhora(MainActivity.this);
            });
        }

        @JavascriptInterface
        public void borrarDispositivo() {
            Avisos.olvidar(MainActivity.this);
        }

        /** La página recoge aquí el último código escaneado (y se borra al leerlo). */
        @JavascriptInterface
        public String tomarCodigo() {
            String c = codigoPendiente;
            codigoPendiente = "";
            return c;
        }

        /** La página pregunta si se pulsó el botón atrás del teléfono. */
        @JavascriptInterface
        public boolean tomarAtras() {
            ultimaConsultaAtras = System.currentTimeMillis();
            boolean a = atrasPendiente;
            atrasPendiente = false;
            return a;
        }

        /** La página estaba ya en el Inicio: no tiene adónde volver. */
        @JavascriptInterface
        public void atrasNoManejado() {
            runOnUiThread(MainActivity.this::salirConDobleAtras);
        }

        /** La página (por ejemplo, el modo puerta) pide abrir la cámara. */
        @JavascriptInterface
        public void escanear() {
            runOnUiThread(MainActivity.this::abrirEscaner);
        }

        @JavascriptInterface
        public void mostrarBotonQR(boolean visible) {
            runOnUiThread(() -> { botonQRQuerido = visible; actualizarBotonQR(); });
        }
    }

    private void pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
        }
    }

    /* ---------- Enlace de la plataforma ---------- */
    private String leerUrlBase() {
        String guardada = getSharedPreferences(PREFS, MODE_PRIVATE).getString(CLAVE_URL, "");
        if (!TextUtils.isEmpty(guardada)) return guardada;
        return getString(R.string.app_url).trim();
    }

    private String urlInicio() {
        String gym = getString(R.string.gym_code).trim().toUpperCase();
        if (TextUtils.isEmpty(gym) || urlBase.contains("?")) return urlBase;
        return urlBase + "?g=" + Uri.encode(gym);
    }

    private boolean enlaceValido(String u) {
        return u.startsWith("https://script.google.com/") && u.contains("/exec");
    }

    private void guardarEnlace() {
        String u = ((EditText) findViewById(R.id.enlace)).getText().toString().trim();
        int q = u.indexOf('?');
        String base = q >= 0 ? u.substring(0, q) : u;
        if (!enlaceValido(base)) {
            Toast.makeText(this, "El enlace debe empezar por https://script.google.com/ y terminar en /exec", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(CLAVE_URL, base).apply();
        urlBase = base;
        configurar.setVisibility(View.GONE);
        if (q >= 0) cargar(u); else cargarInicio();
    }

    private void mostrarConfigurar() {
        configurar.setVisibility(View.VISIBLE);
        sinConexion.setVisibility(View.GONE);
        escanear.setVisibility(View.GONE);
        ((EditText) findViewById(R.id.enlace)).setText(urlBase);
    }

    private void pedirCambioEnlace() {
        new AlertDialog.Builder(this)
                .setTitle("Cambiar enlace de la plataforma")
                .setMessage("¿Quieres pegar otro enlace? Úsalo solo si el administrador te dio uno nuevo.")
                .setPositiveButton("Cambiar", (d, w) -> mostrarConfigurar())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /* ---------- Carga y red ---------- */
    private void cargarInicio() {
        cargar(urlInicio());
    }

    private void cargar(String url) {
        if (!hayInternet()) {
            mostrarSinConexion();
            return;
        }
        errorPrincipal = false;
        sinConexion.setVisibility(View.GONE);
        actualizarBotonQR();
        web.loadUrl(url);
    }

    private void mostrarSinConexion() {
        sinConexion.setVisibility(View.VISIBLE);
        escanear.setVisibility(View.GONE);
        progreso.setVisibility(View.GONE);
    }

    private boolean hayInternet() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /**
     * Decide qué se abre FUERA de la app. Todo lo demás (Google Apps Script, sus redirecciones,
     * marcos internos, about:blank, etc.) se queda SIEMPRE dentro de la app.
     * Solo salen: WhatsApp, teléfono, correo, SMS, otras apps y la hoja de Google (docs.google.com).
     */
    private boolean debeAbrirseFuera(Uri u) {
        String esquema = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if (esquema.equals("http") || esquema.equals("https")) {
            return host.equals("wa.me") || host.equals("api.whatsapp.com") || host.equals("chat.whatsapp.com")
                    || host.equals("docs.google.com") || host.equals("drive.google.com");
        }
        if (esquema.equals("about") || esquema.equals("data") || esquema.equals("blob")
                || esquema.equals("javascript") || esquema.isEmpty()) return false;
        return true; // tel:, mailto:, sms:, whatsapp:, intent:, etc.
    }

    private void abrirFuera(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No hay ninguna app para abrir este enlace.", Toast.LENGTH_SHORT).show();
        }
    }

    private class ClienteWeb extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // Los marcos internos de la página nunca se sacan de la app.
            if (!request.isForMainFrame()) return false;
            Uri u = request.getUrl();
            if (!debeAbrirseFuera(u)) return false;
            abrirFuera(u);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            progreso.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progreso.setVisibility(View.GONE);
            if (!errorPrincipal) sinConexion.setVisibility(View.GONE);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                errorPrincipal = true;
                mostrarSinConexion();
            }
        }
    }

    private class ClienteChrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int nuevo) {
            progreso.setProgress(nuevo);
        }

        /** Enlaces con target="_blank" (WhatsApp, hoja de Google): se capturan y se abren fuera de la app. */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            WebView temporal = new WebView(MainActivity.this);
            temporal.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    Uri u = request.getUrl();
                    if (debeAbrirseFuera(u)) abrirFuera(u); else web.loadUrl(u.toString());
                    v.destroy();
                    return true;
                }
            });
            WebView.WebViewTransport transporte = (WebView.WebViewTransport) resultMsg.obj;
            transporte.setWebView(temporal);
            resultMsg.sendToTarget();
            return true;
        }
    }

    /* ---------- Escáner en un cuadro, dentro de la app y en vertical ---------- */
    private boolean escanerAbierto() {
        return panelEscaner.getVisibility() == View.VISIBLE;
    }

    private void actualizarBotonQR() {
        boolean ver = botonQRQuerido && !escanerAbierto() && configurar.getVisibility() != View.VISIBLE
                && sinConexion.getVisibility() != View.VISIBLE;
        escanear.setVisibility(ver ? View.VISIBLE : View.GONE);
    }

    private void abrirEscaner() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, PERMISO_CAMARA);
            return;
        }
        if (escanerAbierto()) return;
        panelEscaner.setVisibility(View.VISIBLE);
        actualizarBotonQR();
        barcode.resume();
        barcode.decodeSingle(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (result == null || result.getText() == null) return;
                avisoLectura();
                cerrarEscaner();
                abrirCarne(result.getText().trim());
            }

            @Override
            public void possibleResultPoints(List<ResultPoint> resultPoints) { }
        });
        // Línea roja que sube y baja dentro del cuadro
        lineaEscaner.post(() -> {
            float alto = ((View) lineaEscaner.getParent()).getHeight();
            animLinea = ObjectAnimator.ofFloat(lineaEscaner, "translationY", alto * 0.12f, alto * 0.88f);
            animLinea.setDuration(1600);
            animLinea.setRepeatMode(ValueAnimator.REVERSE);
            animLinea.setRepeatCount(ValueAnimator.INFINITE);
            animLinea.start();
        });
    }

    private void cerrarEscaner() {
        if (!escanerAbierto()) return;
        barcode.pause();
        if (animLinea != null) animLinea.cancel();
        panelEscaner.setVisibility(View.GONE);
        actualizarBotonQR();
    }

    private void avisoLectura() {
        try {
            panelEscaner.performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.LONG_PRESS);
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
            manejador.postDelayed(tg::release, 400);
        } catch (Exception ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISO_CAMARA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            abrirEscaner();
        } else {
            Toast.makeText(this, "Sin permiso de cámara no se puede escanear. Actívalo en Ajustes > Apps > Gym Registro > Permisos.", Toast.LENGTH_LONG).show();
        }
    }

    private void abrirCarne(String texto) {
        // Lo normal: se le pasa el código a la página abierta, que abre el perfil (o marca la entrada en modo puerta)
        // sin recargar nada. Si en 3 segundos la página no lo recogió, se usa el método de respaldo.
        codigoPendiente = texto;
        manejador.postDelayed(() -> {
            if (texto.equals(codigoPendiente)) {
                codigoPendiente = "";
                abrirCarneRecargando(texto);
            }
        }, 3000);
    }

    private void abrirCarneRecargando(String texto) {
        // Caso 1: el QR trae un enlace con ?g=IRON&c=GM-XXXXXX. Se usan solo esos dos datos y se abren
        // con el enlace de ESTA app (así funciona aunque el QR se haya generado con un enlace /dev o antiguo).
        if (texto.startsWith("http")) {
            Uri q = Uri.parse(texto);
            String c = q.getQueryParameter("c"), g = q.getQueryParameter("g");
            if (!TextUtils.isEmpty(c) && !TextUtils.isEmpty(urlBase)) {
                String url = urlBase + "?c=" + Uri.encode(c.toUpperCase());
                if (!TextUtils.isEmpty(g)) url += "&g=" + Uri.encode(g.toUpperCase());
                cargar(url);
                return;
            }
        }
        // Caso 2: el QR trae solo "GIMNASIO CODIGO", por ejemplo "IRON GM-7K4QX9"
        Matcher m = Pattern.compile("^([A-Z0-9]{3,12})\\s+(GM-[A-Z0-9]{6})$").matcher(texto.toUpperCase());
        if (m.find() && !TextUtils.isEmpty(urlBase)) {
            cargar(urlBase + "?g=" + m.group(1) + "&c=" + m.group(2));
            return;
        }
        Toast.makeText(this, "No se pudo leer el código. Abre la app, entra con tu usuario y vuelve a escanear.", Toast.LENGTH_LONG).show();
    }

    /* ---------- Botón atrás y ciclo de vida ---------- */
    @Override
    public void onBackPressed() {
        if (escanerAbierto()) {
            cerrarEscaner();
            return;
        }
        if (configurar.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(urlBase)) {
            configurar.setVisibility(View.GONE);
            actualizarBotonQR();
            return;
        }
        if (sinConexion.getVisibility() == View.VISIBLE) {
            salirConDobleAtras();
            return;
        }
        // La página decide: cierra una ventana, sale del modo puerta o vuelve a la pantalla anterior.
        // Se le avisa por el puente (la página consulta cada 200 ms). Si la página no está
        // escuchando (por ejemplo, aún cargando), se usa el doble toque para salir.
        if (System.currentTimeMillis() - ultimaConsultaAtras < 1500) {
            atrasPendiente = true;
        } else {
            salirConDobleAtras();
        }
    }

    private void salirConDobleAtras() {
        long ahora = System.currentTimeMillis();
        if (ahora - ultimoAtras < 2000) {
            finish();
        } else {
            ultimoAtras = ahora;
            Toast.makeText(this, "Pulsa atrás otra vez para salir", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (escanerAbierto()) barcode.pause();
        web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (escanerAbierto()) barcode.resume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
