package com.gymregistro.app;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Pregunta a la plataforma qué clientes deben pagar en 2 días o hoy y muestra una notificación
 * por cliente (una sola vez por cada fecha de pago). Al tocarla se abre WhatsApp con el mensaje listo.
 */
public class AvisosWorker extends Worker {

    private static final String YA_AVISADOS = "avisados";

    public AvisosWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences p = Avisos.prefs(ctx);
        String base = Avisos.urlBase(ctx), gym = p.getString(Avisos.DEV_GYM, ""), clave = p.getString(Avisos.DEV_CLAVE, "");
        if (TextUtils.isEmpty(base) || TextUtils.isEmpty(gym) || TextUtils.isEmpty(clave)) return Result.success();

        JSONObject r;
        try {
            String url = base + "?api=avisos&gym=" + Uri.encode(gym) + "&clave=" + Uri.encode(clave);
            r = new JSONObject(descargar(url));
        } catch (Exception e) {
            return Result.retry();
        }

        if (!r.optBoolean("ok")) {
            // La cuenta fue desactivada o se cerró sesión en otro lado: se deja de avisar en este móvil.
            if ("DISPOSITIVO".equals(r.optString("error"))) Avisos.olvidar(ctx);
            return Result.success();
        }

        JSONObject d = r.optJSONObject("data");
        if (d == null) return Result.success();
        JSONArray items = d.optJSONArray("items");
        String moneda = d.optString("moneda", "CUP");
        Set<String> avisados = new HashSet<>(p.getStringSet(YA_AVISADOS, new HashSet<>()));
        Set<String> vigentes = new HashSet<>();

        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject c = items.optJSONObject(i);
            if (c == null) continue;
            int dias = c.optInt("dias", 99);
            if (dias < 0 || dias > 2) continue;
            String tipo = dias == 0 ? "hoy" : "pronto";
            String clavAviso = c.optString("codigo") + "|" + c.optString("proximoPago") + "|" + tipo;
            vigentes.add(clavAviso);
            if (avisados.contains(clavAviso)) continue;
            if (notificar(ctx, gym, c, dias, moneda)) avisados.add(clavAviso);
        }
        // Se conservan solo los avisos de pagos todavía cercanos para que la lista no crezca sin fin.
        avisados.retainAll(vigentes);
        p.edit().putStringSet(YA_AVISADOS, avisados).apply();
        return Result.success();
    }

    private static String descargar(String direccion) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(direccion).openConnection();
        con.setInstanceFollowRedirects(true);
        con.setConnectTimeout(20000);
        con.setReadTimeout(30000);
        int codigo = con.getResponseCode();
        // Google responde con una redirección a otro servidor; se sigue a mano por si el sistema no lo hace.
        for (int saltos = 0; (codigo == 301 || codigo == 302 || codigo == 303 || codigo == 307) && saltos < 5; saltos++) {
            String destino = con.getHeaderField("Location");
            con.disconnect();
            con = (HttpURLConnection) new URL(destino).openConnection();
            con.setConnectTimeout(20000);
            con.setReadTimeout(30000);
            codigo = con.getResponseCode();
        }
        if (codigo != 200) throw new Exception("HTTP " + codigo);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = br.readLine()) != null) sb.append(linea);
        } finally {
            con.disconnect();
        }
        return sb.toString();
    }

    private static boolean notificar(Context ctx, String gym, JSONObject c, int dias, String moneda) {
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        String codigo = c.optString("codigo");
        String nombre = (c.optString("nombre") + " " + c.optString("apellidos")).trim();
        String telefono = c.optString("telefono");
        String mensaje = c.optString("mensaje");
        long monto = Math.round(c.optDouble("monto", 0));
        int id = codigo.hashCode();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        Intent perfil = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(Avisos.EXTRA_GYM, gym)
                .putExtra(Avisos.EXTRA_CODIGO, codigo);
        PendingIntent piPerfil = PendingIntent.getActivity(ctx, id, perfil, flags);

        PendingIntent piWhats = null;
        if (!TextUtils.isEmpty(telefono)) {
            Intent wa = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + telefono + "?text=" + Uri.encode(mensaje)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            piWhats = PendingIntent.getActivity(ctx, id + 1, wa, flags);
        }

        String titulo = dias == 0 ? "Hoy le toca pagar a " + nombre
                : "A " + nombre + " le faltan " + dias + (dias == 1 ? " día" : " días") + " para pagar";
        String texto = "Mensualidad " + monto + " " + moneda + " · " + codigo
                + (piWhats != null ? " · Toca para avisarle por WhatsApp" : " · Sin teléfono registrado");

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, Avisos.CANAL)
                .setSmallIcon(R.drawable.ic_noti)
                .setColor(0xFFD6392A)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(texto + "\n\nMensaje: " + mensaje))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(piWhats != null ? piWhats : piPerfil)
                .addAction(0, "Ver perfil", piPerfil);
        if (piWhats != null) b.addAction(0, "Enviar WhatsApp", piWhats);

        try {
            NotificationManagerCompat.from(ctx).notify(id, b.build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }
}
