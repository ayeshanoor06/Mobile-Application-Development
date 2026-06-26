package com.ayesha.embernet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content
        .LocalBroadcastManager;

public class SosForegroundService extends Service
        implements BroadcastEngine.BroadcastListener {

    private static final String TAG        =
            "SosForegroundService";
    private static final String CHANNEL_ID =
            "embernet_sos";
    private static final int    NOTIF_ID   = 1001;

    public static final String ACTION_START =
            "START_SOS";
    public static final String ACTION_STOP  =
            "STOP_SOS";
    public static final String ACTION_ENABLE_RECEIVING =
            "ENABLE_RECEIVING";
    public static final String ACTION_SHOW_ALERT_LOCAL =
            "com.ayesha.embernet.LOCAL_ALERT";

    private final IBinder binder = new SosBinder();

    public class SosBinder extends Binder {
        SosForegroundService getService() {
            return SosForegroundService.this;
        }
    }

    private BroadcastEngine engine;
    private ServiceCallback uiCallback;

    // KEY FIX: starts as FALSE
    // Only becomes true when user explicitly taps
    // "Start mesh receiver" or "SOS button"
    // Resets to false when service is destroyed
    private boolean isReceivingEnabled = false;

    private final java.util.Set<String> shownAlerts =
            new java.util.HashSet<>();

    public interface ServiceCallback {
        void onBroadcastTick(SOSMessage msg, int count);
        void onRelayReceived(SOSMessage msg);
        void onGpsLocked(Location location);
    }

    public void setCallback(ServiceCallback cb) {
        this.uiCallback = cb;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        engine = new BroadcastEngine(this);
        engine.setListener(this);
        engine.getRelayEngine().setMeshService(
                MeshService.getInstance(this));

        // Wire MeshService listener
        // isReceivingEnabled = false so NO alerts
        // until user explicitly enables
        MeshService.getInstance(this).setListener(
                new MeshService.MeshListener() {
                    @Override
                    public void onPacketReceived(
                            byte[] payload) {
                        if (!isReceivingEnabled) {
                            Log.d(TAG,
                                    "Packet ignored — "
                                            + "receiving not enabled");
                            return;
                        }
                        Log.d(TAG, "Packet received "
                                + payload.length + "b");
                        engine.getRelayEngine()
                                .handleIncomingPacket(payload);
                    }
                    @Override
                    public void onPeerCountChanged(
                            int c) {}
                    @Override
                    public void onAdvertiseStarted() {}
                    @Override
                    public void onAdvertiseFailed(int c) {
                        Log.e(TAG, "BLE failed: " + c);
                    }
                });

        Log.d(TAG, "Service created. "
                + "isReceivingEnabled=false");
    }

    @Override
    public int onStartCommand(Intent intent,
                              int flags,
                              int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (action == null) action = "";

        switch (action) {

            case ACTION_START:
                // User pressed SOS button
                // Enable receiving immediately
                isReceivingEnabled = true;
                shownAlerts.clear();
                Log.d(TAG,
                        "SOS START — receiving ENABLED");

                Notification n = buildNotification(
                        "Broadcasting SOS...",
                        "Sending location to nearby devices");

                if (Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, n,
                            android.content.pm.ServiceInfo
                                    .FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                                    | android.content.pm.ServiceInfo
                                    .FOREGROUND_SERVICE_TYPE_LOCATION);
                } else {
                    startForeground(NOTIF_ID, n);
                }
                engine.startSOS();
                break;

            case ACTION_STOP:
                // User pressed Stop SOS
                // CRITICAL: do NOT disable receiving
                // Mesh should still receive alerts
                // after SOS stops
                // Only stop broadcasting
                engine.stopSOS();
                shownAlerts.clear();
                stopForeground(true);
                Log.d(TAG,
                        "SOS STOP — receiving stays: "
                                + isReceivingEnabled);
                break;

            case ACTION_ENABLE_RECEIVING:
                // User tapped Start Mesh Receiver
                isReceivingEnabled = true;
                shownAlerts.clear();
                Log.d(TAG,
                        "Receiving ENABLED by user");
                break;

            default:
                Log.d(TAG, "Service alive. "
                        + "receiving=" + isReceivingEnabled);
                break;
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (engine != null) engine.stopSOS();
        // Reset on destroy
        isReceivingEnabled = false;
        Log.d(TAG, "Service destroyed");
    }

    // ── BroadcastEngine.BroadcastListener ────────────────────────────

    @Override
    public void onSosBroadcast(SOSMessage msg,
                               int count) {
        updateNotification(
                "SOS Broadcasting — #" + count,
                "Location: " + msg.getFormattedCoords());
        if (uiCallback != null)
            uiCallback.onBroadcastTick(msg, count);
    }

    @Override
    public void onRelayReceived(SOSMessage msg) {
        Log.d(TAG, "onRelayReceived: "
                + msg.deviceId
                + " receiving=" + isReceivingEnabled);

        if (!isReceivingEnabled) {
            Log.d(TAG, "Alert blocked — not enabled");
            return;
        }

        // Show each message only once
        if (shownAlerts.contains(msg.messageId)) {
            Log.d(TAG, "Already shown — skip");
            return;
        }
        shownAlerts.add(msg.messageId);

        showAlertNotification(msg);

        if (uiCallback != null)
            uiCallback.onRelayReceived(msg);

        // Send via LocalBroadcast to existing
        // MainActivity — does NOT create new activity
        Intent local = new Intent(
                ACTION_SHOW_ALERT_LOCAL);
        local.putExtra("message_json", msg.toJson());
        LocalBroadcastManager
                .getInstance(this)
                .sendBroadcast(local);

        Log.d(TAG, "Alert sent for " + msg.messageId);
    }

    @Override
    public void onGpsLocked(Location location) {
        if (uiCallback != null)
            uiCallback.onGpsLocked(location);
    }

    @Override
    public void onBroadcastStopped() {
        stopForeground(true);
        // Do NOT stopSelf here — keep service alive
        // so mesh can still receive alerts
        Log.d(TAG, "Broadcast stopped — "
                + "service kept alive for receiving");
    }

    // ── Static helpers ────────────────────────────────────────────────

    public static void start(Context ctx) {
        Intent i = new Intent(ctx,
                SosForegroundService.class);
        i.setAction(ACTION_START);
        ctx.startForegroundService(i);
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx,
                SosForegroundService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    public static void enableReceiving(Context ctx) {
        Intent i = new Intent(ctx,
                SosForegroundService.class);
        i.setAction(ACTION_ENABLE_RECEIVING);
        ctx.startService(i);
    }

    public boolean isReceivingEnabled() {
        return isReceivingEnabled;
    }

    public boolean isBroadcasting() {
        return engine != null
                && engine.isBroadcasting();
    }

    public BroadcastEngine getEngine() {
        return engine;
    }

    // ── Notifications ─────────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel ch =
                new NotificationChannel(
                        CHANNEL_ID,
                        "EmberNet SOS",
                        NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Active SOS broadcast");
        ch.enableVibration(true);
        ch.enableLights(true);
        ch.setLightColor(
                android.graphics.Color
                        .parseColor("#E8521A"));
        ch.setLockscreenVisibility(
                Notification.VISIBILITY_PUBLIC);
        getSystemService(NotificationManager.class)
                .createNotificationChannel(ch);
    }

    private void showAlertNotification(SOSMessage m) {
        String cid = "embernet_alert";
        NotificationChannel ac =
                new NotificationChannel(cid,
                        "EmberNet SOS Alerts",
                        NotificationManager.IMPORTANCE_MAX);
        ac.enableVibration(true);
        ac.setBypassDnd(true);
        ac.setLockscreenVisibility(
                Notification.VISIBILITY_PUBLIC);
        getSystemService(NotificationManager.class)
                .createNotificationChannel(ac);

        PendingIntent open =
                PendingIntent.getActivity(this, 2,
                        new Intent(this, MainActivity.class)
                                .putExtra("open_sos", true)
                                .addFlags(
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                                | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);

        Notification alert =
                new NotificationCompat.Builder(this, cid)
                        .setContentTitle(
                                "SOS — Device " + m.deviceId)
                        .setContentText(
                                "Location: " + m.getFormattedCoords()
                                        + " Battery: " + m.battery + "%")
                        .setSmallIcon(R.drawable.ic_sos)
                        .setColor(getColor(R.color.ember))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .setPriority(
                                NotificationCompat.PRIORITY_MAX)
                        .setVibrate(
                                new long[]{0,300,200,300,200,600})
                        .setDefaults(
                                NotificationCompat.DEFAULT_ALL)
                        .setVisibility(
                                NotificationCompat.VISIBILITY_PUBLIC)
                        .build();

        getSystemService(NotificationManager.class)
                .notify(2001, alert);
    }

    private Notification buildNotification(
            String title, String text) {
        PendingIntent open =
                PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class)
                                .putExtra("open_sos", true)
                                .addFlags(
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                                | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stop =
                PendingIntent.getService(this, 1,
                        new Intent(this,
                                SosForegroundService.class)
                                .setAction(ACTION_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_sos)
                .setColor(getColor(R.color.ember))
                .setContentIntent(open)
                .addAction(R.drawable.ic_sos,
                        "Stop SOS", stop)
                .setOngoing(true)
                .setPriority(
                        NotificationCompat.PRIORITY_HIGH)
                .build();
    }

    private void updateNotification(
            String title, String text) {
        getSystemService(NotificationManager.class)
                .notify(NOTIF_ID,
                        buildNotification(title, text));
    }
}