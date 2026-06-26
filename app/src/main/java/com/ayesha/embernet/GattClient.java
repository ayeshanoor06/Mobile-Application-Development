package com.ayesha.embernet;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import java.util.HashMap;
import java.util.Map;

public class GattClient {

    private static final String TAG = "GattClient";

    private static final long RETRY_DELAY_MS = 3000;

    public interface ReadCallback {
        void onPayloadRead(byte[] payload, String address);
        void onReadFailed(String address);
    }

    private final Context context;
    private final ReadCallback callback;

    // Track active connections to avoid duplicate reads
    private final Map<String, BluetoothGatt> activeConnections
            = new HashMap<>();

    // Track addresses we've recently read to avoid hammering
    private final Map<String, Long> recentlyRead = new HashMap<>();
    private static final long READ_COOLDOWN_MS = 8000;

    public GattClient(Context context, ReadCallback callback) {
        this.context  = context.getApplicationContext();
        this.callback = callback;
    }

    // Connect to a device and read its SOS characteristic
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void readFrom(BluetoothDevice device) {
        String address = device.getAddress();

        // Skip if we read from this device recently
        Long lastRead = recentlyRead.get(address);
        if (lastRead != null
                && System.currentTimeMillis() - lastRead
                < READ_COOLDOWN_MS) {
            Log.d(TAG, "Skipping recent device: " + address);
            return;
        }

        // Skip if already connecting
        if (activeConnections.containsKey(address)) {
            Log.d(TAG, "Already connecting to: " + address);
            return;
        }

        Log.d(TAG, "Connecting to GATT: " + address);

        BluetoothGatt gatt = device.connectGatt(
                context,
                false, // autoConnect = false for faster connection
                new BluetoothGattCallback() {

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    @Override
                    public void onConnectionStateChange(
                            BluetoothGatt gatt, int status,
                            int newState) {

                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            Log.d(TAG, "Connected — discovering services");
                            gatt.discoverServices();

                        } else if (newState ==
                                BluetoothProfile.STATE_DISCONNECTED) {
                            Log.d(TAG, "Disconnected from " + address);
                            activeConnections.remove(address);
                            gatt.close();
                        }
                    }

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    @Override
                    public void onServicesDiscovered(
                            BluetoothGatt gatt, int status) {

                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            Log.e(TAG, "Service discovery failed: "
                                    + address);
                            disconnect(gatt, address);
                            if (callback != null) {
                                callback.onReadFailed(address);
                            }
                            return;
                        }

                        BluetoothGattService service =
                                gatt.getService(
                                        BleAdvertiser.EMBERNET_SERVICE_UUID);
                        if (service == null) {
                            Log.e(TAG, "EmberNet service not found: "
                                    + address);
                            disconnect(gatt, address);
                            return;
                        }

                        BluetoothGattCharacteristic characteristic =
                                service.getCharacteristic(
                                        GattServer.SOS_CHARACTERISTIC_UUID);
                        if (characteristic == null) {
                            Log.e(TAG, "SOS characteristic not found");
                            disconnect(gatt, address);
                            return;
                        }

                        Log.d(TAG, "Reading SOS characteristic from "
                                + address);
                        gatt.readCharacteristic(characteristic);
                    }

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    @Override
                    public void onCharacteristicRead(
                            BluetoothGatt gatt,
                            BluetoothGattCharacteristic characteristic,
                            int status) {

                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            byte[] payload = characteristic.getValue();
                            Log.d(TAG, "Read " + payload.length
                                    + " bytes from " + address);

                            recentlyRead.put(address,
                                    System.currentTimeMillis());

                            if (callback != null) {
                                callback.onPayloadRead(payload, address);
                            }
                        } else {
                            Log.e(TAG, "Read failed status=" + status);
                            if (callback != null) {
                                callback.onReadFailed(address);
                            }
                        }

                        disconnect(gatt, address);
                    }
                },
                BluetoothDevice.TRANSPORT_LE
        );

        if (gatt != null) {
            activeConnections.put(address, gatt);
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private void disconnect(BluetoothGatt gatt, String address) {
        activeConnections.remove(address);
        gatt.disconnect();
        gatt.close();
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void closeAll() {
        for (BluetoothGatt gatt : activeConnections.values()) {
            gatt.disconnect();
            gatt.close();
        }
        activeConnections.clear();
    }
}