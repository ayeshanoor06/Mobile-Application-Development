package com.ayesha.embernet;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.Arrays;
import java.util.UUID;

public class BleAdvertiser {
    private static final String TAG = "BleAdvertiser";

    public static final UUID EMBERNET_SERVICE_UUID =
            UUID.fromString("0000EA01-0000-1000-8000-00805F9B34FB");

    private final Context context;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback activeCallback;

    private boolean isAdvertising = false;
    private byte[] lastPayload;

    public interface AdvertiseListener {
        void onStarted();
        void onFailed(int errorCode);
    }

    public BleAdvertiser(Context context) {
        this.context = context.getApplicationContext();
    }

    // ── Start advertising
    public void startAdvertising(byte[] fullPayload, AdvertiseListener listener) {
        if (isAdvertising) {
            stopAdvertising();
        }

        BluetoothManager btManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);

        if (btManager == null) {
            Log.e(TAG, "BluetoothManager unavailable");
            if (listener != null) listener.onFailed(-1);
            return;
        }

        BluetoothAdapter adapter = btManager.getAdapter();

        if (adapter == null || !adapter.isEnabled()) {
            Log.e(TAG, "Bluetooth not enabled");
            if (listener != null) listener.onFailed(-2);
            return;
        }

        if (!adapter.isMultipleAdvertisementSupported()) {
            Log.e(TAG, "BLE advertising not supported");
            if (listener != null) listener.onFailed(-3);
            return;
        }

        advertiser = adapter.getBluetoothLeAdvertiser();

        if (advertiser == null) {
            Log.e(TAG, "Could not get BLE advertiser");
            if (listener != null) listener.onFailed(-4);
            return;
        }

        lastPayload = fullPayload;
        byte[] advertPayload = buildAdvertPayload(fullPayload);

        AdvertiseSettings settings =
                new AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                        .setConnectable(true)
                        .setTimeout(0)
                        .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(EMBERNET_SERVICE_UUID))
                .addServiceData(new ParcelUuid(EMBERNET_SERVICE_UUID), advertPayload)
                .build();

        activeCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settings) {
                isAdvertising = true;
                Log.d(TAG, "BLE advertising ACTIVE payload=" + fullPayload.length
                        + "b advert=" + advertPayload.length + "b");
                if (listener != null) listener.onStarted();
            }

            @Override
            public void onStartFailure(int errorCode) {
                isAdvertising = false;
                String reason;
                switch (errorCode) {
                    case ADVERTISE_FAILED_DATA_TOO_LARGE:
                        reason = "DATA_TOO_LARGE";
                        break;
                    case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                        reason = "TOO_MANY_ADVERTISERS";
                        break;
                    case ADVERTISE_FAILED_ALREADY_STARTED:
                        reason = "ALREADY_STARTED";
                        break;
                    case ADVERTISE_FAILED_INTERNAL_ERROR:
                        reason = "INTERNAL_ERROR";
                        break;
                    case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                        reason = "UNSUPPORTED";
                        break;
                    default:
                        reason = "code=" + errorCode;
                }
                Log.e(TAG, "BLE advertising FAILED: " + reason);
                if (listener != null) {
                    listener.onFailed(errorCode);
                }
            }
        };

        advertiser.startAdvertising(settings, data, activeCallback);
    }

    // ── Stop advertising
    public void stopAdvertising() {
        if (advertiser != null && activeCallback != null) {
            try {
                advertiser.stopAdvertising(activeCallback);
            } catch (Exception e) {
                Log.e(TAG, "Stop error: " + e.getMessage());
            }
        }
        isAdvertising = false;
        activeCallback = null;
    }

    // ── Payload builder
    private byte[] buildAdvertPayload(byte[] fullPayload) {
        try {
            String json = new String(fullPayload, java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject obj = new org.json.JSONObject(json);

            String msgId = obj.optString("id", "000000");
            String devId = obj.optString("dev", "UNKNWN");
            String type = obj.optString("typ", SOSMessage.TYPE_SOS);
            double lat = obj.optDouble("lat", 0.0);
            double lon = obj.optDouble("lon", 0.0);
            int battery = obj.optInt("bat", 0);
            int hops = obj.optInt("hop", 0);

            // Pack into 20 bytes
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(20);

            // Magic header: 0xEB
            buf.put((byte) 0xEB);

            // Differentiate Beacon vs SOS/Relay using the second magic byte
            if (SOSMessage.TYPE_BEACON.equals(type)) {
                buf.put((byte) 0xBE); // 0xBE for Beacon
            } else {
                buf.put((byte) 0xAD); // 0xAD for Alert Data
            }

            // Message ID: first 4 chars as bytes
            byte[] idBytes = msgId.substring(0, Math.min(4, msgId.length()))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.put(Arrays.copyOf(idBytes, 4));

            // Device ID: first 4 chars
            byte[] devBytes = devId.substring(0, Math.min(4, devId.length()))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.put(Arrays.copyOf(devBytes, 4));

            // Lat as float (4 bytes)
            buf.putFloat((float) lat);

            // Lon as float (4 bytes)
            buf.putFloat((float) lon);

            // Battery (1 byte)
            buf.put((byte) battery);

            // Hops (1 byte)
            buf.put((byte) hops);

            byte[] result = buf.array();
            Log.d(TAG, "Built advert payload: " + result.length + " bytes");

            return result;
        } catch (Exception e) {
            Log.e(TAG, "buildAdvertPayload failed: " + e.getMessage());
            // Fallback: return first 20 bytes
            return Arrays.copyOf(fullPayload, Math.min(20, fullPayload.length));
        }
    }

    public boolean isAdvertising() {
        return isAdvertising;
    }

    public byte[] getLastPayload() {
        return lastPayload;
    }
}