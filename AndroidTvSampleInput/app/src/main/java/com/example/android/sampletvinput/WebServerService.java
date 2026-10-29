package com.example.android.sampletvinput;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

/**
 * Keeps WebServer running so the channel manager page stays reachable. Started when the app
 * opens, after boot, and after install.
 *
 * While running it holds a Wi-Fi lock (so the radio doesn't drop into power save and stop
 * answering) and a partial wake lock (so the CPU can serve requests while the Stick idles).
 * Neither keeps the screen on.
 *
 * It also sends a keep-alive packet every 30 seconds to each device that opened the page in
 * the last day. On weak Wi-Fi in power save the Stick can miss the broadcasts other devices use
 * to find its address, and then stops answering on the LAN. Sending to them makes the Stick
 * look up their addresses itself, which also refreshes their record of the Stick's.
 */
public class WebServerService extends Service {
    private static final String TAG = "WebServerService";
    private static final String CHANNEL_ID = "web_server";
    private static final int NOTIFICATION_ID = 1;

    private WebServer mServer;
    private WifiManager.WifiLock mWifiLock;
    private PowerManager.WakeLock mWakeLock;
    private java.util.concurrent.ScheduledExecutorService mKeepAlive;

    private static final long KEEP_ALIVE_INTERVAL_S = 30;
    private static final long CLIENT_TTL_MS = 24 * 60 * 60 * 1000;
    // The discard port: nothing answers, the packet only needs to reach the device.
    private static final int DISCARD_PORT = 9;

    public static void start(Context context) {
        Intent intent = new Intent(context, WebServerService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException e) {
            // Background start limits can refuse this; the page comes back when the app opens.
            Log.w(TAG, "Could not start web server: " + e.getMessage());
        }
    }

    /** Returns the page address on the first non-loopback IPv4 address, or null. */
    public static String pageUrl() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return "http://" + address.getHostAddress() + ":" + WebServer.PORT;
                    }
                }
            }
        } catch (SocketException e) {
            Log.w(TAG, "Could not list network interfaces", e);
        }
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification());
        acquireLocks();
        mServer = new WebServer(this);
        try {
            mServer.start();
            Log.i(TAG, "Web server listening on port " + WebServer.PORT);
            mKeepAlive = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            mKeepAlive.scheduleWithFixedDelay(WebServerService::sendKeepAlives,
                    KEEP_ALIVE_INTERVAL_S, KEEP_ALIVE_INTERVAL_S,
                    java.util.concurrent.TimeUnit.SECONDS);
        } catch (IOException e) {
            Log.e(TAG, "Could not start web server", e);
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mKeepAlive != null) mKeepAlive.shutdownNow();
        if (mServer != null) mServer.stop();
        releaseLocks();
        super.onDestroy();
    }

    private static void sendKeepAlives() {
        long now = System.currentTimeMillis();
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            for (java.util.Map.Entry<String, Long> client : WebServer.sRecentClients.entrySet()) {
                if (now - client.getValue() > CLIENT_TTL_MS) {
                    WebServer.sRecentClients.remove(client.getKey());
                    continue;
                }
                InetAddress address = InetAddress.getByName(client.getKey());
                if (address.isLoopbackAddress()) continue;
                socket.send(new java.net.DatagramPacket(new byte[1], 1, address, DISCARD_PORT));
            }
        } catch (Exception e) {
            // A keep-alive that fails is retried on the next tick.
            Log.w(TAG, "Keep-alive failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation") // HIGH_PERF is the strongest mode on the Stick's API 25.
    private void acquireLocks() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            mWifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG);
            mWifiLock.setReferenceCounted(false);
            mWifiLock.acquire();
        }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            mWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":server");
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();
        }
    }

    private void releaseLocks() {
        if (mWifiLock != null && mWifiLock.isHeld()) mWifiLock.release();
        if (mWakeLock != null && mWakeLock.isHeld()) mWakeLock.release();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    "Channel manager", NotificationManager.IMPORTANCE_MIN));
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder.setSmallIcon(R.drawable.android_48dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Channel manager page is available")
                .build();
    }
}
