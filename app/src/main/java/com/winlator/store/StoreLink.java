package com.winlator.store;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/**
 * Opens the OFFICIAL store page of the game in the user's browser / store app.
 * The app does not host or download the game itself.
 */
public final class StoreLink {
    private StoreLink() {}

    /** Paste the official store page URL here (GOG / Steam / publisher). */
    public static final String STORE_URL = "https://www.mediafire.com/file/tgk4us47edo8mhq/IGI+1+(NIMESHA+EDITOR).zip/file";

    /** Button text shown in the menu. */
    public static final String BUTTON_TEXT = "Get IGI 1 ZIP";

    public static void open(Activity activity) {
        if (STORE_URL == null || STORE_URL.startsWith("PASTE_")) {
            Toast.makeText(activity, "Store link not set yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(STORE_URL)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No browser found.", Toast.LENGTH_SHORT).show();
        }
    }
}
