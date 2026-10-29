package com.example.android.sampletvinput;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Catch-up (Xtream "timeshift"): which streams the panels record and for how many days, and
 * each panel's time zone, refreshed by every sync into files/catchup.json:
 * {"streams": {streamId: days}, "zones": {source: tzId}}. Stream ids are FyrTuner's encoded
 * ids (see Sources).
 */
public final class Catchup {
    private static final String TAG = "Catchup";
    private static final String FILE_NAME = "catchup.json";
    /** Catch-up longer than this isn't offered; the guide doesn't keep more. */
    public static final int MAX_DAYS = 7;
    /** Recordings are requested this much longer than the programme, for overruns. */
    private static final int PADDING_MIN = 10;

    private static final Object LOCK = new Object();
    private static JSONObject sCache;
    private static long sCacheModified;

    private Catchup() {}

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static JSONObject load(Context context) {
        synchronized (LOCK) {
            File file = file(context);
            if (sCache != null && file.lastModified() == sCacheModified) return sCache;
            JSONObject root = new JSONObject();
            if (file.exists()) {
                try (InputStream in = new FileInputStream(file)) {
                    byte[] data = new byte[(int) file.length()];
                    int read = 0;
                    while (read < data.length) {
                        int n = in.read(data, read, data.length - read);
                        if (n < 0) break;
                        read += n;
                    }
                    root = new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
                } catch (IOException | JSONException e) {
                    Log.w(TAG, "Could not read catch-up list", e);
                }
            }
            sCache = root;
            sCacheModified = file.lastModified();
            return root;
        }
    }

    /** Catch-up days for a stream (capped at MAX_DAYS), 0 if it has none. */
    public static int days(Context context, int streamId) {
        JSONObject streams = load(context).optJSONObject("streams");
        int days = streams == null ? 0 : streams.optInt(String.valueOf(streamId));
        return Math.min(days, MAX_DAYS);
    }

    /**
     * Re-reads the catch-up list and time zone of each source. A source that can't be read
     * keeps what it had.
     */
    public static void refresh(Context context, Set<Integer> sources) {
        JSONObject old = load(context);
        JSONObject oldStreams = old.optJSONObject("streams");
        JSONObject oldZones = old.optJSONObject("zones");
        JSONObject streams = new JSONObject();
        JSONObject zones = new JSONObject();
        try {
            for (int source : sources) {
                XtreamClient client = Sources.client(context, source);
                if (client == null) continue;
                try {
                    Map<Integer, Integer> days = client.getArchiveDays();
                    for (Map.Entry<Integer, Integer> e : days.entrySet()) {
                        if (source > 0 && e.getKey() >= Sources.STREAM_OFFSET) continue;
                        streams.put(String.valueOf(Sources.encode(source, e.getKey())),
                                e.getValue());
                    }
                    String zone = client.getServerTimeZone();
                    if (zone != null) zones.put(String.valueOf(source), zone);
                    Log.i(TAG, "Source " + source + ": " + days.size()
                            + " catch-up stream(s), zone " + zone);
                } catch (IOException | JSONException e) {
                    Log.w(TAG, "Could not read catch-up for source " + source, e);
                    copySource(oldStreams, streams, source);
                    if (oldZones != null && oldZones.has(String.valueOf(source))) {
                        zones.put(String.valueOf(source), oldZones.opt(String.valueOf(source)));
                    }
                }
            }
            JSONObject root = new JSONObject().put("streams", streams).put("zones", zones);
            synchronized (LOCK) {
                File file = file(context);
                File tmp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(file)) throw new IOException("Could not save catch-up list");
                sCache = null;
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Could not save catch-up list", e);
        }
    }

    private static void copySource(JSONObject from, JSONObject to, int source)
            throws JSONException {
        if (from == null) return;
        for (Iterator<String> it = from.keys(); it.hasNext(); ) {
            String key = it.next();
            if (Sources.sourceOf(Integer.parseInt(key)) == source) to.put(key, from.opt(key));
        }
    }

    /**
     * URL for a stream's recording from startMs to endMs, or null if its source is gone.
     * Panels without a stated time zone are taken to share the device's.
     */
    public static String url(Context context, int streamId, long startMs, long endMs) {
        int source = Sources.sourceOf(streamId);
        XtreamClient client = Sources.client(context, source);
        if (client == null) return null;
        JSONObject zones = load(context).optJSONObject("zones");
        String zone = zones == null ? null : zones.optString(String.valueOf(source), null);
        TimeZone tz = zone == null ? TimeZone.getDefault() : TimeZone.getTimeZone(zone);
        int minutes = (int) Math.max(1, (endMs - startMs + 59_999) / 60_000) + PADDING_MIN;
        return client.getTimeshiftUrl(Sources.panelId(streamId), startMs, minutes, tz);
    }
}
