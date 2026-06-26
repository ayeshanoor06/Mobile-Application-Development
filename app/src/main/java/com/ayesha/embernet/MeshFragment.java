package com.ayesha.embernet;

import android.content.Context;
import android.content.SharedPreferences;
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

    // SharedPreferences key to persist mesh state
    // across tab switches and app restarts
    private static final String PREFS       =
            "embernet_mesh";
    private static final String KEY_ACTIVE  =
            "mesh_active";

    private View           statusDot;
    private TextView       meshStatus;
    private TextView       relayCount;
    private MaterialButton meshBtn;

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
        meshBtn    = view.findViewById(
                R.id.btn_start_mesh);

        setupStartMeshButton();
        setupPreflightButton(view);
        setupDiagnostics(view);
        startRefreshing();

        // KEY FIX: restore state every time
        // the view is created (tab switch, app reopen)
        restoreState();
    }

    // ── Restore state on every view creation ─────────────────────────

    private void restoreState() {
        boolean savedActive = getPrefs()
                .getBoolean(KEY_ACTIVE, false);
        boolean runningNow  =
                MeshService.getInstance(requireContext())
                        .isActive();

        android.util.Log.d("MeshFragment",
                "restoreState: saved=" + savedActive
                        + " running=" + runningNow);

        if (savedActive && !runningNow) {
            // Was active before, restart it now
            android.util.Log.d("MeshFragment",
                    "Auto-restarting mesh...");
            startMesh();
        } else if (runningNow || savedActive) {
            // Already running — show active button
            showActiveButton();
        } else {
            // Not active — show inactive button
            showInactiveButton();
        }
    }

    // ── Button setup ──────────────────────────────────────────────────

    private void setupStartMeshButton() {
        if (meshBtn == null) return;

        // Button is ALWAYS enabled
        meshBtn.setEnabled(true);

        meshBtn.setOnClickListener(v -> {
            boolean running =
                    MeshService.getInstance(
                                    requireContext())
                            .isActive();
            if (!running) {
                startMesh();
            } else {
                stopMesh();
            }
        });
    }

    // ── Start mesh ────────────────────────────────────────────────────

    private void startMesh() {
        try {
            SOSMessage beacon =
                    SOSMessage.buildBeacon(requireContext());
            MeshService.getInstance(requireContext())
                    .start(beacon.toBytes());

            // Save to prefs FIRST so state persists
            getPrefs().edit()
                    .putBoolean(KEY_ACTIVE, true)
                    .apply();

            showActiveButton();

            // Enable receiving after 2s
            new Handler(Looper.getMainLooper())
                    .postDelayed(() -> {
                        if (!isAdded()) return;
                        SosForegroundService
                                .enableReceiving(
                                        requireContext());
                        android.util.Log.d(
                                "MeshFragment",
                                "Receiving enabled");
                    }, 2000);

            android.util.Log.d("MeshFragment",
                    "Mesh STARTED and saved to prefs");

        } catch (Exception e) {
            android.util.Log.e("MeshFragment",
                    "startMesh error: " + e.getMessage());
        }
    }

    // ── Stop mesh ─────────────────────────────────────────────────────

    private void stopMesh() {
        try {
            MeshService.getInstance(requireContext())
                    .stop();

            // Save to prefs
            getPrefs().edit()
                    .putBoolean(KEY_ACTIVE, false)
                    .apply();

            showInactiveButton();

            android.util.Log.d("MeshFragment",
                    "Mesh STOPPED and saved to prefs");

        } catch (Exception e) {
            android.util.Log.e("MeshFragment",
                    "stopMesh error: " + e.getMessage());
        }
    }

    // ── Button appearance ─────────────────────────────────────────────

    private void showActiveButton() {
        if (!isAdded() || meshBtn == null) return;
        requireActivity().runOnUiThread(() -> {
            meshBtn.setText(
                    "Mesh active ✓  —  Tap to stop");
            // ALWAYS keep enabled = true
            // so user can tap to stop
            meshBtn.setEnabled(true);
            meshBtn.setAlpha(1.0f);
            meshBtn.setBackgroundTintList(
                    android.content.res.ColorStateList
                            .valueOf(requireContext()
                                    .getColor(
                                            R.color.signal_green)));
        });
    }

    private void showInactiveButton() {
        if (!isAdded() || meshBtn == null) return;
        requireActivity().runOnUiThread(() -> {
            meshBtn.setText("Start mesh receiver");
            meshBtn.setEnabled(true);
            meshBtn.setAlpha(1.0f);
            meshBtn.setBackgroundTintList(
                    android.content.res.ColorStateList
                            .valueOf(requireContext()
                                    .getColor(R.color.ember)));
        });
    }

    // ── SharedPreferences helper ──────────────────────────────────────

    private SharedPreferences getPrefs() {
        return requireContext()
                .getSharedPreferences(
                        PREFS, Context.MODE_PRIVATE);
    }

    // ── Preflight button ──────────────────────────────────────────────

    private void setupPreflightButton(View view) {
        MaterialButton btn =
                view.findViewById(R.id.btn_preflight);
        if (btn == null) return;
        btn.setOnClickListener(v -> {
            try {
                startActivity(
                        new android.content.Intent(
                                requireContext(),
                                PreflightActivity.class));
            } catch (Exception e) {
                android.util.Log.e("MeshFragment",
                        "Preflight: " + e.getMessage());
            }
        });
    }

    // ── Live refresh every 2 seconds ──────────────────────────────────

    private void startRefreshing() {
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isAdded() || getView() == null)
                    return;
                updateStatus();
                // Sync button state every 2s
                // to catch any unexpected mesh stop
                syncButton();
                refreshHandler.postDelayed(
                        this, 2000);
            }
        };
        refreshHandler.postDelayed(
                refreshRunnable, 500);
    }

    // Keeps button text correct after tab switch
    private void syncButton() {
        if (!isAdded() || meshBtn == null) return;
        boolean running =
                MeshService.getInstance(requireContext())
                        .isActive();
        boolean savedActive = getPrefs()
                .getBoolean(KEY_ACTIVE, false);
        String  txt =
                meshBtn.getText().toString();

        if (running && txt.contains("Start")) {
            // Mesh running but button says Start
            // — fix button
            showActiveButton();

        } else if (!running
                && txt.contains("active")
                && savedActive) {
            // Mesh was saved as active but stopped
            // unexpectedly — auto restart
            android.util.Log.w("MeshFragment",
                    "Mesh stopped unexpectedly — "
                            + "restarting");
            startMesh();
        }
    }

    private void updateStatus() {
        if (!isAdded() || getContext() == null)
            return;
        try {
            MeshService m =
                    MeshService.getInstance(
                            requireContext());
            boolean active = m.isActive();

            if (meshStatus != null) {
                meshStatus.setText(active
                        ? getString(R.string.mesh_status_on)
                        : getString(
                        R.string.mesh_status_off));
            }
            if (statusDot != null) {
                statusDot.setBackgroundResource(active
                        ? R.drawable.circle_dot
                        : R.drawable.circle_dot_red);
            }

            View v = getView();
            if (v == null) return;

            TextView bleVal = v.findViewById(
                    R.id.mesh_peer_count);
            TextView wifiVal = v.findViewById(
                    R.id.mesh_wifi_peer_count);

            int ble = m.getBleScanner() != null
                    ? m.getBleScanner().getPeerCount() : 0;
            int wifi = m.getWifiDirect() != null
                    ? m.getWifiDirect().getPeerCount() : 0;

            if (bleVal  != null)
                bleVal.setText(String.valueOf(ble));
            if (wifiVal != null)
                wifiVal.setText(String.valueOf(wifi));
            if (relayCount != null)
                relayCount.setText(String.valueOf(
                        m.getSeenMessageCount()));

        } catch (Exception e) {
            android.util.Log.e("MeshFragment",
                    "updateStatus: " + e.getMessage());
        }
    }

    // ── BLE Diagnostics ───────────────────────────────────────────────

    private void setupDiagnostics(View view) {
        MaterialButton btn =
                view.findViewById(R.id.btn_ble_check);
        TextView output =
                view.findViewById(R.id.ble_diag_output);
        if (btn == null || output == null) return;
        btn.setOnClickListener(v -> {
            try { runDiagnostics(output); }
            catch (Exception e) {
                output.setText("Error: "
                        + e.getMessage());
            }
        });
    }

    private void runDiagnostics(TextView out) {
        StringBuilder sb = new StringBuilder();
        android.bluetooth.BluetoothManager bm =
                (android.bluetooth.BluetoothManager)
                        requireContext().getSystemService(
                                Context.BLUETOOTH_SERVICE);
        android.bluetooth.BluetoothAdapter a =
                bm != null ? bm.getAdapter() : null;

        sb.append("── Bluetooth ──\n");
        sb.append("Adapter: ")
                .append(a == null ? "NULL ✗" : "OK ✓")
                .append("\n");
        sb.append("Enabled: ")
                .append(a != null && a.isEnabled()
                        ? "YES ✓" : "NO ✗").append("\n");
        sb.append("Multi-advert: ")
                .append(a != null
                        && a.isMultipleAdvertisementSupported()
                        ? "YES ✓" : "NO ✗").append("\n\n");

        sb.append("── Permissions ──\n");
        String[] perms = android.os.Build
                .VERSION.SDK_INT >= 31
                ? new String[]{
                android.Manifest.permission
                        .BLUETOOTH_SCAN,
                android.Manifest.permission
                        .BLUETOOTH_ADVERTISE,
                android.Manifest.permission
                        .BLUETOOTH_CONNECT,
                android.Manifest.permission
                        .ACCESS_FINE_LOCATION}
                : new String[]{
                android.Manifest.permission.BLUETOOTH,
                android.Manifest.permission
                        .BLUETOOTH_ADMIN,
                android.Manifest.permission
                        .ACCESS_FINE_LOCATION};

        for (String p : perms) {
            boolean ok =
                    androidx.core.content.ContextCompat
                            .checkSelfPermission(
                                    requireContext(), p)
                            == android.content.pm
                            .PackageManager.PERMISSION_GRANTED;
            sb.append(
                            p.substring(p.lastIndexOf('.') + 1))
                    .append(": ")
                    .append(ok ? "✓" : "✗ MISSING")
                    .append("\n");
        }
        sb.append("\n── Mesh ──\n");
        try {
            MeshService ms =
                    MeshService.getInstance(
                            requireContext());
            sb.append("Active: ")
                    .append(ms.isActive()
                            ? "YES ✓" : "NO ✗")
                    .append("\n");
            sb.append("Saved in prefs: ")
                    .append(getPrefs().getBoolean(
                            KEY_ACTIVE, false)
                            ? "YES" : "NO")
                    .append("\n");
        } catch (Exception e) {
            sb.append("Error: ").append(e.getMessage());
        }
        sb.append("\n── Device ──\n");
        sb.append("API: ")
                .append(android.os.Build.VERSION.SDK_INT)
                .append("\nModel: ")
                .append(android.os.Build.MODEL);
        out.setText(sb.toString());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        refreshHandler.removeCallbacks(refreshRunnable);
        statusDot  = null;
        meshStatus = null;
        relayCount = null;
        meshBtn    = null;
    }
}