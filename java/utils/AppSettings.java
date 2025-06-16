package com.winlator.Download.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class AppSettings {

    public static final String PREFS_NAME = "download_settings";
    public static final String KEY_DOWNLOAD_PATH = "download_path";
    // Using "Default: Downloads folder" as the actual default string stored if the user desires the default.
    // This matches the behavior in SettingsActivity where an empty input resets to this string.
    public static final String DEFAULT_DOWNLOAD_PATH = "Default: Downloads folder";
    public static final String KEY_DISABLE_DIRECT_DOWNLOADS = "disable_direct_downloads";

    // Getter for Download Path
    public static String getDownloadPath(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_DOWNLOAD_PATH, DEFAULT_DOWNLOAD_PATH);
    }

    // Setter for Download Path
    public static void setDownloadPath(Context context, String path) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(KEY_DOWNLOAD_PATH, path);
        editor.apply();
    }

    // Getter for Disable Direct Downloads
    public static boolean getDisableDirectDownloads(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_DISABLE_DIRECT_DOWNLOADS, false);
    }

    // Setter for Disable Direct Downloads
    public static void setDisableDirectDownloads(Context context, boolean isDisabled) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putBoolean(KEY_DISABLE_DIRECT_DOWNLOADS, isDisabled);
        editor.apply();
    }
}
