package com.ayesha.embernet;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

public class MeshFragment extends Fragment {

    // statusDot is a View not a TextView
    private View           statusDot;
    private TextView       meshStatus;
    private TextView       relayCount;

    private final Handler  refreshHandler =
            new Handler(Looper.getMainLooper());
    private Runnable       refreshRunnable;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(
                R.layout.fragment_mesh, container, false);
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        statusDot  = view.findViewById(
                R.id.mesh_status_dot);
        meshStatus = view.findViewById(
                R.id.mesh_status_text);
        relayCount = view.findViewById(
                R.id.mesh_relay_count_val);

        setupStartMeshButton(view);
        setupPreflightButton(view);
        setupDiagnostics(view);
        startRefreshing();
    }

    // ── Start mesh button — FIXED ─────────────────────────────────────────

    private void setupStartMeshButton(View view) {
        MaterialButton btn =
                view.findViewById(R.id.btn_start_mesh);
        if (btn == null) return;

        try {
            if (MeshService.getInstance(requireContext())
                    .isActive()) {
                setButtonActive(btn);
            }
        } catch (Exception e) {
            android.util.Log.e("MeshFragment",
                    "State check: " + e.getMessage());
        }

        btn.setOnClickListener(v -> {
            try {
                android.util.Log.d("MeshFragment",
                        "Starting mesh...");

                // Build BEACON — not SOS
                // prevents false alerts on mesh start
                SOSMessage beacon =
                        SOSMessage.buildBeacon(
                                requireContext());
                MeshService.getInstance(
                                requireContext())
                        .start(beacon.toBytes());

                setButtonActive(btn);

                /*
                 Wait 2 seconds THEN enable receiving
                 This prevents startup packets from
                 showing false alerts immediately
                */
                new android.os.Handler(
                        android.os.Looper
                                .getMainLooper())
                        .postDelayed(() -> {
                            if (!isAdded()) return;
                            SosForegroundService
                                    .enableReceiving(
                                            requireContext());
                            android.util.Log.d(
                                    "MeshFragment",
                                    "Receiving ENABLED "
                                            + "after 2s delay");
                        }, 2000);

                android.util.Log.d("MeshFragment",
                        "Mesh started with BEACON");

            } catch (Exception e) {
                android.util.Log.e("MeshFragment",
                        "Start failed: " + e.getMessage());
                btn.setText("Failed — check Logcat");
            }
        });
    }

    private void setButtonActive(MaterialButton btn) {
        if (!isAdded() || btn == null) return;
        btn.setText("Mesh active ✓");
        btn.setEnabled(false);
        btn.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        requireContext().getColor(
                                R.color.signal_green)));
    }

    // ── Pre-flight button ─────────────────────────────────────────────────

    private void setupPreflightButton(View view) {
        MaterialButton btn =
                view.findViewById(R.id.btn_preflight);
        if (btn == null) return;
        btn.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(
                        requireContext(),
                        PreflightActivity.class));
            } catch (Exception e) {
                android.util.Log.e("MeshFragment",
                        "Preflight: " + e.getMessage());
            }
        });
    }

    // ── Live status refresh ───────────────────────────────────────────────

    private void startRefreshing() {
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isAdded() || getView() == null)
                    return;
                updateMeshStatus();
                refreshHandler.postDelayed(this, 2000);
            }
        };
        refreshHandler.postDelayed(refreshRunnable, 500);
    }

    private void updateMeshStatus() {
        if (!isAdded() || getContext() == null) return;
        try {
            MeshService mesh =
                    MeshService.getInstance(requireContext());
            boolean active = mesh.isActive();

            int blePeers = 0;
            if (mesh.getBleScanner() != null) {
                blePeers =
                        mesh.getBleScanner().getPeerCount();
            }
            int wifiPeers = 0;
            if (mesh.getWifiDirect() != null) {
                wifiPeers =
                        mesh.getWifiDirect().getPeerCount();
            }

            if (meshStatus != null) {
                meshStatus.setText(active
                        ? getString(R.string.mesh_status_on)
                        : getString(R.string.mesh_status_off));
            }

            // statusDot is a View — use setBackgroundResource
            if (statusDot != null) {
                statusDot.setBackgroundResource(active
                        ? R.drawable.circle_dot
                        : R.drawable.circle_dot_red);
            }

            View v = getView();
            if (v == null) return;

            TextView bleCount =
                    v.findViewById(R.id.mesh_peer_count);
            TextView wifiCount =
                    v.findViewById(R.id.mesh_wifi_peer_count);

            if (bleCount != null) {
                bleCount.setText(
                        String.valueOf(blePeers));
            }
            if (wifiCount != null) {
                wifiCount.setText(
                        String.valueOf(wifiPeers));
            }
            if (relayCount != null) {
                relayCount.setText(
                        String.valueOf(
                                mesh.getSeenMessageCount()));
            }

        } catch (Exception e) {
            android.util.Log.e("MeshFragment",
                    "updateMeshStatus: " + e.getMessage());
        }
    }

    // ── BLE diagnostics ───────────────────────────────────────────────────

    private void setupDiagnostics(View view) {
        MaterialButton btn =
                view.findViewById(R.id.btn_ble_check);
        TextView output =
                view.findViewById(R.id.ble_diag_output);
        if (btn == null || output == null) return;
        btn.setOnClickListener(v -> {
            try {
                runDiagnostics(output);
            } catch (Exception e) {
                output.setText(
                        "Diagnostics error: "
                                + e.getMessage());
            }
        });
    }

    private void runDiagnostics(TextView output) {
        StringBuilder sb = new StringBuilder();

        android.bluetooth.BluetoothManager bm =
                (android.bluetooth.BluetoothManager)
                        requireContext().getSystemService(
                                Context.BLUETOOTH_SERVICE);
        android.bluetooth.BluetoothAdapter adapter =
                bm != null ? bm.getAdapter() : null;

        sb.append("── Bluetooth ──\n");
        sb.append("Adapter: ")
                .append(adapter == null ? "NULL ✗" : "OK ✓")
                .append("\n");
        sb.append("Enabled: ")
                .append(adapter != null && adapter.isEnabled()
                        ? "YES ✓" : "NO ✗")
                .append("\n");
        sb.append("Multi-advert: ")
                .append(adapter != null
                        && adapter.isMultipleAdvertisementSupported()
                        ? "YES ✓" : "NO ✗")
                .append("\n");
        sb.append("LE supported: ")
                .append(requireContext().getPackageManager()
                        .hasSystemFeature(
                                android.content.pm.PackageManager
                                        .FEATURE_BLUETOOTH_LE)
                        ? "YES ✓" : "NO ✗")
                .append("\n\n");

        sb.append("── Permissions ──\n");
        String[] perms;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            perms = new String[]{
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_ADVERTISE,
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            perms = new String[]{
                    android.Manifest.permission.BLUETOOTH,
                    android.Manifest.permission.BLUETOOTH_ADMIN,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
        for (String p : perms) {
            boolean granted =
                    androidx.core.content.ContextCompat
                            .checkSelfPermission(requireContext(), p)
                            == android.content.pm.PackageManager
                            .PERMISSION_GRANTED;
            sb.append(p.substring(p.lastIndexOf('.') + 1))
                    .append(": ")
                    .append(granted ? "✓" : "✗ MISSING")
                    .append("\n");
        }
        sb.append("\n");

        sb.append("── Mesh service ──\n");
        try {
            MeshService mesh =
                    MeshService.getInstance(requireContext());
            sb.append("Active: ")
                    .append(mesh.isActive() ? "YES ✓" : "NO ✗")
                    .append("\n");
            sb.append("BLE peers: ")
                    .append(mesh.getBleScanner() != null
                            ? mesh.getBleScanner().getPeerCount()
                            : 0)
                    .append("\n");
            sb.append("WiFi peers: ")
                    .append(mesh.getWifiDirect() != null
                            ? mesh.getWifiDirect().getPeerCount()
                            : 0)
                    .append("\n\n");
        } catch (Exception e) {
            sb.append("Error: ")
                    .append(e.getMessage())
                    .append("\n\n");
        }

        sb.append("── Wi-Fi Direct ──\n");
        boolean wfd = requireContext().getPackageManager()
                .hasSystemFeature(
                        android.content.pm.PackageManager
                                .FEATURE_WIFI_DIRECT);
        sb.append("Supported: ")
                .append(wfd ? "YES ✓" : "NO ✗")
                .append("\n");
        android.net.wifi.WifiManager wm =
                (android.net.wifi.WifiManager)
                        requireContext().getSystemService(
                                Context.WIFI_SERVICE);
        sb.append("Wi-Fi enabled: ")
                .append(wm != null && wm.isWifiEnabled()
                        ? "YES ✓" : "NO ✗")
                .append("\n\n");

        sb.append("── Device ──\n");
        sb.append("Android API: ")
                .append(android.os.Build.VERSION.SDK_INT)
                .append("\n");
        sb.append("Model: ")
                .append(android.os.Build.MODEL)
                .append("\n");

        output.setText(sb.toString());
        android.util.Log.d("BLE_DIAG", sb.toString());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        refreshHandler.removeCallbacks(refreshRunnable);
        statusDot  = null;
        meshStatus = null;
        relayCount = null;
    }
}
