package com.winlator.license;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LicenseApi {
    private LicenseApi() {}

    public static final class Result {
        public int status;          // HTTP status, 0 = no connection / unreadable
        public JSONObject json;     // never null
        public boolean networkError() { return status == 0 || status >= 500; }
        public boolean ok() { return status == 200 && json.optBoolean("ok", false); }
        public String error(String fallback) { return json.optString("error", fallback); }
    }

    public interface Callback { void done(Result r); }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** POST JSON to BASE_URL + path on a background thread; callback runs on the main thread. */
    public static void post(final String path, final JSONObject body, final Callback cb) {
        IO.execute(new Runnable() {
            @Override public void run() {
                final Result r = postBlocking(path, body);
                MAIN.post(new Runnable() { @Override public void run() { cb.done(r); } });
            }
        });
    }

    private static Result postBlocking(String path, JSONObject body) {
        Result r = new Result();
        r.json = new JSONObject();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(LicenseConfig.BASE_URL + path).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            r.status = c.getResponseCode();
            InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                try { r.json = new JSONObject(sb.toString()); } catch (Exception ignored) { if (r.status == 200) r.status = 0; }
            }
        } catch (Exception e) {
            r.status = 0;
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }
}
