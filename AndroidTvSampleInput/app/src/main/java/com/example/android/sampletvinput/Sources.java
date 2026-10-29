package com.example.android.sampletvinput;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Xtream sources (panels). Source 0 is the main account in the "xtream" prefs; further sources
 * live in ChannelConfig ("sources", each with a fixed index).
 *
 * Stream ids from different panels can collide, so a stream from source n is known everywhere
 * else as n * STREAM_OFFSET + its panel id; main-source ids are unchanged. Categories follow
 * the same idea: "catId" for the main source, "n:catId" for source n.
 */
public final class Sources {
    private static final String TAG = "Sources";
    public static final int STREAM_OFFSET = 100_000_000;
    public static final int MAX_SOURCES = 20;

    private Sources() {}

    public static int sourceOf(int streamId) {
        return streamId / STREAM_OFFSET;
    }

    public static int panelId(int streamId) {
        return streamId % STREAM_OFFSET;
    }

    public static int encode(int source, int panelId) {
        return source * STREAM_OFFSET + panelId;
    }

    /** The guide source id for a source's own XMLTV guide. */
    public static String guideId(int source) {
        return source == 0 ? ChannelConfig.XTREAM_GUIDE : ChannelConfig.XTREAM_GUIDE + ":" + source;
    }

    /** The source a guide id refers to, or -1 if it isn't a panel guide. */
    public static int sourceOfGuide(String guideId) {
        if (ChannelConfig.XTREAM_GUIDE.equals(guideId)) return 0;
        if (guideId != null && guideId.startsWith(ChannelConfig.XTREAM_GUIDE + ":")) {
            try {
                return Integer.parseInt(guideId.substring(ChannelConfig.XTREAM_GUIDE.length() + 1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    public static String categoryKey(int source, String categoryId) {
        return source == 0 ? categoryId : source + ":" + categoryId;
    }

    /** A client for a source, or null if it doesn't exist or is incomplete. */
    public static XtreamClient client(Context context, int source) {
        if (source == 0) return XtreamClient.fromPrefs(context);
        return client(ChannelConfig.load(context).source(source));
    }

    static XtreamClient client(JSONObject source) {
        if (source == null) return null;
        String server = source.optString("server");
        String username = source.optString("username");
        String password = source.optString("password");
        if (server.isEmpty() || username.isEmpty() || password.isEmpty()) return null;
        return new XtreamClient(server, username, password, source.optBoolean("useHls"));
    }

    /** A source's display name: the main source's from prefs, others' from the config. */
    public static String name(Context context, int source) {
        if (source == 0) {
            String name = XtreamClient.prefs(context).getString(XtreamClient.PREF_NAME, "").trim();
            return name.isEmpty() ? XtreamClient.DEFAULT_NAME : name;
        }
        JSONObject s = ChannelConfig.load(context).source(source);
        return s == null ? "Source " + source : s.optString("name", "Source " + source);
    }

    /**
     * Every source's display name by index, reading the config once; for lists, where calling
     * name() per stream would re-read it each time.
     */
    public static java.util.Map<Integer, String> names(Context context) {
        java.util.Map<Integer, String> names = new java.util.HashMap<>();
        names.put(0, name(context, 0));
        org.json.JSONArray sources = ChannelConfig.load(context).sources();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            if (s != null) names.put(s.optInt("index"), s.optString("name"));
        }
        return names;
    }

    /** A source's name from names(), or a stand-in if it has been removed. */
    public static String name(java.util.Map<Integer, String> names, int streamId) {
        String name = names.get(sourceOf(streamId));
        return name != null ? name : "Source " + sourceOf(streamId);
    }

    /** Playback URL for a stream from any source, or null if its source is gone. */
    public static String streamUrl(Context context, int streamId) {
        XtreamClient client = client(context, sourceOf(streamId));
        return client == null ? null : client.getStreamUrl(panelId(streamId));
    }

    /**
     * A category's streams, with ids and category keys in FyrTuner's form for its source.
     * Used as ChannelConfig's StreamSource.
     */
    public static List<XtreamClient.Stream> streams(Context context, String categoryKey)
            throws IOException, JSONException {
        int source = 0;
        String categoryId = categoryKey;
        int colon = categoryKey.indexOf(':');
        if (colon > 0) {
            source = Integer.parseInt(categoryKey.substring(0, colon));
            categoryId = categoryKey.substring(colon + 1);
        }
        XtreamClient client = client(context, source);
        if (client == null) throw new IOException("Source " + source + " isn't set up");
        List<XtreamClient.Stream> streams = client.getLiveStreams(categoryId);
        if (source == 0) return streams;
        List<XtreamClient.Stream> encoded = new ArrayList<>(streams.size());
        for (XtreamClient.Stream s : streams) {
            if (s.streamId >= STREAM_OFFSET) {
                Log.w(TAG, "Skipping stream id too large to tag: " + s.streamId);
                continue;
            }
            encoded.add(new XtreamClient.Stream(encode(source, s.streamId), s.name, s.iconUrl,
                    s.epgChannelId, categoryKey));
        }
        return encoded;
    }

    /** Every live stream on a source (for Add stream), ids encoded for that source. */
    public static List<XtreamClient.Stream> allStreams(Context context, int source)
            throws IOException {
        XtreamClient client = client(context, source);
        if (client == null) throw new IOException("Source " + source + " isn't set up");
        List<XtreamClient.Stream> streams = client.getAllLiveStreams();
        if (source == 0) return streams;
        List<XtreamClient.Stream> encoded = new ArrayList<>(streams.size());
        for (XtreamClient.Stream s : streams) {
            if (s.streamId >= STREAM_OFFSET) continue;
            encoded.add(new XtreamClient.Stream(encode(source, s.streamId), s.name, null, null,
                    categoryKey(source, s.categoryId)));
        }
        return encoded;
    }
}
