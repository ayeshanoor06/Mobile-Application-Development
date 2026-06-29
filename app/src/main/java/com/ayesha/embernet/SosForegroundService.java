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

public class SosForegroundService extends Service
        implements BroadcastEngine.BroadcastListener {

    private static final String TAG        = "SosForegroundService";
    private static final String CHANNEL_ID = "embernet_sos";
    private static final int    NOTIF_ID   = 1001;

    public static final String ACTION_START = "START_SOS";
    public static final String ACTION_STOP  = "STOP_SOS";

    // Local broadcast action — stays inside the app
    // does NOT create a new activity or task
    public static final String ACTION_SHOW_ALERT_LOCAL =
            "com.ayesha.embernet.LOCAL_ALERT";

    private final IBinder binder = new SosBinder();

    // Static flag to allow MeshFragment to delay alert receiving
    private static boolean sReceivingEnabled = true;

    public class SosBinder extends Binder {
        SosForegroundService getService() {
            return SosForegroundService.this;
        }
    }

    private BroadcastEngine engine;
    private ServiceCallback uiCallback;

    // Tracks which messageIds we have already
    // shown alerts for
    private final java.util.Set<String> shownAlerts =
            new java.util.HashSet<>();

    public interface ServiceCallback {
        void onBroadcastTick(SOSMessage message,
                             int count);
        void onRelayReceived(SOSMessage message);
        void onGpsLocked(Location location);
    }

    public void setCallback(ServiceCallback cb) {
        this.uiCallback = cb;
    }

    // ── Lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        engine = new BroadcastEngine(this);
        engine.setListener(this);

        engine.getRelayEngine().setMeshService(
                MeshService.getInstance(this));

        MeshService.getInstance(this).setListener(
                new MeshService.MeshListener() {
                    @Override
                    public void onPacketReceived(
                            byte[] payload) {
                        if (!sReceivingEnabled) {
                            Log.d(TAG, "Packet ignored (receiving disabled)");
                            return;
                        }
                        Log.d(TAG, "Packet — "
                                + payload.length + "b");
                        engine.getRelayEngine()
                                .handleIncomingPacket(payload);
                    }
                    @Override
                    public void onPeerCountChanged(
                            int count) {}
                    @Override
                    public void onAdvertiseStarted() {}
                    @Override
                    public void onAdvertiseFailed(
                            int code) {
                        Log.e(TAG, "BLE failed: " + code);
                    }
                });

        Log.d(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent,
                              int flags,
                              int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();

        if (ACTION_START.equals(action)) {
            Notification n = buildNotification(
                    "Broadcasting SOS...",
                    "Sending your location to nearby devices");

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

            shownAlerts.clear();
            engine.startSOS();
            Log.d(TAG, "SOS started");

        } else if (ACTION_STOP.equals(action)) {
            engine.stopSOS();
            shownAlerts.clear();
            stopForeground(true);
            stopSelf();
            Log.d(TAG, "SOS stopped");

        } else {
            Log.d(TAG, "Service kept alive");
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
    }

    // ── BroadcastEngine.BroadcastListener

    @Override
    public void onSosBroadcast(SOSMessage message,
                               int broadcastNumber) {
        updateNotification(
                "SOS Broadcasting — #" + broadcastNumber,
                "Location: "
                        + message.getFormattedCoords());
        if (uiCallback != null) {
            uiCallback.onBroadcastTick(
                    message, broadcastNumber);
        }
    }

    @Override
    public void onRelayReceived(SOSMessage message) {
        Log.d(TAG, "onRelayReceived: "
                + message.deviceId
                + " hops=" + message.hopCount);

        // Show exactly once per messageId
        if (shownAlerts.contains(message.messageId)) {
            Log.d(TAG, "Already shown — skipping "
                    + message.messageId);
            return;
        }
        shownAlerts.add(message.messageId);

        // Show system notification
        showAlertNotification(message);

        // Notify bound UI (SosFragment)
        if (uiCallback != null) {
            uiCallback.onRelayReceived(message);
        }

        // ── KEY FIX
        Intent localIntent = new Intent(
                ACTION_SHOW_ALERT_LOCAL);
        localIntent.putExtra(
                "message_json", message.toJson());

        // Send to existing MainActivity directly
        // No new task, no new instance
        androidx.localbroadcastmanager.content
                .LocalBroadcastManager
                .getInstance(this)
                .sendBroadcast(localIntent);

        Log.d(TAG, "LocalBroadcast sent for "
                + message.messageId);
    }

    @Override
    public void onGpsLocked(Location location) {
        if (uiCallback != null) {
            uiCallback.onGpsLocked(location);
        }
    }

    @Override
    public void onBroadcastStopped() {
        stopForeground(true);
        stopSelf();
    }

    // ── Static helpers

    public static void start(Context context) {
        Intent intent = new Intent(context,
                SosForegroundService.class);
        intent.setAction(ACTION_START);
        context.startForegroundService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context,
                SosForegroundService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    /**
     * Ensures the service is running and sets the flag to allow processing
     * incoming mesh packets. Used by MeshFragment to delay receiving.
     */
    public static void enableReceiving(Context context) {
        sReceivingEnabled = true;
        Intent intent = new Intent(context, SosForegroundService.class);
        context.startService(intent);
    }

    // ── Notifications

    private void createNotificationChannel() {
        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        "EmberNet SOS",
                        NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Active SOS broadcast");
        channel.setShowBadge(true);
        channel.enableVibration(true);
        channel.enableLights(true);
        channel.setLightColor(
                android.graphics.Color
                        .parseColor("#E8521A"));
        channel.setLockscreenVisibility(
                android.app.Notification.VISIBILITY_PUBLIC);
        getSystemService(NotificationManager.class)
                .createNotificationChannel(channel);
    }

    private void showAlertNotification(
            SOSMessage message) {
        String alertChannelId = "embernet_alert";
        NotificationChannel alertChannel =
                new NotificationChannel(
                        alertChannelId,
                        "EmberNet SOS Alerts",
                        NotificationManager.IMPORTANCE_MAX);
        alertChannel.enableVibration(true);
        alertChannel.enableLights(true);
        alertChannel.setLightColor(
                android.graphics.Color
                        .parseColor("#E8521A"));
        alertChannel.setLockscreenVisibility(
                android.app.Notification.VISIBILITY_PUBLIC);
        alertChannel.setBypassDnd(true);
        getSystemService(NotificationManager.class)
                .createNotificationChannel(alertChannel);

        PendingIntent openSos =
                PendingIntent.getActivity(
                        this, 2,
                        new Intent(this, MainActivity.class)
                                .putExtra("open_sos", true)
                                .addFlags(
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                                | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);

        android.app.Notification alert =
                new NotificationCompat
                        .Builder(this, alertChannelId)
                        .setContentTitle(
                                "SOS — Device " + message.deviceId)
                        .setContentText(
                                "Location: "
                                        + message.getFormattedCoords()
                                        + " Battery: "
                                        + message.battery + "%")
                        .setSmallIcon(R.drawable.ic_sos)
                        .setColor(getColor(R.color.ember))
                        .setContentIntent(openSos)
                        .setAutoCancel(true)
                        .setPriority(
                                NotificationCompat.PRIORITY_MAX)
                        .setVibrate(new long[]{
                                0, 300, 200, 300, 200, 600})
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
        PendingIntent openApp =
                PendingIntent.getActivity(
                        this, 0,
                        new Intent(this, MainActivity.class)
                                .putExtra("open_sos", true)
                                .addFlags(
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                                | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stopIntent =
                PendingIntent.getService(
                        this, 1,
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
                .setContentIntent(openApp)
                .addAction(R.drawable.ic_sos,
                        "Stop SOS", stopIntent)
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

    public boolean isBroadcasting() {
        return engine != null
                && engine.isBroadcasting();
    }

    public BroadcastEngine getEngine() {
        return engine;
    }
}

