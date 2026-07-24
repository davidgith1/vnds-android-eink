package com.example.vndsandroideink;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Fetches and persists VNDB (https://vndb.org) metadata for an imported VN. There's no reliable
 * automatic way to match a local folder to a VNDB entry, so this is always a manual, user-typed
 * VNDB id (e.g. "v7" for Tsukihime) -- see the "Get info from VNDB" row menu action.
 */
public final class VndbManager {

    public interface Callback {
        void onSuccess(VndbMeta meta);
        void onError(Exception e);
    }

    private static final String API_URL = "https://api.vndb.org/kana/vn";
    private static final String PREFS_FILE = "vnds_vndb";

    private VndbManager() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    /** The locally-stored metadata for a VN, or null if nothing has ever been fetched for it. */
    public static VndbMeta load(Context context, String vnKey) {
        String json = prefs(context).getString(vnKey, null);
        if (json == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            return new VndbMeta(
                    o.getString("id"),
                    o.optString("title", ""),
                    o.isNull("alttitle") ? null : o.getString("alttitle"),
                    o.isNull("released") ? null : o.getString("released"),
                    o.isNull("rating") ? null : o.getDouble("rating"),
                    o.isNull("length") ? null : o.getInt("length"),
                    o.isNull("length_minutes") ? null : o.getInt("length_minutes"));
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear(Context context, String vnKey) {
        prefs(context).edit().remove(vnKey).apply();
    }

    /** Looks up {@code rawId} (accepting "7", "v7", or "V7") on VNDB and, on success, persists it
     * as this VN's linked metadata. */
    public static void fetch(Context context, String vnKey, String rawId, Callback callback) {
        String id = normalizeId(rawId);
        if (!id.matches("v[0-9]+")) {
            callback.onError(new IllegalArgumentException("Not a valid VNDB id (expected e.g. \"v7\")"));
            return;
        }
        Context appContext = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                VndbMeta meta = fetchFromApi(id);
                if (meta == null) {
                    main.post(() -> callback.onError(new IOException("No VN found for " + id)));
                    return;
                }
                save(appContext, vnKey, meta);
                main.post(() -> callback.onSuccess(meta));
            } catch (Exception e) {
                main.post(() -> callback.onError(e));
            }
        }).start();
    }

    private static String normalizeId(String raw) {
        String trimmed = raw.trim().toLowerCase();
        if (trimmed.isEmpty() || trimmed.startsWith("v")) {
            return trimmed;
        }
        return "v" + trimmed;
    }

    private static void save(Context context, String vnKey, VndbMeta meta) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", meta.id);
            o.put("title", meta.title);
            o.put("alttitle", meta.altTitle);
            o.put("released", meta.released);
            o.put("rating", meta.rating);
            o.put("length", meta.length);
            o.put("length_minutes", meta.lengthMinutes);
            prefs(context).edit().putString(vnKey, o.toString()).apply();
        } catch (Exception ignored) {
            // Every field is a primitive/string we control; this cannot actually happen.
        }
    }

    private static VndbMeta fetchFromApi(String id) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        try {
            body.put("filters", new JSONArray().put("id").put("=").put(id));
            body.put("fields", "title, alttitle, released, rating, length, length_minutes");
        } catch (Exception e) {
            throw new IOException(e);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String response = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) {
                throw new IOException("VNDB request failed (" + code + "): " + response);
            }

            JSONArray results = new JSONObject(response).optJSONArray("results");
            if (results == null || results.length() == 0) {
                return null;
            }
            JSONObject vn = results.getJSONObject(0);
            return new VndbMeta(
                    vn.optString("id", id),
                    vn.optString("title", ""),
                    vn.isNull("alttitle") ? null : vn.getString("alttitle"),
                    vn.isNull("released") ? null : vn.getString("released"),
                    vn.isNull("rating") ? null : vn.getDouble("rating"),
                    vn.isNull("length") ? null : vn.getInt("length"),
                    vn.isNull("length_minutes") ? null : vn.getInt("length_minutes"));
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (InputStream stream = in) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = stream.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }
}
