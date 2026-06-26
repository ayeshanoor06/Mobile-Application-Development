package com.ayesha.embernet;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.location.Location;

import androidx.core.content.ContextCompat;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.util.ArrayList;
import java.util.List;



public class MapOverlayManager {
    private MapPinAnimator pinAnimator;
    public interface OnSafeZoneTapped {
        void onTapped(SafeZone zone);
    }

    private OnSafeZoneTapped onSafeZoneTapped;

    public void setOnSafeZoneTapped(OnSafeZoneTapped callback) {
        this.onSafeZoneTapped = callback;
    }
    private final Context context;
    private final MapView mapView;

    private Marker userMarker;
    private final List<Marker> safeZoneMarkers = new ArrayList<>();
    private final List<Marker> sosAlertMarkers = new ArrayList<>();

    public MapOverlayManager(Context context, MapView mapView) {
        this.context = context;
        this.mapView = mapView;
        this.pinAnimator = new MapPinAnimator(context, mapView);
    }

    // ── User location marker ──────────────────────────────────────────────

    public void updateUserLocation(Location location) {
        GeoPoint point = new GeoPoint(
                location.getLatitude(),
                location.getLongitude()
        );

        if (userMarker == null) {
            userMarker = new Marker(mapView);
            userMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            userMarker.setTitle("Your location");
            userMarker.setInfoWindow(null); // no popup bubble
            mapView.getOverlays().add(userMarker);
        }

        Drawable icon = ContextCompat.getDrawable(
                context, R.drawable.marker_user_location
        );
        userMarker.setIcon(icon);
        userMarker.setPosition(point);

        // Show bearing arrow if device is moving
        if (location.hasBearing()) {
            userMarker.setRotation(-location.getBearing());
        }

        mapView.invalidate();
    }

    // ── Safe zone markers ─────────────────────────────────────────────────

    public void addSafeZones() {
        for (Marker m : safeZoneMarkers) {
            mapView.getOverlays().remove(m);
        }
        safeZoneMarkers.clear();

        List<SafeZone> zones = getDefaultSafeZones();
        Drawable icon = ContextCompat.getDrawable(
                context, R.drawable.marker_safe_zone
        );

        for (SafeZone zone : zones) {
            Marker marker = new Marker(mapView);
            marker.setPosition(new GeoPoint(zone.lat, zone.lon));
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            marker.setIcon(icon);
            marker.setTitle(zone.name);
            marker.setSnippet(zone.type);
            marker.setInfoWindow(null);

            // Store zone data in marker for retrieval on tap
            marker.setRelatedObject(zone);

            marker.setOnMarkerClickListener((m, map) -> {
                if (onSafeZoneTapped != null) {
                    onSafeZoneTapped.onTapped(zone);
                }
                return true;
            });

            mapView.getOverlays().add(marker);
            safeZoneMarkers.add(marker);
        }

        mapView.invalidate();
    }

    // ── SOS alert markers ─────────────────────────────────────────────────

    public void addSosAlertMarker(double lat, double lon,
                                  String deviceId, int hops, int battery, String time) {

        // Remove previous SOS markers first — one at a time
        // so the map doesn't get cluttered across multiple alerts
        if (!sosAlertMarkers.isEmpty()) {
            Marker old = sosAlertMarkers.get(sosAlertMarkers.size() - 1);
            mapView.getOverlays().remove(old);
            sosAlertMarkers.remove(old);
        }

        pinAnimator.stopPulse();

        Marker marker = new Marker(mapView);
        marker.setPosition(new GeoPoint(lat, lon));
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        marker.setTitle("SOS — Device " + deviceId);
        marker.setSnippet("Battery: " + battery
                + "%   Hops: " + hops
                + "   Time: " + time);
        marker.setInfoWindow(null);

        mapView.getOverlays().add(marker);
        sosAlertMarkers.add(marker);

        // Start pulsing animation
        pinAnimator.startPulse(marker);

        // Smooth pan to alert location
        mapView.getController().animateTo(new GeoPoint(lat, lon));
        mapView.getController().setZoom(16.0);
        mapView.invalidate();
    }
    public void clearSosMarkers() {
        for (Marker m : sosAlertMarkers) {
            mapView.getOverlays().remove(m);
        }
        sosAlertMarkers.clear();
        mapView.invalidate();
    }

    // ── Safe zone data ────────────────────────────────────────────────────

    private List<SafeZone> getDefaultSafeZones() {
        List<SafeZone> zones = new ArrayList<>();

        zones.add(new SafeZone(
                "DHQ Hospital Layyah",
                "Medical", 30.9611, 70.9378, 300));
        zones.add(new SafeZone(
                "Civil Hospital Layyah",
                "Medical", 30.9630, 70.9410, 150));
        zones.add(new SafeZone(
                "Rescue 1122 Layyah",
                "Rescue",  30.9598, 70.9355, 30));
        zones.add(new SafeZone(
                "DC Office Layyah",
                "Shelter", 30.9642, 70.9401, 200));
        zones.add(new SafeZone(
                "Layyah Chowk",
                "Shelter", 30.9620, 70.9390, 500));
        zones.add(new SafeZone(
                "DHQ Hopital Layyah",
                "Hospital", 30.9741, 70.9575, 500));

        zones.add(new SafeZone(
                "Sirati Hopital pvt ltd Layyah",
                "Hospital", 30.9585, 70.9602, 100));



        return zones;
    }

    // Simple data holder for safe zones
    public static class SafeZone {
        public final String name;
        public final String type;
        public final double lat;
        public final double lon;
        public final int    capacity;

        public SafeZone(String name, String type,
                        double lat, double lon, int capacity) {
            this.name     = name;
            this.type     = type;
            this.lat      = lat;
            this.lon      = lon;
            this.capacity = capacity;
        }
    }

    public void stopAnimations() {
        if (pinAnimator != null) pinAnimator.stopPulse();
    }
}
