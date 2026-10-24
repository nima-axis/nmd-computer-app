package com.winlator.about;

import android.app.Activity;
import com.winlator.update.UpdateManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** About screen: version, contact (WhatsApp) and share. No third-party credits. */
public final class AboutNmdDialog {
    private AboutNmdDialog() {}

    /** WhatsApp number, digits only, country code first. */
    private static final String WHATSAPP_NUMBER = "94726800969";

    /** Direct download link (Cloudflare Worker). */
    private static final String DOWNLOAD_URL = "https://nmd-computer.nmdaxis.workers.dev/download";

    public static void show(final Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 22);
        box.setPadding(pad, pad, pad, pad);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = text(a, "NMD COMPUTER", 22, true);
        title.setGravity(Gravity.CENTER);
        box.addView(title, wrap(a));

        TextView version = text(a, "Version " + versionName(a), 14, false);
        version.setGravity(Gravity.CENTER);
        box.addView(version, wrap(a, 0, 4, 0, 18));

        box.addView(button(a, "Contact us on WhatsApp", v -> openWhatsApp(a)), wrap(a));
        box.addView(button(a, "Share this app", v -> shareApp(a)), wrap(a, 0, 10, 0, 0));
        box.addView(button(a, "Check for updates", v -> UpdateManager.checkNow(a)), wrap(a, 0, 10, 0, 0));

        new AlertDialog.Builder(a)
                .setView(box)
                .setPositiveButton("Close", null)
                .show();
    }

    private static void openWhatsApp(Activity a) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/" + WHATSAPP_NUMBER + "?text=" + Uri.encode("Hi, I need help with NMD COMPUTER."))));
        } catch (Exception e) {
            Toast.makeText(a, "Could not open WhatsApp.", Toast.LENGTH_SHORT).show();
        }
    }

    private static void shareApp(Activity a) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "NMD COMPUTER");
        i.putExtra(Intent.EXTRA_TEXT, "Download NMD COMPUTER here: " + DOWNLOAD_URL);
        a.startActivity(Intent.createChooser(i, "Share NMD COMPUTER"));
    }

    private static String versionName(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private static TextView text(Activity a, String s, int sp, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFFE8EAF0);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static Button button(Activity a, String label, android.view.View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private static LinearLayout.LayoutParams wrap(Activity a) {
        return wrap(a, 0, 0, 0, 0);
    }

    private static LinearLayout.LayoutParams wrap(Activity a, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(a, l), dp(a, t), dp(a, r), dp(a, b));
        return p;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
