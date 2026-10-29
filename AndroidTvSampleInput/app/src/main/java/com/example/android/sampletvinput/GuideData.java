package com.example.android.sampletvinput;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the guide for GuideActivity and PlayerActivity: groups from ChannelConfig, and the
 * channels, programmes and logos GuideSync stored in GuideStore. A channel's id is its Xtream
 * stream id.
 */
public final class GuideData {
    private static final String TAG = "GuideData";
    public static final String ALL_GROUP_ID = "all";
    // Guide window read from the store; the sync stores 2 hours back (more for catch-up
    // channels) and 24 ahead.
    private static final long PAST_MS = 2 * 3600_000L;
    private static final long DAY_MS = 24 * 3600_000L;
    private static final long FUTURE_MS = 26 * 3600_000L;

    public static class Program {
        public final String title;
        public final String description;
        public final long startMs;
        public final long endMs;

        Program(String title, String description, long startMs, long endMs) {
            this.title = title;
            this.description = description;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        /** Fraction of the program already aired, 0..1. */
        public float progress(long now) {
            if (endMs <= startMs) return 0;
            return Math.max(0, Math.min(1, (now - startMs) / (float) (endMs - startMs)));
        }
    }

    public static class Channel {
        public final long id;
        public final int streamId;
        public final String number;
        public final String name;
        public final String groupId;
        /** Stream ids in playback order, with their names and catch-up days (0 for none). */
        public final List<Integer> streams = new ArrayList<>();
        public final List<String> streamNames = new ArrayList<>();
        public final List<Integer> archiveDays = new ArrayList<>();
        public Program now;
        public Program next;
        /** Programs from a little before now to the end of the stored guide, in order. */
        public List<Program> programs = Collections.emptyList();

        Channel(long id, int streamId, String number, String name, String groupId) {
            this.id = id;
            this.streamId = streamId;
            this.number = number;
            this.name = name;
            this.groupId = groupId;
        }

        /** How many days back any of the channel's streams can be watched; 0 for no catch-up. */
        public int catchupDays() {
            int days = 0;
            for (int d : archiveDays) days = Math.max(days, d);
            return days;
        }

        /** Whether a programme has started and is recent enough to watch on catch-up. */
        public boolean canCatchUp(Program p, long now) {
            int days = catchupDays();
            return days > 0 && p.startMs < now && p.startMs >= now - days * DAY_MS;
        }
    }

    public static class Group {
        public final String id;
        public final String name;
        public final List<Channel> channels = new ArrayList<>();

        Group(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    // The last loaded guide, so PlayerActivity can zap through the group the user came from.
    private static volatile List<Group> sLast = Collections.emptyList();

    private static final LruCache<Long, Bitmap> sLogos = new LruCache<Long, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private GuideData() {}

    public static List<Group> last() {
        return sLast;
    }

    public static Group lastGroup(String groupId) {
        for (Group g : sLast) {
            if (g.id.equals(groupId)) return g;
        }
        return null;
    }

    /**
     * Loads groups and channels, with each channel's current and next program. Runs queries,
     * so call it off the main thread. The first group is "All channels".
     */
    public static List<Group> load(Context context) {
        // Logos can change at a sync (new uploads in the web manager), so reread them.
        sLogos.evictAll();
        SQLiteDatabase db = GuideStore.get(context).getReadableDatabase();
        List<Channel> channels = new ArrayList<>();
        try (Cursor c = db.query(GuideStore.CHANNELS,
                new String[]{"stream_id", "number", "name", "group_id", "streams", "stream_names",
                        "archive"},
                null, null, null, null, "number")) {
            while (c.moveToNext()) {
                int streamId = c.getInt(0);
                Channel channel = new Channel(streamId, streamId, String.valueOf(c.getInt(1)),
                        c.getString(2), c.getString(3));
                try {
                    JSONArray streams = new JSONArray(c.getString(4));
                    JSONArray names = new JSONArray(c.getString(5));
                    JSONArray archive = c.isNull(6) ? new JSONArray()
                            : new JSONArray(c.getString(6));
                    for (int i = 0; i < streams.length(); i++) {
                        channel.streams.add(streams.getInt(i));
                        channel.streamNames.add(names.optString(i, channel.name));
                        channel.archiveDays.add(archive.optInt(i));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Bad stream list for channel " + streamId);
                }
                if (channel.streams.isEmpty()) {
                    channel.streams.add(streamId);
                    channel.streamNames.add(channel.name);
                    channel.archiveDays.add(0);
                }
                channels.add(channel);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not read channels", e);
        }

        long now = System.currentTimeMillis();
        for (Channel channel : channels) loadPrograms(db, channel, now);

        Group all = new Group(ALL_GROUP_ID, "All channels");
        all.channels.addAll(channels);
        List<Group> groups = new ArrayList<>();
        groups.add(all);

        // Groups in the web manager's order; only those that have channels on Fire TV.
        Map<String, Group> byId = new HashMap<>();
        JSONArray configGroups = ChannelConfig.load(context).groups();
        for (int i = 0; i < configGroups.length(); i++) {
            JSONObject g = configGroups.optJSONObject(i);
            if (g == null || !g.optBoolean("enabled", true)) continue;
            Group group = new Group(g.optString("id"), g.optString("name"));
            byId.put(group.id, group);
            groups.add(group);
        }
        for (Channel channel : channels) {
            Group group = byId.get(channel.groupId);
            if (group != null) group.channels.add(channel);
        }
        for (int i = groups.size() - 1; i > 0; i--) {
            if (groups.get(i).channels.isEmpty()) groups.remove(i);
        }
        sLast = groups;
        return groups;
    }

    /** Refreshes now/next for the given channels, e.g. when a program ends. */
    public static void refreshPrograms(Context context, List<Channel> channels) {
        SQLiteDatabase db = GuideStore.get(context).getReadableDatabase();
        long now = System.currentTimeMillis();
        for (Channel channel : channels) loadPrograms(db, channel, now);
    }

    private static void loadPrograms(SQLiteDatabase db, Channel channel, long now) {
        channel.now = null;
        channel.next = null;
        List<Program> programs = new ArrayList<>();
        try (Cursor c = db.query(GuideStore.PROGRAMS,
                new String[]{"title", "description", "start_ms", "end_ms"},
                "stream_id = ? AND end_ms > ? AND start_ms < ?",
                new String[]{String.valueOf(channel.streamId),
                        String.valueOf(now - Math.max(PAST_MS, channel.catchupDays() * DAY_MS)),
                        String.valueOf(now + FUTURE_MS)},
                null, null, "start_ms")) {
            while (c.moveToNext()) {
                Program p = new Program(c.getString(0), c.getString(1), c.getLong(2),
                        c.getLong(3));
                programs.add(p);
                if (p.endMs <= now) continue;
                if (channel.now == null && p.startMs <= now) {
                    channel.now = p;
                } else if (channel.next == null) {
                    channel.next = p;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read programs for channel " + channel.id, e);
        }
        channel.programs = programs;
    }

    /** Returns the channel's cached logo, or null. Off the main thread. */
    public static Bitmap logo(Context context, long channelId) {
        Bitmap cached = sLogos.get(channelId);
        if (cached != null) return cached;
        File file = GuideStore.logoFile(context, (int) channelId);
        if (!file.exists()) return null;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath());
        if (bitmap != null) sLogos.put(channelId, bitmap);
        return bitmap;
    }

    public static Bitmap cachedLogo(long channelId) {
        return sLogos.get(channelId);
    }
}
