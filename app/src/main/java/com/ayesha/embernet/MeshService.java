package com.ayesha.embernet;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.util.Log;

import androidx.annotation.RequiresPermission;

public class MeshService {

    private static final String TAG = "MeshService";

    private static MeshService instance;

    public static MeshService getInstance(Context context) {
        if (instance == null) {
            instance = new MeshService(
                    context.getApplicationContext());
        }
        return instance;
    }

    public interface MeshListener {
        void onPacketReceived(byte[] payload);
        void onPeerCountChanged(int count);
        void onAdvertiseStarted();
        void onAdvertiseFailed(int code);
    }

    private final Context           context;
    private final BleAdvertiser     advertiser;
    private final BleScanner        scanner;
    private final GattServer        gattServer;
    private final GattClient        gattClient;
    private final WifiDirectManager wifiDirect;
    private       MeshListener      listener;
    private       boolean           isActive = false;

    private MeshService(Context context) {
        this.context    = context;
        this.advertiser = new BleAdvertiser(context);
        this.scanner    = new BleScanner(context);
        this.gattServer = new GattServer(context);

        this.gattClient = new GattClient(context,
                new GattClient.ReadCallback() {
                    @Override
                    public void onPayloadRead(byte[] payload,
                                              String address) {
                        Log.d(TAG, "GATT read from "
                                + address + " "
                                + payload.length + "b");
                        if (listener != null) {
                            listener.onPacketReceived(payload);
                        }
                    }
                    @Override
                    public void onReadFailed(String address) {
                        Log.w(TAG, "GATT read failed: "
                                + address);
                    }
                });

        // BLE scanner — compact payload is parsed
        // directly in BleScanner.expandCompactPayload()
        // No GATT read needed anymore
        scanner.setListener(new BleScanner.ScanListener() {
            @Override
            public void onPacketReceived(byte[] payload,
                                         String address) {
                Log.d(TAG, "Packet from " + address
                        + " size=" + payload.length);
                if (listener != null) {
                    listener.onPacketReceived(payload);
                } else {
                    Log.w(TAG,
                            "Listener null — packet dropped."
                                    + " Start service first.");
                }
            }

            @Override
            public void onPeerCountChanged(int count) {
                if (listener != null) {
                    listener.onPeerCountChanged(
                            count + wifiDirect.getPeerCount());
                }
            }
        });

        this.wifiDirect = new WifiDirectManager(context);
        wifiDirect.setListener(
                new WifiDirectManager.WifiDirectListener() {
                    @Override
                    public void onPeersFound(int count) {
                        if (listener != null) {
                            listener.onPeerCountChanged(
                                    scanner.getPeerCount() + count);
                        }
                    }
                    @Override
                    public void onPayloadReceived(
                            byte[] payload, String addr) {
                        if (listener != null) {
                            listener.onPacketReceived(payload);
                        }
                    }
                    @Override
                    public void onConnected(String addr,
                                            boolean isOwner) {}
                    @Override
                    public void onDisconnected() {}
                    @Override
                    public void onError(String message) {
                        Log.e(TAG, "WiFi Direct error: "
                                + message);
                    }
                });
    }

    public void setListener(MeshListener listener) {
        this.listener = listener;
        Log.d(TAG, "Listener set: "
                + (listener == null ? "NULL" : "OK"));
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void start(byte[] payload) {
        BluetoothManager bm =
                (BluetoothManager)
                        context.getSystemService(
                                Context.BLUETOOTH_SERVICE);
        if (bm == null) {
            Log.e(TAG, "BluetoothManager null");
            return;
        }
        BluetoothAdapter adapter =
                bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.e(TAG, "Bluetooth not enabled");
            return;
        }
        if (!adapter.isMultipleAdvertisementSupported()) {
            Log.e(TAG, "BLE advertising not supported");
            return;
        }

        if (isActive) {
            Log.d(TAG, "Already active — updating");
            updatePayload(payload);
            return;
        }

        Log.d(TAG, "Starting mesh "
                + payload.length + "b");

        gattServer.start(payload);
        advertiser.startAdvertising(payload,
                new BleAdvertiser.AdvertiseListener() {
                    @Override
                    public void onStarted() {
                        Log.d(TAG, "BLE advertising ACTIVE");
                        if (listener != null) {
                            listener.onAdvertiseStarted();
                        }
                    }
                    @Override
                    public void onFailed(int code) {
                        Log.e(TAG, "BLE FAILED " + code);
                        isActive = false;
                        if (listener != null) {
                            listener.onAdvertiseFailed(code);
                        }
                    }
                });

        scanner.startScanning();
        wifiDirect.startDiscovery();
        isActive = true;
        Log.d(TAG, "Mesh active");
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void send(byte[] payload) {
        if (!isActive) {
            start(payload);
            return;
        }
        advertiser.stopAdvertising();
        advertiser.startAdvertising(payload, null);
        gattServer.updatePayload(payload);
        wifiDirect.sendPayload(payload);
    }

    public void updatePayload(byte[] payload) {
        gattServer.updatePayload(payload);
        advertiser.stopAdvertising();
        advertiser.startAdvertising(payload, null);
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void stop() {
        advertiser.stopAdvertising();
        scanner.stopScanning();
        gattServer.stop();
        gattClient.closeAll();
        wifiDirect.stopDiscovery();
        isActive = false;
        Log.d(TAG, "Mesh stopped");
    }

    public boolean isActive() { return isActive; }
    public int getPeerCount() {
        return scanner.getPeerCount()
                + wifiDirect.getPeerCount();
    }
    public BleScanner getBleScanner() { return scanner; }
    public WifiDirectManager getWifiDirect() {
        return wifiDirect;
    }
    public int getSeenMessageCount() { return 0; }
}
