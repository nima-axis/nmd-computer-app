package com.winlator.config;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Remote config (config.json in the releases repo, served by the Worker at /config).
 *  - "menu": drawer items. Each item opens an https link. Changes show on the NEXT app start.
 *  - "announcement": a popup shown once per announcement id.
 * No APK is needed for these changes.
 */
public final class ConfigManager {
    private ConfigManager() {}

    private static final String CONFIG_URL = "https://nmd-computer.nmdaxis.workers.dev/config";
    private static final String PREFS = "nmd_config";
    private static final String KEY_JSON = "json";
    private static final String KEY_SEEN = "seen_announcement";
    public static final int MENU_ID_BASE = 9100;
    private static final int MENU_ID_MAX = 9199;
    private static final long REFRESH_EVERY_MS = 30L * 60 * 1000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile long lastRefresh = 0;

    public static final class Entry {
        public int id;
        public String label;
        public String url;
    }

    /** Menu items from the last saved config (used when building the drawer). */
    public static List<Entry> menu(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = load(c).optJSONArray("menu");
            if (arr == null) return out;
            for (int i = 0; i < arr.length() && out.size() < (MENU_ID_MAX - MENU_ID_BASE); i++) {
                JSONObject m = arr.getJSONObject(i);
                String label = m.optString("label", "").trim();
                String url = m.optString("url", "").trim();
                if (label.isEmpty() || !url.startsWith("https://")) continue;   // only https links
                Entry e = new Entry();
                e.id = MENU_ID_BASE + out.size();
                e.label = label;
                e.url = url;
                out.add(e);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Call from the drawer click handler. Returns true if the item belonged to the config menu. */
    public static boolean handle(Activity a, int itemId) {
        if (itemId < MENU_ID_BASE || itemId > MENU_ID_MAX) return false;
        for (Entry e : menu(a)) {
            if (e.id == itemId) {
                try {
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(e.url)));
                } catch (Exception ex) {
                    Toast.makeText(a, "Could not open the link.", Toast.LENGTH_SHORT).show();
                }
                break;
            }
        }
        return true;
    }

    /** Downloads the newest config in the background (at most every 30 minutes). Call from onResume. */
    public static void refresh(final Activity a) {
        if (System.currentTimeMillis() - lastRefresh < REFRESH_EVERY_MS) return;
        lastRefresh = System.currentTimeMillis();
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(CONFIG_URL).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) return;
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                final JSONObject j = new JSONObject(sb.toString());   // throws if not valid JSON -> keep old config
                a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_JSON, j.toString()).apply();
                MAIN.post(() -> showAnnouncementIfNew(a, j));
            } catch (Exception ignored) { }
        }).start();
    }

    private static void showAnnouncementIfNew(Activity a, JSONObject j) {
        if (a.isFinishing()) return;
        JSONObject ann = j.optJSONObject("announcement");
        if (ann == null) return;
        String id = ann.optString("id", "");
        if (id.isEmpty()) return;
        Context c = a.getApplicationContext();
        if (id.equals(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SEEN, ""))) return;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SEEN, id).apply();
        new AlertDialog.Builder(a)
                .setTitle(ann.optString("title", "Announcement"))
                .setMessage(ann.optString("text", ""))
                .setPositiveButton("OK", null)
                .show();
    }

    private static JSONObject load(Context c) {
        try {
            String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, null);
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
