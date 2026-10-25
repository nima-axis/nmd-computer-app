package com.winlator.inputcontrols;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.JsonReader;

import androidx.preference.PreferenceManager;

import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

public class InputControlsManager {
    private final Context context;
    private ArrayList<ControlsProfile> profiles;
    private int maxProfileId;
    private boolean profilesLoaded = false;

    public InputControlsManager(Context context) {
        this.context = context;
    }

    public static File getProfilesDir(Context context) {
        File profilesDir = new File(context.getFilesDir(), "profiles");
        if (!profilesDir.isDirectory()) profilesDir.mkdir();
        return profilesDir;
    }

    public ArrayList<ControlsProfile> getProfiles() {
        return getProfiles(false);
    }

    public ArrayList<ControlsProfile> getProfiles(boolean ignoreTemplates) {
        if (!profilesLoaded) loadProfiles(ignoreTemplates);
        return profiles;
    }

    /**
     * Adds bundled asset profiles that the phone does not have yet (matched by NAME, not by id).
     * - Same name already exists -> skipped (nothing is overwritten, nothing is duplicated).
     * - Id already used by another profile -> the copy gets a new free id, so it is not skipped.
     */
    private void addMissingAssetProfiles() {
        try {
            AssetManager assetManager = context.getAssets();
            String[] assetFiles = assetManager.list("inputcontrols/profiles");
            if (assetFiles == null) return;
            for (String assetFile : assetFiles) {
                String assetPath = "inputcontrols/profiles/" + assetFile;
                String json = readAssetText(assetManager, assetPath);
                if (json == null) continue;

                ControlsProfile origin;
                try { origin = loadProfile(context, new java.io.ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))); }
                catch (Exception e) { continue; }
                if (origin == null || origin.getName() == null) continue;

                boolean nameExists = false;
                boolean idTaken = false;
                int maxId = 0;
                File[] files = getProfilesDir(context).listFiles();
                if (files != null) {
                    for (File file : files) {
                        ControlsProfile existing = loadProfile(context, file);
                        if (existing == null) continue;
                        maxId = Math.max(maxId, existing.id);
                        if (origin.getName().equals(existing.getName())) nameExists = true;
                        if (existing.id == origin.id) idTaken = true;
                    }
                }
                if (nameExists) continue;

                int targetId = origin.id;
                String finalJson = json;
                if (idTaken) {
                    targetId = Math.max(maxId, maxProfileId) + 1;
                    JSONObject data = new JSONObject(json);
                    data.put("id", targetId);
                    finalJson = data.toString();
                }
                FileUtils.writeString(ControlsProfile.getProfileFile(context, targetId), finalJson);
            }
        }
        catch (Exception e) {}
    }

    private static String readAssetText(AssetManager assetManager, String path) {
        try (InputStream in = assetManager.open(path)) {
            InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            return sb.toString();
        }
        catch (IOException e) {
            return null;
        }
    }

    private void copyAssetProfilesIfNeeded() {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        if (FileUtils.isEmpty(profilesDir)) {
            FileUtils.copy(context, "inputcontrols/profiles", profilesDir);
            return;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        int newVersion = AppUtils.getVersionCode(context);
        int oldVersion = preferences.getInt("inputcontrols_app_version", 0);
        if (oldVersion == newVersion) return;
        preferences.edit().putInt("inputcontrols_app_version", newVersion).apply();

        File[] files = profilesDir.listFiles();
        if (files == null) return;

        try {
            AssetManager assetManager = context.getAssets();
            String[] assetFiles = assetManager.list("inputcontrols/profiles");
            for (String assetFile : assetFiles) {
                String assetPath = "inputcontrols/profiles/"+assetFile;
                ControlsProfile originProfile = loadProfile(context, assetManager.open(assetPath));

                File targetFile = null;
                for (File file : files) {
                    ControlsProfile targetProfile = loadProfile(context, file);
                    if (originProfile.id == targetProfile.id && originProfile.getName().equals(targetProfile.getName())) {
                        targetFile = file;
                        break;
                    }
                }

                if (targetFile != null) {
                    FileUtils.copy(context, assetPath, targetFile);
                }
            }
        }
        catch (IOException e) {}
    }

    public void loadProfiles(boolean ignoreTemplates) {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        copyAssetProfilesIfNeeded();
        addMissingAssetProfiles();

        ArrayList<ControlsProfile> profiles = new ArrayList<>();
        File[] files = profilesDir.listFiles();
        if (files != null) {
            for (File file : files) {
                ControlsProfile profile = loadProfile(context, file);
                if (!(ignoreTemplates && profile.isTemplate())) profiles.add(profile);
                maxProfileId = Math.max(maxProfileId, profile.id);
            }
        }

        Collections.sort(profiles);
        this.profiles = profiles;
        profilesLoaded = true;
    }

    public ControlsProfile createProfile(String name) {
        ControlsProfile profile = new ControlsProfile(context, ++maxProfileId);
        profile.setName(name);
        profile.save();
        profiles.add(profile);
        return profile;
    }

    public ControlsProfile duplicateProfile(ControlsProfile source) {
        String newName;
        for (int i = 1;;i++) {
            newName = source.getName() + " ("+i+")";
            boolean found = false;
            for (ControlsProfile profile : profiles) {
                if (profile.getName().equals(newName)) {
                    found = true;
                    break;
                }
            }
            if (!found) break;
        }

        int newId = ++maxProfileId;
        File newFile = ControlsProfile.getProfileFile(context, newId);

        try {
            JSONObject data = new JSONObject(FileUtils.readString(ControlsProfile.getProfileFile(context, source.id)));
            data.put("id", newId);
            data.put("name", newName);
            if (data.has("template")) data.remove("template");
            FileUtils.writeString(newFile, data.toString());
        }
        catch (JSONException e) {}

        ControlsProfile profile = loadProfile(context, newFile);
        profiles.add(profile);
        return profile;
    }

    public void removeProfile(ControlsProfile profile) {
        File file = ControlsProfile.getProfileFile(context, profile.id);
        if (file.isFile() && file.delete()) profiles.remove(profile);
    }

    public ControlsProfile importProfile(JSONObject data) {
        try {
            if (!data.has("id") || !data.has("name")) return null;
            int newId = ++maxProfileId;
            File newFile = ControlsProfile.getProfileFile(context, newId);
            data.put("id", newId);
            FileUtils.writeString(newFile, data.toString());
            ControlsProfile newProfile = loadProfile(context, newFile);

            int foundIndex = -1;
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile.getName().equals(newProfile.getName())) {
                    foundIndex = i;
                    break;
                }
            }

            if (foundIndex != -1) {
                profiles.set(foundIndex, newProfile);
            }
            else profiles.add(newProfile);
            return newProfile;
        }
        catch (JSONException e) {
            return null;
        }
    }

    public File exportProfile(ControlsProfile profile) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File destination = new File(downloadsDir, "Winlator/profiles/"+profile.getName()+".icp");
        FileUtils.copy(ControlsProfile.getProfileFile(context, profile.id), destination);
        MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, null, null);
        return destination.isFile() ? destination : null;
    }

    public static ControlsProfile loadProfile(Context context, File file) {
        try {
            return loadProfile(context, new FileInputStream(file));
        }
        catch (FileNotFoundException e) {
            return null;
        }
    }

    public static ControlsProfile loadProfile(Context context, InputStream inStream) {
        try (JsonReader reader = new JsonReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))) {
            int profileId = 0;
            String profileName = null;
            float cursorSpeed = Float.NaN;
            boolean disableMouseInput = false;
            int fieldsRead = 0;
            final byte numFieldsToBreak = 4;

            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();

                if (name.equals("id")) {
                    profileId = reader.nextInt();
                    fieldsRead++;
                }
                else if (name.equals("name")) {
                    profileName = reader.nextString();
                    fieldsRead++;
                }
                else if (name.equals("cursorSpeed")) {
                    cursorSpeed = (float)reader.nextDouble();
                    fieldsRead++;
                }
                else if (name.equals("disableMouseInput")) {
                    disableMouseInput = reader.nextBoolean();
                    fieldsRead++;
                }
                else {
                    if (fieldsRead == numFieldsToBreak) break;
                    reader.skipValue();
                }
            }

            ControlsProfile profile = new ControlsProfile(context, profileId);
            profile.setName(profileName);
            profile.setCursorSpeed(cursorSpeed);
            profile.setDisableMouseInput(disableMouseInput);
            return profile;
        }
        catch (IOException e) {
            return null;
        }
    }

    public ControlsProfile getProfile(int id) {
        for (ControlsProfile profile : getProfiles()) if (profile.id == id) return profile;
        return null;
    }
}
