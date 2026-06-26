package com.ayesha.embernet;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresPermission;
import androidx.preference.PreferenceManager;

import org.osmdroid.config.Configuration;

public class EmberNetApp extends Application {

    private static final String TAG = "EmberNetApp";

    @Override
    public void onCreate() {
        super.onCreate();

        // OSMDroid init
        Configuration.getInstance().load(
                getApplicationContext(),
                PreferenceManager.getDefaultSharedPreferences(
                        getApplicationContext()));
        Configuration.getInstance()
                .setUserAgentValue(getPackageName());
        Configuration.getInstance()
                .setOsmdroidTileCache(
                        new java.io.File(
                                getCacheDir(), "osmdroid"));

        // Start mesh as soon as BT is ready
        startMeshWhenReady();
    }

    private void startMeshWhenReady() {
        BluetoothManager bm = (BluetoothManager)
                getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) {
            Log.e(TAG, "BluetoothManager null");
            return;
        }

        BluetoothAdapter adapter = bm.getAdapter();

        if (adapter != null && adapter.isEnabled()) {

            new Handler(Looper.getMainLooper())
                    .postDelayed(this::doStartMesh, 1000);
            new Handler(Looper.getMainLooper())
                    .postDelayed(this::doStartMesh, 3000);
            new Handler(Looper.getMainLooper())
                    .postDelayed(this::doStartMesh, 6000);
        } else {
            Log.d(TAG,
                    "BT not ready — waiting for STATE_ON");
            registerBluetoothStateReceiver();
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private void doStartMesh() {
        if (MeshService.getInstance(
                getApplicationContext()).isActive()) {
            Log.d(TAG, "Mesh already active");
            return;
        }

        try {
            BluetoothManager bm =
                    (BluetoothManager) getSystemService(
                            Context.BLUETOOTH_SERVICE);
            if (bm == null) return;
            BluetoothAdapter adapter = bm.getAdapter();
            if (adapter == null
                    || !adapter.isEnabled()) return;


            SOSMessage beacon =
                    SOSMessage.buildBeacon(
                            getApplicationContext());

            MeshService.getInstance(
                            getApplicationContext())
                    .start(beacon.toBytes());

            Log.d(TAG,
                    "Mesh started with BEACON payload "
                            + "— no false alerts");

        } catch (Exception e) {
            Log.e(TAG, "Mesh start failed: "
                    + e.getMessage());
        }
    }

    private void registerBluetoothStateReceiver() {
        BroadcastReceiver receiver =
                new BroadcastReceiver() {
                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    @Override
                    public void onReceive(
                            Context ctx, Intent intent) {
                        int state = intent.getIntExtra(
                                BluetoothAdapter.EXTRA_STATE,
                                BluetoothAdapter.ERROR);
                        if (state ==
                                BluetoothAdapter.STATE_ON) {
                            Log.d(TAG, "BT STATE_ON received");
                            new Handler(Looper.getMainLooper())
                                    .postDelayed(() -> {
                                        doStartMesh();
                                        try {
                                            unregisterReceiver(
                                                    this);
                                        } catch (Exception e) {
                                            Log.w(TAG,
                                                    "Unregister: "
                                                            + e.getMessage());
                                        }
                                    }, 1500);
                        }
                    }
                };
        registerReceiver(receiver,
                new IntentFilter(
                        BluetoothAdapter.ACTION_STATE_CHANGED));
    }
}