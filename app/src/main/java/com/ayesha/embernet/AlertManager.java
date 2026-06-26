package com.ayesha.embernet;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.TranslateAnimation;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayDeque;
import java.util.Queue;

public class AlertManager {

    public interface AlertActionListener {
        void onShowOnMap(SOSMessage message);
        void onDismiss(SOSMessage message);
    }

    private final Activity        activity;
    private final ViewGroup       rootView;
    private       AlertActionListener listener;
    private       View            currentAlert = null;
    private final Queue<SOSMessage> pendingAlerts =
            new ArrayDeque<>();

    public AlertManager(Activity activity,
                        ViewGroup rootView) {
        this.activity = activity;
        this.rootView = rootView;
    }

    public void setListener(
            AlertActionListener listener) {
        this.listener = listener;
    }

    // ── Show alert ────────────────────────────────────────────────────

    public void showAlert(SOSMessage message) {
        if (message == null) return;

        // Only show real SOS — never beacons
        // or safe zone shares
        if (!message.isRealSOS()) {
            android.util.Log.d("AlertManager",
                    "Skipping non-SOS message type="
                            + message.type);
            return;
        }

        activity.runOnUiThread(() -> {
            // If alert already showing, queue this one
            if (currentAlert != null) {
                pendingAlerts.offer(message);
                android.util.Log.d("AlertManager",
                        "Queued alert from "
                                + message.deviceId);
                return;
            }
            displayAlert(message);
        });
    }

    private void displayAlert(SOSMessage message) {
        // Inflate alert view
        View alertView = LayoutInflater.from(activity)
                .inflate(R.layout.alert_incoming,
                        rootView, false);

        // Bind data
        TextView deviceId =
                alertView.findViewById(
                        R.id.alert_device_id);
        TextView coords =
                alertView.findViewById(
                        R.id.alert_coords);
        TextView battery =
                alertView.findViewById(
                        R.id.alert_battery);
        TextView hops =
                alertView.findViewById(
                        R.id.alert_hop_count);
        TextView time =
                alertView.findViewById(
                        R.id.alert_time);

        if (deviceId != null)
            deviceId.setText(
                    "Device " + message.deviceId);
        if (coords != null)
            coords.setText(
                    message.getFormattedCoords());
        if (battery != null) {
            battery.setText(message.battery + "%");
            battery.setTextColor(
                    activity.getColor(
                            message.battery <= 20
                                    ? R.color.danger
                                    : message.battery <= 40
                                    ? R.color.signal_yellow
                                    : R.color.signal_green));
        }
        if (hops != null)
            hops.setText(message.hopCount + " hop"
                    + (message.hopCount == 1 ? "" : "s"));
        if (time != null)
            time.setText(message.getFormattedTime());

        // Pulsing alert dot
        View dot = alertView.findViewById(
                R.id.alert_pulse_dot);
        if (dot != null) {
            AlphaAnimation blink =
                    new AlphaAnimation(1f, 0.2f);
            blink.setDuration(600);
            blink.setRepeatCount(
                    Animation.INFINITE);
            blink.setRepeatMode(
                    Animation.REVERSE);
            dot.startAnimation(blink);
        }

        // Show on map button
        MaterialButton btnMap =
                alertView.findViewById(
                        R.id.btn_alert_show_map);
        if (btnMap != null) {
            btnMap.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onShowOnMap(message);
                }
                // FIXED: properly remove overlay
                removeAlert(alertView);
            });
        }

        // Dismiss button — FIXED
        MaterialButton btnDismiss =
                alertView.findViewById(
                        R.id.btn_alert_dismiss);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDismiss(message);
                }
                // FIXED: properly remove overlay
                removeAlert(alertView);
            });
        }

        // Add to root — FIXED: use correct layout params
        // so overlay does not block underlying views
        ViewGroup.LayoutParams params =
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        rootView.addView(alertView, params);
        currentAlert = alertView;

        // Slide up animation
        slideIn(alertView);

        android.util.Log.d("AlertManager",
                "Alert displayed for "
                        + message.deviceId);
    }

    // ── Remove alert — FIXED ──────────────────────────────────────────

    private void removeAlert(View alertView) {
        if (alertView == null) return;

        slideOut(alertView, () -> {
            // Remove from parent
            if (alertView.getParent() != null) {
                ((ViewGroup) alertView.getParent())
                        .removeView(alertView);
            }
            currentAlert = null;

            android.util.Log.d("AlertManager",
                    "Alert removed. Pending: "
                            + pendingAlerts.size());

            // Show next queued alert if any
            if (!pendingAlerts.isEmpty()) {
                SOSMessage next =
                        pendingAlerts.poll();
                activity.runOnUiThread(() ->
                        displayAlert(next));
            }
        });
    }

    // ── Animations ────────────────────────────────────────────────────

    private void slideIn(View view) {
        AnimationSet set = new AnimationSet(true);

        TranslateAnimation slide =
                new TranslateAnimation(
                        Animation.RELATIVE_TO_PARENT, 0f,
                        Animation.RELATIVE_TO_PARENT, 0f,
                        Animation.RELATIVE_TO_SELF, 1f,
                        Animation.RELATIVE_TO_SELF, 0f);
        slide.setDuration(350);

        AlphaAnimation fade =
                new AlphaAnimation(0f, 1f);
        fade.setDuration(350);

        set.addAnimation(slide);
        set.addAnimation(fade);
        set.setFillAfter(true);
        view.startAnimation(set);
    }

    private void slideOut(View view,
                          Runnable onComplete) {
        AnimationSet set = new AnimationSet(true);

        TranslateAnimation slide =
                new TranslateAnimation(
                        Animation.RELATIVE_TO_PARENT, 0f,
                        Animation.RELATIVE_TO_PARENT, 0f,
                        Animation.RELATIVE_TO_SELF, 0f,
                        Animation.RELATIVE_TO_SELF, 1f);
        slide.setDuration(280);

        AlphaAnimation fade =
                new AlphaAnimation(1f, 0f);
        fade.setDuration(280);

        set.addAnimation(slide);
        set.addAnimation(fade);
        set.setFillAfter(true);
        set.setAnimationListener(
                new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(
                            Animation a) {}
                    @Override
                    public void onAnimationEnd(
                            Animation a) {
                        // Run on main thread
                        view.post(onComplete);
                    }
                    @Override
                    public void onAnimationRepeat(
                            Animation a) {}
                });
        view.startAnimation(set);
    }

    // ── Dismiss all ───────────────────────────────────────────────────

    public void dismissAll() {
        pendingAlerts.clear();
        if (currentAlert != null) {
            if (currentAlert.getParent() != null) {
                ((ViewGroup) currentAlert.getParent())
                        .removeView(currentAlert);
            }
            currentAlert = null;
        }
    }
}
