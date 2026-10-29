package com.example.android.sampletvinput;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.util.Log;

import org.json.JSONException;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Syncs the lineup and guide into GuideStore: ChannelConfig's lineup (groups in order, hidden
 * channels left out, names cleaned), programmes from each channel's linked XMLTV guide, and
 * logos cached as files. Channels without guide data get hour-long placeholder programmes.
 *
 * Channels with catch-up keep listings as far back as their recordings go. Guides only carry a
 * day or so of the past, so those listings build up over syncs: a sync replaces a channel's
 * programmes from the first one the guide still has, unless its guide link or offset changed.
 */
public final class GuideSync {
    private static final String TAG = "GuideSync";

    // Guide window kept on each sync.
    private static final long GUIDE_PAST_MS = 2 * 60 * 60 * 1000;
    private static final long GUIDE_FUTURE_MS = 24 * 60 * 60 * 1000;
    private static final int LOGO_TIMEOUT_MS = 10_000;

    /** Progress of the current or last sync, shown on the web page. */
    public static volatile boolean sSyncRunning;
    public static volatile String sSyncStatus = "";

    private GuideSync() {}

    /**
     * Runs a sync. synchronized: called from the boot receiver, the periodic job, the web page
     * and the settings screen — prevents two of them racing.
     *
     * @return true if the lineup and every guide source were read
     */
    public static synchronized boolean syncChannels(Context context) {
        sSyncRunning = true;
        sSyncStatus = "Syncing…";
        boolean ok = false;
        try {
            ok = doSync(context.getApplicationContext());
        } catch (RuntimeException e) {
            Log.e(TAG, "Sync failed", e);
            sSyncStatus = "Sync failed: " + e.getMessage();
        } finally {
            sSyncRunning = false;
            if (ok) sSyncStatus = "Last sync succeeded";
            else if ("Syncing…".equals(sSyncStatus)) sSyncStatus = "Last sync had errors";
        }
        return ok;
    }

    private static boolean doSync(Context context) {
        removeFireTvChannels(context);

        // Build the lineup before touching the store, so a network failure leaves it alone.
        XtreamClient client = XtreamClient.fromPrefs(context);
        ChannelConfig config = ChannelConfig.load(context);
        List<ChannelConfig.Entry> lineup;
        try {
            lineup = client == null ? java.util.Collections.emptyList()
                    : config.buildLineup(key -> Sources.streams(context, key), null);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not read lineup from panel", e);
            sSyncStatus = "Could not read channels from the panel: " + e.getMessage();
            return false;
        }
        Log.i(TAG, "Lineup: " + lineup.size() + " channel(s)");

        sSyncStatus = "Checking catch-up…";
        Set<Integer> sources = new HashSet<>();
        for (ChannelConfig.Entry entry : lineup) {
            for (int id : entry.streams) sources.add(Sources.sourceOf(id));
        }
        Catchup.refresh(context, sources);

        // Each channel's listings start 2 hours back, or as far back as its catch-up goes.
        long now = System.currentTimeMillis();
        long windowEnd = now + GUIDE_FUTURE_MS;
        Map<Integer, Long> channelStart = new HashMap<>();
        Map<Integer, String> archive = new HashMap<>();
        long earliest = now - GUIDE_PAST_MS;
        int maxOffsetMs = 0;
        for (ChannelConfig.Entry entry : lineup) {
            int days = 0;
            org.json.JSONArray list = new org.json.JSONArray();
            for (int id : entry.streams) {
                int d = Catchup.days(context, id);
                list.put(d);
                days = Math.max(days, d);
            }
            long start = days > 0 ? now - days * 24 * 3600_000L : now - GUIDE_PAST_MS;
            channelStart.put(entry.stream.streamId, start);
            archive.put(entry.stream.streamId, list.toString());
            earliest = Math.min(earliest, start);
            maxOffsetMs = Math.max(maxOffsetMs, Math.abs(entry.epgOffsetMin) * 60_000);
        }
        // Read the guides wide enough for every channel's window after its offset.
        long windowStart = earliest - maxOffsetMs;
        Set<String> failedGuides = new HashSet<>();
        Map<String, List<Xmltv.Programme>> programmes =
                fetchProgrammes(context, config, lineup, windowStart, windowEnd + maxOffsetMs,
                        failedGuides);

        SQLiteDatabase db = GuideStore.get(context).getWritableDatabase();
        Map<Integer, String> storedLogoKeys = readColumn(db, "logo_key");
        Map<Integer, String> storedGuideKeys = readColumn(db, "guide_key");
        Set<Integer> storedChannels = storedLogoKeys.keySet();

        // Logos first (network), then one transaction for the guide.
        sSyncStatus = "Updating logos…";
        Map<Integer, String> logoKeys = new HashMap<>();
        for (ChannelConfig.Entry entry : lineup) {
            int streamId = entry.stream.streamId;
            String key = logoKey(entry);
            String stored = storedLogoKeys.get(streamId);
            File file = GuideStore.logoFile(context, streamId);
            if (key.isEmpty()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                logoKeys.put(streamId, "");
            } else if (key.equals(stored) && file.exists()) {
                logoKeys.put(streamId, key);
            } else if (cacheLogo(context, entry, file)) {
                logoKeys.put(streamId, key);
            } else {
                // Keep the old key so the next sync retries.
                logoKeys.put(streamId, stored);
            }
        }

        sSyncStatus = "Saving guide…";
        db.beginTransaction();
        try {
            db.delete(GuideStore.CHANNELS, null, null);
            int number = 1;
            for (ChannelConfig.Entry entry : lineup) {
                int streamId = entry.stream.streamId;
                ContentValues channel = new ContentValues();
                channel.put("stream_id", streamId);
                channel.put("number", number++);
                channel.put("name", entry.displayName);
                channel.put("group_id", entry.groupId);
                channel.put("logo_key", logoKeys.get(streamId));
                org.json.JSONArray streams = new org.json.JSONArray();
                org.json.JSONArray names = new org.json.JSONArray();
                for (int id : entry.streams) {
                    streams.put(id);
                    String name = entry.streamNames.get(id);
                    names.put(name == null ? "Stream " + id : name);
                }
                channel.put("streams", streams.toString());
                channel.put("stream_names", names.toString());
                channel.put("archive", archive.get(streamId));
                String guideKey = entry.guideSource + "\n" + entry.guideChannel + "\n"
                        + entry.epgOffsetMin;
                long start = channelStart.get(streamId);
                String id = String.valueOf(streamId);

                // If this channel's guide couldn't be read, keep what it had.
                if (failedGuides.contains(entry.guideSource) && storedChannels.contains(streamId)) {
                    channel.put("guide_key", storedGuideKeys.get(streamId));
                    db.insert(GuideStore.CHANNELS, null, channel);
                    db.delete(GuideStore.PROGRAMS, "stream_id = ? AND end_ms < ?",
                            new String[]{id, String.valueOf(start)});
                    continue;
                }
                channel.put("guide_key", guideKey);
                db.insert(GuideStore.CHANNELS, null, channel);
                List<Xmltv.Programme> list = entry.guideChannel == null ? null
                        : programmes.get(guideKey(entry.guideSource, entry.guideChannel));
                long shift = entry.epgOffsetMin * 60_000L;
                if (list != null && !list.isEmpty()
                        && guideKey.equals(storedGuideKeys.get(streamId))) {
                    // Same guide as before: keep older listings the guide no longer has.
                    long firstStart = list.get(0).startMs + shift;
                    for (Xmltv.Programme p : list) {
                        firstStart = Math.min(firstStart, p.startMs + shift);
                    }
                    db.delete(GuideStore.PROGRAMS, "stream_id = ? AND (end_ms > ? OR end_ms < ?)",
                            new String[]{id, String.valueOf(firstStart), String.valueOf(start)});
                } else {
                    db.delete(GuideStore.PROGRAMS, "stream_id = ?", new String[]{id});
                }
                insertPrograms(db, streamId, entry.displayName, list, shift, start, windowEnd);
            }
            db.execSQL("DELETE FROM " + GuideStore.PROGRAMS + " WHERE stream_id NOT IN "
                    + "(SELECT stream_id FROM " + GuideStore.CHANNELS + ") OR end_ms < ?",
                    new Object[]{earliest});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        removeUnusedLogos(context, logoKeys.keySet());
        GuideStore.notifyChanged();

        if (!failedGuides.isEmpty()) {
            sSyncStatus = "Channels synced, but " + failedGuides.size()
                    + " guide source(s) could not be read";
            return false;
        }
        XtreamClient.prefs(context).edit()
                .putLong(XtreamClient.PREF_LAST_SYNC, System.currentTimeMillis())
                .apply();
        Log.i(TAG, "Sync complete");
        return true;
    }

    // ---- Guide ----

    /**
     * When sync last read each guide source: "<id>.ok" and "<id>.failed" hold the times of the
     * last successful and failed reads. Kept out of backups; it describes this device.
     */
    public static SharedPreferences guideTimes(Context context) {
        return context.getSharedPreferences("guide_times", Context.MODE_PRIVATE);
    }

    private static String guideKey(String source, String channel) {
        return source + "\n" + channel;
    }

    /**
     * Fetches programmes for every linked guide channel, reading each guide source once.
     * Sources that can't be read are added to failedGuides; disabled ones are skipped, so their
     * channels get placeholders.
     */
    private static Map<String, List<Xmltv.Programme>> fetchProgrammes(
            Context context, ChannelConfig config, List<ChannelConfig.Entry> lineup,
            long windowStart, long windowEnd, Set<String> failedGuides) {
        Map<String, Set<String>> idsBySource = new HashMap<>();
        for (ChannelConfig.Entry entry : lineup) {
            if (entry.guideChannel == null) continue;
            Set<String> ids = idsBySource.get(entry.guideSource);
            if (ids == null) idsBySource.put(entry.guideSource, ids = new HashSet<>());
            ids.add(entry.guideChannel);
        }

        Map<String, List<Xmltv.Programme>> result = new HashMap<>();
        for (Map.Entry<String, Set<String>> source : idsBySource.entrySet()) {
            if (config.guideDisabled(source.getKey())) continue;
            String url = config.guideUrl(context, source.getKey());
            if (url == null) continue;
            try {
                long started = System.currentTimeMillis();
                sSyncStatus = "Reading guide " + source.getKey() + "…";
                Map<String, List<Xmltv.Programme>> programmes =
                        Xmltv.readProgrammes(url, source.getValue(), windowStart, windowEnd);
                for (Map.Entry<String, List<Xmltv.Programme>> e : programmes.entrySet()) {
                    result.put(guideKey(source.getKey(), e.getKey()), e.getValue());
                }
                Log.i(TAG, "Guide " + source.getKey() + " parsed in "
                        + (System.currentTimeMillis() - started) + "ms");
                guideTimes(context).edit()
                        .putLong(source.getKey() + ".ok", System.currentTimeMillis()).apply();
            } catch (IOException | XmlPullParserException e) {
                Log.e(TAG, "Could not read guide " + source.getKey(), e);
                failedGuides.add(source.getKey());
                guideTimes(context).edit()
                        .putLong(source.getKey() + ".failed", System.currentTimeMillis()).apply();
            }
        }
        return result;
    }

    /**
     * Inserts a channel's programmes within its window, moved by shiftMs (its EPG offset).
     * Without guide data, inserts hour-long placeholders named after the channel so the guide
     * still has something to show.
     */
    private static void insertPrograms(SQLiteDatabase db, int streamId, String channelName,
                                       List<Xmltv.Programme> programmes, long shiftMs,
                                       long windowStart, long windowEnd) {
        ContentValues values = new ContentValues();
        if (programmes != null && !programmes.isEmpty()) {
            for (Xmltv.Programme p : programmes) {
                long start = p.startMs + shiftMs;
                long end = p.endMs + shiftMs;
                if (end <= windowStart || start >= windowEnd) continue;
                values.clear();
                values.put("stream_id", streamId);
                values.put("start_ms", start);
                values.put("end_ms", end);
                values.put("title", p.title);
                values.put("description", p.description);
                db.insert(GuideStore.PROGRAMS, null, values);
            }
            return;
        }
        long hour = 60 * 60 * 1000;
        for (long start = windowStart - windowStart % hour; start < windowEnd; start += hour) {
            values.clear();
            values.put("stream_id", streamId);
            values.put("start_ms", start);
            values.put("end_ms", start + hour);
            values.put("title", channelName);
            db.insert(GuideStore.PROGRAMS, null, values);
        }
    }

    // ---- Logos ----

    /** Identifies the logo in use: its URL, or "custom:<upload time>"; "" for none. */
    private static String logoKey(ChannelConfig.Entry entry) {
        if (ChannelConfig.LOGO_CUSTOM.equals(entry.logoSource)) {
            return "custom:" + entry.logoVersion;
        }
        return entry.logoUrl == null ? "" : entry.logoUrl;
    }

    /** Stream id -> a column of the stored channels, from before this sync. */
    private static Map<Integer, String> readColumn(SQLiteDatabase db, String column) {
        Map<Integer, String> keys = new HashMap<>();
        try (Cursor c = db.query(GuideStore.CHANNELS, new String[]{"stream_id", column},
                null, null, null, null, null)) {
            while (c.moveToNext()) keys.put(c.getInt(0), c.getString(1));
        }
        return keys;
    }

    /** Copies an uploaded logo, or downloads a URL logo, into the cache. */
    private static boolean cacheLogo(Context context, ChannelConfig.Entry entry, File target) {
        byte[] data;
        try {
            if (ChannelConfig.LOGO_CUSTOM.equals(entry.logoSource)) {
                File custom = ChannelConfig.customLogoFile(context, entry.stream.streamId);
                try (InputStream in = new FileInputStream(custom)) {
                    data = readAll(in);
                }
            } else {
                HttpURLConnection connection =
                        (HttpURLConnection) new URL(entry.logoUrl).openConnection();
                connection.setConnectTimeout(LOGO_TIMEOUT_MS);
                connection.setReadTimeout(LOGO_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(true);
                try (InputStream in = connection.getInputStream()) {
                    data = readAll(in);
                } finally {
                    connection.disconnect();
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not get logo for " + entry.displayName + ": " + e.getMessage());
            return false;
        }
        // Only keep files that decode as images.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0) return false;
        //noinspection ResultOfMethodCallIgnored
        target.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(target)) {
            out.write(data);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Could not save logo: " + e.getMessage());
            return false;
        }
    }

    private static void removeUnusedLogos(Context context, Set<Integer> streamIds) {
        File[] files = new File(context.getFilesDir(), "logo_cache").listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            try {
                int id = Integer.parseInt(name.substring(0, name.indexOf('.')));
                //noinspection ResultOfMethodCallIgnored
                if (!streamIds.contains(id)) f.delete();
            } catch (RuntimeException e) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    // ---- Fire TV clean-up ----

    /**
     * Removes the channels earlier versions put in Fire TV's TV database, so they leave Fire
     * TV's Live tab and guide. The provider only lets an app delete its own rows.
     */
    private static void removeFireTvChannels(Context context) {
        try {
            int removed = context.getContentResolver().delete(TvContract.Channels.CONTENT_URI,
                    null, null);
            if (removed > 0) Log.i(TAG, "Removed " + removed + " channel(s) from Fire TV");
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not clear Fire TV channels: " + e.getMessage());
        }
    }
}
