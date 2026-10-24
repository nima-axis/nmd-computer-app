package com.winlator.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks the download Worker for a newer version. Shows a dialog with a Download button if one exists. */
public final class UpdateChecker {
    private UpdateChecker() {}

    // Your Worker URL (no trailing slash)
    private static final String VERSION_URL = "https://nmd-computer.nmdaxis.workers.dev/version";

    public static void check(final Activity activity) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(VERSION_URL).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    if (c.getResponseCode() != 200) return;
                    BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    c.disconnect();

                    JSONObject j = new JSONObject(sb.toString());
                    final String latest = j.getString("versionName");
                    final String notes = j.optString("notes", "");
                    final String downloadUrl = j.getString("downloadUrl");
                    String current = activity.getPackageManager()
                            .getPackageInfo(activity.getPackageName(), 0).versionName;

                    if (!isNewer(latest, current)) return;

                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (activity.isFinishing()) return;
                            new AlertDialog.Builder(activity)
                                    .setTitle("Update " + latest + " available")
                                    .setMessage(notes.isEmpty() ? "A new version is ready." : notes)
                                    .setPositiveButton("Download", new android.content.DialogInterface.OnClickListener() {
                                        @Override public void onClick(android.content.DialogInterface d, int w) {
                                            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)));
                                        }
                                    })
                                    .setNegativeButton("Later", null)
                                    .show();
                        }
                    });
                } catch (Exception ignored) {
                    // No internet or bad response: silently skip the check.
                }
            }
        }).start();
    }

    /** "11.3" > "11.2" ; compares numeric parts. */
    static boolean isNewer(String latest, String current) {
        String[] a = latest.split("\\."), b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? parse(a[i]) : 0;
            int y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }
}
