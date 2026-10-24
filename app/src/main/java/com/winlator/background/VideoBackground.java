package com.winlator.background;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plays the videos bundled in app/src/main/assets/backgrounds/ one after another, looping, as the app background.
 * Users cannot change the videos: they come from the repo / APK only. Videos are muted.
 */
public final class VideoBackground {
    private VideoBackground() {}

    private static final String ASSET_DIR = "backgrounds";
    private static final String PREFS = "nmd_video_bg";
    private static final String KEY_VERSION = "copied_for_version";
    private static final String[] VIDEO_EXT = {".mp4", ".webm", ".mkv", ".3gp"};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Context appContext;
    private static FrameLayout holder;      // black box behind the whole app
    private static TextureView texture;     // video, sized to keep its shape (centre crop)
    private static Surface surface;
    private static MediaPlayer player;
    private static final List<String> playlist = new ArrayList<>();
    private static int index = 0;
    private static int failures = 0;
    private static boolean surfaceReady = false;
    private static boolean paused = false;

    /** Call once in MainActivity.onCreate, AFTER setContentView(). */
    public static void attach(final Activity a) {
        appContext = a.getApplicationContext();
        ViewGroup root = a.findViewById(android.R.id.content);

        if (holder == null) {
            holder = new FrameLayout(a);
            holder.setBackgroundColor(Color.BLACK);

            texture = new TextureView(a);
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    surface = new Surface(st);
                    surfaceReady = true;
                    startPlayback();
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    surfaceReady = false;
                    releasePlayer();
                    if (surface != null) { surface.release(); surface = null; }
                    return true;
                }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
            });
            holder.addView(texture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

            View dim = new View(a);
            dim.setBackgroundColor(0x99000000); // darkens the video so the menus stay readable
            holder.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        ViewGroup oldParent = (ViewGroup) holder.getParent();
        if (oldParent != null) oldParent.removeView(holder);
        root.addView(holder, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Let the video show through the app's own background layers
        View drawer = a.findViewById(R.id.DrawerLayout);
        if (drawer != null) drawer.setBackgroundColor(Color.TRANSPARENT);
        View frag = a.findViewById(R.id.FLFragmentContainer);
        if (frag != null) frag.setBackgroundColor(Color.TRANSPARENT);

        loadPlaylistFromAssets();
    }

    /** Call from MainActivity.onPause. */
    public static void onPause() {
        paused = true;
        if (player != null) { try { if (player.isPlaying()) player.pause(); } catch (Exception ignored) { } }
    }

    /** Call from MainActivity.onResume. */
    public static void onResume() {
        if (!paused) return;
        paused = false;
        if (player != null) { try { player.start(); } catch (Exception ignored) { } }
        else startPlayback();
    }

    // ───────────────────────── internals ─────────────────────────

    /** Copies the bundled videos to app storage (once per app version), then starts the playlist. */
    private static void loadPlaylistFromAssets() {
        final Context c = appContext;
        new Thread(() -> {
            List<String> paths = new ArrayList<>();
            try {
                File dir = new File(c.getFilesDir(), ASSET_DIR);
                dir.mkdirs();
                int version = versionCode(c);
                boolean recopy = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VERSION, -1) != version;

                String[] names = c.getAssets().list(ASSET_DIR);
                if (names != null) {
                    for (String n : names) {
                        if (!isVideo(n)) continue;
                        File out = new File(dir, n);
                        if (recopy || !out.exists() || out.length() == 0) copyAsset(c, ASSET_DIR + "/" + n, out);
                        paths.add(out.getAbsolutePath());
                    }
                }
                if (recopy) c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_VERSION, version).apply();
            } catch (Exception ignored) { }

            Collections.sort(paths);
            MAIN.post(() -> {
                playlist.clear();
                playlist.addAll(paths);
                index = 0;
                failures = 0;
                startPlayback();
            });
        }).start();
    }

    private static void startPlayback() {
        if (!surfaceReady || surface == null || playlist.isEmpty() || paused) return;
        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setSurface(surface);
            player.setVolume(0f, 0f);
            player.setDataSource(playlist.get(index));
            player.setOnVideoSizeChangedListener((mp, w, h) -> fitVideo(w, h));
            player.setOnPreparedListener(mp -> {
                failures = 0;
                fitVideo(mp.getVideoWidth(), mp.getVideoHeight());
                mp.start();
            });
            player.setOnCompletionListener(mp -> nextVideo());
            player.setOnErrorListener((mp, what, extra) -> { nextVideo(); return true; });
            player.prepareAsync();
        } catch (Exception e) {
            nextVideo();
        }
    }

    private static void nextVideo() {
        if (playlist.isEmpty()) return;
        failures++;
        if (failures > playlist.size()) return;   // every file failed -> stop, do not loop forever
        index = (index + 1) % playlist.size();
        startPlayback();
    }

    /** Keeps the video's shape and fills the screen (centre crop). */
    private static void fitVideo(final int vw, final int vh) {
        if (vw <= 0 || vh <= 0 || holder == null || texture == null) return;
        holder.post(() -> {
            int hw = holder.getWidth(), hh = holder.getHeight();
            if (hw <= 0 || hh <= 0) return;
            float videoAspect = (float) vw / vh;
            float screenAspect = (float) hw / hh;
            int w, h;
            if (videoAspect > screenAspect) { h = hh; w = Math.round(hh * videoAspect); }
            else { w = hw; h = Math.round(hw / videoAspect); }
            texture.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        });
    }

    private static void releasePlayer() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    private static boolean isVideo(String name) {
        String n = name.toLowerCase();
        for (String ext : VIDEO_EXT) if (n.endsWith(ext)) return true;
        return false;
    }

    private static void copyAsset(Context c, String assetPath, File out) throws IOException {
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        try (InputStream in = c.getAssets().open(assetPath); FileOutputStream fo = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) fo.write(buf, 0, n);
        }
        if (out.exists()) out.delete();
        tmp.renameTo(out);
    }

    @SuppressWarnings("deprecation")
    private static int versionCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }
}
