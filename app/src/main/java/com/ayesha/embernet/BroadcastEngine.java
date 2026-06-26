package com.ayesha.embernet;

import android.Manifest;
import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresPermission;

public class BroadcastEngine {

    private static final String TAG = "BroadcastEngine";

    private static final long BROADCAST_INTERVAL_MS  = 4000;
    private static final long RELAY_SCAN_INTERVAL_MS = 2000;

    public interface BroadcastListener {
        void onSosBroadcast(SOSMessage message, int broadcastNumber);
        void onRelayReceived(SOSMessage message);
        void onGpsLocked(Location location);
        void onBroadcastStopped();
    }

    private final Context           context;
    private final Handler           handler;
    private final RelayEngine       relayEngine;
    private       BroadcastListener listener;
    private       LocationTracker   locationTracker;
    private       SOSMessage        currentMessage;
    private       boolean           isBroadcasting  = false;
    private       int               broadcastNumber = 0;
    private       Runnable          sosRepeatRunnable;
    private       Runnable          relayScanRunnable;

    public BroadcastEngine(Context context) {
        this.context         = context.getApplicationContext();
        this.handler         = new Handler(Looper.getMainLooper());
        this.locationTracker = new LocationTracker(context);
        this.relayEngine     = new RelayEngine(context);

        // Wire RelayEngine back to BroadcastEngine listener
        relayEngine.setListener(new RelayEngine.RelayListener() {
            @Override
            public void onNewSosReceived(SOSMessage message) {
                Log.d(TAG, "RelayEngine: new SOS from "
                        + message.deviceId);
                if (listener != null) {
                    listener.onRelayReceived(message);
                }
            }

            @Override
            public void onRelaying(SOSMessage relayed) {
                Log.d(TAG, "RelayEngine: relayed "
                        + relayed.messageId
                        + " hops=" + relayed.hopCount);
            }
        });
    }

    public void setListener(BroadcastListener listener) {
        this.listener = listener;
    }

    // ── Start SOS broadcast ───────────────────────────────────────────────

    public void startSOS() {
        if (isBroadcasting) {
            Log.w(TAG, "Already broadcasting");
            return;
        }

        isBroadcasting  = true;
        broadcastNumber = 0;

        // Give RelayEngine the MeshService reference
        relayEngine.setMeshService(
                MeshService.getInstance(context));

        // Start GPS tracking
        locationTracker.setListener(
                new LocationTracker.LocationListener() {
                    @Override
                    public void onLocationUpdated(Location loc) {
                        if (currentMessage != null && isBroadcasting) {
                            currentMessage = SOSMessage.buildFromDevice(
                                    context, loc);
                        }
                        if (listener != null) {
                            listener.onGpsLocked(loc);
                        }
                    }
                    @Override
                    public void onLocationUnavailable() {
                        Log.w(TAG, "GPS unavailable");
                    }
                });
        locationTracker.startTracking();

        // Build first message
        Location lastLoc = locationTracker.getLastKnownLocation();
        currentMessage   = SOSMessage.buildFromDevice(
                context, lastLoc);

        Log.d(TAG, "SOS started — deviceId="
                + currentMessage.deviceId
                + " messageId=" + currentMessage.messageId);

        // Wire MeshService listener
        MeshService.getInstance(context).setListener(
                new MeshService.MeshListener() {
                    @Override
                    public void onPacketReceived(byte[] payload) {
                        Log.d(TAG, "Mesh packet received — "
                                + payload.length + " bytes");
                        // Route through RelayEngine
                        relayEngine.handleIncomingPacket(payload);
                    }
                    @Override
                    public void onPeerCountChanged(int count) {
                        Log.d(TAG, "Peers: " + count);
                    }
                    @Override
                    public void onAdvertiseStarted() {
                        Log.d(TAG, "BLE advertising active ✓");
                    }
                    @Override
                    public void onAdvertiseFailed(int code) {
                        Log.e(TAG, "BLE advertising failed: " + code);
                    }
                });

        // Start mesh with initial payload
        MeshService.getInstance(context).start(
                currentMessage.toBytes());

        // Repeating SOS broadcast
        sosRepeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isBroadcasting) return;

                broadcastNumber++;

                // Rebuild with fresh GPS + battery
                Location freshLoc =
                        locationTracker.getLastKnownLocation();
                SOSMessage fresh =
                        SOSMessage.buildFromDevice(context, freshLoc);

                // Keep same messageId for deduplication
                currentMessage = new SOSMessage(
                        currentMessage.messageId, fresh);

                doPhysicalBroadcast(currentMessage);

                if (listener != null) {
                    listener.onSosBroadcast(
                            currentMessage, broadcastNumber);
                }

                handler.postDelayed(this, BROADCAST_INTERVAL_MS);
            }
        };
        handler.post(sosRepeatRunnable);

        // Relay scanner loop
        relayScanRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isBroadcasting) return;
                handler.postDelayed(this, RELAY_SCAN_INTERVAL_MS);
            }
        };
        handler.postDelayed(relayScanRunnable,
                RELAY_SCAN_INTERVAL_MS);
    }

    // ── Stop SOS broadcast ────────────────────────────────────────────────

    public void stopSOS() {
        MeshService.getInstance(context).stop();
        isBroadcasting = false;
        handler.removeCallbacks(sosRepeatRunnable);
        handler.removeCallbacks(relayScanRunnable);
        locationTracker.stopTracking();
        currentMessage  = null;
        broadcastNumber = 0;
        relayEngine.reset();

        if (listener != null) listener.onBroadcastStopped();
        Log.d(TAG, "SOS broadcast stopped");
    }

    // ── Physical broadcast ────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private void doPhysicalBroadcast(SOSMessage message) {
        byte[] payload = message.toBytes();
        Log.d(TAG, "BROADCAST [" + broadcastNumber + "] "
                + message.messageId
                + " lat="  + message.latitude
                + " bat="  + message.battery + "%"
                + " hops=" + message.hopCount
                + " bytes=" + payload.length);

        MeshService.getInstance(context).send(payload);
    }

    // ── Getters ───────────────────────────────────────────────────────────

    public boolean    isBroadcasting()      { return isBroadcasting;  }
    public SOSMessage getCurrentMessage()   { return currentMessage;  }
    public int        getBroadcastNumber()  { return broadcastNumber; }
    public RelayEngine getRelayEngine()     { return relayEngine;     }

    public java.util.Set<String> getSeenIds() {
        return new java.util.HashSet<>();
    }
}