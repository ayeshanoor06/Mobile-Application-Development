package com.ayesha.embernet;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BleScanner {
    private static final String TAG = "BleScanner";

    public interface ScanListener {
        void onPacketReceived(byte[] payload, String senderAddress);
        void onPeerCountChanged(int count);
    }

    private final Context context;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private ScanListener listener;

    private boolean isScanning = false;
    private final Map<String, Long> activePeers = new HashMap<>();
    private static final long PEER_TIMEOUT_MS = 15000;

    public BleScanner(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(ScanListener listener) {
        this.listener = listener;
    }

    // ── Start scanning ────────────────────────────────────────────────
    public void startScanning() {
        if (isScanning) return;

        BluetoothManager btManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);

        if (btManager == null) {
            Log.e(TAG, "BluetoothManager unavailable");
            return;
        }

        BluetoothAdapter adapter = btManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.e(TAG, "Bluetooth not enabled");
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.e(TAG, "Could not get BLE scanner");
            return;
        }

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(BleAdvertiser.EMBERNET_SERVICE_UUID))
                .build();

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(filter);

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(0)
                .build();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                handleScanResult(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult r : results) {
                    handleScanResult(r);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "BLE scan failed — code=" + errorCode);
                isScanning = false;
            }
        };

        scanner.startScan(filters, settings, scanCallback);
        isScanning = true;
        Log.d(TAG, "BLE scanning started on API " + android.os.Build.VERSION.SDK_INT);
    }

    // ── Stop scanning
    public void stopScanning() {
        if (scanner != null && scanCallback != null && isScanning) {
            try {
                scanner.stopScan(scanCallback);
                Log.d(TAG, "BLE scanning stopped");
            } catch (Exception e) {
                Log.e(TAG, "Stop error: " + e.getMessage());
            }
        }
        isScanning = false;
        scanCallback = null;
        activePeers.clear();
    }

    // ── Handle scan result
    private void handleScanResult(ScanResult result) {
        String address = result.getDevice().getAddress();

        activePeers.put(address, System.currentTimeMillis());
        pruneExpiredPeers();

        if (listener != null) {
            listener.onPeerCountChanged(activePeers.size());
        }

        ScanRecord record = result.getScanRecord();
        if (record == null) return;

        byte[] serviceData = record.getServiceData(
                new ParcelUuid(BleAdvertiser.EMBERNET_SERVICE_UUID));

        if (serviceData == null || serviceData.length == 0) {
            return;
        }

        Log.d(TAG, "Packet received from " + address + " — " + serviceData.length + " bytes");

        // Check for EmberNet magic header (0xEB)
        if (serviceData.length >= 20 && (serviceData[0] & 0xFF) == 0xEB) {
            int magic2 = serviceData[1] & 0xFF;

            // Check if it's an SOS (0xAD) or BEACON (0xBE)
            if (magic2 == 0xAD || magic2 == 0xBE) {
                boolean isBeacon = (magic2 == 0xBE);
                Log.d(TAG, "EmberNet compact packet detected — parsing directly. isBeacon=" + isBeacon);

                byte[] fullPayload = expandCompactPayload(serviceData, isBeacon);

                if (fullPayload != null && listener != null) {
                    listener.onPacketReceived(fullPayload, address);
                }
            }
        } else if (serviceData.length >= 100) {
            // Full JSON payload received directly
            Log.d(TAG, "Full JSON payload received");
            if (listener != null) {
                listener.onPacketReceived(serviceData, address);
            }
        } else {
            Log.w(TAG, "Unknown payload format " + serviceData.length + "b from " + address);
        }
    }

    // ── Expand compact payload back to full JSON
    private byte[] expandCompactPayload(byte[] compact, boolean isBeacon) {
        try {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(compact);
            buf.get(); // skip 0xEB
            buf.get(); // skip second magic byte (0xAD or 0xBE)

            byte[] idBytes  = new byte[4];
            byte[] devBytes = new byte[4];

            buf.get(idBytes);
            buf.get(devBytes);

            float latF    = buf.getFloat();
            float lonF    = buf.getFloat();
            int   battery = buf.get() & 0xFF;
            int   hops    = buf.get() & 0xFF;

            String msgId = new String(idBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            String devId = new String(devBytes, java.nio.charset.StandardCharsets.UTF_8).trim();

            double lat = (double) latF;
            double lon = (double) lonF;

            android.util.Log.d("BleScanner", "Expanded: dev=" + devId
                    + " lat=" + lat + " lon=" + lon + " bat=" + battery + " hops=" + hops);

            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put("id",  msgId);
            obj.put("dev", devId);

            // Assign the correct type based on the magic byte check
            obj.put("typ", isBeacon ? SOSMessage.TYPE_BEACON : SOSMessage.TYPE_SOS);

            obj.put("lat", lat);
            obj.put("lon", lon);
            obj.put("acc", 15); // ~15m float precision
            obj.put("bat", battery);
            obj.put("ts", new java.text.SimpleDateFormat("HH:mm:ss",
                    java.util.Locale.getDefault()).format(new java.util.Date()));
            obj.put("hop", hops);

            return obj.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        } catch (Exception e) {
            android.util.Log.e("BleScanner", "expandCompactPayload failed: " + e.getMessage());
            return null;
        }
    }

    private void pruneExpiredPeers() {
        long now = System.currentTimeMillis();
        activePeers.entrySet().removeIf(entry -> (now - entry.getValue()) > PEER_TIMEOUT_MS);
    }

    public boolean isScanning() { return isScanning; }
    public int getPeerCount() { return activePeers.size(); }
}