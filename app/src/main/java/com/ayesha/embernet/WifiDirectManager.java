package com.ayesha.embernet;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class WifiDirectManager {

    private static final String TAG       = "WifiDirectManager";
    private static final int    PORT      = 8988;
    private static final int    TIMEOUT   = 5000;

    // How often to re-run peer discovery (ms)
    private static final long DISCOVERY_INTERVAL_MS = 15000;

    public interface WifiDirectListener {
        void onPeersFound(int count);
        void onPayloadReceived(byte[] payload, String peerAddress);
        void onConnected(String peerAddress, boolean isGroupOwner);
        void onDisconnected();
        void onError(String message);
    }

    private final Context           context;
    private final WifiP2pManager    p2pManager;
    private final WifiP2pManager.Channel channel;
    private final Handler           handler;
    private       WifiDirectListener listener;
    private       BroadcastReceiver wifiReceiver;

    private boolean isDiscovering  = false;
    private boolean isConnected    = false;
    private final List<WifiP2pDevice> discoveredPeers = new ArrayList<>();

    // Server socket — listens for incoming payload transfers
    private ServerSocket serverSocket;
    private Thread       serverThread;

    public WifiDirectManager(Context context) {
        this.context    = context.getApplicationContext();
        this.handler    = new Handler(Looper.getMainLooper());
        this.p2pManager = (WifiP2pManager) context
                .getSystemService(Context.WIFI_P2P_SERVICE);
        this.channel    = p2pManager.initialize(
                context,
                Looper.getMainLooper(),
                () -> Log.w(TAG, "Wi-Fi Direct channel disconnected")
        );
    }

    public void setListener(WifiDirectListener listener) {
        this.listener = listener;
    }

    // ── Start discovery ───────────────────────────────────────────────────

    public void startDiscovery() {
        if (isDiscovering) return;
        isDiscovering = true;

        registerReceiver();
        runDiscoveryCycle();
        Log.d(TAG, "Wi-Fi Direct discovery started");
    }

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES})
    private void runDiscoveryCycle() {
        if (!isDiscovering) return;

        p2pManager.discoverPeers(channel,
                new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        Log.d(TAG, "Peer discovery cycle started");
                    }
                    @Override
                    public void onFailure(int reason) {
                        Log.w(TAG, "Discovery failed reason=" + reason
                                + " " + reasonString(reason));
                        // Retry after interval even on failure
                    }
                });

        // Re-run discovery on interval — Android stops it after 2 min
        handler.postDelayed(this::runDiscoveryCycle,
                DISCOVERY_INTERVAL_MS);
    }

    // ── Stop discovery ────────────────────────────────────────────────────

    public void stopDiscovery() {
        isDiscovering = false;
        handler.removeCallbacksAndMessages(null);

        p2pManager.stopPeerDiscovery(channel,
                new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() {
                        Log.d(TAG, "Peer discovery stopped");
                    }
                    @Override public void onFailure(int r) {}
                });

        unregisterReceiver();
        stopServer();
        Log.d(TAG, "Wi-Fi Direct stopped");
    }

    // ── Connect to a peer and send payload ───────────────────────────────

    public void sendPayload(byte[] payload) {
        if (discoveredPeers.isEmpty()) {
            Log.w(TAG, "No peers to send to");
            return;
        }

        // Send to all discovered peers
        for (WifiP2pDevice peer : discoveredPeers) {
            connectAndSend(peer, payload);
        }
    }

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES})
    private void connectAndSend(WifiP2pDevice peer,
                                byte[] payload) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = peer.deviceAddress;

        Log.d(TAG, "Connecting to peer: "
                + peer.deviceName + " " + peer.deviceAddress);

        p2pManager.connect(channel, config,
                new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        Log.d(TAG, "Connection initiated to "
                                + peer.deviceAddress);
                        // Actual transfer happens in onConnectionInfoAvailable
                        // which fires via the BroadcastReceiver below
                        pendingPayload   = payload;
                        pendingPeerAddr  = peer.deviceAddress;
                    }
                    @Override
                    public void onFailure(int reason) {
                        Log.w(TAG, "Connect failed to "
                                + peer.deviceAddress
                                + " reason=" + reasonString(reason));
                    }
                });
    }

    // Held while we wait for connection info
    private byte[]  pendingPayload;
    private String  pendingPeerAddr;

    // ── Server socket — receives incoming payloads ────────────────────────

    private void startServer() {
        if (serverThread != null
                && serverThread.isAlive()) return;

        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                serverSocket.setSoTimeout(0); // block indefinitely
                Log.d(TAG, "Wi-Fi Direct server listening on :"
                        + PORT);

                while (isDiscovering && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        Log.d(TAG, "Client connected: "
                                + client.getInetAddress());
                        handleIncomingConnection(client);
                    } catch (Exception e) {
                        if (isDiscovering) {
                            Log.e(TAG, "Accept error: "
                                    + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Server socket error: " + e.getMessage());
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void handleIncomingConnection(Socket client) {
        new Thread(() -> {
            try {
                InputStream is = client.getInputStream();

                // Read 4-byte length header first
                byte[] lenBytes = new byte[4];
                int read = is.read(lenBytes);
                if (read < 4) {
                    client.close();
                    return;
                }

                int payloadLen =
                        ((lenBytes[0] & 0xFF) << 24)
                                | ((lenBytes[1] & 0xFF) << 16)
                                | ((lenBytes[2] & 0xFF) << 8)
                                | (lenBytes[3] & 0xFF);

                if (payloadLen <= 0 || payloadLen > 4096) {
                    Log.w(TAG, "Invalid payload length: "
                            + payloadLen);
                    client.close();
                    return;
                }

                // Read exactly payloadLen bytes
                byte[] payload = new byte[payloadLen];
                int totalRead = 0;
                while (totalRead < payloadLen) {
                    int n = is.read(
                            payload, totalRead,
                            payloadLen - totalRead);
                    if (n < 0) break;
                    totalRead += n;
                }

                String peerAddr =
                        client.getInetAddress().getHostAddress();
                Log.d(TAG, "Received " + totalRead
                        + " bytes from " + peerAddr);

                client.close();

                if (totalRead == payloadLen && listener != null) {
                    handler.post(() ->
                            listener.onPayloadReceived(
                                    payload, peerAddr));
                }

            } catch (Exception e) {
                Log.e(TAG, "Receive error: " + e.getMessage());
                try { client.close(); } catch (Exception ignored) {}
            }
        }).start();
    }

    private void stopServer() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (Exception ignored) {}
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }
    }

    // ── Send payload over socket ──────────────────────────────────────────

    private void sendOverSocket(InetAddress address,
                                byte[] payload) {
        new Thread(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(
                        new InetSocketAddress(address, PORT),
                        TIMEOUT);

                OutputStream os = socket.getOutputStream();

                // Write 4-byte length header
                int len = payload.length;
                os.write(new byte[]{
                        (byte)(len >> 24),
                        (byte)(len >> 16),
                        (byte)(len >> 8),
                        (byte)(len)
                });

                // Write payload
                os.write(payload);
                os.flush();
                socket.close();

                Log.d(TAG, "Sent " + payload.length
                        + " bytes to " + address.getHostAddress());

            } catch (Exception e) {
                Log.e(TAG, "Send error: " + e.getMessage());
            }
        }).start();
    }

    // ── BroadcastReceiver — Wi-Fi Direct system events ────────────────────

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        wifiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;

                switch (action) {
                    case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:
                        onWifiP2pStateChanged(intent);
                        break;
                    case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:
                        onPeersChanged();
                        break;
                    case WifiP2pManager
                                 .WIFI_P2P_CONNECTION_CHANGED_ACTION:
                        onConnectionChanged(intent);
                        break;
                }
            }
        };

        context.registerReceiver(wifiReceiver, filter);
    }

    private void unregisterReceiver() {
        if (wifiReceiver != null) {
            try {
                context.unregisterReceiver(wifiReceiver);
            } catch (Exception ignored) {}
            wifiReceiver = null;
        }
    }

    private void onWifiP2pStateChanged(Intent intent) {
        int state = intent.getIntExtra(
                WifiP2pManager.EXTRA_WIFI_STATE, -1);
        boolean enabled =
                state == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
        Log.d(TAG, "Wi-Fi Direct state: "
                + (enabled ? "ENABLED ✓" : "DISABLED ❌"));
    }

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES})
    private void onPeersChanged() {
        p2pManager.requestPeers(channel, peerList -> {
            Collection<WifiP2pDevice> peers =
                    peerList.getDeviceList();
            discoveredPeers.clear();
            discoveredPeers.addAll(peers);

            Log.d(TAG, "Peers found: " + peers.size());
            for (WifiP2pDevice d : peers) {
                Log.d(TAG, "  → " + d.deviceName
                        + " " + d.deviceAddress
                        + " status=" + statusString(d.status));
            }

            if (listener != null) {
                listener.onPeersFound(peers.size());
            }
        });
    }

    private void onConnectionChanged(Intent intent) {
        // NetworkInfo is deprecated in API 29+
        // Use the network info from the intent safely
        android.net.NetworkInfo netInfo =
                intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_NETWORK_INFO);

        boolean connected = false;
        if (netInfo != null) {
            connected = netInfo.isConnected();
        }

        if (connected) {
            p2pManager.requestConnectionInfo(channel,
                    info -> {
                        if (info.groupFormed) {
                            isConnected = true;
                            boolean isOwner = info.isGroupOwner;
                            java.net.InetAddress ownerAddr =
                                    info.groupOwnerAddress;

                            Log.d(TAG, "Group formed — isOwner="
                                    + isOwner);

                            if (listener != null) {
                                listener.onConnected(
                                        ownerAddr.getHostAddress(),
                                        isOwner);
                            }

                            if (isOwner) {
                                startServer();
                            } else {
                                if (pendingPayload != null) {
                                    sendOverSocket(
                                            ownerAddr, pendingPayload);
                                    pendingPayload = null;
                                }
                            }
                        }
                    });
        } else {
            isConnected = false;
            Log.d(TAG, "Wi-Fi Direct disconnected");
            if (listener != null) listener.onDisconnected();
            p2pManager.removeGroup(channel,
                    new WifiP2pManager.ActionListener() {
                        @Override public void onSuccess() {}
                        @Override public void onFailure(int r) {}
                    });
        }
    }

    // ── Getters ───────────────────────────────────────────────────────────

    public boolean isDiscovering()    { return isDiscovering;         }
    public boolean isConnected()      { return isConnected;           }
    public int     getPeerCount()     { return discoveredPeers.size();}
    public List<WifiP2pDevice> getPeers() { return discoveredPeers;  }

    // ── Debug helpers ─────────────────────────────────────────────────────

    private String reasonString(int r) {
        switch (r) {
            case WifiP2pManager.ERROR:          return "ERROR";
            case WifiP2pManager.P2P_UNSUPPORTED:return "UNSUPPORTED";
            case WifiP2pManager.BUSY:           return "BUSY";
            case WifiP2pManager.NO_SERVICE_REQUESTS:
                return "NO_SERVICE";
            default:                            return "code=" + r;
        }
    }

    private String statusString(int s) {
        switch (s) {
            case WifiP2pDevice.AVAILABLE:    return "AVAILABLE";
            case WifiP2pDevice.CONNECTED:    return "CONNECTED";
            case WifiP2pDevice.INVITED:      return "INVITED";
            case WifiP2pDevice.FAILED:       return "FAILED";
            case WifiP2pDevice.UNAVAILABLE:  return "UNAVAILABLE";
            default:                         return "status=" + s;
        }
    }
}