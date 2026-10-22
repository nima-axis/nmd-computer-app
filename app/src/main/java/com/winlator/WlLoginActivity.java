package com.winlator;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Three steps, built in code (no layout/resource files to merge):
 *   1. email  →  2. 6-digit code from the email  →  3. premium code (required)
 * The app can't be entered without step 3 succeeding. Back button leaves the app.
 */
public class WlLoginActivity extends Activity {
    private static final int BG = Color.parseColor("#0b1115"), PANEL = Color.parseColor("#141f26"),
            ACCENT = Color.parseColor("#2dd7a7"), TEXT = Color.parseColor("#eaf5f1"),
            MUTED = Color.parseColor("#8aa5a0"), DANGER = Color.parseColor("#ff6b6b");

    private LinearLayout box;
    private TextView title, sub, err;
    private EditText input;
    private Button primary, secondary;
    private LinearLayout planBox;
    private int selectedPlan = WlConfig.DEFAULT_PLAN_INDEX;
    private int step; // 1 email, 2 otp, 3 premium code, 4 internet required
    private String pendingEmail;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Already unlocked (e.g. relaunched)? straight in.
        final int state = WlGate.check(this);
        if (state == WlGate.OK) { openApp(); return; }

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(20), dp(40), dp(20), dp(40));
        sv.addView(root, new ViewGroup.LayoutParams(-1, -1));

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(PANEL);
        box.setPadding(dp(22), dp(24), dp(22), dp(24));
        root.addView(box, new LinearLayout.LayoutParams(-1, -2));

        title = text(22, TEXT, true); box.addView(title);
        sub = text(14, MUTED, false); sub.setPadding(0, dp(8), 0, dp(18)); box.addView(sub);
        planBox = new LinearLayout(this); planBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(planBox, new LinearLayout.LayoutParams(-1, -2));
        input = new EditText(this);
        input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setSingleLine(true);
        input.setBackgroundColor(Color.parseColor("#0b1418")); input.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        err = text(13, DANGER, false); err.setPadding(0, dp(8), 0, dp(8)); box.addView(err);
        primary = button(true); box.addView(primary, lp());
        secondary = button(false); box.addView(secondary, lp());
        setContentView(sv);

        // Resume at the right step: verified email but no premium yet → straight to step 3.
        if (state == WlGate.NEED_ONLINE) { pendingEmail = WlGate.email(this); showStep(4); retryOnline(); }
        else if (WlGate.isLoggedIn(this)) { pendingEmail = WlGate.email(this); showStep(3); checkInBackground(); }
        else showStep(1);
    }

    private void showStep(int s) {
        step = s; err.setText(""); input.setText(""); secondary.setVisibility(View.VISIBLE); input.setVisibility(View.VISIBLE);
        planBox.removeAllViews();
        if (s == 1) {
            title.setText("Welcome"); sub.setText("Enter your email. We'll send you a verification code.");
            input.setHint("you@example.com"); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            primary.setText("Send code"); secondary.setVisibility(View.GONE);
            String notice = WlGate.takeNotice(this); if (notice != null) err.setText(notice);
        } else if (s == 2) {
            title.setText("Check your email"); sub.setText("Enter the 6-digit code we sent to " + pendingEmail + ".");
            input.setHint("123456"); input.setInputType(InputType.TYPE_CLASS_NUMBER);
            primary.setText("Verify"); secondary.setText("Use a different email");
        } else if (s == 4) {
            title.setText("Internet connection required");
            sub.setText("To keep using " + WlConfig.APP_NAME + ", the app must connect to the internet at least once every 7 days. "
                    + "Connect to the internet and tap Retry — if your premium is still active it unlocks right away.");
            input.setVisibility(View.GONE); primary.setText("Retry"); secondary.setText("Log out");
        } else {
            buildPlans();
            title.setText("Premium required");
            sub.setText("Logged in as " + pendingEmail + ".\nA premium code is required to use this app.\n\n"
                    + "1) Pick a plan and tap \"Send order on WhatsApp\".\n2) Pay as agreed there — nothing is charged in the app.\n3) We send you a code for " + pendingEmail + ". Enter it below.");
            input.setHint("Already have a code? Enter it"); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            primary.setText("Activate code"); secondary.setText("Send order on WhatsApp");
        }
        primary.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onPrimary(); } });
        secondary.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onSecondary(); } });
    }

    private void onPrimary() {
        if (step == 4) { retryOnline(); return; }
        final String val = input.getText().toString().trim();
        if (val.isEmpty()) { err.setText(step == 1 ? "Enter your email." : "Enter the code."); return; }
        busy(true);
        if (step == 1) {
            pendingEmail = val.toLowerCase();
            WlGate.start(this, pendingEmail, new WlGate.Callback() { public void done(WlGate.Result r) {
                busy(false); if (r.ok) showStep(2); else err.setText(msg(r)); } });
        } else if (step == 2) {
            WlGate.confirm(this, pendingEmail, val, new WlGate.Callback() { public void done(WlGate.Result r) {
                busy(false);
                if (!r.ok) { err.setText(msg(r)); return; }
                if (WlGate.isUnlocked(WlLoginActivity.this)) openApp(); else showStep(3); } });
        } else {
            WlGate.redeem(this, val, new WlGate.Callback() { public void done(WlGate.Result r) {
                busy(false);
                if (!r.ok) { err.setText(msg(r)); if ("replaced".equals(r.session) || "none".equals(r.session)) showStep(1); return; }
                if (WlGate.isUnlocked(WlLoginActivity.this)) openApp(); else err.setText("Couldn't activate. Try again."); } });
        }
    }

    /** Price list: one tappable row per plan, with the per-month price so longer plans clearly win. */
    private void buildPlans() {
        planBox.removeAllViews();
        for (int i = 0; i < WlConfig.PLANS.length; i++) {
            final int idx = i; WlConfig.Plan p = WlConfig.PLANS[i];
            String tag = p.id.equals("lifetime") ? "   ★ BEST VALUE" : p.id.equals("1y") ? "   POPULAR" : "";
            Button row = new Button(this); row.setAllCaps(false); row.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            row.setText(p.label + " — LKR " + p.priceLkr + tag + "\n" + p.perMonth());
            row.setTextColor(idx == selectedPlan ? Color.parseColor("#04201a") : TEXT);
            row.setBackgroundColor(idx == selectedPlan ? ACCENT : Color.parseColor("#1f2f38"));
            row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { selectedPlan = idx; buildPlans(); } });
            planBox.addView(row, lp());
        }
        View gap = new View(this); planBox.addView(gap, new LinearLayout.LayoutParams(-1, dp(12)));
    }

    private void onSecondary() {
        if (step == 3) {
            WlConfig.Plan p = WlConfig.PLANS[selectedPlan];
            String url = "https://wa.me/" + WlConfig.WHATSAPP_NUMBER + "?text=" + Uri.encode(WlConfig.orderMessage(p, pendingEmail));
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (Exception e) { Toast.makeText(this, "+" + WlConfig.WHATSAPP_NUMBER, Toast.LENGTH_LONG).show(); }
        } else { WlGate.logout(this); showStep(1); }
    }

    /** Step 4: ask the server; unlock if premium is still valid, otherwise continue at the right login step. */
    private void retryOnline() {
        busy(true);
        WlGate.refresh(this, new WlGate.Callback() { public void done(WlGate.Result r) {
            busy(false);
            if (!r.ok) { err.setText(msg(r)); return; }            // still offline
            int st = WlGate.check(WlLoginActivity.this);
            if (st == WlGate.OK) openApp();
            else if (WlGate.isLoggedIn(WlLoginActivity.this)) showStep(3); // premium ended → buy / enter a new code
            else showStep(1);                                       // logged in elsewhere → notice shown on step 1
        } });
    }

    /** Re-check with the server (e.g. premium granted meanwhile / session replaced). */
    private void checkInBackground() {
        WlGate.refresh(this, new WlGate.Callback() { public void done(WlGate.Result r) {
            if (r.ok && WlGate.isUnlocked(WlLoginActivity.this)) openApp();
            else if (r.ok && !WlGate.isLoggedIn(WlLoginActivity.this)) showStep(1); } });
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    @Override public void onBackPressed() { finishAffinity(); } // can't back out into the app

    // ── small UI helpers ──
    private String msg(WlGate.Result r) { return r.error != null && !r.error.isEmpty() ? r.error : "Something went wrong. Try again."; }
    private void busy(boolean b) { primary.setEnabled(!b); secondary.setEnabled(!b); if (b) err.setText(""); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams lp() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(8); return p; }
    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t;
    }
    private Button button(boolean main) {
        Button b = new Button(this); b.setAllCaps(false);
        b.setTextColor(main ? Color.parseColor("#04201a") : TEXT);
        b.setBackgroundColor(main ? ACCENT : Color.parseColor("#1f2f38"));
        return b;
    }
}
