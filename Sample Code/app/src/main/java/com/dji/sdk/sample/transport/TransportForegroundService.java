package com.dji.sdk.sample.transport;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.djihub.DjiDataHub;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.controller.MainActivity;

import dji.sdk.products.Aircraft;

/** Keeps an operator-selected passive Edge transport alive while the Activity is backgrounded. */
public final class TransportForegroundService extends Service {
    private static final String CHANNEL_ID = "matrice_transport_active";
    private static final int NOTIFICATION_ID = 41061;
    private static final long RECONCILE_INTERVAL_MS = 2_000L;
    private final Handler handler = new Handler();

    /** Must be called from an operator-visible UI action or foreground app lifecycle callback. */
    public static void startForSelectedEndpoint(Context context) {
        if (!TransportSessionManager.hasSelectedEndpoint(context)) return;
        Intent intent = new Intent(context.getApplicationContext(), TransportForegroundService.class);
        ContextCompat.startForegroundService(context.getApplicationContext(), intent);
    }

    /** Endpoint removal is the sole normal UI action that stops background transport retention. */
    public static void stopForNoEndpoint(Context context) {
        context.getApplicationContext().stopService(
                new Intent(context.getApplicationContext(), TransportForegroundService.class));
    }

    private final Runnable reconcile = new Runnable() {
        @Override public void run() {
            if (!TransportSessionManager.hasSelectedEndpoint(TransportForegroundService.this)) {
                stopSelf();
                return;
            }
            Aircraft aircraft = DJISampleApplication.getAircraftInstance();
            if (aircraft != null && aircraft.isConnected()) {
                // Idempotent hub refresh; this service never registers a direct DJI callback.
                DjiDataHub.getInstance().start(aircraft);
            }
            try {
                TransportSessionManager.getInstance().reconcile(TransportForegroundService.this, aircraft);
            } catch (Exception ignored) {
                // Keep selected intent across transient Wi-Fi or DJI reconnection.
            }
            handler.postDelayed(this, RECONCILE_INTERVAL_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
    }

    @Override public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        handler.removeCallbacks(reconcile);
        handler.post(reconcile);
        // A system reclaim may restart this while a selected endpoint remains. Explicit endpoint
        // removal and a user/system force-stop still terminate it normally.
        return START_STICKY;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        handler.removeCallbacks(reconcile);
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.transport_notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.transport_notification_channel_description));
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private Notification notification() {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.transport_notification_title))
                .setContentText(getString(R.string.transport_notification_text))
                .setContentIntent(content)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
