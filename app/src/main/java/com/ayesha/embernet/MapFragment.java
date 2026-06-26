package com.ayesha.embernet;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;

public class MapFragment extends Fragment
        implements LocationTracker.LocationListener {

    private MapView             mapView;
    private LocationTracker     locationTracker;
    private MapOverlayManager   overlayManager;
    private OfflineMapManager   offlineMapManager;
    private TextView            coordsText;
    private TextView            statusText;
    private View                statusDot;
    private MaterialButton      btnDownloadMap;
    private boolean             mapCenteredOnUser = false;

    private static final double DEFAULT_LAT  = 30.9625;
    private static final double DEFAULT_LON  = 70.9394;
    private static final double DEFAULT_ZOOM = 17.0;
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(
                R.layout.fragment_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mapView       = view.findViewById(R.id.map_view);
        coordsText    = view.findViewById(R.id.map_coords_text);
        statusText    = view.findViewById(R.id.map_status_text);
        statusDot     = view.findViewById(R.id.map_status_dot);
        btnDownloadMap = view.findViewById(R.id.btn_download_map);

        offlineMapManager =
                new OfflineMapManager(requireContext());

        setupMap();

        overlayManager =
                new MapOverlayManager(requireContext(), mapView);
        locationTracker =
                new LocationTracker(requireContext());
        locationTracker.setListener(this);

        overlayManager.addSafeZones();
        setupMarkerListeners();
        setupButtons(view);
    }

    // ── Map setup ─────────────────────────────────────────────────────────

    private void setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getZoomController().setVisibility(
                org.osmdroid.views.CustomZoomButtonsController
                        .Visibility.NEVER);
        mapView.getController().setZoom(DEFAULT_ZOOM);
        mapView.getController().setCenter(
                new GeoPoint(DEFAULT_LAT, DEFAULT_LON));
        // NO color filter — shows map in natural colors
        mapView.setTilesScaledToDpi(true);
        mapView.setUseDataConnection(true);
    }

    // ── Buttons ───────────────────────────────────────────────────────────

    private void setupButtons(@NonNull View view) {

        // My location button
        MaterialButton btnLocation =
                view.findViewById(R.id.btn_my_location);
        if (btnLocation != null) {
            btnLocation.setOnClickListener(v -> {
                Location loc =
                        locationTracker.getLastKnownLocation();
                if (loc != null) {
                    mapView.getController().animateTo(
                            new GeoPoint(
                                    loc.getLatitude(),
                                    loc.getLongitude()));
                    mapView.getController().setZoom(17.0);
                } else {
                    Toast.makeText(requireContext(),
                            "GPS not locked yet",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Download map button — FIX 1
        if (btnDownloadMap != null) {
            btnDownloadMap.setOnClickListener(v ->
                    downloadOfflineMap());
        }
    }

    // ── Marker listeners ──────────────────────────────────────────────────

    private void setupMarkerListeners() {
        overlayManager.setOnSafeZoneTapped(zone -> {
            Location userLoc =
                    locationTracker.getLastKnownLocation();
            SafeZoneBottomSheet sheet =
                    SafeZoneBottomSheet.newInstance(
                            zone, userLoc);
            sheet.show(getChildFragmentManager(),
                    "safe_zone");
        });
    }

    // ── SOS pin on map ────────────────────────────────────────────────────

    public void showSosAlertOnMap(double lat, double lon,
                                  int battery, int hops,
                                  String time, String deviceId) {
        if (!isAdded() || mapView == null) return;
        requireActivity().runOnUiThread(() -> {
            overlayManager.addSosAlertMarker(
                    lat, lon, deviceId, hops, battery, time);
            SosAlertBottomSheet sheet =
                    SosAlertBottomSheet.newInstance(
                            lat, lon, battery, hops,
                            time, deviceId);
            sheet.show(getChildFragmentManager(),
                    "sos_alert");
        });
    }

    // ── Download offline map — FIXED ──────────────────────────────────────

    private void downloadOfflineMap() {
        // Check internet connection
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager)
                        requireContext().getSystemService(
                                android.content.Context
                                        .CONNECTIVITY_SERVICE);
        android.net.NetworkInfo netInfo =
                cm.getActiveNetworkInfo();
        if (netInfo == null || !netInfo.isConnected()) {
            Toast.makeText(requireContext(),
                    "No internet. Connect to Wi-Fi first.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (offlineMapManager.isMapSaved()) {
            // Ask user if they want to re-download
            new com.google.android.material.dialog
                    .MaterialAlertDialogBuilder(
                    requireContext())
                    .setTitle("Map already saved")
                    .setMessage(
                            "You already have an offline map. "
                                    + "Download again to refresh?")
                    .setPositiveButton("Re-download",
                            (d, w) -> startDownload())
                    .setNegativeButton("Keep existing",
                            null)
                    .show();
        } else {
            startDownload();
        }
    }

    private void startDownload() {
        GeoPoint center =
                (GeoPoint) mapView.getMapCenter();

        // Disable button during download
        if (btnDownloadMap != null) {
            btnDownloadMap.setEnabled(false);
            btnDownloadMap.setText("Downloading…");
        }

        // Clear old partial download so it
        // always starts fresh — fixes instant toast bug
        offlineMapManager.clearOfflineMap();

        // Show progress dialog
        android.app.ProgressDialog progress =
                new android.app.ProgressDialog(
                        requireContext());
        progress.setTitle("Downloading offline map");
        progress.setMessage(
                "Stay on Wi-Fi. About 1 minute...");
        progress.setProgressStyle(
                android.app.ProgressDialog
                        .STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setCancelable(false);
        progress.setButton(
                android.app.ProgressDialog.BUTTON_NEGATIVE,
                "Cancel",
                (d, w) -> {
                    offlineMapManager.cancelDownload();
                    if (btnDownloadMap != null) {
                        btnDownloadMap.setEnabled(true);
                        btnDownloadMap.setText(
                                getString(R.string.download_map));
                    }
                });
        progress.show();

        android.util.Log.d("MapFragment",
                "Download started — center: "
                        + center.getLatitude()
                        + ", " + center.getLongitude());

        offlineMapManager.downloadArea(center,
                new OfflineMapManager.DownloadCallback() {

                    @Override
                    public void onProgress(int percent,
                                           long done,
                                           long total) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            progress.setProgress(percent);
                            progress.setMessage(
                                    done + " / " + total
                                            + " tiles  ("
                                            + percent + "%)");
                        });
                    }

                    @Override
                    public void onComplete(long totalTiles) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            progress.dismiss();
                            Toast.makeText(requireContext(),
                                    "Map downloaded — "
                                            + totalTiles
                                            + " tiles saved",
                                    Toast.LENGTH_LONG).show();
                            if (btnDownloadMap != null) {
                                btnDownloadMap.setText(
                                        "Map downloaded ✓");
                                btnDownloadMap.setEnabled(
                                        false);
                            }
                            if (statusText != null) {
                                statusText.setText(
                                        "Offline map saved");
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            progress.dismiss();
                            if (btnDownloadMap != null) {
                                btnDownloadMap.setEnabled(
                                        true);
                                btnDownloadMap.setText(
                                        getString(
                                                R.string.download_map));
                            }
                            if (!"Cancelled".equals(message)) {
                                Toast.makeText(requireContext(),
                                        "Download failed: "
                                                + message,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
    }

    // ── LocationTracker.LocationListener ──────────────────────────────────

    @Override
    public void onLocationUpdated(Location location) {
        if (!isAdded()) return;
        coordsText.setText(
                LocationTracker.formatCoords(location));
        statusText.setText("GPS locked");
        statusDot.setBackgroundResource(
                R.drawable.circle_dot);
        overlayManager.updateUserLocation(location);
        if (!mapCenteredOnUser) {
            mapView.getController().animateTo(
                    new GeoPoint(
                            location.getLatitude(),
                            location.getLongitude()));
            mapView.getController().setZoom(18.0);
            mapCenteredOnUser = true;
        }
    }

    @Override
    public void onLocationUnavailable() {
        if (!isAdded()) return;
        coordsText.setText("Location unavailable");
        statusText.setText("No GPS");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onResume() {
        super.onResume();
        mapView.onResume();
        locationTracker.startTracking();
    }

    @Override
    public void onPause() {
        super.onPause();
        mapView.onPause();
        locationTracker.stopTracking();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (overlayManager != null) {
            overlayManager.stopAnimations();
        }
        locationTracker.stopTracking();
        mapView.onDetach();
    }
}