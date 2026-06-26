package com.ayesha.embernet;

import android.Manifest;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SafeZoneBottomSheet
        extends BottomSheetDialogFragment {

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

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args    = requireArguments();
        String name    = args.getString(ARG_NAME, "Safe Zone");
        String type    = args.getString(ARG_TYPE, "Shelter");
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
        if (verifiedView != null) verifiedView.setText("Yes");

        // Badge color
        if (badgeView != null) {
            int color;
            switch (type) {
                case "Medical": color =
                        requireContext().getColor(
                                R.color.signal_green); break;
                case "Rescue":  color =
                        requireContext().getColor(
                                R.color.signal_blue);  break;
                default:        color =
                        requireContext().getColor(
                                R.color.signal_yellow);
            }
            badgeView.setTextColor(color);
        }

        // Distance
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

        // Navigate button
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
                    Intent i =
                            new Intent(
                                    Intent
                                            .ACTION_VIEW,
                                    Uri.parse(url));
                    i.setPackage(
                            "com.google.android.apps.maps");
                    if (i.resolveActivity(
                            requireContext()
                                    .getPackageManager())
                            != null) {
                        startActivity(i);
                    } else {
                        startActivity(
                                new Intent(
                                        Intent
                                                .ACTION_VIEW,
                                        Uri.parse(
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


        MaterialButton btnShare =
                view.findViewById(R.id.btn_share_zone);
        if (btnShare != null) {
            btnShare.setOnClickListener(v -> {
                shareSafeZoneViaMesh(name, type,
                        lat, lon, capacity);
                dismiss();
            });
        }
    }

    // ── Share via mesh — sends to other devices only ──────────────────

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private void shareSafeZoneViaMesh(
            String name, String type,
            double lat, double lon, int capacity) {
        try {
            // Build compact payload for mesh broadcast
            // This goes to OTHER phones only
            // We use MeshService.send() directly
            // NOT sendBroadcast which triggers local alert

            // Build a special safe zone JSON
            JSONObject obj =
                    new JSONObject();
            obj.put("id",
                    "SZ" + (System.currentTimeMillis()
                            % 100000));
            obj.put("dev", "SAFEZONE");
            obj.put("typ",
                    SOSMessage.TYPE_BEACON); // BEACON not SOS
            obj.put("lat", lat);
            obj.put("lon", lon);
            obj.put("acc", 0);
            obj.put("bat", 0);
            obj.put("ts",
                    new SimpleDateFormat(
                            "HH:mm:ss",
                            Locale.getDefault())
                            .format(new Date()));
            obj.put("hop", 0);
            obj.put("name", name);
            obj.put("type", type);

            byte[] payload = obj.toString().getBytes(
                    StandardCharsets.UTF_8);

            // Send directly via MeshService
            // This broadcasts over BLE to nearby phones
            // but does NOT trigger a local alert
            MeshService.getInstance(requireContext())
                    .send(payload);

            Toast.makeText(requireContext(),
                    "Safe zone shared to nearby devices",
                    Toast.LENGTH_SHORT).show();

            Log.d("SafeZone",
                    "Shared " + name
                            + " via mesh to nearby devices");

        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    "Share failed: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
            Log.e("SafeZone",
                    "Share error: " + e.getMessage());
        }
    }

    @Override
    public int getTheme() {
        return com.google.android.material.R.style
                .ThemeOverlay_Material3_BottomSheetDialog;
    }
}