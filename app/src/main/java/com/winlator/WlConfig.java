package com.winlator;

/** Edit these three values, nothing else in the gate needs touching. */
public final class WlConfig {
    private WlConfig() {}

    /** Your deployed vercel-pair-sites URL, no trailing slash. */
    public static final String API_BASE = "https://vercel-pair-sites.vercel.app";

    /** Output of `node scripts/wl-keygen.js` (the PUBLIC key line). Safe to embed. */
    public static final String PUBLIC_KEY_B64 = "PASTE_PUBLIC_KEY_HERE";

    /** Same WhatsApp number the Auto Saver app sends orders to (international format, no +). */
    public static final String WHATSAPP_NUMBER = "94726800969";
    public static final String APP_NAME = "Winlator";

    /** Price list shown in the app. KEEP IN SYNC with PLAN_PRICE in lib/winlator.js (admin "expected payment"). */
    public static final class Plan {
        public final String id, label; public final int months; public final int priceLkr; // months 0 = lifetime
        Plan(String id, String label, int months, int priceLkr) { this.id = id; this.label = label; this.months = months; this.priceLkr = priceLkr; }
        /** e.g. "LKR 67 / month" — what makes the longer plans visibly cheaper. */
        public String perMonth() { return months == 0 ? "pay once, use forever" : "≈ LKR " + Math.round(priceLkr / (double) months) + " / month"; }
    }
    public static final Plan[] PLANS = {
        new Plan("1m", "1 Month", 1, 100),
        new Plan("3m", "3 Months", 3, 200),
        new Plan("1y", "1 Year", 12, 300),
        new Plan("lifetime", "Lifetime", 0, 400),
    };
    public static final int DEFAULT_PLAN_INDEX = 1;

    /** The WhatsApp message the buyer sends; the admin reads it to generate the right code. */
    public static String orderMessage(Plan p, String email) {
        StringBuilder sb = new StringBuilder("Hi! I'd like to buy " + APP_NAME + " Premium.\n\n");
        sb.append("Duration: ").append(p.label).append('\n');
        sb.append("Total: LKR ").append(p.priceLkr).append('\n');
        if (email != null && !email.isEmpty()) sb.append("My verified email: ").append(email);
        return sb.toString().trim();
    }

    /** How long the app keeps working with no internet after the last successful server check (then it locks until it can reach the server again). */
    public static final long OFFLINE_GRACE_MS = 7L * 24 * 60 * 60 * 1000;
    /** Re-check with the server at most this often while the app is in use. */
    public static final long REFRESH_INTERVAL_MS = 30L * 60 * 1000;
}
