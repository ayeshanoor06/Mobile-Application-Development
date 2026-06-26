package com.ayesha.embernet;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

public class LocationTracker {

    private static final String TAG = "LocationTracker";

    private static final long INTERVAL_MS     = 3000;
    private static final long MIN_INTERVAL_MS = 5000;

    // Shared last known location across all instances
    // so GPS fix from map screen is reused on SOS screen
    private static Location globalLastLocation = null;

    public interface LocationListener {
        void onLocationUpdated(Location location);
        void onLocationUnavailable();
    }

    private final Context                  context;
    private final FusedLocationProviderClient fusedClient;
    private final LocationManager          locationManager;
    private       LocationCallback         fusedCallback;
    private       android.location.LocationListener         gpsListener;
    private       LocationListener        listener;
    private       boolean                  isTracking = false;

    public LocationTracker(Context context) {
        this.context         = context.getApplicationContext();
        this.fusedClient     = LocationServices
                .getFusedLocationProviderClient(context);
        this.locationManager = (LocationManager)
                context.getSystemService(
                        Context.LOCATION_SERVICE);
    }

    public void setListener(LocationListener listener) {
        this.listener = listener;
    }

    // ── Start tracking ────────────────────────────────────────────────

    public void startTracking() {
        if (isTracking) return;

        if (ActivityCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted");
            if (listener != null) {
                listener.onLocationUnavailable();
            }
            return;
        }

        isTracking = true;

        // Deliver cached location immediately if available
        if (globalLastLocation != null) {
            Log.d(TAG, "Delivering cached location");
            if (listener != null) {
                listener.onLocationUpdated(
                        globalLastLocation);
            }
        }

        // Start FusedLocation (works best with network)
        startFusedTracking();

        // ALSO start direct GPS provider
        // This works on Airplane Mode using satellites only
        startDirectGpsTracking();

        // Get last known from fused immediately
        fusedClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        updateLocation(location,
                                "Fused last known");
                    }
                });
    }

    // ── Fused location ────────────────────────────────────────────────

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    private void startFusedTracking() {
        LocationRequest request =
                new LocationRequest.Builder(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        INTERVAL_MS)
                        .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
                        .setWaitForAccurateLocation(false)
                        .build();

        fusedCallback = new LocationCallback() {
            @Override
            public void onLocationResult(
                    LocationResult result) {
                if (result == null) return;
                Location loc = result.getLastLocation();
                if (loc != null) {
                    updateLocation(loc, "Fused");
                }
            }
        };

        try {
            fusedClient.requestLocationUpdates(
                    request, fusedCallback,
                    Looper.getMainLooper());
            Log.d(TAG, "Fused tracking started");
        } catch (Exception e) {
            Log.e(TAG, "Fused start failed: "
                    + e.getMessage());
        }
    }

    // ── Direct GPS — works on Airplane Mode ──────────────────────────

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    private void startDirectGpsTracking() {
        try {
            boolean gpsEnabled = locationManager
                    .isProviderEnabled(
                            LocationManager.GPS_PROVIDER);

            if (!gpsEnabled) {
                Log.w(TAG, "GPS provider disabled");
                return;
            }

            gpsListener = new android.location.LocationListener() {
                @Override
                public void onLocationChanged(
                        Location location) {
                    updateLocation(location,
                            "Direct GPS");
                }

                @Override
                public void onStatusChanged(
                        String provider, int status,
                        Bundle extras) {}

                @Override
                public void onProviderEnabled(
                        String provider) {
                    Log.d(TAG, provider + " enabled");
                }

                @Override
                public void onProviderDisabled(
                        String provider) {
                    Log.w(TAG, provider + " disabled");
                }
            };

            // Request updates directly from GPS satellite
            // minTime=3000ms, minDistance=0m
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    3000, 0f, gpsListener,
                    Looper.getMainLooper());


            if (locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        3000, 0f, gpsListener,
                        Looper.getMainLooper());
            }

            // Get last known GPS location immediately
            Location lastGps = locationManager
                    .getLastKnownLocation(
                            LocationManager.GPS_PROVIDER);
            if (lastGps != null) {
                updateLocation(lastGps,
                        "GPS last known");
            }

            Log.d(TAG, "Direct GPS tracking started");

        } catch (Exception e) {
            Log.e(TAG, "Direct GPS start failed: "
                    + e.getMessage());
        }
    }

    // ── Update location ───────────────────────────────────────────────

    private void updateLocation(Location location,
                                String source) {
        if (location == null) return;

        // Only accept location with valid coordinates
        if (location.getLatitude() == 0.0
                && location.getLongitude() == 0.0) {
            Log.w(TAG, source + ": invalid 0,0 location");
            return;
        }

        // Prefer more accurate fix
        if (globalLastLocation != null
                && location.getAccuracy()
                > globalLastLocation.getAccuracy()
                + 10) {
            Log.d(TAG, source
                    + ": less accurate than cached, skip");
            return;
        }

        globalLastLocation = location;
        Log.d(TAG, source + " location: "
                + location.getLatitude() + ", "
                + location.getLongitude()
                + " acc=" + location.getAccuracy() + "m");

        if (listener != null) {
            listener.onLocationUpdated(location);
        }
    }

    // ── Stop tracking ─────────────────────────────────────────────────

    public void stopTracking() {
        if (!isTracking) return;

        // Stop fused
        if (fusedCallback != null) {
            try {
                fusedClient.removeLocationUpdates(
                        fusedCallback);
            } catch (Exception e) {
                Log.e(TAG, "Fused stop: "
                        + e.getMessage());
            }
            fusedCallback = null;
        }

        // Stop direct GPS
        if (gpsListener != null) {
            try {
                locationManager
                        .removeUpdates(gpsListener);
            } catch (Exception e) {
                Log.e(TAG, "GPS stop: "
                        + e.getMessage());
            }
            gpsListener = null;
        }

        isTracking = false;
        Log.d(TAG, "Location tracking stopped");
    }

    // ── Getters ───────────────────────────────────────────────────────

    public Location getLastKnownLocation() {
        // Return global cached location even if
        // this instance is not currently tracking
        return globalLastLocation;
    }

    public boolean isTracking() { return isTracking; }

    // ── Static formatters ─────────────────────────────────────────────

    public static String formatCoords(
            Location location) {
        if (location == null
                || (location.getLatitude() == 0.0
                && location.getLongitude() == 0.0)) {
            return "Acquiring GPS...";
        }
        return String.format(
                "%.4f N  %.4f E  +/-%.0fm",
                location.getLatitude(),
                location.getLongitude(),
                location.getAccuracy());
    }

    public static String formatCoordsShort(
            Location location) {
        if (location == null
                || (location.getLatitude() == 0.0
                && location.getLongitude() == 0.0)) {
            return "GPS acquiring...";
        }
        return String.format("%.6f, %.6f",
                location.getLatitude(),
                location.getLongitude());
    }
}
