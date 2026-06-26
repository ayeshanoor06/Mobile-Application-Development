package com.ayesha.embernet;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.osmdroid.tileprovider.tilesource
        .TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public class OfflineMapManager {

    private static final String TAG =
            "OfflineMapManager";

    private static final String PREFS_NAME =
            "embernet_map";
    private static final String KEY_MAP_SAVED =
            "offline_map_saved";
    private static final String KEY_MAP_LAT =
            "map_center_lat";
    private static final String KEY_MAP_LON =
            "map_center_lon";

    // Smaller area = much faster download
    // 0.04 degrees = ~4.5km radius
    // enough to cover Layyah city centre
    private static final double DOWNLOAD_RADIUS = 0.04;

    // Only download zoom levels 12-15
    // Zoom 15 is detailed enough for streets
    // Zoom 16+ multiplies tile count by 4x
    public static final int ZOOM_MIN = 12;
    public static final int ZOOM_MAX = 17;

    // Download 4 tiles at once in parallel
    // Reduces download time by ~4x
    private static final int THREAD_COUNT = 4;

    // Timeout per tile — short enough to skip
    // slow tiles instead of hanging forever
    private static final int CONNECT_TIMEOUT = 4000;
    private static final int READ_TIMEOUT    = 4000;

    // Retry each failed tile once before skipping
    private static final int MAX_RETRIES = 1;

    private final Context           context;
    private final SharedPreferences prefs;
    private       boolean           isCancelled = false;

    public interface DownloadCallback {
        void onProgress(int percent,
                        long done, long total);
        void onComplete(long totalTiles);
        void onError(String message);
    }

    public OfflineMapManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs   = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── Public API ────────────────────────────────────────────────────

    public boolean isMapSaved() {
        return prefs.getBoolean(KEY_MAP_SAVED, false);
    }

    public GeoPoint getSavedMapCenter() {
        double lat = Double.longBitsToDouble(
                prefs.getLong(KEY_MAP_LAT,
                        Double.doubleToLongBits(30.9625)));
        double lon = Double.longBitsToDouble(
                prefs.getLong(KEY_MAP_LON,
                        Double.doubleToLongBits(70.9394)));
        return new GeoPoint(lat, lon);
    }

    public File getOfflineTileFile() {
        return new File(context.getCacheDir(),
                "embernet_tiles.sqlite");
    }

    public void clearOfflineMap() {
        File f = getOfflineTileFile();
        if (f.exists()) f.delete();
        prefs.edit()
                .putBoolean(KEY_MAP_SAVED, false)
                .apply();
    }

    public void cancelDownload() {
        isCancelled = true;
    }

    public BoundingBox buildBoundingBox(
            GeoPoint center) {
        return new BoundingBox(
                center.getLatitude()  + DOWNLOAD_RADIUS,
                center.getLongitude() + DOWNLOAD_RADIUS,
                center.getLatitude()  - DOWNLOAD_RADIUS,
                center.getLongitude() - DOWNLOAD_RADIUS);
    }

    // ── Tile count estimator ──────────────────────────────────────────

    public long estimateTileCount(BoundingBox bbox,
                                  int zMin, int zMax) {
        long total = 0;
        for (int z = zMin; z <= zMax; z++) {
            int xMin = lonToTileX(
                    bbox.getLonWest(),  z);
            int xMax = lonToTileX(
                    bbox.getLonEast(),  z);
            int yMin = latToTileY(
                    bbox.getLatNorth(), z);
            int yMax = latToTileY(
                    bbox.getLatSouth(), z);
            total += (long)(xMax - xMin + 1)
                    * (yMax - yMin + 1);
        }
        return total;
    }

    // ── Main download — parallel with retry ───────────────────────────

    public void downloadArea(GeoPoint center,
                             DownloadCallback callback) {

        isCancelled = false;

        // Run download on background thread
        new Thread(() -> {

            BoundingBox bbox =
                    buildBoundingBox(center);
            long estimated =
                    estimateTileCount(
                            bbox, ZOOM_MIN, ZOOM_MAX);

            Log.d(TAG, "Starting download — "
                    + estimated + " tiles "
                    + "zoom " + ZOOM_MIN
                    + "-" + ZOOM_MAX
                    + " threads=" + THREAD_COUNT);

            // Delete old file before starting
            File outputFile = getOfflineTileFile();
            if (outputFile.exists()) {
                outputFile.delete();
                Log.d(TAG, "Deleted old cache");
            }

            // Build full list of tiles to download
            List<int[]> tiles = new ArrayList<>();
            for (int z = ZOOM_MIN; z <= ZOOM_MAX; z++) {
                int xMin = lonToTileX(
                        bbox.getLonWest(),  z);
                int xMax = lonToTileX(
                        bbox.getLonEast(),  z);
                int yMin = latToTileY(
                        bbox.getLatNorth(), z);
                int yMax = latToTileY(
                        bbox.getLatSouth(), z);
                for (int x = xMin; x <= xMax; x++) {
                    for (int y = yMin;
                         y <= yMax; y++) {
                        tiles.add(
                                new int[]{z, x, y});
                    }
                }
            }

            long actualTotal = tiles.size();
            Log.d(TAG, "Actual tile count: "
                    + actualTotal);

            // Open SQLite writer
            org.osmdroid.tileprovider.modules
                    .SqliteArchiveTileWriter writer;
            try {
                writer = new org.osmdroid
                        .tileprovider.modules
                        .SqliteArchiveTileWriter(
                        outputFile
                                .getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Writer open failed: "
                        + e.getMessage());
                callback.onError(
                        "Failed to create map file: "
                                + e.getMessage());
                return;
            }

            // Thread-safe counters
            AtomicLong downloaded =
                    new AtomicLong(0);
            AtomicLong failed =
                    new AtomicLong(0);
            final int[] lastPct = {-1};

            // Use thread pool for parallel download
            ExecutorService pool =
                    Executors.newFixedThreadPool(
                            THREAD_COUNT);

            // Writer must be synchronized — SQLite
            // is not thread-safe for writes
            final Object writerLock = new Object();

            List<Future<?>> futures =
                    new ArrayList<>();

            for (int[] tile : tiles) {
                if (isCancelled) break;

                final int tz = tile[0];
                final int tx = tile[1];
                final int ty = tile[2];

                Future<?> f = pool.submit(() -> {
                    if (isCancelled) return;

                    boolean success =
                            downloadTileWithRetry(
                                    tz, tx, ty,
                                    writer, writerLock);

                    if (!success) {
                        failed.incrementAndGet();
                    }

                    long done =
                            downloaded.incrementAndGet();
                    int pct = (int)(
                            (done * 100L) / actualTotal);

                    if (pct != lastPct[0]) {
                        lastPct[0] = pct;
                        callback.onProgress(
                                pct, done, actualTotal);
                    }
                });

                futures.add(f);
            }

            // Wait for all tiles to finish
            pool.shutdown();
            try {
                pool.awaitTermination(
                        10, java.util.concurrent
                                .TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Log.e(TAG, "Pool interrupted");
            }

            // Close writer
            try {
                writer.onDetach();
            } catch (Exception e) {
                Log.w(TAG, "Writer close: "
                        + e.getMessage());
            }

            if (isCancelled) {
                Log.d(TAG, "Download cancelled");
                outputFile.delete();
                callback.onError("Cancelled");
                return;
            }

            // Save center even if some tiles failed
            // Partial maps are still useful
            saveMapCenter(center);

            long successCount =
                    downloaded.get() - failed.get();
            Log.d(TAG, "Download complete — "
                    + successCount + " tiles ok, "
                    + failed.get() + " skipped");

            callback.onComplete(successCount);

        }).start();
    }

    // ── Tile downloader with retry ────────────────────────────────────

    private boolean downloadTileWithRetry(
            int zoom, int x, int y,
            org.osmdroid.tileprovider.modules
                    .SqliteArchiveTileWriter writer,
            Object writerLock) {

        for (int attempt = 0;
             attempt <= MAX_RETRIES;
             attempt++) {

            if (isCancelled) return false;

            try {
                String urlStr =
                        buildTileUrl(zoom, x, y);
                URL url = new URL(urlStr);
                HttpURLConnection conn =
                        (HttpURLConnection)
                                url.openConnection();

                conn.setRequestProperty(
                        "User-Agent",
                        context.getPackageName());
                conn.setConnectTimeout(
                        CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.connect();

                int code =
                        conn.getResponseCode();

                if (code == 200) {
                    try (InputStream is =
                                 conn.getInputStream()) {

                        long tileIndex =
                                org.osmdroid.util
                                        .MapTileIndex
                                        .getTileIndex(
                                                zoom, x, y);

                        // Synchronize SQLite writes
                        synchronized (writerLock) {
                            writer.saveFile(
                                    TileSourceFactory
                                            .MAPNIK,
                                    tileIndex,
                                    is, null);
                        }
                        conn.disconnect();
                        return true;
                    }
                } else {
                    conn.disconnect();
                    Log.w(TAG, "HTTP " + code
                            + " z=" + zoom
                            + " x=" + x + " y=" + y);
                }

            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    Log.w(TAG, "Retry tile z="
                            + zoom + " x=" + x
                            + " y=" + y + " — "
                            + e.getMessage());
                    try {
                        Thread.sleep(200);
                    } catch (Exception ignored) {}
                }
            }
        }

        // Skip this tile — not critical
        return false;
    }

    // ── Coordinate math ───────────────────────────────────────────────

    private int lonToTileX(double lon, int zoom) {
        return (int) Math.floor(
                (lon + 180.0) / 360.0 * (1 << zoom));
    }

    private int latToTileY(double lat, int zoom) {
        double rad = Math.toRadians(lat);
        return (int) Math.floor(
                (1.0 - Math.log(
                        Math.tan(rad)
                                + 1.0 / Math.cos(rad))
                        / Math.PI) / 2.0 * (1 << zoom));
    }

    private String buildTileUrl(
            int zoom, int x, int y) {
        // Rotate between 3 OSM tile servers
        // to avoid rate limiting
        String[] servers = {"a", "b", "c"};
        String s = servers[(x + y) % 3];
        return "https://" + s
                + ".tile.openstreetmap.org/"
                + zoom + "/" + x + "/" + y + ".png";
    }

    // ── Persistence ───────────────────────────────────────────────────

    private void saveMapCenter(GeoPoint center) {
        prefs.edit()
                .putBoolean(KEY_MAP_SAVED, true)
                .putLong(KEY_MAP_LAT,
                        Double.doubleToLongBits(
                                center.getLatitude()))
                .putLong(KEY_MAP_LON,
                        Double.doubleToLongBits(
                                center.getLongitude()))
                .apply();
    }
}