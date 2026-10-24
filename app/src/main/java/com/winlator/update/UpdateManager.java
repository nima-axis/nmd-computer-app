package com.winlator.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app update flow (same behaviour as Nimesha Auto Saver):
 *  - Shows the update size; the user chooses Download or Later.
 *  - Download can be cancelled; the partial file is kept and resumed next time (HTTP Range).
 *  - A finished APK is kept. If the install is cancelled, the next app open offers "Install" without downloading again.
 */
public final class UpdateManager {
    private UpdateManager() {}

    /** Worker URL (no trailing slash). */
    private static final String BASE = "https://nmd-computer.nmdaxis.workers.dev";
    private static final String VERSION_URL = BASE + "/version";
    private static final String DOWNLOAD_URL = BASE + "/download";
    private static final String UA = "nmd-update";
    private static final long CHECK_EVERY_MS = 10L * 60 * 1000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile long lastCheckAt = 0;
    private static volatile boolean cancelRequested = false;
    private static volatile boolean downloadRunning = false;
    private static volatile boolean fetchFailed = false;

    public static final class Info {
        public String version, notes;
        public long size;
    }

    // ───────────────── entry point (call from MainActivity.onResume) ─────────────────

    public static void checkOnStart(final Activity a) {
        if (downloadRunning) return;
        // 1) A finished update from an earlier session? Offer install, no network needed.
        File ready = findReadyUpdate(a);
        if (ready != null) { askInstall(a, versionOf(ready), ready); return; }

        // 2) Ask the server, at most once every 10 minutes.
        if (System.currentTimeMillis() - lastCheckAt < CHECK_EVERY_MS) return;
        lastCheckAt = System.currentTimeMillis();
        new Thread(() -> {
            final Info info = fetchInfo(a);
            if (info == null) return;
            MAIN.post(() -> { if (!a.isFinishing()) askDownload(a, info); });
        }).start();
    }

    /** Manual check from the About screen. Ignores the 10-minute limit and always tells the user the result. */
    public static void checkNow(final Activity a) {
        if (downloadRunning) { Toast.makeText(a, "Download already running.", Toast.LENGTH_SHORT).show(); return; }
        File ready = findReadyUpdate(a);
        if (ready != null) { askInstall(a, versionOf(ready), ready); return; }
        Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final Info info = fetchInfo(a);
            final boolean failed = fetchFailed;
            lastCheckAt = System.currentTimeMillis();
            MAIN.post(() -> {
                if (a.isFinishing()) return;
                if (info != null) askDownload(a, info);
                else if (failed) Toast.makeText(a, "Could not check for updates. Check your internet.", Toast.LENGTH_LONG).show();
                else Toast.makeText(a, "You are up to date.", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    // ───────────────── dialogs ─────────────────

    private static void askDownload(final Activity a, final Info info) {
        File partial = partFile(a, info.version);
        String sizeText = "Size: " + mb(info.size);
        if (partial.exists()) sizeText += "\nAlready downloaded: " + mb(partial.length()) + " (will resume)";
        new AlertDialog.Builder(a)
                .setTitle("Update " + info.version + " available")
                .setMessage((info.notes.isEmpty() ? "A new version is ready." : info.notes) + "\n\n" + sizeText)
                .setPositiveButton("Download", (d, w) -> startDownload(a, info))
                .setNegativeButton("Later", null)
                .show();
    }

    private static void startDownload(final Activity a, final Info info) {
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 20);
        box.setPadding(pad, pad, pad, pad);
        final TextView status = new TextView(a);
        status.setText("Downloading " + info.version + " (" + mb(info.size) + ")…");
        final ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        box.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cancelRequested = false;
        downloadRunning = true;
        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("Updating")
                .setView(box)
                .setCancelable(true)
                .setNegativeButton("Cancel", (d, w) -> cancelRequested = true)
                .setOnCancelListener(d -> cancelRequested = true)
                .create();
        dialog.show();

        new Thread(() -> {
            final File f = download(a, info, p -> MAIN.post(() -> { bar.setProgress(p); status.setText(p + "%"); }));
            MAIN.post(() -> {
                downloadRunning = false;
                if (dialog.isShowing()) dialog.dismiss();
                if (a.isFinishing()) return;
                if (f != null) askInstall(a, info.version, f);
                else if (cancelRequested) Toast.makeText(a, "Download paused. It will resume next time.", Toast.LENGTH_LONG).show();
                else Toast.makeText(a, "Download failed. Open the app again to resume.", Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private static void askInstall(final Activity a, String version, final File apk) {
        new AlertDialog.Builder(a)
                .setTitle("Update " + version + " ready")
                .setMessage("Install now? If you cancel, the update stays ready and you will be asked again when you open the app.")
                .setPositiveButton("Install", (d, w) -> install(a, apk))
                .setNegativeButton("Later", null)
                .show();
    }

    private static void install(Activity a, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a)
                    .setTitle("Allow install")
                    .setMessage("Allow NMD COMPUTER to install apps, then open the app again to install the update.")
                    .setPositiveButton("Open settings", (d, w) -> a.startActivity(new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))))
                    .setNegativeButton("Later", null)
                    .show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            // The APK is kept, so the next app open offers install again.
            Toast.makeText(a, "Could not open installer. Try again later.", Toast.LENGTH_SHORT).show();
        }
    }

    // ───────────────── network ─────────────────

    private static Info fetchInfo(Activity a) {
        HttpURLConnection c = null;
        fetchFailed = true;
        try {
            c = (HttpURLConnection) new URL(VERSION_URL).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject j = new JSONObject(sb.toString());
            fetchFailed = false;
            Info info = new Info();
            info.version = j.getString("versionName");
            info.notes = j.optString("notes", "").trim();
            info.size = j.optLong("size", 0L);
            String current = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
            return isNewer(info.version, current) ? info : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Downloads (or resumes) the APK. Returns the finished file, or null if cancelled/failed (partial kept). */
    private static File download(Activity a, Info info, Progress cb) {
        File done = apkFile(a, info.version);
        if (done.exists() && isValidApk(a, done)) { cb.on(100); return done; }

        File part = partFile(a, info.version);
        HttpURLConnection c = null;
        try {
            long existing = part.exists() ? part.length() : 0L;
            c = (HttpURLConnection) new URL(DOWNLOAD_URL).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (existing > 0) c.setRequestProperty("Range", "bytes=" + existing + "-");

            int code = c.getResponseCode();
            boolean append;
            if (code == 206) append = true;
            else if (code == 200) { append = false; existing = 0; }
            else { if (code == 416) part.delete(); return null; }

            long total = info.size > 0 ? info.size : (c.getContentLengthLong() > 0 ? existing + c.getContentLengthLong() : -1);
            long written = existing;
            int last = -1;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelRequested) return null;          // keep .part, resume later
                    out.write(buf, 0, n);
                    written += n;
                    if (total > 0) {
                        int p = (int) (written * 100 / total);
                        if (p != last) { last = p; cb.on(p); }
                    }
                }
            }
            if (total > 0 && written != total) return null;     // cut short, keep .part

            if (done.exists()) done.delete();
            if (!part.renameTo(done)) return null;
            if (!isValidApk(a, done)) { done.delete(); return null; }
            return done;
        } catch (Exception e) {
            return null;                                         // .part stays → resume next time
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ───────────────── files ─────────────────

    private static File dir(Activity a) {
        File d = a.getExternalFilesDir(null);
        return d != null ? d : a.getFilesDir();
    }
    private static File apkFile(Activity a, String v) { return new File(dir(a), "update-" + v + ".apk"); }
    private static File partFile(Activity a, String v) { return new File(dir(a), "update-" + v + ".apk.part"); }

    /** A finished, valid APK for a version NEWER than the installed one; deletes older leftovers. */
    private static File findReadyUpdate(Activity a) {
        File[] files = dir(a).listFiles();
        if (files == null) return null;
        String current;
        try { current = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; }
        catch (Exception e) { return null; }
        File found = null;
        for (File f : files) {
            String n = f.getName();
            if (!n.startsWith("update-") || !n.endsWith(".apk")) continue;
            String v = versionOf(f);
            if (!isNewer(v, current) || !isValidApk(a, f)) { f.delete(); continue; }
            found = f;
        }
        return found;
    }

    private static String versionOf(File f) {
        return f.getName().replace("update-", "").replace(".apk", "");
    }

    private static boolean isValidApk(Activity a, File f) {
        try {
            android.content.pm.PackageInfo pi = a.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
            return pi != null && a.getPackageName().equals(pi.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    // ───────────────── helpers ─────────────────

    private interface Progress { void on(int percent); }

    static boolean isNewer(String latest, String current) {
        try {
            String[] l = latest.split("\\."), c = current.split("\\.");
            for (int i = 0; i < 3; i++) {
                int lv = i < l.length ? Integer.parseInt(l[i].replaceAll("[^0-9]", "")) : 0;
                int cv = i < c.length ? Integer.parseInt(c[i].replaceAll("[^0-9]", "")) : 0;
                if (lv != cv) return lv > cv;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String mb(long bytes) {
        if (bytes <= 0) return "unknown size";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
