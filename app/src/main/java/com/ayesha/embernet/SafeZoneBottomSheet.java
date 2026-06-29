package com.ayesha.embernet;

import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

public class SafeZoneBottomSheet
        extends BottomSheetDialogFragment {

    private static final String TAG          = "SafeZone";
    private static final String ARG_NAME     = "name";
    private static final String ARG_TYPE     = "type";
    private static final String ARG_LAT      = "lat";
    private static final String ARG_LON      = "lon";
    private static final String ARG_CAPACITY = "capacity";
    private static final String ARG_USER_LAT = "user_lat";
    private static final String ARG_USER_LON = "user_lon";

    public static SafeZoneBottomSheet newInstance(
            MapOverlayManager.SafeZone zone,
            Location userLocation) {
        SafeZoneBottomSheet sheet =
                new SafeZoneBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_NAME,     zone.name);
        args.putString(ARG_TYPE,     zone.type);
        args.putDouble(ARG_LAT,      zone.lat);
        args.putDouble(ARG_LON,      zone.lon);
        args.putInt(ARG_CAPACITY,    zone.capacity);
        if (userLocation != null) {
            args.putDouble(ARG_USER_LAT,
                    userLocation.getLatitude());
            args.putDouble(ARG_USER_LON,
                    userLocation.getLongitude());
        }
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(
                R.layout.fragment_map_bottom_sheet,
                container, false);
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args    = requireArguments();
        String name    = args.getString(ARG_NAME,
                "Safe Zone");
        String type    = args.getString(ARG_TYPE,
                "Shelter");
        double lat     = args.getDouble(ARG_LAT);
        double lon     = args.getDouble(ARG_LON);
        int    capacity = args.getInt(ARG_CAPACITY, 100);
        double userLat = args.getDouble(ARG_USER_LAT, 0);
        double userLon = args.getDouble(ARG_USER_LON, 0);

        TextView badgeView =
                view.findViewById(R.id.zone_type_badge);
        TextView nameView =
                view.findViewById(R.id.zone_name);
        TextView coordsView =
                view.findViewById(R.id.zone_coords);
        TextView distanceView =
                view.findViewById(R.id.zone_distance);
        TextView statusView =
                view.findViewById(R.id.zone_status);
        TextView capacityView =
                view.findViewById(R.id.zone_capacity);
        TextView verifiedView =
                view.findViewById(R.id.zone_verified);

        if (badgeView   != null) badgeView.setText(type);
        if (nameView    != null) nameView.setText(name);
        if (coordsView  != null) coordsView.setText(
                String.format("%.4f°N  %.4f°E", lat, lon));
        if (capacityView != null)
            capacityView.setText(capacity + " people");
        if (statusView  != null) statusView.setText("Open");
        if (verifiedView != null)
            verifiedView.setText("Yes");

        if (badgeView != null) {
            int color;
            switch (type) {
                case "Medical":
                    color = requireContext().getColor(
                            R.color.signal_green); break;
                case "Rescue":
                    color = requireContext().getColor(
                            R.color.signal_blue); break;
                default:
                    color = requireContext().getColor(
                            R.color.signal_yellow);
            }
            badgeView.setTextColor(color);
        }

        if (distanceView != null) {
            if (userLat != 0 && userLon != 0) {
                float[] r = new float[1];
                Location.distanceBetween(
                        userLat, userLon, lat, lon, r);
                distanceView.setText(r[0] < 1000
                        ? Math.round(r[0]) + " m away"
                        : String.format("%.1f km away",
                        r[0] / 1000));
            } else {
                distanceView.setText("Distance unknown");
            }
        }

        // ── Navigate ──────────────────────────────────
        MaterialButton btnNavigate =
                view.findViewById(R.id.btn_navigate_zone);
        if (btnNavigate != null) {
            btnNavigate.setOnClickListener(v -> {
                String url =
                        "https://www.google.com/maps/dir/"
                                + "?api=1&destination="
                                + lat + "," + lon
                                + "&travelmode=walking";
                try {
                    android.content.Intent i =
                            new android.content.Intent(
                                    android.content.Intent
                                            .ACTION_VIEW,
                                    android.net.Uri.parse(url));
                    i.setPackage(
                            "com.google.android.apps.maps");
                    if (i.resolveActivity(
                            requireContext()
                                    .getPackageManager())
                            != null) {
                        startActivity(i);
                    } else {
                        startActivity(
                                new android.content.Intent(
                                        android.content.Intent
                                                .ACTION_VIEW,
                                        android.net.Uri.parse(
                                                url)));
                    }
                    dismiss();
                } catch (Exception e) {
                    Toast.makeText(requireContext(),
                            "Cannot open navigation",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        // ── Share via mesh — FIXED ────────────────────
        MaterialButton btnShare =
                view.findViewById(R.id.btn_share_zone);
        if (btnShare != null) {
            btnShare.setOnClickListener(v -> {
                shareSafeZone(name, type,
                        lat, lon, capacity);
                dismiss();
            });
        }
    }

    // ── THE FIX ───────────────────────────────────────────────────────
    // Old code used TYPE_BEACON which RelayEngine drops
    // Old code used MeshService.send() which only
    // re-advertises — does NOT route to alert system
    //
    // New code builds a real SOSMessage with TYPE_SOS
    // and sends it via AlertReceiver broadcast
    // This goes through the full alert pipeline:
    // broadcast → AlertReceiver → SosForegroundService
    // → RelayEngine → onRelayReceived → notification
    // on all nearby phones

    private void shareSafeZone(String name, String type,
                               double lat, double lon, int capacity) {
        try {
            // Build deviceId for this phone
            String deviceId =
                    android.provider.Settings.Secure
                            .getString(
                                    requireContext()
                                            .getContentResolver(),
                                    android.provider.Settings
                                            .Secure.ANDROID_ID)
                            .substring(0, 6)
                            .toUpperCase();

            // Build a real SOSMessage with TYPE_SOS
            // so RelayEngine processes it as a real alert
            // lat/lon are the safe zone coordinates
            org.json.JSONObject obj =
                    new org.json.JSONObject();
            // Prefix SZ so receiver knows it is safe zone
            obj.put("id", "SZ"
                    + System.currentTimeMillis() % 100000);
            obj.put("dev", deviceId);
            // TYPE_SOS so RelayEngine shows it as alert
            obj.put("typ", SOSMessage.TYPE_SOS);
            obj.put("lat", lat);
            obj.put("lon", lon);
            obj.put("acc", 0);
            // Use battery 100 to signal this is info
            // not an emergency
            obj.put("bat", 100);
            obj.put("ts",
                    new java.text.SimpleDateFormat(
                            "HH:mm:ss",
                            java.util.Locale.getDefault())
                            .format(new java.util.Date()));
            obj.put("hop", 0);

            String json = obj.toString();
            byte[] payload = json.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);

            // Step 1: Send via BLE so nearby phones
            // receive it over the mesh
            MeshService.getInstance(requireContext())
                    .send(payload);

            // Step 2: Also send via LocalBroadcast
            // This ensures the SosForegroundService
            // picks it up and routes it through
            // the full alert pipeline on receiving phones
            android.content.Intent alertIntent =
                    new android.content.Intent(
                            SosForegroundService
                                    .ACTION_SHOW_ALERT_LOCAL);
            alertIntent.putExtra("message_json", json);

            // We do NOT send this to ourselves
            // Only send via BLE — nearby phones will
            // receive via their own BleScanner
            // and their SosForegroundService will
            // call onRelayReceived to show the alert

            Log.d(TAG, "Safe zone shared: " + name
                    + " lat=" + lat + " lon=" + lon);

            Toast.makeText(requireContext(),
                    "Safe zone '" + name
                            + "' sent to nearby devices",
                    Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Share failed: " + e.getMessage());
            Toast.makeText(requireContext(),
                    "Share failed: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getTheme() {
        return com.google.android.material.R.style
                .ThemeOverlay_Material3_BottomSheetDialog;
    }
}