package com.example.android.sampletvinput;

import android.content.Context;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the app from cutting off someone else. Xtream accounts often allow one connection, and
 * starting a stream takes it over from whoever is watching on another TV. Before tuning, the
 * account is asked how many of its connections are in use; a full account is skipped.
 *
 * The app's own streams count too, and a panel takes a while to notice one has stopped, so an
 * account this app used in the last {@link #RECENT_MS} is never counted as busy.
 */
public final class Connections {
    private static final String TAG = "Connections";
    private static final long RECENT_MS = 90_000;
    private static final long IN_USE = Long.MAX_VALUE;

    // Source -> when this app last stopped using it (IN_USE while it still is).
    private static final Map<Integer, Long> sOurs = new ConcurrentHashMap<>();

    private Connections() {}

    public static void using(int source) {
        sOurs.put(source, IN_USE);
    }

    public static void released(int source) {
        sOurs.put(source, System.currentTimeMillis());
    }

    /**
     * Whether the source's account is full with someone else's streams. Asks the panel, so call
     * it off the main thread. Any doubt (no answer, no limit given) counts as free.
     */
    public static boolean busy(Context context, int source) {
        Long ours = sOurs.get(source);
        if (ours != null && (ours == IN_USE || System.currentTimeMillis() - ours < RECENT_MS)) {
            return false;
        }
        XtreamClient client = Sources.client(context, source);
        int[] cons = client == null ? null : client.connections();
        if (cons == null || cons[1] <= 0) return false;
        Log.i(TAG, "Source " + source + ": " + cons[0] + " of " + cons[1] + " connections in use");
        return cons[0] >= cons[1];
    }
}
