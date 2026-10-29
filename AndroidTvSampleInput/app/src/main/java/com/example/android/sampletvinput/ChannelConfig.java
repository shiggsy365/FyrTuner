package com.example.android.sampletvinput;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The user's lineup edits, stored as JSON in files/config.json and edited from the web page:
 *
 * - groups: Xtream categories ("x:<categoryId>") and custom groups ("c:<id>"), in the order
 *   their channels are numbered. Each can be turned off and given a channel order (stream
 *   ids dragged into place on the web page).
 * - channels: per-channel edits keyed by the channel's own stream id — the group it was moved
 *   to, hidden, a guide link and time offset ("epgOffset", minutes added to its listings),
 *   a logo choice (the guide's logo, or an uploaded one in
 *   files/logos), and its streams: by default a channel is just its own stream; merging adds
 *   other streams ("streams", in playback order) and marks those streams "mergedInto" it. Moved channels keep a snapshot of the stream so the web page can list
 *   them without fetching their original category.
 * - prefixes / suffixes: comma-separated text stripped from channel names.
 * - guide sources: extra XMLTV URLs; "xtream" is the main panel's own guide and "xtream:n"
 *   source n's.
 * - disabledGuides: ids of guide sources (panel guides included) that sync skips.
 * - sources: further Xtream accounts (see Sources); the main one stays in prefs.
 *
 * The whole document is small, so every edit loads, changes and rewrites it under one lock.
 */
public final class ChannelConfig {
    private static final String TAG = "ChannelConfig";
    private static final String FILE_NAME = "config.json";
    private static final Object LOCK = new Object();

    public static final String XTREAM_GUIDE = "xtream";
    public static final String XTREAM_GROUP_PREFIX = "x:";
    public static final String CUSTOM_GROUP_PREFIX = "c:";

    /** Where a channel's logo comes from: the panel (default), its guide, or an upload. */
    public static final String LOGO_M3U = "m3u";
    public static final String LOGO_EPG = "epg";
    public static final String LOGO_CUSTOM = "custom";

    private final JSONObject mRoot;

    private ChannelConfig(JSONObject root) {
        mRoot = root;
    }

    // ---- Loading and saving ----

    public interface Edit {
        void apply(ChannelConfig config) throws JSONException;
    }

    public static ChannelConfig load(Context context) {
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            JSONObject root = null;
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
                    Log.e(TAG, "Could not read config; starting fresh", e);
                }
            }
            if (root == null) root = migrateFromPrefs(context);
            return new ChannelConfig(root);
        }
    }

    /** Loads, applies the edit and saves, all under the config lock. */
    public static void edit(Context context, Edit edit) throws JSONException, IOException {
        synchronized (LOCK) {
            ChannelConfig config = load(context);
            edit.apply(config);
            File file = new File(context.getFilesDir(), FILE_NAME);
            File tmp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(config.mRoot.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) throw new IOException("Could not save config");
        }
    }

    /** Replaces the whole config, e.g. from a backup. */
    public static void replace(Context context, JSONObject root) throws IOException {
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            File tmp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) throw new IOException("Could not save config");
        }
    }

    /** Seeds groups from the category list the first version of the app stored in prefs. */
    private static JSONObject migrateFromPrefs(Context context) {
        JSONObject root = new JSONObject();
        try {
            JSONArray groups = new JSONArray();
            for (String id : XtreamClient.selectedCategoryIds(context)) {
                groups.put(newGroup(XTREAM_GROUP_PREFIX + id, "Category " + id));
            }
            root.put("groups", groups);
        } catch (JSONException e) {
            Log.e(TAG, "Migration failed", e);
        }
        return root;
    }

    private static JSONObject newGroup(String id, String name) throws JSONException {
        return new JSONObject().put("id", id).put("name", name).put("enabled", true);
    }

    public JSONObject toJson() {
        return mRoot;
    }

    // ---- Groups ----

    public JSONArray groups() {
        JSONArray groups = mRoot.optJSONArray("groups");
        if (groups == null) {
            groups = new JSONArray();
            try {
                mRoot.put("groups", groups);
            } catch (JSONException ignored) {
            }
        }
        return groups;
    }

    public JSONObject group(String id) {
        JSONArray groups = groups();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g != null && id.equals(g.optString("id"))) return g;
        }
        return null;
    }

    private int groupIndex(String id) {
        JSONArray groups = groups();
        for (int i = 0; i < groups.length(); i++) {
            if (id.equals(groups.optJSONObject(i).optString("id"))) return i;
        }
        return -1;
    }

    /** Adds an Xtream category as a group, or returns the existing one. */
    public JSONObject addXtreamGroup(String categoryId, String name) throws JSONException {
        String id = XTREAM_GROUP_PREFIX + categoryId;
        JSONObject existing = group(id);
        if (existing != null) {
            existing.put("name", name);
            return existing;
        }
        JSONObject group = newGroup(id, name);
        groups().put(group);
        return group;
    }

    public JSONObject addCustomGroup(String name) throws JSONException {
        JSONObject group = newGroup(CUSTOM_GROUP_PREFIX
                + UUID.randomUUID().toString().substring(0, 8), name);
        groups().put(group);
        return group;
    }

    /**
     * Removes a group. Channels moved into it go back to their own category; channels moved
     * out of a removed Xtream category stay where they were moved to.
     */
    public void removeGroup(String id) throws JSONException {
        int index = groupIndex(id);
        if (index < 0) return;
        groups().remove(index);
        JSONObject channels = channels();
        for (Iterator<String> it = channels.keys(); it.hasNext(); ) {
            JSONObject edit = channels.optJSONObject(it.next());
            if (edit != null && id.equals(edit.optString("group"))) edit.remove("group");
        }
    }

    /** Moves a group up (-1) or down (+1) in the numbering order. */
    public void moveGroup(String id, int direction) throws JSONException {
        JSONArray groups = groups();
        int from = groupIndex(id);
        int to = from + direction;
        if (from < 0 || to < 0 || to >= groups.length()) return;
        JSONObject a = groups.getJSONObject(from);
        groups.put(from, groups.getJSONObject(to));
        groups.put(to, a);
    }

    /**
     * Sets a group's channel order. Channels not in the list (e.g. new on the panel) follow
     * the ordered ones in panel order.
     */
    public void setChannelOrder(String groupId, JSONArray streamIds) throws JSONException {
        JSONObject group = group(groupId);
        if (group == null) throw new IllegalArgumentException("No such group");
        group.put("order", streamIds);
    }

    // ---- Channel edits ----

    public JSONObject channels() {
        JSONObject channels = mRoot.optJSONObject("channels");
        if (channels == null) {
            channels = new JSONObject();
            try {
                mRoot.put("channels", channels);
            } catch (JSONException ignored) {
            }
        }
        return channels;
    }

    private JSONObject channelEdit(int streamId) {
        return channels().optJSONObject(String.valueOf(streamId));
    }

    private JSONObject channelEditForWrite(int streamId) throws JSONException {
        JSONObject edit = channelEdit(streamId);
        if (edit == null) {
            edit = new JSONObject();
            channels().put(String.valueOf(streamId), edit);
        }
        return edit;
    }

    private void dropIfEmpty(int streamId) {
        JSONObject edit = channelEdit(streamId);
        if (edit == null) return;
        boolean empty = !edit.has("group") && !edit.optBoolean("hidden")
                && !edit.has("guideSource") && !edit.has("epgOffset") && !edit.has("logo")
                && !edit.has("streams")
                && !edit.has("mergedInto") && !edit.has("name") && !edit.has("streamLabels");
        if (empty) channels().remove(String.valueOf(streamId));
    }

    // ---- Streams ----

    /** A channel's streams in playback order; just itself unless streams were merged in. */
    public List<Integer> channelStreams(int channelId) {
        List<Integer> streams = new ArrayList<>();
        JSONObject edit = channelEdit(channelId);
        JSONArray list = edit == null ? null : edit.optJSONArray("streams");
        if (list == null || list.length() == 0) {
            streams.add(channelId);
        } else {
            for (int i = 0; i < list.length(); i++) streams.add(list.optInt(i));
        }
        return streams;
    }

    /** Display names of a channel's streams, as saved when they were merged. */
    public Map<Integer, String> streamNames(int channelId) {
        Map<Integer, String> names = new HashMap<>();
        JSONObject edit = channelEdit(channelId);
        JSONObject saved = edit == null ? null : edit.optJSONObject("streamNames");
        if (saved != null) {
            for (Iterator<String> it = saved.keys(); it.hasNext(); ) {
                String key = it.next();
                names.put(Integer.parseInt(key), saved.optString(key));
            }
        }
        return names;
    }

    private void saveStreams(int channelId, List<Integer> streams, Map<Integer, String> names)
            throws JSONException {
        JSONObject edit = channelEditForWrite(channelId);
        if (streams.size() <= 1) {
            edit.remove("streams");
            edit.remove("streamNames");
        } else {
            JSONArray list = new JSONArray();
            JSONObject savedNames = new JSONObject();
            for (int id : streams) {
                list.put(id);
                String name = names.get(id);
                if (name != null) savedNames.put(String.valueOf(id), name);
            }
            edit.put("streams", list);
            edit.put("streamNames", savedNames);
        }
        dropIfEmpty(channelId);
    }

    /**
     * Merges channel {@code fromId} (with all its streams) into channel {@code intoId}: its
     * streams go to the end of intoId's list and it leaves the lineup. Its own logo, guide and
     * group settings are dropped; intoId's apply to the merged channel.
     *
     * @param names display names for the streams, kept for the stream menu and web page
     */
    public void mergeChannel(int fromId, int intoId, Map<Integer, String> names)
            throws JSONException {
        if (fromId == intoId) return;
        List<Integer> into = channelStreams(intoId);
        Map<Integer, String> labels = streamLabels(intoId);
        labels.putAll(streamLabels(fromId));
        Map<Integer, String> allNames = streamNames(intoId);
        allNames.putAll(streamNames(fromId));
        allNames.putAll(names);
        for (int id : channelStreams(fromId)) {
            if (!into.contains(id)) into.add(id);
            if (id != intoId) {
                JSONObject edit = channelEditForWrite(id);
                edit.put("mergedInto", intoId);
                edit.remove("streams");
                edit.remove("streamNames");
            }
        }
        saveStreams(intoId, into, allNames);
        if (!labels.isEmpty()) {
            JSONObject saved = new JSONObject();
            for (Map.Entry<Integer, String> label : labels.entrySet()) {
                saved.put(String.valueOf(label.getKey()), label.getValue());
            }
            channelEditForWrite(intoId).put("streamLabels", saved);
        }
    }

    /** Renames a channel; an empty name goes back to the panel's (cleaned) name. */
    public void renameChannel(int channelId, String name) throws JSONException {
        JSONObject edit = channelEditForWrite(channelId);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) edit.remove("name"); else edit.put("name", trimmed);
        dropIfEmpty(channelId);
    }

    /**
     * Renames one of a channel's streams, as shown in the player's stream menu. An empty name
     * goes back to the panel's.
     */
    public void renameStream(int channelId, int streamId, String name) throws JSONException {
        JSONObject edit = channelEditForWrite(channelId);
        JSONObject labels = edit.optJSONObject("streamLabels");
        if (labels == null) labels = new JSONObject();
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) labels.remove(String.valueOf(streamId));
        else labels.put(String.valueOf(streamId), trimmed);
        if (labels.length() == 0) edit.remove("streamLabels"); else edit.put("streamLabels", labels);
        dropIfEmpty(channelId);
    }

    /** Stream names the user set for a channel's streams. */
    private Map<Integer, String> streamLabels(int channelId) {
        Map<Integer, String> labels = new HashMap<>();
        JSONObject edit = channelEdit(channelId);
        JSONObject saved = edit == null ? null : edit.optJSONObject("streamLabels");
        if (saved != null) {
            for (Iterator<String> it = saved.keys(); it.hasNext(); ) {
                String key = it.next();
                labels.put(Integer.parseInt(key), saved.optString(key));
            }
        }
        return labels;
    }

    /** Takes a stream back out of a channel; it returns to its own place in the lineup. */
    public void splitStream(int channelId, int streamId) throws JSONException {
        if (streamId == channelId) {
            throw new IllegalArgumentException("A channel's own stream can't be split off");
        }
        List<Integer> streams = channelStreams(channelId);
        streams.remove(Integer.valueOf(streamId));
        saveStreams(channelId, streams, streamNames(channelId));
        JSONObject edit = channelEdit(streamId);
        if (edit != null) {
            edit.remove("mergedInto");
            dropIfEmpty(streamId);
        }
    }

    /** Sets the playback order of a channel's streams. */
    public void orderStreams(int channelId, List<Integer> order) throws JSONException {
        List<Integer> streams = channelStreams(channelId);
        if (order.size() != streams.size() || !order.containsAll(streams)) {
            throw new IllegalArgumentException("The order must list the channel's streams");
        }
        saveStreams(channelId, order, streamNames(channelId));
    }

    /** Moves a stream to the front of its channel, e.g. after it worked as a fallback. */
    public void promoteStream(int channelId, int streamId) throws JSONException {
        List<Integer> streams = channelStreams(channelId);
        if (!streams.remove(Integer.valueOf(streamId))) return;
        streams.add(0, streamId);
        saveStreams(channelId, streams, streamNames(channelId));
    }

    /** The channel a stream was merged into, or -1. */
    public int mergedInto(int streamId) {
        JSONObject edit = channelEdit(streamId);
        return edit == null ? -1 : edit.optInt("mergedInto", -1);
    }

    /**
     * Name used to spot the same channel across streams: cleaned, lowercase, without quality
     * tags (HD, 4K, HEVC, RAW…), small-caps tags and symbols. "+1" is kept: it's a different
     * channel.
     */
    public static String matchName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        // Superscript/small-caps tags panels add, like ᴴᴰ ᴿᴬᵂ ⁶⁰ᶠᵖˢ.
        n = n.replaceAll("[\u1d00-\u1dbf\u2070-\u209f\u00b2\u00b3\u00b9]+", " ");
        n = n.replaceAll("[\u25c9\u25cf\u2022|]", " ");
        n = n.replaceAll("\\b(uhd|fhd|hd|sd|4k|8k|hevc|h\\.?265|raw|vip|backup|alt|"
                + "\\d{2,3}\\s?fps)\\b", " ");
        n = n.replaceAll("[^a-z0-9+&]+", " ").trim().replaceAll("\\s+", " ");
        return n;
    }

    public void setHidden(int streamId, boolean hidden) throws JSONException {
        JSONObject edit = channelEditForWrite(streamId);
        if (hidden) edit.put("hidden", true); else edit.remove("hidden");
        dropIfEmpty(streamId);
    }

    /** Moves a channel to a group. Moving it back to its own category clears the move. */
    public void moveChannel(XtreamClient.Stream stream, String groupId) throws JSONException {
        JSONObject edit = channelEditForWrite(stream.streamId);
        if ((XTREAM_GROUP_PREFIX + stream.categoryId).equals(groupId)) {
            edit.remove("group");
            edit.remove("stream");
        } else {
            edit.put("group", groupId);
            edit.put("stream", streamToJson(stream));
        }
        dropIfEmpty(stream.streamId);
    }

    /** Links a channel to a guide channel; a null source restores the Xtream default. */
    public void linkGuide(int streamId, String sourceId, String guideChannelId)
            throws JSONException {
        JSONObject edit = channelEditForWrite(streamId);
        if (sourceId == null) {
            edit.remove("guideSource");
            edit.remove("guideChannel");
        } else {
            edit.put("guideSource", sourceId);
            edit.put("guideChannel", guideChannelId == null ? "" : guideChannelId);
        }
        dropIfEmpty(streamId);
    }

    /**
     * Shifts a channel's listings by this many minutes (0 clears it), for a guide whose times
     * are off for that channel.
     */
    public void setEpgOffset(int streamId, int minutes) throws JSONException {
        JSONObject edit = channelEditForWrite(streamId);
        if (minutes == 0) edit.remove("epgOffset"); else edit.put("epgOffset", minutes);
        dropIfEmpty(streamId);
    }

    /**
     * Sets a channel's logo source. EPG takes the guide logo's URL; a custom logo records
     * when it was uploaded, so a new upload counts as a change at the next sync.
     */
    public void setLogo(int streamId, String source, String epgUrl) throws JSONException {
        JSONObject edit = channelEditForWrite(streamId);
        edit.remove("logoUrl");
        edit.remove("logoVersion");
        if (LOGO_EPG.equals(source)) {
            edit.put("logo", LOGO_EPG);
            edit.put("logoUrl", epgUrl);
        } else if (LOGO_CUSTOM.equals(source)) {
            edit.put("logo", LOGO_CUSTOM);
            edit.put("logoVersion", System.currentTimeMillis());
        } else {
            edit.remove("logo");
        }
        dropIfEmpty(streamId);
    }

    public String logoSource(int streamId) {
        JSONObject edit = channelEdit(streamId);
        return edit == null ? LOGO_M3U : edit.optString("logo", LOGO_M3U);
    }

    /** Where an uploaded logo for a stream is kept. */
    public static File customLogoFile(Context context, int streamId) {
        return new File(new File(context.getFilesDir(), "logos"), streamId + ".png");
    }

    private static JSONObject streamToJson(XtreamClient.Stream s) throws JSONException {
        return new JSONObject().put("id", s.streamId).put("name", s.name)
                .put("icon", s.iconUrl == null ? "" : s.iconUrl)
                .put("epg", s.epgChannelId == null ? "" : s.epgChannelId)
                .put("cat", s.categoryId);
    }

    private static XtreamClient.Stream streamFromJson(JSONObject o) {
        return new XtreamClient.Stream(o.optInt("id"), o.optString("name"),
                emptyToNull(o.optString("icon")), emptyToNull(o.optString("epg")),
                o.optString("cat"));
    }

    // ---- Names ----

    public String prefixes() {
        return mRoot.optString("prefixes", "");
    }

    public String suffixes() {
        return mRoot.optString("suffixes", "");
    }

    public void setStripLists(String prefixes, String suffixes) throws JSONException {
        mRoot.put("prefixes", prefixes == null ? "" : prefixes);
        mRoot.put("suffixes", suffixes == null ? "" : suffixes);
    }

    /** Removes any listed prefix and suffix (ignoring case), repeatedly, then trims. */
    public static String cleanName(String name, List<String> prefixes, List<String> suffixes) {
        String result = name.trim();
        boolean changed = true;
        while (changed && !result.isEmpty()) {
            changed = false;
            String lower = result.toLowerCase(Locale.ROOT);
            for (String p : prefixes) {
                if (lower.startsWith(p.toLowerCase(Locale.ROOT))) {
                    result = result.substring(p.length()).trim();
                    changed = true;
                    break;
                }
            }
            lower = result.toLowerCase(Locale.ROOT);
            for (String s : suffixes) {
                if (lower.endsWith(s.toLowerCase(Locale.ROOT))) {
                    result = result.substring(0, result.length() - s.length()).trim();
                    changed = true;
                    break;
                }
            }
        }
        // Never strip a name down to nothing.
        return result.isEmpty() ? name.trim() : result;
    }

    public static List<String> splitList(String list) {
        List<String> items = new ArrayList<>();
        for (String item : list.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) items.add(trimmed);
        }
        return items;
    }

    // ---- Guide sources ----

    public JSONArray guideSources() {
        JSONArray sources = mRoot.optJSONArray("guideSources");
        if (sources == null) {
            sources = new JSONArray();
            try {
                mRoot.put("guideSources", sources);
            } catch (JSONException ignored) {
            }
        }
        return sources;
    }

    public JSONObject guideSource(String id) {
        JSONArray sources = guideSources();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            if (s != null && id.equals(s.optString("id"))) return s;
        }
        return null;
    }

    public JSONObject addGuideSource(String name, String url) throws JSONException {
        JSONObject source = new JSONObject()
                .put("id", "g" + UUID.randomUUID().toString().substring(0, 8))
                .put("name", TextUtils.isEmpty(name) ? url : name)
                .put("url", url);
        guideSources().put(source);
        return source;
    }

    public boolean guideDisabled(String id) {
        JSONArray disabled = mRoot.optJSONArray("disabledGuides");
        if (disabled == null) return false;
        for (int i = 0; i < disabled.length(); i++) {
            if (id.equals(disabled.optString(i))) return true;
        }
        return false;
    }

    /** Turns a guide source off or on. Sync skips a disabled guide, so its channels go blank. */
    public void setGuideDisabled(String id, boolean disabled) throws JSONException {
        JSONArray list = mRoot.optJSONArray("disabledGuides");
        if (list == null) mRoot.put("disabledGuides", list = new JSONArray());
        for (int i = list.length() - 1; i >= 0; i--) {
            if (id.equals(list.optString(i))) list.remove(i);
        }
        if (disabled) list.put(id);
    }

    /** Removes a guide source and unlinks the channels that used it. */
    public void removeGuideSource(String id) throws JSONException {
        setGuideDisabled(id, false);
        JSONArray sources = guideSources();
        for (int i = 0; i < sources.length(); i++) {
            if (id.equals(sources.getJSONObject(i).optString("id"))) {
                sources.remove(i);
                break;
            }
        }
        JSONObject channels = channels();
        List<Integer> unlinked = new ArrayList<>();
        for (Iterator<String> it = channels.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONObject edit = channels.optJSONObject(key);
            if (edit != null && id.equals(edit.optString("guideSource"))) {
                unlinked.add(Integer.parseInt(key));
            }
        }
        for (int streamId : unlinked) linkGuide(streamId, null, null);
    }

    /** Returns the guide URL for a guide source id, or null if it no longer exists. */
    public String guideUrl(Context context, String sourceId) {
        int panel = Sources.sourceOfGuide(sourceId);
        if (panel >= 0) {
            XtreamClient client = panel == 0 ? XtreamClient.fromPrefs(context)
                    : Sources.client(source(panel));
            return client == null ? null : client.getXmltvUrl();
        }
        JSONObject source = guideSource(sourceId);
        return source == null ? null : source.optString("url");
    }

    // ---- Xtream sources beyond the main account ----

    public JSONArray sources() {
        JSONArray sources = mRoot.optJSONArray("sources");
        if (sources == null) {
            sources = new JSONArray();
            try {
                mRoot.put("sources", sources);
            } catch (JSONException ignored) {
            }
        }
        return sources;
    }

    public JSONObject source(int index) {
        JSONArray sources = sources();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            if (s != null && s.optInt("index") == index) return s;
        }
        return null;
    }

    /** Adds a source with the lowest free index (1..MAX_SOURCES) and returns it. */
    public JSONObject addSource(String name, String server, String username, String password,
                                boolean useHls) throws JSONException {
        for (int index = 1; index <= Sources.MAX_SOURCES; index++) {
            if (source(index) != null) continue;
            JSONObject source = new JSONObject().put("index", index)
                    .put("name", TextUtils.isEmpty(name) ? "Source " + (index + 1) : name)
                    .put("server", server).put("username", username).put("password", password)
                    .put("useHls", useHls);
            sources().put(source);
            return source;
        }
        throw new IllegalArgumentException("Up to " + Sources.MAX_SOURCES + " extra sources");
    }

    /**
     * Removes a source with its groups and guide links. Channels elsewhere keep any of its
     * streams in their lists; those simply fail over at playback.
     */
    public void removeSource(int index) throws JSONException {
        JSONArray sources = sources();
        for (int i = 0; i < sources.length(); i++) {
            if (sources.getJSONObject(i).optInt("index") == index) {
                sources.remove(i);
                break;
            }
        }
        String prefix = XTREAM_GROUP_PREFIX + index + ":";
        JSONArray groups = groups();
        for (int i = groups.length() - 1; i >= 0; i--) {
            if (groups.getJSONObject(i).optString("id").startsWith(prefix)) groups.remove(i);
        }
        String guide = Sources.guideId(index);
        setGuideDisabled(guide, false);
        JSONObject channels = channels();
        List<Integer> unlinked = new ArrayList<>();
        for (Iterator<String> it = channels.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONObject edit = channels.optJSONObject(key);
            if (edit != null && guide.equals(edit.optString("guideSource"))) {
                unlinked.add(Integer.parseInt(key));
            }
        }
        for (int streamId : unlinked) linkGuide(streamId, null, null);
    }

    // ---- Lineup ----

    public interface StreamSource {
        List<XtreamClient.Stream> streams(String categoryId) throws IOException, JSONException;
    }

    /** One channel as it will appear (or, for the web page, could appear) on Fire TV. */
    public static class Entry {
        public final XtreamClient.Stream stream;
        public final String groupId;
        public final String displayName;
        /** The panel's name after clean-up; differs from displayName when renamed. */
        public final String defaultName;
        public final boolean hidden;
        public final boolean moved;
        public final String guideSource;
        /** Guide channel id, or null if the channel has no guide link. */
        public final String guideChannel;
        /** Minutes added to the guide's times for this channel. */
        public final int epgOffsetMin;
        public final String logoSource;
        /** Logo URL for M3U and EPG logos; null for a custom logo or none. */
        public final String logoUrl;
        /** Upload time of a custom logo, 0 otherwise. */
        public final long logoVersion;
        /** Stream ids in playback order; the first is usually the channel's own stream. */
        public final List<Integer> streams;
        /** Names for merged-in streams (the channel's own stream uses stream.name). */
        public final Map<Integer, String> streamNames;

        Entry(XtreamClient.Stream stream, String groupId, String displayName,
              String defaultName, boolean hidden,
              boolean moved, String guideSource, String guideChannel, int epgOffsetMin,
              String logoSource,
              String logoUrl, long logoVersion, List<Integer> streams,
              Map<Integer, String> streamNames) {
            this.stream = stream;
            this.groupId = groupId;
            this.displayName = displayName;
            this.defaultName = defaultName;
            this.hidden = hidden;
            this.moved = moved;
            this.guideSource = guideSource;
            this.guideChannel = guideChannel;
            this.epgOffsetMin = epgOffsetMin;
            this.logoSource = logoSource;
            this.logoUrl = logoUrl;
            this.logoVersion = logoVersion;
            this.streams = streams;
            this.streamNames = streamNames;
        }
    }

    /**
     * Builds the lineup in numbering order: groups in order, each with its category's streams
     * (minus those moved elsewhere) followed by streams moved into it.
     *
     * @param onlyGroupId if set, returns just that group's channels, including hidden ones
     *                    and even if the group is turned off (for the web page)
     */
    public List<Entry> buildLineup(StreamSource source, String onlyGroupId)
            throws IOException, JSONException {
        List<String> prefixes = splitList(prefixes());
        List<String> suffixes = splitList(suffixes());

        // Moved streams, by the group they were moved to.
        Map<String, List<XtreamClient.Stream>> movedInto = new HashMap<>();
        Set<Integer> movedIds = new HashSet<>();
        JSONObject channels = channels();
        for (Iterator<String> it = channels.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONObject edit = channels.optJSONObject(key);
            if (edit == null || !edit.has("group") || edit.optJSONObject("stream") == null) {
                continue;
            }
            movedIds.add(Integer.parseInt(key));
            String target = edit.optString("group");
            List<XtreamClient.Stream> list = movedInto.get(target);
            if (list == null) movedInto.put(target, list = new ArrayList<>());
            list.add(streamFromJson(edit.getJSONObject("stream")));
        }

        // Fetch each category once; moved streams are refreshed from their own category
        // where it's fetched anyway, and otherwise use their snapshot.
        Map<String, List<XtreamClient.Stream>> categories = new LinkedHashMap<>();
        Map<Integer, XtreamClient.Stream> fresh = new HashMap<>();
        JSONArray groups = groups();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.getJSONObject(i);
            String id = group.optString("id");
            boolean wanted = onlyGroupId != null ? id.equals(onlyGroupId)
                    : group.optBoolean("enabled", true);
            if (!wanted || !id.startsWith(XTREAM_GROUP_PREFIX)) continue;
            String categoryId = id.substring(XTREAM_GROUP_PREFIX.length());
            List<XtreamClient.Stream> streams = source.streams(categoryId);
            categories.put(categoryId, streams);
            for (XtreamClient.Stream s : streams) fresh.put(s.streamId, s);
        }

        List<Entry> lineup = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.getJSONObject(i);
            String id = group.optString("id");
            if (onlyGroupId != null ? !id.equals(onlyGroupId)
                    : !group.optBoolean("enabled", true)) {
                continue;
            }

            List<XtreamClient.Stream> members = new ArrayList<>();
            if (id.startsWith(XTREAM_GROUP_PREFIX)) {
                List<XtreamClient.Stream> own =
                        categories.get(id.substring(XTREAM_GROUP_PREFIX.length()));
                if (own != null) {
                    for (XtreamClient.Stream s : own) {
                        if (!movedIds.contains(s.streamId)) members.add(s);
                    }
                }
            }
            List<XtreamClient.Stream> moved = movedInto.get(id);
            int ownCount = members.size();
            if (moved != null) {
                for (XtreamClient.Stream s : moved) {
                    XtreamClient.Stream current = fresh.get(s.streamId);
                    members.add(current != null ? current : s);
                }
            }
            Set<Integer> movedHere = new HashSet<>();
            for (int m = ownCount; m < members.size(); m++) movedHere.add(members.get(m).streamId);
            applyOrder(members, group.optJSONArray("order"));

            for (int m = 0; m < members.size(); m++) {
                XtreamClient.Stream s = members.get(m);
                // A stream can sit in more than one category; the first group wins.
                if (!seen.add(s.streamId)) continue;
                JSONObject edit = channelEdit(s.streamId);
                // Streams merged into another channel play from that channel instead.
                if (edit != null && edit.has("mergedInto")) continue;
                boolean hidden = edit != null && edit.optBoolean("hidden");
                if (hidden && onlyGroupId == null) continue;

                // By default a channel uses its own panel's guide.
                String guideSource = Sources.guideId(Sources.sourceOf(s.streamId));
                String guideChannel = s.epgChannelId;
                if (edit != null && edit.has("guideSource")) {
                    guideSource = edit.optString("guideSource");
                    guideChannel = emptyToNull(edit.optString("guideChannel"));
                }
                String logoSource = edit == null ? LOGO_M3U : edit.optString("logo", LOGO_M3U);
                String logoUrl = s.iconUrl;
                long logoVersion = 0;
                if (LOGO_EPG.equals(logoSource)) {
                    logoUrl = emptyToNull(edit.optString("logoUrl"));
                } else if (LOGO_CUSTOM.equals(logoSource)) {
                    logoUrl = null;
                    logoVersion = edit.optLong("logoVersion");
                }
                Map<Integer, String> names = streamNames(s.streamId);
                names.put(s.streamId, s.name);
                names.putAll(streamLabels(s.streamId));
                String defaultName = cleanName(s.name, prefixes, suffixes);
                String customName = edit == null ? "" : edit.optString("name", "");
                lineup.add(new Entry(s, id, customName.isEmpty() ? defaultName : customName,
                        defaultName, hidden, movedHere.contains(s.streamId), guideSource,
                        guideChannel, edit == null ? 0 : edit.optInt("epgOffset"), logoSource,
                        logoUrl, logoVersion,
                        channelStreams(s.streamId), names));
            }
        }
        return lineup;
    }

    /** Sorts members by the group's saved order; unlisted ones keep panel order at the end. */
    private static void applyOrder(List<XtreamClient.Stream> members, JSONArray order) {
        if (order == null || order.length() == 0) return;
        Map<Integer, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.length(); i++) rank.put(order.optInt(i), i);
        Map<Integer, Integer> position = new HashMap<>();
        for (int i = 0; i < members.size(); i++) position.put(members.get(i).streamId, i);
        int unranked = order.length();
        Collections.sort(members, (a, b) -> {
            Integer ra = rank.get(a.streamId);
            Integer rb = rank.get(b.streamId);
            int ka = ra != null ? ra : unranked + position.get(a.streamId);
            int kb = rb != null ? rb : unranked + position.get(b.streamId);
            return Integer.compare(ka, kb);
        });
    }

    private static String emptyToNull(String s) {
        return TextUtils.isEmpty(s) ? null : s;
    }
}
