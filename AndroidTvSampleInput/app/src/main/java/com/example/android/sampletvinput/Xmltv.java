package com.example.android.sampletvinput;

import android.text.TextUtils;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Downloads and parses XMLTV guides. Guides can be tens of megabytes, so they are parsed as
 * they download rather than held in memory.
 */
public final class Xmltv {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    public static class GuideChannel {
        public final String id;
        public final String name;
        /** Logo URL from the guide, or null. */
        public final String icon;

        GuideChannel(String id, String name, String icon) {
            this.id = id;
            this.name = name;
            this.icon = icon;
        }
    }

    public static class Programme {
        public final String channelId;
        public final long startMs;
        public final long endMs;
        public final String title;
        public final String description;
        public final String iconUrl;

        Programme(String channelId, long startMs, long endMs, String title, String description,
                  String iconUrl) {
            this.channelId = channelId;
            this.startMs = startMs;
            this.endMs = endMs;
            this.title = title;
            this.description = description;
            this.iconUrl = iconUrl;
        }
    }

    private Xmltv() {}

    /**
     * Returns the guide's channel list. XMLTV lists channels before programmes, so this stops
     * reading at the first programme.
     */
    public static List<GuideChannel> readChannels(String url)
            throws IOException, XmlPullParserException {
        List<GuideChannel> channels = new ArrayList<>();
        HttpURLConnection connection = open(url);
        try (InputStream in = body(connection)) {
            XmlPullParser parser = newParser(in);
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if ("programme".equals(parser.getName())) break;
                    if ("channel".equals(parser.getName())) {
                        String id = parser.getAttributeValue(null, "id");
                        String[] nameAndIcon = readChannelNameAndIcon(parser);
                        if (!TextUtils.isEmpty(id)) {
                            String name = nameAndIcon[0];
                            channels.add(new GuideChannel(id, TextUtils.isEmpty(name) ? id : name,
                                    largerLogo(nameAndIcon[1])));
                        }
                    }
                }
                event = parser.next();
            }
        } finally {
            connection.disconnect();
        }
        return channels;
    }

    /**
     * Returns the programmes for the given guide channel ids that overlap
     * [windowStartMs, windowEndMs], grouped by guide channel id.
     */
    public static Map<String, List<Programme>> readProgrammes(String url,
                                                              Collection<String> channelIds,
                                                              long windowStartMs, long windowEndMs)
            throws IOException, XmlPullParserException {
        Map<String, List<Programme>> result = new HashMap<>();
        for (String id : channelIds) result.put(id, new ArrayList<>());

        HttpURLConnection connection = open(url);
        try (InputStream in = body(connection)) {
            XmlPullParser parser = newParser(in);
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "programme".equals(parser.getName())) {
                    List<Programme> list = result.get(parser.getAttributeValue(null, "channel"));
                    if (list == null) {
                        skip(parser);
                    } else {
                        Programme programme = readProgramme(parser, format);
                        if (programme != null && programme.endMs > windowStartMs
                                && programme.startMs < windowEndMs) {
                            list.add(programme);
                        }
                    }
                }
                event = parser.next();
            }
        } finally {
            connection.disconnect();
        }
        return result;
    }

    private static XmlPullParser newParser(InputStream in) throws XmlPullParserException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        parser.setInput(in, null);
        return parser;
    }

    /** Reads a channel element's first display name and icon URL. */
    private static String[] readChannelNameAndIcon(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        String name = null;
        String icon = null;
        int depth = parser.getDepth();
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT
                && !(event == XmlPullParser.END_TAG && parser.getDepth() == depth)) {
            if (event != XmlPullParser.START_TAG) continue;
            if (name == null && "display-name".equals(parser.getName())) {
                name = parser.nextText().trim();
            } else if (icon == null && "icon".equals(parser.getName())) {
                icon = parser.getAttributeValue(null, "src");
            }
        }
        return new String[]{name, TextUtils.isEmpty(icon) ? null : icon};
    }

    /**
     * Sky's logo service (used by many UK guides) serves any size from the path; guides ask for
     * 80x35, which looks soft on a TV, so ask for 280x120 instead.
     */
    static String largerLogo(String url) {
        if (url == null) return null;
        return url.replace("/newchanlogos/80/35/", "/newchanlogos/280/120/");
    }

    private static Programme readProgramme(XmlPullParser parser, SimpleDateFormat format)
            throws IOException, XmlPullParserException {
        String channel = parser.getAttributeValue(null, "channel");
        long start = parseTime(format, parser.getAttributeValue(null, "start"));
        long stop = parseTime(format, parser.getAttributeValue(null, "stop"));
        String title = null;
        String desc = null;
        String icon = null;

        int depth = parser.getDepth();
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT
                && !(event == XmlPullParser.END_TAG && parser.getDepth() == depth)) {
            if (event != XmlPullParser.START_TAG) continue;
            switch (parser.getName()) {
                case "title":
                    if (title == null) title = parser.nextText().trim();
                    break;
                case "desc":
                    if (desc == null) desc = parser.nextText().trim();
                    break;
                case "icon":
                    icon = parser.getAttributeValue(null, "src");
                    break;
                default:
                    break;
            }
        }
        if (start <= 0 || stop <= start || TextUtils.isEmpty(title)) return null;
        return new Programme(channel, start, stop, title, desc,
                TextUtils.isEmpty(icon) ? null : icon);
    }

    private static long parseTime(SimpleDateFormat format, String value) {
        if (value == null) return -1;
        try {
            return format.parse(value.trim()).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }

    private static void skip(XmlPullParser parser) throws IOException, XmlPullParserException {
        int depth = 1;
        while (depth > 0) {
            switch (parser.next()) {
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.END_DOCUMENT:
                    return;
            }
        }
    }

    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept-Encoding", "gzip");
        connection.setInstanceFollowRedirects(true);
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            // Don't log the full URL: Xtream URLs carry the password.
            throw new IOException("HTTP " + code + " from " + connection.getURL().getHost());
        }
        return connection;
    }

    /**
     * Opens the response body, un-gzipping it whether the server compressed the transfer or
     * the file itself is a .gz.
     */
    static InputStream body(HttpURLConnection connection) throws IOException {
        InputStream in = new BufferedInputStream(connection.getInputStream(), 64 * 1024);
        if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
            in = new BufferedInputStream(new GZIPInputStream(in, 64 * 1024), 64 * 1024);
        }
        in.mark(2);
        int b1 = in.read();
        int b2 = in.read();
        in.reset();
        if (b1 == 0x1f && b2 == 0x8b) {
            in = new GZIPInputStream(in, 64 * 1024);
        }
        return in;
    }
}
