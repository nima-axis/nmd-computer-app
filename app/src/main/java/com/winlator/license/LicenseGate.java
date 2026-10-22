package com.winlator.license;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * The single place that decides "may the app run right now?".
 *
 *  UNLOCKED         valid premium, and the server confirmed it less than 7 days ago
 *  NEED_LOGIN       no session (never logged in, or this email logged in on another device)
 *  NEED_PREMIUM     logged in, but no premium / premium expired -> enter a premium code
 *  OFFLINE_LOCKED   premium was fine, but the phone has not reached the server for 7+ days
 *                   -> unlocks again the moment it is online and the server confirms
 */
public final class LicenseGate {
    private LicenseGate() {}

    public enum State { UNLOCKED, NEED_LOGIN, NEED_PREMIUM, OFFLINE_LOCKED }
    public interface StateCallback { void done(State s); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean loggedOutElsewhere = false;
    private static long lastRefreshAt = 0;

    /** True once, right after the server said this email is now used on another device. */
    public static boolean consumeLoggedOutNotice() {
        boolean b = loggedOutElsewhere; loggedOutElsewhere = false; return b;
    }

    /** Offline decision from what is stored on the phone. */
    public static State evaluate(Context c) {
        if (LicenseStore.email(c) == null || LicenseStore.token(c) == null) return State.NEED_LOGIN;
        LicenseStore.License l = LicenseStore.load(c);
        if (l == null) return State.OFFLINE_LOCKED;           // no valid signed license yet: needs one online check
        long now = LicenseStore.bumpNow(c);
        if (now - l.issuedAt > LicenseConfig.OFFLINE_GRACE_MS) return State.OFFLINE_LOCKED;
        if (!l.premium) return State.NEED_PREMIUM;
        if (!l.lifetime && l.expiresAt != -1 && now >= l.expiresAt) return State.NEED_PREMIUM;
        return State.UNLOCKED;
    }

    /** Asks the server, stores the signed answer, then reports the new state. Falls back to the offline rule if unreachable. */
    public static void refresh(final Context ctx, final StateCallback cb) {
        final Context c = ctx.getApplicationContext();
        String email = LicenseStore.email(c), token = LicenseStore.token(c);
        if (email == null || token == null) { cb.done(State.NEED_LOGIN); return; }
        try {
            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("token", token);
            body.put("deviceId", LicenseStore.deviceId(c));
            LicenseApi.post("/api/nmd/check", body, new LicenseApi.Callback() {
                @Override public void done(LicenseApi.Result r) {
                    if (r.status == 401) {                    // another device took this email (or token invalid)
                        LicenseStore.clearSession(c);
                        loggedOutElsewhere = true;
                        cb.done(State.NEED_LOGIN);
                        return;
                    }
                    if (r.ok() && applyLicense(c, r.json)) lastRefreshAt = System.currentTimeMillis();
                    cb.done(evaluate(c));                      // network error -> offline rule (7-day grace)
                }
            });
        } catch (Exception e) {
            cb.done(evaluate(c));
        }
    }

    /** Verifies + stores {license:{payload,sig}} from any server answer. */
    public static boolean applyLicense(Context c, JSONObject json) {
        try {
            JSONObject lic = json.getJSONObject("license");
            String payload = lic.getString("payload"), sig = lic.getString("sig");
            if (!LicenseStore.saveLicense(c, payload, sig)) return false;
            LicenseStore.setSeen(c, new JSONObject(payload).getLong("issuedAt"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- call these from MainActivity ----------------------------------------------------------

    private static Runnable watcher;

    /** MainActivity.onResume(): locks immediately if needed, otherwise re-checks the server in the background. */
    public static void onResume(final Activity a) {
        State s = evaluate(a);
        if (s != State.UNLOCKED) { goToLicense(a); return; }
        if (System.currentTimeMillis() - lastRefreshAt > 60_000) recheck(a);
        onPause();
        watcher = new Runnable() {
            @Override public void run() { recheck(a); MAIN.postDelayed(this, LicenseConfig.RECHECK_MS); }
        };
        MAIN.postDelayed(watcher, LicenseConfig.RECHECK_MS);
    }

    /** MainActivity.onPause() */
    public static void onPause() {
        if (watcher != null) { MAIN.removeCallbacks(watcher); watcher = null; }
    }

    private static void recheck(final Activity a) {
        refresh(a, new StateCallback() {
            @Override public void done(State s) { if (s != State.UNLOCKED && !a.isFinishing()) goToLicense(a); }
        });
    }

    public static void goToLicense(Activity a) {
        onPause();
        Intent i = new Intent(a, LicenseActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        a.startActivity(i);
        a.finish();
    }
}
