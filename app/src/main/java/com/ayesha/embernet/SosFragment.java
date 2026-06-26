package com.ayesha.embernet;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

public class SosFragment extends Fragment
        implements SosForegroundService.ServiceCallback {

    // History
    private AlertHistoryManager historyManager;
    private LinearLayout        historyContainer;
    private TextView            historyCount;
    private TextView            historyEmpty;

    // UI views
    private TextView       gpsValue;
    private TextView       accuracyValue;
    private TextView       batteryValue;
    private TextView       statusText;
    private TextView       hopCountView;
    private TextView       peersReachedView;
    private TextView       broadcastCountView;
    private MaterialButton btnSos;
    private MaterialButton btnCancel;

    // Pulse ring views — FIXED: properly declared
    private View pulseRing;
    private View midRing;
    private View statsCard;

    // Service binding
    private SosForegroundService boundService;
    private boolean              serviceBound = false;

    private final ServiceConnection serviceConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(
                        ComponentName name, IBinder binder) {
                    SosForegroundService.SosBinder sosBinder =
                            (SosForegroundService.SosBinder) binder;
                    boundService = sosBinder.getService();
                    boundService.setCallback(
                            SosFragment.this);
                    serviceBound = true;
                    if (boundService.isBroadcasting()) {
                        applyBroadcastingUI(true);
                    }
                }

                @Override
                public void onServiceDisconnected(
                        ComponentName name) {
                    serviceBound  = false;
                    boundService  = null;
                }
            };

    // ── Fragment lifecycle ────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(
                R.layout.fragment_sos, container, false);
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindViews(view);
        updateBatteryDisplay();
        setupButtons();
        startLocationDisplay();
        setupAlertHistory();
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(
                requireContext(),
                SosForegroundService.class);
        requireContext().bindService(
                intent, serviceConnection,
                Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (serviceBound) {
            boundService.setCallback(null);
            requireContext().unbindService(
                    serviceConnection);
            serviceBound = false;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopLocationDisplay();
    }

    // ── View binding ──────────────────────────────────────────────────────

    private void bindViews(View view) {
        gpsValue         = view.findViewById(R.id.sos_gps_value);
        accuracyValue    = view.findViewById(R.id.sos_accuracy_value);
        batteryValue     = view.findViewById(R.id.sos_battery_value);
        statusText       = view.findViewById(R.id.sos_status_text);
        hopCountView     = view.findViewById(R.id.sos_hop_count);
        peersReachedView = view.findViewById(R.id.sos_peers_reached);
        broadcastCountView = view.findViewById(R.id.sos_broadcast_count);
        btnSos           = view.findViewById(R.id.btn_sos);
        btnCancel        = view.findViewById(R.id.btn_sos_cancel);
        statsCard        = view.findViewById(R.id.sos_stats_card);

        // Pulse ring views — find by ID from layout
        pulseRing = view.findViewById(R.id.sos_pulse_ring);
        midRing   = view.findViewById(R.id.sos_mid_ring);

        // Safety check — log if rings not found
        if (pulseRing == null) {
            android.util.Log.e("SosFragment",
                    "sos_pulse_ring NOT found in layout. "
                            + "Add it to fragment_sos.xml");
        }
        if (midRing == null) {
            android.util.Log.e("SosFragment",
                    "sos_mid_ring NOT found in layout. "
                            + "Add it to fragment_sos.xml");
        }
    }

    // ── Button setup ──────────────────────────────────────────────────────

    private void setupButtons() {
        if (btnSos == null) return;

        btnSos.setOnClickListener(v -> {
            if (serviceBound
                    && !boundService.isBroadcasting()) {
                // Press-down bounce animation
                android.animation.ObjectAnimator scaleX =
                        android.animation.ObjectAnimator
                                .ofFloat(btnSos, "scaleX",
                                        1f, 0.92f, 1f);
                android.animation.ObjectAnimator scaleY =
                        android.animation.ObjectAnimator
                                .ofFloat(btnSos, "scaleY",
                                        1f, 0.92f, 1f);
                android.animation.AnimatorSet press =
                        new android.animation.AnimatorSet();
                press.playTogether(scaleX, scaleY);
                press.setDuration(180);
                press.setInterpolator(
                        new android.view.animation
                                .OvershootInterpolator(2f));
                press.addListener(
                        new android.animation
                                .AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(
                                    android.animation.Animator a) {
                                SosForegroundService.start(
                                        requireContext());
                                applyBroadcastingUI(true);
                            }
                        });
                press.start();
            }
        });

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> {
                SosForegroundService.stop(
                        requireContext());
                applyBroadcastingUI(false);
            });
        }
    }

    // ── UI state ──────────────────────────────────────────────────────────

    private void applyBroadcastingUI(boolean broadcasting) {
        if (!isAdded()) return;

        if (broadcasting) {
            if (btnSos != null) {
                btnSos.setText(getString(
                        R.string.sos_broadcasting));
                btnSos.setBackgroundTintList(
                        android.content.res.ColorStateList
                                .valueOf(requireContext()
                                        .getColor(R.color.ember_dark)));
            }
            if (btnCancel != null) {
                btnCancel.setVisibility(View.VISIBLE);
            }
            if (statsCard != null) {
                statsCard.setVisibility(View.VISIBLE);
            }
            if (statusText != null) {
                statusText.setText(
                        "Broadcasting SOS to nearby devices…");
                statusText.setTextColor(
                        requireContext()
                                .getColor(R.color.ember));
            }
            // Start pulse — FIXED
            startPulseAnimation();

        } else {
            if (btnSos != null) {
                btnSos.setText("SOS");
                btnSos.setBackgroundTintList(
                        android.content.res.ColorStateList
                                .valueOf(requireContext()
                                        .getColor(R.color.ember)));
            }
            if (btnCancel != null) {
                btnCancel.setVisibility(View.GONE);
            }
            if (statsCard != null) {
                statsCard.setVisibility(View.GONE);
            }
            if (statusText != null) {
                statusText.setText(
                        getString(R.string.sos_waiting));
                statusText.setTextColor(
                        requireContext()
                                .getColor(R.color.warm_gray));
            }
            stopPulseAnimation();
            resetStats();
        }
    }

    private void resetStats() {
        if (hopCountView != null)
            hopCountView.setText("0");
        if (peersReachedView != null)
            peersReachedView.setText("0");
        if (broadcastCountView != null)
            broadcastCountView.setText("0");
    }

    // ── ServiceCallback ───────────────────────────────────────────────────

    @Override
    public void onBroadcastTick(SOSMessage message,
                                int count) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (broadcastCountView != null)
                broadcastCountView.setText(
                        String.valueOf(count));
            if (gpsValue != null)
                gpsValue.setText(
                        message.getFormattedCoords());
            if (batteryValue != null)
                batteryValue.setText(
                        message.battery + "%");
            colorBattery(message.battery);
        });
    }

    @Override
    public void onRelayReceived(SOSMessage message) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (historyManager != null) {
                historyManager.save(message);
                refreshHistoryList();
            }
            if (hopCountView != null) {
                int cur = Integer.parseInt(
                        hopCountView.getText().toString());
                hopCountView.setText(String.valueOf(
                        Math.max(cur, message.hopCount)));
            }
            if (peersReachedView != null) {
                int cur = Integer.parseInt(
                        peersReachedView.getText().toString());
                peersReachedView.setText(
                        String.valueOf(cur + 1));
            }
            if (statusText != null) {
                statusText.setText(
                        "Relay confirmed — " + message.deviceId);
                statusText.setTextColor(
                        requireContext()
                                .getColor(R.color.signal_green));
                new android.os.Handler().postDelayed(() -> {
                    if (!isAdded()) return;
                    statusText.setText(
                            "Broadcasting SOS to nearby devices…");
                    statusText.setTextColor(
                            requireContext()
                                    .getColor(R.color.ember));
                }, 2000);
            }
        });
    }

    @Override
    public void onGpsLocked(Location location) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (gpsValue != null)
                gpsValue.setText(
                        LocationTracker
                                .formatCoordsShort(location));
            if (accuracyValue != null)
                accuracyValue.setText(
                        "± " + Math.round(
                                location.getAccuracy()) + " m");
            if (gpsValue != null)
                gpsValue.setTextColor(
                        requireContext()
                                .getColor(R.color.cream));
        });
    }

    // ── Location display ──────────────────────────────────────────────────

    private LocationTracker displayTracker;

    private void startLocationDisplay() {
        displayTracker =
                new LocationTracker(requireContext());
        displayTracker.setListener(
                new LocationTracker.LocationListener() {
                    @Override
                    public void onLocationUpdated(
                            Location location) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            if (gpsValue != null)
                                gpsValue.setText(
                                        LocationTracker
                                                .formatCoordsShort(
                                                        location));
                            if (accuracyValue != null)
                                accuracyValue.setText(
                                        "± " + Math.round(
                                                location.getAccuracy())
                                                + " m");
                        });
                    }

                    @Override
                    public void onLocationUnavailable() {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            if (gpsValue != null)
                                gpsValue.setText(
                                        "GPS unavailable");
                        });
                    }
                });
        displayTracker.startTracking();
    }

    private void stopLocationDisplay() {
        if (displayTracker != null)
            displayTracker.stopTracking();
    }

    // ── Battery display ───────────────────────────────────────────────────

    private void updateBatteryDisplay() {
        android.content.IntentFilter filter =
                new android.content.IntentFilter(
                        android.content.Intent
                                .ACTION_BATTERY_CHANGED);
        android.content.Intent intent =
                requireContext().registerReceiver(
                        null, filter);
        if (intent == null) return;
        int level = intent.getIntExtra(
                android.os.BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(
                android.os.BatteryManager.EXTRA_SCALE, -1);
        int pct = (int)((level / (float) scale) * 100);
        if (batteryValue != null)
            batteryValue.setText(pct + "%");
        colorBattery(pct);
    }

    private void colorBattery(int pct) {
        if (!isAdded() || batteryValue == null) return;
        int color = pct <= 20 ? R.color.danger
                : pct <= 40 ? R.color.signal_yellow
                : R.color.signal_green;
        batteryValue.setTextColor(
                requireContext().getColor(color));
    }

    // ── Pulse animation — FIXED ───────────────────────────────────────────

    private void startPulseAnimation() {
        // Guard — rings must exist in layout
        if (pulseRing == null || midRing == null) {
            android.util.Log.e("SosFragment",
                    "Cannot animate — ring views null. "
                            + "Check fragment_sos.xml has "
                            + "R.id.sos_pulse_ring and "
                            + "R.id.sos_mid_ring");
            return;
        }

        pulseRing.setVisibility(View.VISIBLE);
        midRing.setVisibility(View.VISIBLE);
        pulseRing.setAlpha(1f);
        midRing.setAlpha(1f);

        // Start animations
        pulseRing.startAnimation(
                buildPulseAnim(1.4f, 0.6f, 1800, 0));
        midRing.startAnimation(
                buildPulseAnim(1.2f, 0.4f, 1800, 300));

        android.util.Log.d("SosFragment",
                "Pulse animation started ✓");
    }

    private void stopPulseAnimation() {
        if (pulseRing != null) {
            pulseRing.clearAnimation();
            pulseRing.setVisibility(View.INVISIBLE);
            pulseRing.setAlpha(0f);
        }
        if (midRing != null) {
            midRing.clearAnimation();
            midRing.setVisibility(View.INVISIBLE);
            midRing.setAlpha(0f);
        }
    }

    private AnimationSet buildPulseAnim(
            float scaleTo, float alphaFrom,
            long dur, long delay) {

        ScaleAnimation scale = new ScaleAnimation(
                1.0f, scaleTo, 1.0f, scaleTo,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f);
        scale.setDuration(dur);
        scale.setRepeatCount(Animation.INFINITE);
        scale.setRepeatMode(Animation.RESTART);
        scale.setStartOffset(delay);

        AlphaAnimation alpha = new AlphaAnimation(
                alphaFrom, 0.0f);
        alpha.setDuration(dur);
        alpha.setRepeatCount(Animation.INFINITE);
        alpha.setRepeatMode(Animation.RESTART);
        alpha.setStartOffset(delay);

        AnimationSet set = new AnimationSet(true);
        set.addAnimation(scale);
        set.addAnimation(alpha);
        return set;
    }

    // ── Alert history ─────────────────────────────────────────────────────

    private void setupAlertHistory() {
        historyManager =
                new AlertHistoryManager(requireContext());
        historyContainer = requireView().findViewById(
                R.id.alert_history_container);
        historyCount = requireView().findViewById(
                R.id.alert_history_count);
        historyEmpty = requireView().findViewById(
                R.id.alert_history_empty);
        refreshHistoryList();
    }

    private void refreshHistoryList() {
        if (historyContainer == null) return;
        historyContainer.removeAllViews();

        java.util.List<SOSMessage> alerts =
                historyManager.getAll();

        if (historyCount != null)
            historyCount.setText(
                    String.valueOf(alerts.size()));

        // Clear all button
        View clearAllBtn = requireView().findViewById(
                R.id.btn_clear_all_alerts);
        if (clearAllBtn != null) {
            clearAllBtn.setVisibility(
                    alerts.isEmpty()
                            ? View.GONE : View.VISIBLE);
            clearAllBtn.setOnClickListener(v -> {
                new com.google.android.material.dialog
                        .MaterialAlertDialogBuilder(
                        requireContext())
                        .setTitle("Clear all alerts?")
                        .setMessage(
                                "This will permanently delete "
                                        + "all " + alerts.size()
                                        + " received alerts.")
                        .setPositiveButton("Clear all",
                                (d, w) -> {
                                    historyManager.clear();
                                    refreshHistoryList();
                                })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }

        if (alerts.isEmpty()) {
            if (historyEmpty != null)
                historyEmpty.setVisibility(View.VISIBLE);
            return;
        }

        if (historyEmpty != null)
            historyEmpty.setVisibility(View.GONE);

        for (SOSMessage msg : alerts) {
            View row = android.view.LayoutInflater
                    .from(requireContext())
                    .inflate(R.layout.item_alert_history,
                            historyContainer, false);

            ((android.widget.TextView) row.findViewById(
                    R.id.history_item_coords))
                    .setText(msg.getFormattedCoords());

            ((android.widget.TextView) row.findViewById(
                    R.id.history_item_device))
                    .setText("Device " + msg.deviceId);

            ((android.widget.TextView) row.findViewById(
                    R.id.history_item_hops))
                    .setText(msg.hopCount + " hop"
                            + (msg.hopCount == 1 ? "" : "s"));

            ((android.widget.TextView) row.findViewById(
                    R.id.history_item_time))
                    .setText(msg.getFormattedTime());

            android.widget.TextView battView =
                    row.findViewById(
                            R.id.history_item_battery);
            battView.setText(msg.battery + "%");
            battView.setTextColor(
                    requireContext().getColor(
                            msg.battery <= 20
                                    ? R.color.danger
                                    : msg.battery <= 40
                                    ? R.color.signal_yellow
                                    : R.color.signal_green));

            // Tap row — show on map
            row.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity())
                            .onShowOnMap(msg);
                }
            });

            // Delete single alert
            android.widget.TextView deleteBtn =
                    row.findViewById(
                            R.id.history_item_delete);
            if (deleteBtn != null) {
                deleteBtn.setOnClickListener(v -> {
                    new com.google.android.material
                            .dialog
                            .MaterialAlertDialogBuilder(
                            requireContext())
                            .setTitle("Delete alert?")
                            .setMessage(
                                    "Remove alert from Device "
                                            + msg.deviceId + "?")
                            .setPositiveButton("Delete",
                                    (d, w) -> {
                                        historyManager
                                                .deleteById(
                                                        msg.messageId);
                                        refreshHistoryList();
                                    })
                            .setNegativeButton("Cancel",
                                    null)
                            .show();
                });
            }

            historyContainer.addView(row);
        }
    }
}