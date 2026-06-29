package com.ayesha.embernet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity
        implements AlertManager.AlertActionListener {

    private NavController navController;
    private AlertManager  alertManager;

    // LocalBroadcast receiver — receives alerts from
    // SosForegroundService without creating new activity
    private BroadcastReceiver localAlertReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Permissions
        if (!PermissionHelper.allGranted(this)) {
            PermissionHelper.requestAll(this);
        }

        // Navigation
        NavHostFragment navHostFragment =
                (NavHostFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment == null) return;

        navController =
                navHostFragment.getNavController();

        BottomNavigationView bottomNav =
                findViewById(R.id.bottom_nav);
        NavigationUI.setupWithNavController(
                bottomNav, navController);

        navController.addOnDestinationChangedListener(
                (ctrl, dest, args) ->
                        android.util.Log.d("MainActivity",
                                "Nav to: " + dest.getLabel()));

        // Alert overlay
        ViewGroup rootView =
                findViewById(android.R.id.content);
        alertManager = new AlertManager(this, rootView);
        alertManager.setListener(this);

        // Register LocalBroadcast receiver
        // This is the KEY FIX — receives alert inside
        // existing activity, no new task created
        registerLocalAlertReceiver();

        // One-time tips
        showAirplaneTip();
        requestIgnoreBatteryOptimization();

        // Handle intent from notification tap
        handleIncomingIntent(getIntent());
    }

    // ── LocalBroadcast receiver ───────────────────────────────────────

    private void registerLocalAlertReceiver() {
        localAlertReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context,
                                  Intent intent) {
                if (!SosForegroundService
                        .ACTION_SHOW_ALERT_LOCAL
                        .equals(intent.getAction())) {
                    return;
                }

                String json = intent.getStringExtra(
                        "message_json");
                if (json == null) return;

                try {
                    SOSMessage message =
                            SOSMessage.fromJson(json);

                    if (!message.isRealSOS()) return;

                    android.util.Log.d("MainActivity",
                            "LocalBroadcast alert: "
                                    + message.deviceId);

                    // Show alert in existing activity
                    // Navigation stays fully functional
                    alertManager.showAlert(message);

                } catch (Exception e) {
                    android.util.Log.e("MainActivity",
                            "Local alert parse: "
                                    + e.getMessage());
                }
            }
        };

        LocalBroadcastManager
                .getInstance(this)
                .registerReceiver(
                        localAlertReceiver,
                        new IntentFilter(
                                SosForegroundService
                                        .ACTION_SHOW_ALERT_LOCAL));

        android.util.Log.d("MainActivity",
                "LocalBroadcast receiver registered");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        // Start service to ensure mesh listener is set
        startService(new Intent(this,
                SosForegroundService.class));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unregister to prevent memory leak
        if (localAlertReceiver != null) {
            LocalBroadcastManager
                    .getInstance(this)
                    .unregisterReceiver(
                            localAlertReceiver);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    // ── Intent handling ───────────────────────────────────────────────

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        if (intent.getBooleanExtra(
                "open_sos", false)) {
            intent.removeExtra("open_sos");
            if (navController != null) {
                navController.navigate(R.id.nav_sos);
            }
        }

        String alertJson =
                intent.getStringExtra("show_alert_json");
        if (alertJson != null) {
            intent.removeExtra("show_alert_json");
            try {
                SOSMessage message =
                        SOSMessage.fromJson(alertJson);
                if (message.isRealSOS()
                        && alertManager != null) {
                    alertManager.showAlert(message);
                    navController.navigate(
                            R.id.nav_sos);
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity",
                        "Alert parse: " + e.getMessage());
            }
        }
    }

    // ── AlertManager.AlertActionListener ─────────────────────────────

    @Override
    public void onShowOnMap(SOSMessage message) {
        navController.navigate(R.id.nav_map);

        new android.os.Handler(
                android.os.Looper.getMainLooper())
                .postDelayed(() -> {
                    NavHostFragment navHost =
                            (NavHostFragment)
                                    getSupportFragmentManager()
                                            .findFragmentById(
                                                    R.id.nav_host_fragment);
                    if (navHost == null) return;

                    androidx.fragment.app.Fragment f =
                            navHost.getChildFragmentManager()
                                    .getPrimaryNavigationFragment();

                    if (f instanceof MapFragment) {
                        ((MapFragment) f).showSosAlertOnMap(
                                message.latitude,
                                message.longitude,
                                message.battery,
                                message.hopCount,
                                message.getFormattedTime(),
                                message.deviceId);
                    }
                }, 400);
    }

    @Override
    public void onDismiss(SOSMessage message) {}

    public void showInAppAlert(SOSMessage message) {
        if (alertManager != null
                && message.isRealSOS()) {
            alertManager.showAlert(message);
        }
    }

    // ── Permissions ───────────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(
                requestCode, permissions, grantResults);
        if (requestCode == PermissionHelper.REQUEST_CODE
                && !PermissionHelper.allGranted(this)) {
            showPermissionSettingsDialog();
        }
    }

    private void showPermissionSettingsDialog() {
        new com.google.android.material.dialog
                .MaterialAlertDialogBuilder(this)
                .setTitle("Permissions required")
                .setMessage(
                        "EmberNet needs Bluetooth and Location "
                                + "to work without internet.")
                .setPositiveButton("Open Settings",
                        (d, w) -> {
                            startActivity(new Intent(
                                    android.provider.Settings
                                            .ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(android.net.Uri.parse(
                                            "package:"
                                                    + getPackageName())));
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAirplaneTip() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("embernet_prefs",
                        MODE_PRIVATE);
        if (prefs.getBoolean("tip_shown", false)) {
            return;
        }
        new com.google.android.material.dialog
                .MaterialAlertDialogBuilder(this)
                .setTitle("Offline mode tip")
                .setMessage(
                        "In an emergency:\n\n"
                                + "1. Turn on Airplane Mode\n"
                                + "2. Re-enable Bluetooth\n"
                                + "3. Re-enable Wi-Fi\n\n"
                                + "EmberNet works without internet.")
                .setPositiveButton("Got it", (d, w) ->
                        prefs.edit()
                                .putBoolean("tip_shown", true)
                                .apply())
                .show();
    }

    private void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT
                < Build.VERSION_CODES.M) return;
        android.os.PowerManager pm =
                (android.os.PowerManager)
                        getSystemService(POWER_SERVICE);
        if (pm != null
                && !pm.isIgnoringBatteryOptimizations(
                getPackageName())) {
            new com.google.android.material.dialog
                    .MaterialAlertDialogBuilder(this)
                    .setTitle("Battery optimization")
                    .setMessage(
                            "Please disable battery "
                                    + "optimization so EmberNet "
                                    + "can receive alerts in background.")
                    .setPositiveButton("OK", (d, w) -> {
                        try {
                            startActivity(new Intent(
                                    android.provider.Settings
                                            .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(
                                            android.net.Uri.parse(
                                                    "package:"
                                                            + getPackageName())));
                        } catch (Exception e) {
                            android.util.Log.e(
                                    "MainActivity",
                                    "Battery opt: "
                                            + e.getMessage());
                        }
                    })
                    .setNegativeButton("Skip", null)
                    .show();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        return navController.navigateUp()
                || super.onSupportNavigateUp();
    }
}

