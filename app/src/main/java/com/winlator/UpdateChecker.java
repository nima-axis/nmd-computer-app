package com.winlator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {
    private static final String API =
        "https://api.github.com/repos/nima-axis/nmd-computer-app/releases/latest";

    public static void check(final Activity act) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(8000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder(); String l;
                while ((l = r.readLine()) != null) sb.append(l);
                JSONObject j = new JSONObject(sb.toString());
                String latest = j.getString("tag_name").replaceFirst("^v", "");
                String current = act.getPackageManager()
                    .getPackageInfo(act.getPackageName(), 0).versionName;
                if (latest.equals(current)) return;
                String url = j.getJSONArray("assets").getJSONObject(0)
                    .getString("browser_download_url");
                String notes = j.optString("body", "");
                act.runOnUiThread(() -> new AlertDialog.Builder(act)
                    .setTitle("NMD COMPUTER Update " + latest)
                    .setMessage(notes)
                    .setPositiveButton("Update", (d, w) ->
                        act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))))
                    .setNegativeButton("Later", null).show());
            } catch (Exception ignored) {}
        }).start();
    }
}
