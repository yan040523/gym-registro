package com.gymregistro.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Programa la revisión automática de pagos próximos (cada 3 horas, solo con internet). */
public final class Avisos {
    static final String CANAL = "pagos";
    static final String DEV_ID = "dev_id", DEV_GYM = "dev_gym", DEV_CLAVE = "dev_clave", DEV_NOMBRE = "dev_nombre";
    static final String EXTRA_GYM = "abrir_gym", EXTRA_CODIGO = "abrir_codigo";
    private static final String TRABAJO = "revisar-pagos", TRABAJO_YA = "revisar-pagos-ahora";

    private Avisos() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    static boolean hayDispositivo(Context c) {
        return !TextUtils.isEmpty(prefs(c).getString(DEV_CLAVE, ""));
    }

    static String urlBase(Context c) {
        String u = prefs(c).getString(MainActivity.CLAVE_URL, "");
        return TextUtils.isEmpty(u) ? c.getString(R.string.app_url).trim() : u;
    }

    static void crearCanal(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CANAL, "Avisos de pago", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Clientes a los que les falta poco para pagar la mensualidad o les toca hoy");
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private static Constraints conInternet() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }

    static void programar(Context c) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(AvisosWorker.class, 3, TimeUnit.HOURS)
                .setConstraints(conInternet())
                .build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(TRABAJO, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    static void revisarAhora(Context c) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(AvisosWorker.class).setConstraints(conInternet()).build();
        WorkManager.getInstance(c).enqueueUniqueWork(TRABAJO_YA, ExistingWorkPolicy.REPLACE, req);
    }

    static void olvidar(Context c) {
        prefs(c).edit().remove(DEV_ID).remove(DEV_GYM).remove(DEV_CLAVE).remove(DEV_NOMBRE).apply();
        WorkManager.getInstance(c).cancelUniqueWork(TRABAJO);
    }
}
