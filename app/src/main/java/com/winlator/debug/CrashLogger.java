package com.winlator.debug;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Saves any crash to a file, so it can be read inside the app without Termux/logcat.
 * install() once at startup. showPending() shows the last crash (if any) and lets the user copy it.
 */
public final class CrashLogger {
    private CrashLogger() {}

    private static final String FILE_NAME = "crash-log.txt";
    private static boolean installed = false;

    private static File file(Context c) {
        File d = c.getExternalFilesDir(null);
        if (d == null) d = c.getFilesDir();
        return new File(d, FILE_NAME);
    }

    /** Call once from each Activity's onCreate (safe to call many times). */
    public static synchronized void install(final Context c) {
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        final Context app = c.getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                String entry = "==== CRASH " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " ====\n"
                        + "App version: " + versionOf(app) + "\n"
                        + "Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n"
                        + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                        + "Thread: " + thread.getName() + "\n\n"
                        + sw + "\n";
                FileOutputStream out = new FileOutputStream(file(app), true);
                out.write(entry.getBytes(StandardCharsets.UTF_8));
                out.close();
            } catch (Throwable ignored) {
                // never block the normal crash handling
            }
            if (previous != null) previous.uncaughtException(thread, ex);
            else System.exit(10);
        });
    }

    /** If a crash was saved earlier, show it in a dialog with Copy / Clear buttons. */
    public static void showPending(final Activity a) {
        final File f = file(a);
        if (!f.exists() || f.length() == 0) return;
        String text;
        try {
            text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return;
        }
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextIsSelectable(true);
        int pad = Math.round(16 * a.getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(a);
        sv.addView(tv);

        new AlertDialog.Builder(a)
                .setTitle("Last crash report")
                .setView(sv)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("crash", text));
                    Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Clear", (d, w) -> f.delete())
                .setNegativeButton("Close", null)
                .show();
    }

    private static String versionOf(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
