package com.winlator.license;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.UUID;

/** Local cache. Nothing here is trusted on its own: the license is re-verified against the server signature on every read. */
public final class LicenseStore {
    private LicenseStore() {}

    public static final class License {
        public String email, deviceId;
        public boolean premium, lifetime;
        public long expiresAt;   // -1 = never
        public long issuedAt;    // SERVER clock, ms
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("nmd_lic", Context.MODE_PRIVATE);
    }

    public static synchronized String deviceId(Context c) {
        String id = p(c).getString("did", null);
        if (id == null) { id = UUID.randomUUID().toString(); p(c).edit().putString("did", id).apply(); }
        return id;
    }

    public static String email(Context c) { return p(c).getString("em", null); }
    public static String token(Context c) { return p(c).getString("tk", null); }

    public static void saveSession(Context c, String email, String token) {
        p(c).edit().putString("em", email).putString("tk", token).remove("lp").remove("ls").apply();
    }

    public static void clearSession(Context c) {
        p(c).edit().remove("em").remove("tk").remove("lp").remove("ls").apply();
    }

    /** Verifies the signature and that it was issued to THIS email and THIS device. Returns false if anything is off. */
    public static boolean saveLicense(Context c, String payload, String sig) {
        License l = parse(c, payload, sig);
        if (l == null) return false;
        p(c).edit().putString("lp", payload).putString("ls", sig).apply();
        return true;
    }

    public static License load(Context c) {
        String payload = p(c).getString("lp", null), sig = p(c).getString("ls", null);
        return payload == null || sig == null ? null : parse(c, payload, sig);
    }

    private static License parse(Context c, String payload, String sig) {
        try {
            if (!LicenseCrypto.verify(payload, sig)) return null;
            JSONObject o = new JSONObject(payload);
            License l = new License();
            l.email = o.getString("email");
            l.deviceId = o.getString("deviceId");
            l.premium = o.getBoolean("premium");
            l.lifetime = o.optBoolean("lifetime", false);
            l.expiresAt = o.isNull("expiresAt") ? -1L : o.getLong("expiresAt");
            l.issuedAt = o.getLong("issuedAt");
            if (!l.deviceId.equals(deviceId(c))) return null;
            String em = email(c);
            if (em == null || !em.equalsIgnoreCase(l.email)) return null;
            return l;
        } catch (Exception e) {
            return null;
        }
    }

    // Highest time ever seen. Offline, "now" is never allowed to go backwards, so rolling the phone clock back gains nothing.
    public static synchronized long lastSeen(Context c) { return p(c).getLong("ts", 0L); }

    public static synchronized long bumpNow(Context c) {
        long now = Math.max(System.currentTimeMillis(), lastSeen(c));
        p(c).edit().putLong("ts", now).apply();
        return now;
    }

    /** After a successful online check the server clock is the truth, so a wrongly set phone clock recovers. */
    public static synchronized void setSeen(Context c, long serverTime) {
        p(c).edit().putLong("ts", serverTime).apply();
    }
}
