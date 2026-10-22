package com.winlator.license;

/** Everything you may need to edit lives here. */
public final class LicenseConfig {
    private LicenseConfig() {}

    /** Your Vercel deployment of nmd-license-server (no trailing slash). */
    public static final String BASE_URL = "https://YOUR-PROJECT.vercel.app";

    /** Output of `npm run keys` in the server folder (the PUBLIC key only, never the private one). */
    public static final String PUBLIC_KEY_B64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAESfhbvchaIW6Mid9EzCcDDcS9VGG4OuZsnmo000a8c5yO+zrBlbjuD7Od1UDTlK1qoYq2dh8NyFWHNTsVp4c/pQ==";

    /** Where users send the payment message (digits only, country code first). */
    public static final String WHATSAPP_NUMBER = "94726800969";

    /** Offline use is allowed this long after the last successful server check. */
    public static final long OFFLINE_GRACE_MS = 7L * 24 * 60 * 60 * 1000;

    /** While the app is open it re-checks the server this often. */
    public static final long RECHECK_MS = 10L * 60 * 1000;

    /** Display only: ids/prices must match lib/plans.js on the server. */
    public static final String[] PLAN_IDS    = {"1m", "3m", "1y", "lifetime"};
    public static final String[] PLAN_LABELS = {"1 Month", "3 Months", "1 Year", "Lifetime"};
    public static final int[]    PLAN_PRICES = {100, 200, 300, 400};
}
