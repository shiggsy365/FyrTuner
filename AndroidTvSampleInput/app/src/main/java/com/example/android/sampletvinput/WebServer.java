package com.example.android.sampletvinput;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Serves the channel manager page (assets/web/index.html) and its JSON API on the local
 * network. The Xtream password is never sent to the browser; the page can only replace it.
 *
 * API (all JSON):
 *   GET  /api/state                       settings, groups, guide sources, sync status
 *   POST /api/settings                    account, strip lists, group rows
 *   GET  /api/categories?source=N         a source's live categories (0 = main account)
 *   POST /api/sources                     {name, server, username, password, useHls}
 *   POST /api/sources/update              {index, name?, server?, username?, password?, useHls?}
 *   POST /api/sources/delete              {index}
 *   POST /api/groups                      {categoryId,name} or {name} for a custom group
 *   POST /api/groups/update               {id, name?, enabled?, move?: -1|1}
 *   POST /api/groups/delete               {id}
 *   POST /api/groups/order                {id, order: [streamId, ...]}
 *   GET  /api/channels?group=ID           a group's channels, including hidden ones
 *   POST /api/channels                    {streamIds, group, hidden?, moveTo?, remove?, guide?,
 *                                          epgOffset? (minutes)}
 *   POST /api/channels/merge              {group, from, into} — merge channel "from" into "into"
 *   POST /api/channels/split              {channel, stream}
 *   POST /api/channels/order              {channel, order: [streamId, ...]}
 *   POST /api/channels/rename             {channel, name} — "" restores the panel name
 *   POST /api/channels/renamestream       {channel, stream, name} — "" restores it
 *   GET  /api/merges/suggest              channels with matching names, as merge suggestions
 *   POST /api/merges/apply                {merges: [{into, from: [channelId, ...]}], group?}
 *   GET  /api/streams/search?q=TEXT       any live stream on the panel, by name
 *   POST /api/channels/addstream          {channel, stream, name} — add a stream as a backup
 *   POST /api/guides                      {name, url}
 *   POST /api/guides/delete               {id}
 *   POST /api/guides/update               {id, disabled}
 *   POST /api/guides/refresh              {id} — re-reads the source's channel list
 *   GET  /api/guides/channels?source=ID&q=TEXT
 *   POST /api/logos                       {streamIds, group, source: "m3u"|"epg"}
 *   POST /api/logos/upload?streamId=ID    image bytes — sets a custom logo
 *   GET  /logos/ID.png                    an uploaded logo
 *   GET  /api/backup?password=0|1         zip of settings, config, logos and guide lists
 *   POST /api/restore                     zip bytes from /api/backup; replaces everything
 *   GET  /api/connections                each account's connections in use and limit
 *   POST /api/sync
 */
public class WebServer extends NanoHTTPD {
    private static final String TAG = "WebServer";
    public static final int PORT = 8089;
    private static final long STREAM_CACHE_MS = 10 * 60 * 1000;
    private static final int GUIDE_SEARCH_LIMIT = 50;
    private static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
    // Uploaded logos are scaled down to fit this box; Fire TV shows them much smaller.
    private static final int MAX_LOGO_PX = 600;
    private static final int MAX_BACKUP_BYTES = 64 * 1024 * 1024;
    private static final int BACKUP_VERSION = 1;

    private final Context mContext;
    // Category id -> streams, so browsing groups doesn't refetch from the panel every time.
    private final Map<String, List<XtreamClient.Stream>> mStreamCache = new HashMap<>();
    private final Map<String, Long> mStreamCacheTime = new HashMap<>();
    // Source index -> its categories.
    private final Map<Integer, List<XtreamClient.Category>> mCategories = new HashMap<>();

    /** Address -> last request time of devices that opened the page (see WebServerService). */
    static final Map<String, Long> sRecentClients = new java.util.concurrent.ConcurrentHashMap<>();

    public WebServer(Context context) {
        super(PORT);
        mContext = context.getApplicationContext();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String client = session.getRemoteIpAddress();
        if (client != null) sRecentClients.put(client, System.currentTimeMillis());
        try {
            if (uri.startsWith("/logos/")) return serveLogo(uri);
            if (!uri.startsWith("/api/")) return serveAsset(uri);
            if ("/api/logos/upload".equals(uri) && Method.POST.equals(session.getMethod())) {
                return json(Response.Status.OK, uploadLogo(session));
            }
            if ("/api/backup".equals(uri)) {
                return backup(!"0".equals(session.getParms().get("password")));
            }
            if ("/api/restore".equals(uri) && Method.POST.equals(session.getMethod())) {
                return json(Response.Status.OK, restore(session));
            }
            JSONObject body = Method.POST.equals(session.getMethod()) ? readBody(session) : null;
            Object result = route(uri, session.getParms(), body);
            return json(Response.Status.OK, result);
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Request failed: " + uri, e);
            return error(Response.Status.INTERNAL_ERROR, e.getMessage());
        }
    }

    private Object route(String uri, Map<String, String> params, JSONObject body)
            throws Exception {
        switch (uri) {
            case "/api/state":
                return state();
            case "/api/settings":
                return saveSettings(require(body));
            case "/api/categories":
                return categories(sourceParam(params));
            case "/api/sources":
                return addSource(require(body));
            case "/api/sources/update":
                return updateSource(require(body));
            case "/api/sources/delete":
                ChannelConfig.edit(mContext, c -> c.removeSource(require(body).getInt("index")));
                clearSourceCaches();
                return state();
            case "/api/groups":
                return addGroup(require(body));
            case "/api/groups/update":
                return updateGroup(require(body));
            case "/api/groups/delete":
                ChannelConfig.edit(mContext, c -> c.removeGroup(require(body).getString("id")));
                return state();
            case "/api/groups/order":
                ChannelConfig.edit(mContext, c -> c.setChannelOrder(require(body).getString("id"),
                        require(body).getJSONArray("order")));
                return new JSONObject().put("ok", true);
            case "/api/channels":
                return body == null ? channels(params.get("group")) : editChannels(body);
            case "/api/channels/merge":
                return mergeChannels(require(body));
            case "/api/channels/split":
                ChannelConfig.edit(mContext, c -> c.splitStream(require(body).getInt("channel"),
                        require(body).getInt("stream")));
                return new JSONObject().put("ok", true);
            case "/api/channels/order": {
                JSONArray order = require(body).getJSONArray("order");
                List<Integer> ids = new ArrayList<>();
                for (int i = 0; i < order.length(); i++) ids.add(order.getInt(i));
                ChannelConfig.edit(mContext, c -> c.orderStreams(body.getInt("channel"), ids));
                return new JSONObject().put("ok", true);
            }
            case "/api/channels/rename":
                ChannelConfig.edit(mContext, c -> c.renameChannel(require(body).getInt("channel"),
                        body.optString("name", "")));
                return new JSONObject().put("ok", true);
            case "/api/channels/renamestream":
                ChannelConfig.edit(mContext, c -> c.renameStream(require(body).getInt("channel"),
                        body.getInt("stream"), body.optString("name", "")));
                return new JSONObject().put("ok", true);
            case "/api/merges/suggest":
                return suggestMerges();
            case "/api/merges/apply":
                return applyMerges(require(body));
            case "/api/streams/search":
                return searchStreams(params.get("q"), sourceParam(params));
            case "/api/channels/addstream": {
                int channel = require(body).getInt("channel");
                int stream = body.getInt("stream");
                if (stream == channel) throw new IllegalArgumentException("That's this channel's own stream");
                Map<Integer, String> names = new HashMap<>();
                names.put(stream, body.optString("name", "Stream " + stream));
                ChannelConfig.edit(mContext, c -> c.mergeChannel(stream, channel, names));
                return new JSONObject().put("ok", true);
            }
            case "/api/guides":
                return addGuide(require(body));
            case "/api/guides/delete":
                return deleteGuide(require(body).getString("id"));
            case "/api/guides/update":
                return updateGuide(require(body));
            case "/api/guides/refresh":
                return refreshGuide(require(body).getString("id"));
            case "/api/guides/channels":
                return searchGuide(params.get("source"), params.get("q"));
            case "/api/logos":
                return setLogos(require(body));
            case "/api/connections":
                return connections();
            case "/api/sync":
                ChannelSyncWorker.syncNow(mContext);
                return new JSONObject().put("ok", true);
            default:
                throw new IllegalArgumentException("Unknown endpoint " + uri);
        }
    }

    // ---- State and settings ----

    private JSONObject state() throws JSONException {
        SharedPreferences prefs = XtreamClient.prefs(mContext);
        ChannelConfig config = ChannelConfig.load(mContext);
        JSONObject settings = new JSONObject()
                .put("name", Sources.name(mContext, 0))
                .put("server", prefs.getString(XtreamClient.PREF_SERVER, ""))
                .put("username", prefs.getString(XtreamClient.PREF_USERNAME, ""))
                .put("hasPassword", !prefs.getString(XtreamClient.PREF_PASSWORD, "").isEmpty())
                .put("useHls", prefs.getBoolean(XtreamClient.PREF_USE_HLS, false))
                .put("prefixes", config.prefixes())
                .put("suffixes", config.suffixes());
        return new JSONObject()
                .put("settings", settings)
                .put("groups", config.groups())
                .put("sources", sourcesJson(config))
                .put("guideSources", guideSourcesWithCounts(config))
                .put("sync", new JSONObject()
                        .put("running", GuideSync.sSyncRunning)
                        .put("status", GuideSync.sSyncStatus)
                        .put("last", prefs.getLong(XtreamClient.PREF_LAST_SYNC, 0)));
    }

    /** Every guide source with its channel count, whether it's off and when sync last read it. */
    private JSONArray guideSourcesWithCounts(ChannelConfig config) throws JSONException {
        JSONArray result = guideSourcesList(config);
        SharedPreferences times = GuideSync.guideTimes(mContext);
        for (int i = 0; i < result.length(); i++) {
            JSONObject s = result.getJSONObject(i);
            String id = s.getString("id");
            s.put("disabled", config.guideDisabled(id))
                    .put("refreshed", times.getLong(id + ".ok", 0))
                    .put("failed", times.getLong(id + ".failed", 0));
        }
        return result;
    }

    private JSONArray guideSourcesList(ChannelConfig config) throws JSONException {
        JSONArray result = new JSONArray();
        result.put(new JSONObject().put("id", ChannelConfig.XTREAM_GUIDE)
                .put("name", Sources.name(mContext, 0) + " panel guide").put("builtIn", true)
                .put("channels", cachedGuideCount(ChannelConfig.XTREAM_GUIDE)));
        JSONArray panels = config.sources();
        for (int i = 0; i < panels.length(); i++) {
            JSONObject panel = panels.getJSONObject(i);
            String id = Sources.guideId(panel.optInt("index"));
            result.put(new JSONObject().put("id", id)
                    .put("name", panel.optString("name") + " panel guide").put("builtIn", true)
                    .put("channels", cachedGuideCount(id)));
        }
        JSONArray sources = config.guideSources();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject s = new JSONObject(sources.getJSONObject(i).toString());
            s.put("channels", cachedGuideCount(s.getString("id")));
            result.put(s);
        }
        return result;
    }

    private JSONObject saveSettings(JSONObject body) throws Exception {
        SharedPreferences.Editor editor = XtreamClient.prefs(mContext).edit();
        if (body.has("name")) {
            editor.putString(XtreamClient.PREF_NAME, body.getString("name").trim());
        }
        if (body.has("server")) {
            editor.putString(XtreamClient.PREF_SERVER, body.getString("server").trim());
        }
        if (body.has("username")) {
            editor.putString(XtreamClient.PREF_USERNAME, body.getString("username").trim());
        }
        String password = body.optString("password", "");
        if (!password.isEmpty()) editor.putString(XtreamClient.PREF_PASSWORD, password.trim());
        if (body.has("useHls")) editor.putBoolean(XtreamClient.PREF_USE_HLS, body.getBoolean("useHls"));
        editor.commit();
        ChannelConfig.edit(mContext, c -> {
            c.setStripLists(body.optString("prefixes", c.prefixes()),
                    body.optString("suffixes", c.suffixes()));
        });
        synchronized (this) {
            mCategories.clear();
            mStreamCache.clear();
        }
        XtreamClient client = XtreamClient.fromPrefs(mContext);
        String accountError = client == null ? "Account details incomplete" : client.checkAccount();
        return state().put("accountError", accountError == null ? JSONObject.NULL : accountError);
    }

    // ---- Groups ----

    /** A source's categories; "key" is what a group stores (see Sources.categoryKey). */
    private JSONArray categories(int source) throws Exception {
        List<XtreamClient.Category> categories;
        synchronized (this) {
            categories = mCategories.get(source);
        }
        if (categories == null) {
            XtreamClient client = Sources.client(mContext, source);
            if (client == null) throw new IllegalArgumentException("That source isn't set up");
            categories = client.getLiveCategories();
            synchronized (this) {
                mCategories.put(source, categories);
            }
        }
        JSONArray result = new JSONArray();
        for (XtreamClient.Category c : categories) {
            result.put(new JSONObject().put("id", c.id).put("name", c.name)
                    .put("key", Sources.categoryKey(source, c.id)));
        }
        return result;
    }

    private static int sourceParam(Map<String, String> params) {
        String source = params.get("source");
        return TextUtils.isEmpty(source) ? 0 : Integer.parseInt(source);
    }

    // ---- Sources ----

    private JSONArray sourcesJson(ChannelConfig config) throws JSONException {
        SharedPreferences prefs = XtreamClient.prefs(mContext);
        JSONArray result = new JSONArray();
        result.put(new JSONObject().put("index", 0).put("name", Sources.name(mContext, 0))
                .put("server", prefs.getString(XtreamClient.PREF_SERVER, ""))
                .put("username", prefs.getString(XtreamClient.PREF_USERNAME, ""))
                .put("useHls", prefs.getBoolean(XtreamClient.PREF_USE_HLS, false))
                .put("main", true));
        JSONArray sources = config.sources();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject src = sources.getJSONObject(i);
            result.put(new JSONObject().put("index", src.optInt("index"))
                    .put("name", src.optString("name"))
                    .put("server", src.optString("server"))
                    .put("username", src.optString("username"))
                    .put("useHls", src.optBoolean("useHls"))
                    .put("hasPassword", !src.optString("password").isEmpty()));
        }
        return result;
    }

    /** Each Xtream account's connections in use and its limit, asked of the panels at once. */
    private JSONObject connections() throws Exception {
        JSONArray sources = sourcesJson(ChannelConfig.load(mContext));
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, sources.length()));
        try {
            List<java.util.concurrent.Future<int[]>> results = new ArrayList<>();
            for (int i = 0; i < sources.length(); i++) {
                int index = sources.getJSONObject(i).getInt("index");
                results.add(pool.submit(() -> {
                    XtreamClient client = Sources.client(mContext, index);
                    return client == null ? null : client.connections();
                }));
            }
            JSONArray out = new JSONArray();
            for (int i = 0; i < sources.length(); i++) {
                JSONObject src = sources.getJSONObject(i);
                int[] cons = results.get(i).get(10, java.util.concurrent.TimeUnit.SECONDS);
                JSONObject o = new JSONObject().put("index", src.getInt("index"))
                        .put("name", src.optString("name"));
                if (cons != null) o.put("active", cons[0]).put("max", cons[1]);
                out.put(o);
            }
            return new JSONObject().put("connections", out);
        } finally {
            pool.shutdownNow();
        }
    }

    private JSONObject addSource(JSONObject body) throws Exception {
        String server = body.optString("server", "").trim();
        String username = body.optString("username", "").trim();
        String password = body.optString("password", "").trim();
        if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
            throw new IllegalArgumentException("Server, username and password are required");
        }
        boolean useHls = body.optBoolean("useHls", false);
        String error = new XtreamClient(server, username, password, useHls).checkAccount();
        if (error != null) throw new IllegalArgumentException(error);
        ChannelConfig.edit(mContext, c -> c.addSource(body.optString("name", "").trim(), server,
                username, password, useHls));
        return state();
    }

    /** Updates a source's details; an empty password keeps the current one. */
    private JSONObject updateSource(JSONObject body) throws Exception {
        int index = body.getInt("index");
        JSONObject current = ChannelConfig.load(mContext).source(index);
        if (current == null) throw new IllegalArgumentException("No such source");
        String server = body.optString("server", current.optString("server")).trim();
        String username = body.optString("username", current.optString("username")).trim();
        String password = body.optString("password", "").trim();
        if (password.isEmpty()) password = current.optString("password");
        boolean useHls = body.optBoolean("useHls", current.optBoolean("useHls"));
        String error = new XtreamClient(server, username, password, useHls).checkAccount();
        if (error != null) throw new IllegalArgumentException(error);
        String finalPassword = password;
        ChannelConfig.edit(mContext, c -> {
            JSONObject src = c.source(index);
            src.put("name", body.optString("name", src.optString("name")).trim());
            src.put("server", server).put("username", username).put("password", finalPassword)
                    .put("useHls", useHls);
        });
        clearSourceCaches();
        return state();
    }

    private synchronized void clearSourceCaches() {
        mCategories.clear();
        mStreamCache.clear();
        mStreamCacheTime.clear();
        mAllStreams.clear();
    }

    private JSONObject addGroup(JSONObject body) throws Exception {
        String name = body.optString("name", "").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Group name is required");
        String categoryId = body.optString("categoryId", "");
        ChannelConfig.edit(mContext, c -> {
            if (categoryId.isEmpty()) c.addCustomGroup(name);
            else c.addXtreamGroup(categoryId, name);
        });
        return state();
    }

    private JSONObject updateGroup(JSONObject body) throws Exception {
        String id = body.getString("id");
        ChannelConfig.edit(mContext, c -> {
            JSONObject group = c.group(id);
            if (group == null) throw new IllegalArgumentException("No such group");
            if (body.has("name") && !body.getString("name").trim().isEmpty()) {
                group.put("name", body.getString("name").trim());
            }
            if (body.has("enabled")) group.put("enabled", body.getBoolean("enabled"));
            if (body.has("move")) c.moveGroup(id, body.getInt("move"));
        });
        return state();
    }

    // ---- Channels ----

    private JSONArray channels(String groupId) throws Exception {
        if (TextUtils.isEmpty(groupId)) throw new IllegalArgumentException("group is required");
        ChannelConfig config = ChannelConfig.load(mContext);
        Map<String, String> guideNames = new HashMap<>();
        Map<Integer, String> sourceNames = Sources.names(mContext);
        JSONArray result = new JSONArray();
        for (ChannelConfig.Entry e : config.buildLineup(this::cachedStreams, groupId)) {
            String guideName = null;
            if (e.guideChannel != null) {
                guideName = guideChannelName(guideNames, e.guideSource, e.guideChannel);
            }
            result.put(new JSONObject()
                    .put("streamId", e.stream.streamId)
                    .put("name", e.stream.name)
                    .put("displayName", e.displayName)
                    .put("defaultName", e.defaultName)
                    .put("icon", e.stream.iconUrl == null ? "" : e.stream.iconUrl)
                    .put("logoSource", e.logoSource)
                    .put("logo", ChannelConfig.LOGO_CUSTOM.equals(e.logoSource)
                            ? "/logos/" + e.stream.streamId + ".png?v=" + e.logoVersion
                            : e.logoUrl == null ? "" : e.logoUrl)
                    .put("category", e.stream.categoryId)
                    .put("hidden", e.hidden)
                    .put("moved", e.moved)
                    .put("guideSource", e.guideSource)
                    .put("guideChannel", e.guideChannel == null ? "" : e.guideChannel)
                    .put("guideName", guideName == null ? JSONObject.NULL : guideName)
                    .put("epgOffset", e.epgOffsetMin)
                    .put("catchup", catchupDays(e.streams))
                    .put("streams", streamsJson(e, sourceNames)));
        }
        return result;
    }

    /**
     * Applies one edit to a set of channels in a group: hide/show, move to another group,
     * remove from the group they were moved to (back to their own category), or link a guide
     * channel ({source, channel}, or null to restore the panel default).
     */
    private JSONObject editChannels(JSONObject body) throws Exception {
        JSONArray ids = body.getJSONArray("streamIds");
        String groupId = body.getString("group");
        // Moving needs each stream's details; take them from the group being edited.
        Map<Integer, XtreamClient.Stream> streams = new HashMap<>();
        if (body.has("moveTo") || body.optBoolean("remove")) {
            for (ChannelConfig.Entry e : ChannelConfig.load(mContext)
                    .buildLineup(this::cachedStreams, groupId)) {
                streams.put(e.stream.streamId, e.stream);
            }
        }
        ChannelConfig.edit(mContext, c -> {
            for (int i = 0; i < ids.length(); i++) {
                int streamId = ids.getInt(i);
                if (body.has("hidden")) c.setHidden(streamId, body.getBoolean("hidden"));
                if (body.has("moveTo")) {
                    XtreamClient.Stream stream = streams.get(streamId);
                    if (stream != null) c.moveChannel(stream, body.getString("moveTo"));
                }
                if (body.optBoolean("remove")) {
                    XtreamClient.Stream stream = streams.get(streamId);
                    if (stream != null) c.moveChannel(stream,
                            ChannelConfig.XTREAM_GROUP_PREFIX + stream.categoryId);
                }
                if (body.has("epgOffset")) c.setEpgOffset(streamId, body.getInt("epgOffset"));
                if (body.has("guide")) {
                    JSONObject guide = body.optJSONObject("guide");
                    if (guide == null) c.linkGuide(streamId, null, null);
                    else c.linkGuide(streamId, guide.getString("source"),
                            guide.optString("channel", ""));
                }
            }
        });
        // A channel showing its guide's logo follows the new guide link.
        if (body.has("guide")) refreshEpgLogos(ids, groupId);
        return new JSONObject().put("ok", true);
    }

    // ---- Channels and streams ----

    private JSONArray streamsJson(ChannelConfig.Entry e, Map<Integer, String> sourceNames)
            throws JSONException {
        JSONArray streams = new JSONArray();
        for (int id : e.streams) {
            String name = e.streamNames.get(id);
            streams.put(new JSONObject().put("id", id)
                    .put("name", name == null ? "Stream " + id : name)
                    .put("source", Sources.name(sourceNames, id))
                    .put("catchup", Catchup.days(mContext, id)));
        }
        return streams;
    }

    /** The most catch-up days of any of the streams, as of the last sync. */
    private int catchupDays(List<Integer> streams) {
        int days = 0;
        for (int id : streams) days = Math.max(days, Catchup.days(mContext, id));
        return days;
    }

    /** Stream id -> display name for every stream of the given entries. */
    private static Map<Integer, String> streamNames(List<ChannelConfig.Entry> entries) {
        Map<Integer, String> names = new HashMap<>();
        for (ChannelConfig.Entry e : entries) names.putAll(e.streamNames);
        return names;
    }

    /** Merges one channel into another in the same group ("merge up" on the web page). */
    private JSONObject mergeChannels(JSONObject body) throws Exception {
        int from = body.getInt("from");
        int into = body.getInt("into");
        Map<Integer, String> names = streamNames(ChannelConfig.load(mContext)
                .buildLineup(this::cachedStreams, body.getString("group")));
        ChannelConfig.edit(mContext, c -> c.mergeChannel(from, into, names));
        return new JSONObject().put("ok", true);
    }

    /**
     * Finds visible channels whose names match once quality tags are ignored (BBC 1 HD, BBC 1
     * 4K, BBC 1 HEVC...). Each suggestion merges the later ones into the first in the lineup.
     */
    private JSONObject suggestMerges() throws Exception {
        ChannelConfig config = ChannelConfig.load(mContext);
        List<ChannelConfig.Entry> lineup = config.buildLineup(this::cachedStreams, null);
        Map<String, List<ChannelConfig.Entry>> byName = new java.util.LinkedHashMap<>();
        for (ChannelConfig.Entry e : lineup) {
            String key = ChannelConfig.matchName(e.displayName);
            if (key.isEmpty()) continue;
            List<ChannelConfig.Entry> list = byName.get(key);
            if (list == null) byName.put(key, list = new ArrayList<>());
            list.add(e);
        }
        JSONArray suggestions = new JSONArray();
        for (List<ChannelConfig.Entry> list : byName.values()) {
            if (list.size() < 2) continue;
            JSONArray channels = new JSONArray();
            for (ChannelConfig.Entry e : list) {
                JSONObject group = config.group(e.groupId);
                channels.put(new JSONObject().put("id", e.stream.streamId)
                        .put("name", e.displayName)
                        .put("original", e.stream.name)
                        .put("group", group == null ? "" : group.optString("name"))
                        .put("streams", e.streams.size()));
            }
            suggestions.put(new JSONObject().put("into", list.get(0).stream.streamId)
                    .put("channels", channels));
        }
        return new JSONObject().put("suggestions", suggestions);
    }

    private JSONObject applyMerges(JSONObject body) throws Exception {
        JSONArray merges = body.getJSONArray("merges");
        ChannelConfig config = ChannelConfig.load(mContext);
        Map<Integer, String> names = streamNames(config.buildLineup(this::cachedStreams, null));
        // The group being edited may include hidden channels, which the full lineup leaves out.
        if (body.has("group")) {
            names.putAll(streamNames(config.buildLineup(this::cachedStreams,
                    body.getString("group"))));
        }
        int[] merged = {0};
        ChannelConfig.edit(mContext, c -> {
            for (int i = 0; i < merges.length(); i++) {
                JSONObject merge = merges.getJSONObject(i);
                int into = merge.getInt("into");
                JSONArray from = merge.getJSONArray("from");
                for (int j = 0; j < from.length(); j++) {
                    c.mergeChannel(from.getInt(j), into, names);
                    merged[0]++;
                }
            }
        });
        return new JSONObject().put("merged", merged[0]);
    }

    // Every live stream on the panel, for Add stream; slow to fetch, so kept for a while.
    // Source index -> every live stream on it, and when that was fetched.
    private final Map<Integer, List<XtreamClient.Stream>> mAllStreams = new HashMap<>();
    private final Map<Integer, Long> mAllStreamsTime = new HashMap<>();
    private static final long ALL_STREAMS_CACHE_MS = 30 * 60 * 1000;
    private static final int STREAM_SEARCH_LIMIT = 60;

    /** Matches every word of the query against stream names, across the whole panel. */
    private JSONObject searchStreams(String query, int source) throws Exception {
        List<XtreamClient.Stream> all;
        synchronized (this) {
            Long fetched = mAllStreamsTime.get(source);
            all = mAllStreams.get(source);
            if (all == null || fetched == null
                    || System.currentTimeMillis() - fetched > ALL_STREAMS_CACHE_MS) {
                all = Sources.allStreams(mContext, source);
                // Keep one source's list at a time to bound memory.
                mAllStreams.clear();
                mAllStreamsTime.clear();
                mAllStreams.put(source, all);
                mAllStreamsTime.put(source, System.currentTimeMillis());
            }
        }
        Map<String, String> categoryNames = new HashMap<>();
        JSONArray categories = categories(source);
        for (int i = 0; i < categories.length(); i++) {
            JSONObject c = categories.getJSONObject(i);
            categoryNames.put(c.getString("key"), c.getString("name"));
        }
        List<String> words = new ArrayList<>();
        for (String w : (query == null ? "" : query).toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!w.isEmpty()) words.add(w);
        }
        JSONArray matches = new JSONArray();
        if (!words.isEmpty()) {
            for (XtreamClient.Stream st : all) {
                String name = st.name.toLowerCase(Locale.ROOT);
                boolean ok = true;
                for (String w : words) {
                    if (!name.contains(w)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                String category = categoryNames.get(st.categoryId);
                matches.put(new JSONObject().put("id", st.streamId).put("name", st.name)
                        .put("category", category == null ? "" : category)
                        .put("catchup", Catchup.days(mContext, st.streamId)));
                if (matches.length() >= STREAM_SEARCH_LIMIT) break;
            }
        }
        return new JSONObject().put("total", all.size()).put("matches", matches);
    }

    // ---- Logos ----

    /** Sets the logo source for channels: "m3u" (the panel's) or "epg" (the linked guide's). */
    private JSONObject setLogos(JSONObject body) throws Exception {
        JSONArray ids = body.getJSONArray("streamIds");
        String groupId = body.getString("group");
        String source = body.getString("source");
        if (ChannelConfig.LOGO_M3U.equals(source)) {
            ChannelConfig.edit(mContext, c -> {
                for (int i = 0; i < ids.length(); i++) {
                    c.setLogo(ids.getInt(i), ChannelConfig.LOGO_M3U, null);
                }
            });
            return new JSONObject().put("updated", ids.length()).put("missing", 0);
        }
        if (!ChannelConfig.LOGO_EPG.equals(source)) {
            throw new IllegalArgumentException("Unknown logo source " + source);
        }
        Map<Integer, String> icons = epgIcons(ids, groupId);
        ChannelConfig.edit(mContext, c -> {
            for (Map.Entry<Integer, String> icon : icons.entrySet()) {
                c.setLogo(icon.getKey(), ChannelConfig.LOGO_EPG, icon.getValue());
            }
        });
        return new JSONObject().put("updated", icons.size())
                .put("missing", ids.length() - icons.size());
    }

    /** Re-reads the guide logo for channels whose logo source is EPG. */
    private void refreshEpgLogos(JSONArray ids, String groupId) throws Exception {
        ChannelConfig config = ChannelConfig.load(mContext);
        JSONArray epgIds = new JSONArray();
        for (int i = 0; i < ids.length(); i++) {
            if (ChannelConfig.LOGO_EPG.equals(config.logoSource(ids.getInt(i)))) {
                epgIds.put(ids.getInt(i));
            }
        }
        if (epgIds.length() == 0) return;
        Map<Integer, String> icons = epgIcons(epgIds, groupId);
        ChannelConfig.edit(mContext, c -> {
            for (int i = 0; i < epgIds.length(); i++) {
                int streamId = epgIds.getInt(i);
                String icon = icons.get(streamId);
                if (icon != null) c.setLogo(streamId, ChannelConfig.LOGO_EPG, icon);
                else c.setLogo(streamId, ChannelConfig.LOGO_M3U, null);
            }
        });
    }

    /** Stream id -> logo URL from each channel's linked guide, for those that have one. */
    private Map<Integer, String> epgIcons(JSONArray ids, String groupId) throws Exception {
        java.util.Set<Integer> wanted = new java.util.HashSet<>();
        for (int i = 0; i < ids.length(); i++) wanted.add(ids.getInt(i));
        Map<Integer, String> icons = new HashMap<>();
        for (ChannelConfig.Entry e : ChannelConfig.load(mContext)
                .buildLineup(this::cachedStreams, groupId)) {
            if (!wanted.contains(e.stream.streamId) || e.guideChannel == null) continue;
            String icon = guideChannelIcon(e.guideSource, e.guideChannel);
            if (icon != null) icons.put(e.stream.streamId, icon);
        }
        return icons;
    }

    private String guideChannelIcon(String source, String channel) {
        JSONArray channels = guideChannels(source);
        if (channels == null) return null;
        for (int i = 0; i < channels.length(); i++) {
            JSONArray ch = channels.optJSONArray(i);
            if (ch != null && channel.equals(ch.optString(0))) {
                String icon = ch.optString(2, "");
                return icon.isEmpty() ? null : icon;
            }
        }
        return null;
    }

    /** Saves an uploaded image as a channel's custom logo, scaled down to MAX_LOGO_PX. */
    private JSONObject uploadLogo(IHTTPSession session) throws Exception {
        String id = session.getParms().get("streamId");
        if (TextUtils.isEmpty(id)) throw new IllegalArgumentException("streamId is required");
        int streamId = Integer.parseInt(id);
        byte[] data = readBytes(session, MAX_UPLOAD_BYTES);
        android.graphics.Bitmap bitmap =
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bitmap == null) throw new IllegalArgumentException("That file isn't an image");
        float scale = Math.min(1f, MAX_LOGO_PX / (float) Math.max(bitmap.getWidth(),
                bitmap.getHeight()));
        if (scale < 1f) {
            bitmap = android.graphics.Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
        }
        File file = ChannelConfig.customLogoFile(mContext, streamId);
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
        ChannelConfig.edit(mContext, c -> c.setLogo(streamId, ChannelConfig.LOGO_CUSTOM, null));
        return new JSONObject().put("ok", true).put("width", bitmap.getWidth())
                .put("height", bitmap.getHeight());
    }

    private Response serveLogo(String uri) throws IOException {
        String name = uri.substring("/logos/".length());
        if (!name.matches("\\d+\\.png")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }
        File file = ChannelConfig.customLogoFile(mContext,
                Integer.parseInt(name.substring(0, name.length() - 4)));
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }
        return newFixedLengthResponse(Response.Status.OK, "image/png", new FileInputStream(file),
                file.length());
    }

    private static byte[] readBytes(IHTTPSession session, int max) throws IOException {
        String length = session.getHeaders().get("content-length");
        int size = length == null ? 0 : Integer.parseInt(length);
        if (size <= 0) throw new IllegalArgumentException("Empty upload");
        if (size > max) {
            throw new IllegalArgumentException("File is too large (max "
                    + (max / (1024 * 1024)) + " MB)");
        }
        byte[] data = new byte[size];
        InputStream in = session.getInputStream();
        int read = 0;
        while (read < size) {
            int n = in.read(data, read, size - read);
            if (n < 0) break;
            read += n;
        }
        return data;
    }

    private synchronized List<XtreamClient.Stream> cachedStreams(String categoryId)
            throws IOException, JSONException {
        Long fetched = mStreamCacheTime.get(categoryId);
        List<XtreamClient.Stream> streams = mStreamCache.get(categoryId);
        if (streams == null || fetched == null
                || System.currentTimeMillis() - fetched > STREAM_CACHE_MS) {
            streams = Sources.streams(mContext, categoryId);
            mStreamCache.put(categoryId, streams);
            mStreamCacheTime.put(categoryId, System.currentTimeMillis());
        }
        return streams;
    }

    // ---- Guide sources ----

    private JSONObject addGuide(JSONObject body) throws Exception {
        String url = body.optString("url", "").trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("Guide URL must start with http:// or https://");
        }
        String[] id = new String[1];
        ChannelConfig.edit(mContext,
                c -> id[0] = c.addGuideSource(body.optString("name", "").trim(), url)
                        .getString("id"));
        refreshGuide(id[0]);
        return state();
    }

    private JSONObject deleteGuide(String id) throws Exception {
        if (Sources.sourceOfGuide(id) >= 0) {
            throw new IllegalArgumentException("The panel guide can't be removed");
        }
        ChannelConfig.edit(mContext, c -> c.removeGuideSource(id));
        //noinspection ResultOfMethodCallIgnored
        guideCacheFile(id).delete();
        GuideSync.guideTimes(mContext).edit().remove(id + ".ok").remove(id + ".failed").apply();
        return state();
    }

    private JSONObject updateGuide(JSONObject body) throws Exception {
        String id = body.getString("id");
        if (ChannelConfig.load(mContext).guideUrl(mContext, id) == null) {
            throw new IllegalArgumentException("No such guide source");
        }
        ChannelConfig.edit(mContext, c -> c.setGuideDisabled(id, body.getBoolean("disabled")));
        return state();
    }

    /** Downloads a guide's channel list and caches it for linking and search. */
    private JSONObject refreshGuide(String id) throws Exception {
        String url = ChannelConfig.load(mContext).guideUrl(mContext, id);
        if (url == null) throw new IllegalArgumentException("No such guide source");
        List<Xmltv.GuideChannel> channels = Xmltv.readChannels(url);
        JSONArray cache = new JSONArray();
        for (Xmltv.GuideChannel ch : channels) {
            cache.put(new JSONArray().put(ch.id).put(ch.name).put(ch.icon == null ? "" : ch.icon));
        }
        try (OutputStream out = new FileOutputStream(guideCacheFile(id))) {
            out.write(cache.toString().getBytes(StandardCharsets.UTF_8));
        }
        synchronized (this) {
            mGuideCache.remove(id);
        }
        return state().put("refreshed", new JSONObject().put("id", id)
                .put("channels", channels.size()));
    }

    // Guide id -> [[channelId, name], ...], loaded from the cache file on first use.
    private final Map<String, JSONArray> mGuideCache = new HashMap<>();

    private synchronized JSONArray guideChannels(String id) {
        JSONArray channels = mGuideCache.get(id);
        if (channels != null) return channels;
        File file = guideCacheFile(id);
        if (!file.exists()) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            channels = new JSONArray(new String(data, 0, read, StandardCharsets.UTF_8));
            mGuideCache.put(id, channels);
            return channels;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Could not read guide cache " + id, e);
            return null;
        }
    }

    private int cachedGuideCount(String id) {
        JSONArray channels = guideChannels(id);
        return channels == null ? -1 : channels.length();
    }

    private String guideChannelName(Map<String, String> memo, String source, String channel) {
        String key = source + "\n" + channel;
        if (memo.containsKey(key)) return memo.get(key);
        String name = null;
        JSONArray channels = guideChannels(source);
        if (channels != null) {
            for (int i = 0; i < channels.length(); i++) {
                JSONArray ch = channels.optJSONArray(i);
                if (ch != null && channel.equals(ch.optString(0))) {
                    name = ch.optString(1);
                    break;
                }
            }
        }
        memo.put(key, name);
        return name;
    }

    /** Matches every word of the query against guide channel names and ids. */
    private JSONObject searchGuide(String source, String query) throws JSONException {
        if (TextUtils.isEmpty(source)) throw new IllegalArgumentException("source is required");
        JSONArray channels = guideChannels(source);
        JSONObject result = new JSONObject().put("loaded", channels != null);
        JSONArray matches = new JSONArray();
        if (channels != null) {
            List<String> words = new ArrayList<>();
            for (String w : (query == null ? "" : query).toLowerCase(Locale.ROOT).split("\\s+")) {
                if (!w.isEmpty()) words.add(w);
            }
            for (int i = 0; i < channels.length() && matches.length() < GUIDE_SEARCH_LIMIT; i++) {
                JSONArray ch = channels.getJSONArray(i);
                String haystack = (ch.optString(0) + " " + ch.optString(1)).toLowerCase(Locale.ROOT);
                boolean all = true;
                for (String w : words) {
                    if (!haystack.contains(w)) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    matches.put(new JSONObject().put("id", ch.optString(0))
                            .put("name", ch.optString(1)));
                }
            }
        }
        return result.put("matches", matches);
    }

    private File guideCacheFile(String id) {
        return new File(mContext.getFilesDir(), "guide_" + id.replaceAll("[^A-Za-z0-9_-]", "")
                + ".json");
    }

    // ---- Backup and restore ----

    /**
     * Builds a zip of everything needed to set FyrTuner up again: account settings (optionally
     * without the password), the channel config, uploaded logos and cached guide channel lists.
     */
    private Response backup(boolean includePassword) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            ChannelConfig config = ChannelConfig.load(mContext);
            SharedPreferences prefs = XtreamClient.prefs(mContext);
            JSONObject settings = new JSONObject()
                    .put("name", prefs.getString(XtreamClient.PREF_NAME, ""))
                    .put("server", prefs.getString(XtreamClient.PREF_SERVER, ""))
                    .put("username", prefs.getString(XtreamClient.PREF_USERNAME, ""))
                    .put("useHls", prefs.getBoolean(XtreamClient.PREF_USE_HLS, false));
            if (includePassword) {
                settings.put("password", prefs.getString(XtreamClient.PREF_PASSWORD, ""));
            }
            putEntry(zip, "manifest.json", new JSONObject().put("app", "FyrTuner")
                    .put("version", BACKUP_VERSION).put("created", System.currentTimeMillis())
                    .toString(2).getBytes(StandardCharsets.UTF_8));
            putEntry(zip, "settings.json", settings.toString(2).getBytes(StandardCharsets.UTF_8));
            JSONObject configJson = new JSONObject(config.toJson().toString());
            if (!includePassword) {
                JSONArray sources = configJson.optJSONArray("sources");
                for (int i = 0; sources != null && i < sources.length(); i++) {
                    sources.getJSONObject(i).remove("password");
                }
            }
            putEntry(zip, "config.json", configJson.toString(2)
                    .getBytes(StandardCharsets.UTF_8));
            // Only logos a channel uses: switching back to M3U/EPG leaves the old file behind.
            File[] logos = new File(mContext.getFilesDir(), "logos").listFiles();
            if (logos != null) {
                for (File logo : logos) {
                    String name = logo.getName();
                    if (name.matches("\\d+\\.png") && ChannelConfig.LOGO_CUSTOM.equals(
                            config.logoSource(Integer.parseInt(name.substring(0, name.length() - 4))))) {
                        putEntry(zip, "logos/" + name, readFile(logo));
                    }
                }
            }
            File[] files = mContext.getFilesDir().listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().matches("guide_[A-Za-z0-9_-]+\\.json")) {
                        putEntry(zip, "guides/" + f.getName(), readFile(f));
                    }
                }
            }
        }
        String name = "fyrtuner-backup-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm",
                Locale.ROOT).format(new java.util.Date()) + ".zip";
        Response response = newFixedLengthResponse(Response.Status.OK, "application/zip",
                new java.io.ByteArrayInputStream(bytes.toByteArray()), bytes.size());
        response.addHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    /**
     * Restores a backup zip, replacing settings, config, logos and guide lists, then starts a
     * sync. Entries are checked against the names a backup contains, so a zip can't write
     * anywhere else. A backup without a password keeps the current one.
     */
    private JSONObject restore(IHTTPSession session) throws Exception {
        byte[] data = readBytes(session, MAX_BACKUP_BYTES);
        JSONObject manifest = null;
        JSONObject settings = null;
        JSONObject config = null;
        Map<String, byte[]> logos = new HashMap<>();
        Map<String, byte[]> guides = new HashMap<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(data))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] content = readAll(zip);
                if ("manifest.json".equals(name)) {
                    manifest = new JSONObject(new String(content, StandardCharsets.UTF_8));
                } else if ("settings.json".equals(name)) {
                    settings = new JSONObject(new String(content, StandardCharsets.UTF_8));
                } else if ("config.json".equals(name)) {
                    config = new JSONObject(new String(content, StandardCharsets.UTF_8));
                } else if (name.matches("logos/\\d+\\.png")) {
                    logos.put(name.substring("logos/".length()), content);
                } else if (name.matches("guides/guide_[A-Za-z0-9_-]+\\.json")) {
                    guides.put(name.substring("guides/".length()), content);
                }
            }
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException("That file isn't a FyrTuner backup");
        }
        if (manifest == null || !"FyrTuner".equals(manifest.optString("app"))
                || settings == null || config == null) {
            throw new IllegalArgumentException("That file isn't a FyrTuner backup");
        }
        if (manifest.optInt("version") > BACKUP_VERSION) {
            throw new IllegalArgumentException("This backup is from a newer FyrTuner; update first");
        }

        SharedPreferences.Editor editor = XtreamClient.prefs(mContext).edit()
                .putString(XtreamClient.PREF_NAME, settings.optString("name"))
                .putString(XtreamClient.PREF_SERVER, settings.optString("server"))
                .putString(XtreamClient.PREF_USERNAME, settings.optString("username"))
                .putBoolean(XtreamClient.PREF_USE_HLS, settings.optBoolean("useHls"));
        boolean hasPassword = !settings.optString("password", "").isEmpty();
        if (hasPassword) editor.putString(XtreamClient.PREF_PASSWORD, settings.getString("password"));
        editor.commit();
        JSONArray restoredSources = config.optJSONArray("sources");
        if (restoredSources != null) {
            ChannelConfig current = ChannelConfig.load(mContext);
            for (int i = 0; i < restoredSources.length(); i++) {
                JSONObject src = restoredSources.getJSONObject(i);
                JSONObject existing = current.source(src.optInt("index"));
                if (src.optString("password", "").isEmpty() && existing != null) {
                    src.put("password", existing.optString("password"));
                }
            }
        }
        ChannelConfig.replace(mContext, config);

        File logoDir = new File(mContext.getFilesDir(), "logos");
        File[] oldLogos = logoDir.listFiles();
        if (oldLogos != null) {
            for (File f : oldLogos) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        logoDir.mkdirs();
        for (Map.Entry<String, byte[]> logo : logos.entrySet()) {
            writeFile(new File(logoDir, logo.getKey()), logo.getValue());
        }
        for (Map.Entry<String, byte[]> guide : guides.entrySet()) {
            writeFile(new File(mContext.getFilesDir(), guide.getKey()), guide.getValue());
        }
        synchronized (this) {
            mCategories.clear();
            mStreamCache.clear();
            mStreamCacheTime.clear();
            mGuideCache.clear();
        }

        ChannelSyncWorker.syncNow(mContext);
        ChannelSyncWorker.schedule(mContext, ChannelSyncWorker.SYNC_INTERVAL_HOURS);
        return state().put("restored", new JSONObject()
                .put("logos", logos.size())
                .put("guides", guides.size())
                .put("passwordRestored", hasPassword));
    }

    private static void putEntry(java.util.zip.ZipOutputStream zip, String name, byte[] content)
            throws IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static byte[] readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        }
    }

    private static void writeFile(File file, byte[] content) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
    }

    // ---- Plumbing ----

    private XtreamClient client() {
        XtreamClient client = XtreamClient.fromPrefs(mContext);
        if (client == null) throw new IllegalArgumentException("Enter the Xtream account first");
        return client;
    }

    private static JSONObject require(JSONObject body) {
        if (body == null) throw new IllegalArgumentException("Expected a JSON body");
        return body;
    }

    private static JSONObject readBody(IHTTPSession session) throws IOException, JSONException {
        String length = session.getHeaders().get("content-length");
        int size = length == null ? 0 : Integer.parseInt(length);
        if (size <= 0) return new JSONObject();
        byte[] data = new byte[size];
        InputStream in = session.getInputStream();
        int read = 0;
        while (read < size) {
            int n = in.read(data, read, size - read);
            if (n < 0) break;
            read += n;
        }
        return new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
    }

    private Response serveAsset(String uri) throws IOException {
        if (!"/".equals(uri) && !"/index.html".equals(uri)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }
        InputStream in = mContext.getAssets().open("web/index.html");
        Response response = newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", in);
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static Response json(Response.Status status, Object body) {
        Response response = newFixedLengthResponse(status, "application/json; charset=utf-8",
                body.toString());
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static Response error(Response.Status status, String message) {
        try {
            return json(status, new JSONObject().put("error",
                    message == null ? "Unknown error" : message));
        } catch (JSONException e) {
            return newFixedLengthResponse(status, MIME_PLAINTEXT, String.valueOf(message));
        }
    }
}
