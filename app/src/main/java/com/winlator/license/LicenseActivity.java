package com.winlator.license;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Launcher screen. Flow:  email -> 6-digit code from email -> (premium code, only if this email has no premium yet) -> app opens.
 * Layout is built in code so no XML/resources have to be merged into the Winlator project.
 */
public class LicenseActivity extends Activity {
    private static final int BG = 0xFF1C1F24, CARD = 0xFF262A31, ACCENT = 0xFF8E2DE2, TEXT = 0xFFE8EAF0, MUTED = 0xFF8B91A0, ERR = 0xFFFF6B6B;

    private LinearLayout root;
    private TextView errorView;
    private String pendingEmail = "";
    private boolean busy = false;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        com.winlator.debug.CrashLogger.install(this);
        com.winlator.debug.CrashLogger.showPending(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(48), dp(22), dp(32));
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
        showLoading("Checking license…");
        LicenseGate.refresh(this, new LicenseGate.StateCallback() {
            @Override public void done(LicenseGate.State s) { route(s); }
        });
    }

    @Override protected void onDestroy() { stopWatchingNetwork(); super.onDestroy(); }

    // ------------------------------------------------------------------ routing

    private void route(LicenseGate.State s) {
        stopWatchingNetwork();
        switch (s) {
            case UNLOCKED:
                startActivity(new Intent(this, com.winlator.MainActivity.class));
                finish();
                break;
            case NEED_LOGIN:
                showEmail(LicenseGate.consumeLoggedOutNotice()
                        ? "This email was signed in on another device, so this device was logged out." : null);
                break;
            case NEED_PREMIUM:
                showPremiumCode();
                break;
            case OFFLINE_LOCKED:
                showOfflineLocked();
                break;
        }
    }

    private void afterServerAnswer(JSONObject json) {
        if (!LicenseGate.applyLicense(this, json)) { fail("Could not verify the license. Update the app and try again."); return; }
        route(LicenseGate.evaluate(this));
    }

    // ------------------------------------------------------------------ screens

    private void showLoading(String msg) {
        header();
        TextView t = text(msg, 15, MUTED);
        t.setGravity(Gravity.CENTER);
        root.addView(t, lp(0, 40, 0, 0));
    }

    private void showEmail(String notice) {
        header();
        if (notice != null) root.addView(text(notice, 14, ERR), lp(0, 0, 0, 14));
        root.addView(text("Enter your email. We will send a 6-digit code to verify it.", 15, TEXT), lp(0, 0, 0, 14));
        final EditText email = edit("Email address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(pendingEmail);
        root.addView(email, lp(0, 0, 0, 10));
        errorView = text("", 13, ERR);
        root.addView(errorView, lp(0, 0, 0, 6));
        root.addView(button("Send code", true, new View.OnClickListener() {
            @Override public void onClick(View v) { sendOtp(email.getText().toString().trim().toLowerCase()); }
        }), lp(0, 4, 0, 0));
        footer();
    }

    private void showOtp() {
        header();
        root.addView(text("We sent a code to\n" + pendingEmail, 15, TEXT), lp(0, 0, 0, 14));
        final EditText otp = edit("6-digit code", InputType.TYPE_CLASS_NUMBER);
        otp.setGravity(Gravity.CENTER);
        otp.setTextSize(24);
        root.addView(otp, lp(0, 0, 0, 10));
        errorView = text("", 13, ERR);
        root.addView(errorView, lp(0, 0, 0, 6));
        root.addView(button("Verify", true, new View.OnClickListener() {
            @Override public void onClick(View v) { verifyOtp(otp.getText().toString().trim()); }
        }), lp(0, 4, 0, 8));
        root.addView(button("Send a new code", false, new View.OnClickListener() {
            @Override public void onClick(View v) { sendOtp(pendingEmail); }
        }), lp(0, 4, 0, 0));
        root.addView(button("Change email", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showEmail(null); }
        }), lp(0, 4, 0, 0));
    }

    private void showPremiumCode() {
        header();
        root.addView(text("Signed in as " + LicenseStore.email(this), 13, MUTED), lp(0, 0, 0, 10));
        root.addView(text("Premium required", 19, TEXT, true), lp(0, 0, 0, 6));
        root.addView(text("Enter your premium code to open NMD COMPUTER. Premium is linked to your email, so it follows you to a new phone.", 14, MUTED), lp(0, 0, 0, 14));
        final EditText code = edit("NMD-XXXX-XXXX-XXXX", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setGravity(Gravity.CENTER);
        root.addView(code, lp(0, 0, 0, 10));
        errorView = text("", 13, ERR);
        root.addView(errorView, lp(0, 0, 0, 6));
        root.addView(button("Activate", true, new View.OnClickListener() {
            @Override public void onClick(View v) { redeem(code.getText().toString().trim()); }
        }), lp(0, 4, 0, 18));

        root.addView(text("Buy premium (tap a plan, pay on WhatsApp)", 14, TEXT, true), lp(0, 0, 0, 8));
        for (int i = 0; i < LicenseConfig.PLAN_IDS.length; i++) {
            final int idx = i;
            Button row = button(LicenseConfig.PLAN_LABELS[i] + "   ·   LKR " + LicenseConfig.PLAN_PRICES[i], false, new View.OnClickListener() {
                @Override public void onClick(View v) { openWhatsApp(idx); }
            });
            root.addView(row, lp(0, 3, 0, 0));
        }
        root.addView(button("Log out", false, new View.OnClickListener() {
            @Override public void onClick(View v) { LicenseStore.clearSession(LicenseActivity.this); showEmail(null); }
        }), lp(0, 18, 0, 0));
    }

    private void showOfflineLocked() {
        header();
        root.addView(text("App locked", 20, TEXT, true), lp(0, 20, 0, 8));
        root.addView(text("NMD COMPUTER needs to go online at least once every 7 days to confirm your premium.\n\nConnect to the internet. It unlocks automatically as soon as the check succeeds.", 14, MUTED), lp(0, 0, 0, 16));
        errorView = text("", 13, ERR);
        root.addView(errorView, lp(0, 0, 0, 6));
        root.addView(button("Check again", true, new View.OnClickListener() {
            @Override public void onClick(View v) { retryOnline(true); }
        }), lp(0, 4, 0, 0));
        watchNetwork();
    }

    // ------------------------------------------------------------------ actions

    private void sendOtp(final String email) {
        if (busy) return;
        if (!email.contains("@") || email.length() < 5) { fail("Enter a valid email address."); return; }
        pendingEmail = email;
        setBusy(true);
        try {
            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("deviceId", LicenseStore.deviceId(this));
            LicenseApi.post("/api/nmd/send-otp", body, new LicenseApi.Callback() {
                @Override public void done(LicenseApi.Result r) {
                    setBusy(false);
                    if (r.ok()) { showOtp(); toast("Code sent. Check your inbox (and spam)."); }
                    else fail(r.status == 0 ? "No internet connection." : r.error("Could not send the code."));
                }
            });
        } catch (Exception e) { setBusy(false); fail("Something went wrong."); }
    }

    private void verifyOtp(String otp) {
        if (busy) return;
        if (otp.length() != 6) { fail("Enter the 6-digit code."); return; }
        setBusy(true);
        try {
            JSONObject body = new JSONObject();
            body.put("email", pendingEmail);
            body.put("deviceId", LicenseStore.deviceId(this));
            body.put("otp", otp);
            LicenseApi.post("/api/nmd/verify-otp", body, new LicenseApi.Callback() {
                @Override public void done(LicenseApi.Result r) {
                    setBusy(false);
                    if (!r.ok()) { fail(r.status == 0 ? "No internet connection." : r.error("Verification failed.")); return; }
                    LicenseStore.saveSession(LicenseActivity.this, pendingEmail, r.json.optString("token"));
                    afterServerAnswer(r.json);   // premium already on this email -> opens directly, no code needed
                }
            });
        } catch (Exception e) { setBusy(false); fail("Something went wrong."); }
    }

    private void redeem(String code) {
        if (busy) return;
        if (code.length() < 8) { fail("Enter your premium code."); return; }
        setBusy(true);
        try {
            JSONObject body = new JSONObject();
            body.put("email", LicenseStore.email(this));
            body.put("token", LicenseStore.token(this));
            body.put("deviceId", LicenseStore.deviceId(this));
            body.put("code", code);
            LicenseApi.post("/api/nmd/redeem", body, new LicenseApi.Callback() {
                @Override public void done(LicenseApi.Result r) {
                    setBusy(false);
                    if (r.status == 401) { LicenseStore.clearSession(LicenseActivity.this); showEmail("Please sign in again."); return; }
                    if (!r.ok()) { fail(r.status == 0 ? "No internet connection." : r.error("Could not activate the code.")); return; }
                    toast("Premium activated");
                    afterServerAnswer(r.json);
                }
            });
        } catch (Exception e) { setBusy(false); fail("Something went wrong."); }
    }

    private void retryOnline(final boolean manual) {
        if (busy) return;
        setBusy(true);
        LicenseGate.refresh(this, new LicenseGate.StateCallback() {
            @Override public void done(LicenseGate.State s) {
                setBusy(false);
                if (s == LicenseGate.State.OFFLINE_LOCKED) { if (manual) fail("Still cannot reach the server."); }
                else route(s);
            }
        });
    }

    private void openWhatsApp(int planIdx) {
        String msg = "Hi! I want NMD COMPUTER Premium.\nPlan: " + LicenseConfig.PLAN_LABELS[planIdx]
                + " (LKR " + LicenseConfig.PLAN_PRICES[planIdx] + ")\nMy email: " + LicenseStore.email(this);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + LicenseConfig.WHATSAPP_NUMBER + "?text=" + Uri.encode(msg))));
        } catch (Exception e) { toast("Could not open WhatsApp."); }
    }

    // ------------------------------------------------------------------ unlock as soon as internet returns

    private void watchNetwork() {
        stopWatchingNetwork();
        try {
            cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    main.post(new Runnable() { @Override public void run() { retryOnline(false); } });
                }
            };
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Exception ignored) { netCb = null; }   // the "Check again" button still works
    }

    private void stopWatchingNetwork() {
        try { if (cm != null && netCb != null) cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) {}
        netCb = null;
    }

    // ------------------------------------------------------------------ small UI helpers

    private void setBusy(boolean b) {
        busy = b;
        if (errorView != null) { errorView.setTextColor(MUTED); errorView.setText(b ? "Please wait…" : ""); }
    }

    private void fail(String msg) {
        if (errorView != null) { errorView.setTextColor(ERR); errorView.setText(msg); } else toast(msg);
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    private void header() {
        root.removeAllViews();
        errorView = null;
        TextView t = text("NMD COMPUTER", 26, TEXT, true);
        t.setGravity(Gravity.CENTER);
        root.addView(t, lp(0, 0, 0, 4));
        TextView s = text("Windows on Android", 13, MUTED);
        s.setGravity(Gravity.CENTER);
        root.addView(s, lp(0, 0, 0, 28));
    }

    private void footer() {
        TextView t = text("One email can be used on one device at a time.", 12, MUTED);
        t.setGravity(Gravity.CENTER);
        root.addView(t, lp(0, 22, 0, 0));
    }

    private TextView text(String s, int sp, int color) { return text(s, sp, color, false); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText edit(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(MUTED); e.setTextColor(TEXT);
        e.setInputType(type); e.setSingleLine(true);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setBackground(round(CARD, 10));
        return e;
    }

    private Button button(String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(TEXT);
        b.setBackground(round(primary ? ACCENT : CARD, 10));
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
