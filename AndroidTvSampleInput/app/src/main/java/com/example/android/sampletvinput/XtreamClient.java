package com.example.android.sampletvinput;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to an Xtream Codes panel: account check, live categories and streams, and URLs for
 * streams and the panel's XMLTV guide.
 *
 * Account settings live in the "xtream" SharedPreferences file. Groups and channel edits
 * live in ChannelConfig.
 */
public final class XtreamClient {
    private static final String TAG = "XtreamClient";

    public static final String PREFS = "xtream";
    /** What the main source is called in the app and on the web page. */
    public static final String PREF_NAME = "name";
    public static final String DEFAULT_NAME = "Main";
    public static final String PREF_SERVER = "server";
    public static final String PREF_USERNAME = "username";
    public static final String PREF_PASSWORD = "password";
    /** Comma-separated category ids from before ChannelConfig existed; read once to migrate. */
    public static final String PREF_CATEGORIES = "categories";
    public static final String PREF_USE_HLS = "use_hls";
    // Asked just before tuning, so it mustn't hold the channel change up for long.
    private static final int CONNECTIONS_TIMEOUT_MS = 4_000;
    public static final String PREF_LAST_SYNC = "last_sync";

    public static class Category {
        public final String id;
        public final String name;

        Category(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static class Stream {
        public final int streamId;
        public final String name;
        public final String iconUrl;
        public final String epgChannelId;
        public final String categoryId;

        Stream(int streamId, String name, String iconUrl, String epgChannelId, String categoryId) {
            this.streamId = streamId;
            this.name = name;
            this.iconUrl = iconUrl;
            this.epgChannelId = epgChannelId;
            this.categoryId = categoryId;
        }
    }

    private final String mServer;
    private final String mUsername;
    private final String mPassword;
    private final boolean mUseHls;

    public XtreamClient(String server, String username, String password, boolean useHls) {
        String s = server.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        mServer = s;
        mUsername = username.trim();
        mPassword = password.trim();
        mUseHls = useHls;
    }

    /** Returns a client for the saved settings, or null if they are incomplete. */
    public static XtreamClient fromPrefs(Context context) {
        SharedPreferences prefs = prefs(context);
        String server = prefs.getString(PREF_SERVER, "");
        String username = prefs.getString(PREF_USERNAME, "");
        String password = prefs.getString(PREF_PASSWORD, "");
        if (TextUtils.isEmpty(server) || TextUtils.isEmpty(username)
                || TextUtils.isEmpty(password)) {
            return null;
        }
        return new XtreamClient(server, username, password, prefs.getBoolean(PREF_USE_HLS, false));
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<String> selectedCategoryIds(Context context) {
        List<String> ids = new ArrayList<>();
        for (String id : prefs(context).getString(PREF_CATEGORIES, "").split(",")) {
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    /** Checks the login. Returns null on success, or a message describing the failure. */
    public String checkAccount() {
        try {
            JSONObject info = new JSONObject(get(apiUrl(null).build()));
            JSONObject user = info.optJSONObject("user_info");
            if (user == null || user.optInt("auth", 0) != 1) return "Login rejected";
            String status = user.optString("status");
            if (!"Active".equalsIgnoreCase(status)) return "Account status: " + status;
            return null;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Account check failed", e);
            return "Could not reach server: " + e.getMessage();
        }
    }

    /**
     * The account's connections in use and its limit, as {active, max}, from player_api's
     * user_info; null if the panel can't be asked quickly or doesn't say.
     */
    public int[] connections() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new java.net.URL(apiUrl(null).build().toString())
                    .openConnection();
            connection.setConnectTimeout(CONNECTIONS_TIMEOUT_MS);
            connection.setReadTimeout(CONNECTIONS_TIMEOUT_MS);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            JSONObject user = new JSONObject(out.toString("UTF-8")).optJSONObject("user_info");
            if (user == null || !user.has("active_cons") || !user.has("max_connections")) {
                return null;
            }
            // Panels send these as strings or numbers.
            return new int[]{Integer.parseInt(user.optString("active_cons").trim()),
                    Integer.parseInt(user.optString("max_connections").trim())};
        } catch (IOException | JSONException | NumberFormatException e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public List<Category> getLiveCategories() throws IOException, JSONException {
        JSONArray array = new JSONArray(get(apiUrl("get_live_categories").build()));
        List<Category> categories = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            categories.add(new Category(o.getString("category_id"), o.optString("category_name")));
        }
        return categories;
    }

    public List<Stream> getLiveStreams(String categoryId) throws IOException, JSONException {
        Uri.Builder url = apiUrl("get_live_streams").appendQueryParameter("category_id", categoryId);
        JSONArray array = new JSONArray(get(url.build()));
        List<Stream> streams = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            String name = o.optString("name").trim();
            // Panels use "##### ... #####" rows as visual separators; they don't play.
            if (name.isEmpty() || name.startsWith("####")) continue;
            streams.add(new Stream(o.getInt("stream_id"), name, emptyToNull(o.optString("stream_icon")),
                    emptyToNull(o.optString("epg_channel_id")), categoryId));
        }
        return streams;
    }

    /**
     * Every live stream on the panel (id, name, category only), for searching. The list can be
     * tens of thousands of streams, so it's read as a stream rather than parsed whole.
     */
    public List<Stream> getAllLiveStreams() throws IOException {
        List<Stream> streams = new ArrayList<>();
        HttpURLConnection connection = Xmltv.open(apiUrl("get_live_streams").build().toString());
        try (android.util.JsonReader reader = new android.util.JsonReader(
                new java.io.InputStreamReader(Xmltv.body(connection), "UTF-8"))) {
            reader.beginArray();
            while (reader.hasNext()) {
                int id = -1;
                String name = "";
                String category = "";
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (reader.peek() == android.util.JsonToken.NULL) {
                        reader.skipValue();
                    } else if ("stream_id".equals(key)) {
                        id = reader.nextInt();
                    } else if ("name".equals(key)) {
                        name = reader.nextString().trim();
                    } else if ("category_id".equals(key)) {
                        category = reader.nextString();
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
                if (id > 0 && !name.isEmpty() && !name.startsWith("####")) {
                    streams.add(new Stream(id, name, null, null, category));
                }
            }
            reader.endArray();
        } finally {
            connection.disconnect();
        }
        return streams;
    }

    /**
     * Panel stream id -> catch-up days, for every stream with catch-up (tv_archive). Read as a
     * stream like getAllLiveStreams; only the few archived streams are kept.
     */
    public java.util.Map<Integer, Integer> getArchiveDays() throws IOException {
        java.util.Map<Integer, Integer> days = new java.util.HashMap<>();
        HttpURLConnection connection = Xmltv.open(apiUrl("get_live_streams").build().toString());
        try (android.util.JsonReader reader = new android.util.JsonReader(
                new java.io.InputStreamReader(Xmltv.body(connection), "UTF-8"))) {
            reader.beginArray();
            while (reader.hasNext()) {
                int id = -1;
                boolean archive = false;
                int duration = 0;
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (reader.peek() == android.util.JsonToken.NULL) {
                        reader.skipValue();
                    } else if ("stream_id".equals(key)) {
                        id = reader.nextInt();
                    } else if ("tv_archive".equals(key)) {
                        archive = "1".equals(reader.nextString().trim());
                    } else if ("tv_archive_duration".equals(key)) {
                        try {
                            duration = Integer.parseInt(reader.nextString().trim());
                        } catch (NumberFormatException e) {
                            duration = 0;
                        }
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
                if (id > 0 && archive) days.put(id, Math.max(1, duration));
            }
            reader.endArray();
        } finally {
            connection.disconnect();
        }
        return days;
    }

    /** The panel's time zone id (catch-up times are in panel time), or null if not given. */
    public String getServerTimeZone() throws IOException, JSONException {
        JSONObject info = new JSONObject(get(apiUrl(null).build()));
        JSONObject server = info.optJSONObject("server_info");
        return server == null ? null : emptyToNull(server.optString("timezone"));
    }

    /**
     * A catch-up recording: durationMin minutes of a stream from startMs, which the panel
     * takes as a local time in its own time zone.
     */
    public String getTimeshiftUrl(int streamId, long startMs, int durationMin,
                                  java.util.TimeZone panelZone) {
        java.text.SimpleDateFormat format =
                new java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US);
        format.setTimeZone(panelZone);
        return mServer + "/timeshift/" + Uri.encode(mUsername) + "/" + Uri.encode(mPassword)
                + "/" + durationMin + "/" + format.format(new java.util.Date(startMs)) + "/"
                + streamId + ".ts";
    }

    /** The panel's XMLTV guide, which covers every channel on the panel. */
    public String getXmltvUrl() {
        return Uri.parse(mServer + "/xmltv.php").buildUpon()
                .appendQueryParameter("username", mUsername)
                .appendQueryParameter("password", mPassword)
                .build().toString();
    }

    public String getStreamUrl(int streamId) {
        return mServer + "/live/" + Uri.encode(mUsername) + "/" + Uri.encode(mPassword) + "/"
                + streamId + (mUseHls ? ".m3u8" : ".ts");
    }

    private Uri.Builder apiUrl(String action) {
        Uri.Builder builder = Uri.parse(mServer + "/player_api.php").buildUpon()
                .appendQueryParameter("username", mUsername)
                .appendQueryParameter("password", mPassword);
        if (action != null) builder.appendQueryParameter("action", action);
        return builder;
    }

    private static String get(Uri url) throws IOException {
        HttpURLConnection connection = Xmltv.open(url.toString());
        try (InputStream in = Xmltv.body(connection)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    private static String emptyToNull(String s) {
        return TextUtils.isEmpty(s) || "null".equals(s) ? null : s;
    }
}
