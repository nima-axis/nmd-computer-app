package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;

/**
 * Login + premium gate.
 *
 * Unlocked ONLY when this device holds a server-signed entitlement token that
 *   - verifies against WlConfig.PUBLIC_KEY_B64 (ECDSA P-256 / SHA-256),
 *   - was issued for THIS email and THIS device id,
 *   - has not expired (plan expiry), and was issued less than OFFLINE_GRACE_MS ago,
 *   - isn't older than a clock we have already seen (clock-rollback guard).
 * A fake server or an edited response can't forge the signature.
 *
 * (An APK can always be patched by someone who decompiles it; this stops casual
 *  bypass, shared codes, fake servers and clock tricks — see WINLATOR_SETUP.md.)
 */
public final class WlGate {
    private WlGate() {}

    private static final String PREFS = "wl_gate";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean refreshing = false;

    public interface Callback { void done(Result r); }

    public static final class Result {
        public boolean ok;            // request itself succeeded
        public String error;          // human readable
        public boolean active;        // premium active
        public String session = "";   // ok | replaced | none
        public String plan;
        public long expiresAt;        // 0 = lifetime
    }

    // ───────────── local state ─────────────
    private static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static synchronized String deviceId(Context c) {
        SharedPreferences p = sp(c);
        String id = p.getString("did", null);
        if (id == null) { id = UUID.randomUUID().toString(); p.edit().putString("did", id).apply(); }
        return id;
    }
    public static String email(Context c) { return sp(c).getString("email", null); }
    public static boolean isLoggedIn(Context c) { return email(c) != null; }

    public static void logout(Context c) {
        sp(c).edit().remove("email").remove("token").remove("checkedAt").apply(); // device id stays
    }

    // ───────────── gate entry point ─────────────
    /**
     * Call at the top of onCreate() (and onResume()) of MainActivity and of every other entry
     * activity (e.g. the one shortcuts launch). Returns true when the app may continue.
     * When it returns false the login screen was launched and the caller must `return` at once.
     */
    public static boolean ensure(final Activity a) {
        if (isUnlocked(a)) { refreshIfDue(a); return true; }
        Intent i = new Intent(a, WlLoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        a.startActivity(i);
        a.finish();
        return false;
    }

    public static final int OK = 0, NEED_LOGIN = 1, NEED_ONLINE = 2;

    public static boolean isUnlocked(Context c) { return check(c) == OK; }

    /**
     * OK          → app may run.
     * NEED_ONLINE → we hold a genuine token for this email+device but it is stale (no server check for
     *               OFFLINE_GRACE_MS), the plan end passed, or the clock looks rolled back: the app is locked
     *               until it reaches the server once. If premium is still valid there, it unlocks again at once.
     * NEED_LOGIN  → no / forged / foreign token → email login + premium code.
     */
    public static int check(Context c) {
        try {
            String email = email(c);
            String token = sp(c).getString("token", null);
            if (email == null || token == null) return NEED_LOGIN;
            JSONObject p = verifyToken(token);
            if (p == null) return NEED_LOGIN;
            if (!email.equals(p.optString("e")) || !deviceId(c).equals(p.optString("d"))) return NEED_LOGIN;

            long now = System.currentTimeMillis();
            long issued = p.optLong("t");
            long exp = p.optLong("x"); // 0 = lifetime
            if (now < issued - 5 * 60 * 1000) return NEED_ONLINE; // phone clock earlier than the token's issue time
            if (exp != 0 && now >= exp) return NEED_ONLINE;       // plan ended (maybe renewed on the server)
            if (now - issued > WlConfig.OFFLINE_GRACE_MS) return NEED_ONLINE; // 7 days without a server check

            SharedPreferences s = sp(c);
            long lastSeen = s.getLong("lastSeen", 0);
            if (now < lastSeen - 5 * 60 * 1000) return NEED_ONLINE; // clock rolled back
            if (now > lastSeen) s.edit().putLong("lastSeen", now).apply();
            return OK;
        } catch (Throwable t) {
            return NEED_LOGIN; // any doubt → locked
        }
    }

    /** One-time message for the login screen (e.g. "logged in on another phone"), then cleared. */
    public static String takeNotice(Context c) {
        SharedPreferences s = sp(c);
        String n = s.getString("notice", null);
        if (n != null) s.edit().remove("notice").apply();
        return n;
    }

    /** null when the signature is bad. */
    private static JSONObject verifyToken(String token) throws Exception {
        int dot = token.indexOf('.');
        if (dot <= 0) return null;
        String part = token.substring(0, dot);
        byte[] sig = Base64.decode(token.substring(dot + 1), Base64.NO_WRAP);
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Base64.decode(WlConfig.PUBLIC_KEY_B64, Base64.NO_WRAP)));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initVerify(key);
        s.update(part.getBytes("UTF-8"));
        if (!s.verify(sig)) return null;
        return new JSONObject(new String(Base64.decode(part, Base64.NO_WRAP), "UTF-8"));
    }

    // ───────────── server calls (all run on a background thread, callback on main) ─────────────
    public static void start(final Context c, final String email, final Callback cb) {
        final JSONObject b = body(c, email);
        run(cb, new Job() { public Result go() throws Exception { return parse(c, email, http("POST", "/api/admin/wl-start", b), false); } });
    }

    public static void confirm(final Context c, final String email, final String otp, final Callback cb) {
        final JSONObject b = body(c, email);
        try { b.put("otp", otp); } catch (Exception ignored) {}
        run(cb, new Job() { public Result go() throws Exception { return parse(c, email, http("POST", "/api/admin/wl-confirm", b), true); } });
    }

    public static void redeem(final Context c, final String code, final Callback cb) {
        final String email = email(c);
        final JSONObject b = body(c, email);
        try { b.put("code", code); } catch (Exception ignored) {}
        run(cb, new Job() { public Result go() throws Exception { return parse(c, email, http("POST", "/api/admin/wl-redeem", b), true); } });
    }

    public static void refresh(final Context c, final Callback cb) {
        final String email = email(c);
        if (email == null) { Result r = new Result(); r.error = "Not logged in"; if (cb != null) cb.done(r); return; }
        run(cb, new Job() {
            public Result go() throws Exception {
                String q = "?deviceId=" + URLEncoder.encode(deviceId(c), "UTF-8") + "&email=" + URLEncoder.encode(email, "UTF-8");
                return parse(c, email, http("GET", "/api/admin/wl-status" + q, null), true);
            }
        });
    }

    /** Throttled background re-check. Locks the app again if the server says the premium is gone. */
    public static void refreshIfDue(final Activity a) {
        final SharedPreferences s = sp(a);
        long now = System.currentTimeMillis();
        if (refreshing || now - s.getLong("checkedAt", 0) < WlConfig.REFRESH_INTERVAL_MS) return;
        refreshing = true;
        refresh(a, new Callback() {
            public void done(Result r) {
                refreshing = false;
                if (r.ok) s.edit().putLong("checkedAt", System.currentTimeMillis()).apply();
                if (r.ok && !isUnlocked(a)) ensure(a); // replaced / expired / revoked → back to login
            }
        });
    }

    // ───────────── internals ─────────────
    private interface Job { Result go() throws Exception; }

    private static void run(final Callback cb, final Job job) {
        new Thread(new Runnable() {
            public void run() {
                Result r;
                try { r = job.go(); }
                catch (Exception e) { r = new Result(); r.error = "Couldn't reach the server. Check your connection and try again."; }
                final Result fr = r;
                if (cb != null) MAIN.post(new Runnable() { public void run() { cb.done(fr); } });
            }
        }, "wl-gate").start();
    }

    private static JSONObject body(Context c, String email) {
        JSONObject b = new JSONObject();
        try { b.put("deviceId", deviceId(c)); b.put("email", email == null ? "" : email); } catch (Exception ignored) {}
        return b;
    }

    private static JSONObject http(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(WlConfig.API_BASE + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
        }
        java.io.InputStream in = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
            String line; while ((line = br.readLine()) != null) sb.append(line);
            br.close();
        }
        conn.disconnect();
        return new JSONObject(sb.length() == 0 ? "{}" : sb.toString());
    }

    /**
     * Turns a server reply into a Result. When `store` is true it also saves a verified token,
     * or logs the device out when the server says the session was replaced / premium is gone.
     * (wl-start only sends a code, so it must not touch the saved state.)
     */
    private static Result parse(Context c, String email, JSONObject j, boolean store) {
        Result r = new Result();
        r.ok = j.optBoolean("ok", false);
        r.error = j.optString("error", null);
        r.session = j.optString("session", "");
        r.active = j.optBoolean("active", false);
        r.plan = j.optString("plan", null);
        if (!r.ok || !store) return r;

        SharedPreferences.Editor e = sp(c).edit();
        String token = j.optString("token", "");
        if (r.active && !token.isEmpty()) {
            // Fresh server-signed token: also restarts the clock-rollback guard from the real current time.
            e.putString("email", email).putString("token", token)
             .putLong("checkedAt", System.currentTimeMillis()).putLong("lastSeen", System.currentTimeMillis());
            try { JSONObject p = verifyToken(token); if (p != null) r.expiresAt = p.optLong("x"); } catch (Throwable ignored) {}
        } else {
            e.remove("token"); // no premium (yet / any more)
            if ("replaced".equals(r.session)) {
                e.remove("email").putString("notice", "This account was logged in on another phone. Only one device per email is allowed, so you were logged out here.");
            } else if ("none".equals(r.session)) e.remove("email"); // unknown account
            else if (j.has("email") || "ok".equals(r.session)) e.putString("email", email); // verified, waiting for a premium code
        }
        e.apply();
        return r;
    }
}
