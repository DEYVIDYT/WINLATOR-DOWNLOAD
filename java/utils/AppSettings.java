package com.winlator.Download.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log; // Added import for android.util.Log

import org.json.JSONObject; // Added import
import org.json.JSONException; // Added import

import java.io.BufferedReader; // Added import
import java.io.IOException; // Added import
import java.io.InputStream; // Added import
import java.io.InputStreamReader; // Added import
import java.net.HttpURLConnection; // Added import
import java.net.MalformedURLException; // Added import
import java.net.URL; // Added import

public class AppSettings {

    public static final String PREFS_NAME = "download_settings";
    public static final String KEY_DOWNLOAD_PATH = "download_path";
    // Using "Default: Downloads folder" as the actual default string stored if the user desires the default.
    // This matches the behavior in SettingsActivity where an empty input resets to this string.
    public static final String DEFAULT_DOWNLOAD_PATH = "Default: Downloads folder";
    public static final String KEY_DISABLE_DIRECT_DOWNLOADS = "disable_direct_downloads";
    public static final String KEY_GOFILE_ACCOUNT_TOKEN = "gofile_account_token";
    private static final String GOFILE_CREATE_ACCOUNT_URL = "https://api.gofile.io/accounts";

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

    // Getter for Gofile Account Token
    public static String getGofileAccountToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_GOFILE_ACCOUNT_TOKEN, null);
        if (token == null || token.isEmpty()) {
            // Perform network operation on a background thread if called from main thread
            // For simplicity in this subtask, direct call. In real app, ensure background execution.
            // However, this method might be called from DownloadService's background executor.
            try {
                Log.d("AppSettings.Gofile", "No Gofile token found, attempting to fetch a new one.");
                token = fetchNewGofileAccountToken(context);
            } catch (IOException e) {
                Log.e("AppSettings.Gofile", "IOException fetching new Gofile token", e);
                // In a real app, you might want to propagate this error or handle it more gracefully
                return null;
            }
        }
        return token;
    }

    // Setter for Gofile Account Token
    public static void setGofileAccountToken(Context context, String token) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_GOFILE_ACCOUNT_TOKEN, token);
        editor.apply();
        Log.i("AppSettings.Gofile", "Gofile account token saved.");
    }

    // Fetch new Gofile Account Token (private static)
    private static String fetchNewGofileAccountToken(Context context) throws IOException {
        // This method performs network operations and should ideally be called from a background thread.
        // The caller (getGofileAccountToken) should manage threading if necessary.
        HttpURLConnection connection = null;
        String token = null;
        try {
            URL url = new URL(GOFILE_CREATE_ACCOUNT_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            // Gofile's createAccount endpoint seems to be a POST, but might not require a body.
            // It might also prefer specific headers like 'Origin' or 'Referer' if it's web-oriented.
            // The provided Python script example did not include a body for this specific POST.
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate, br");
            connection.setRequestProperty("Connection", "keep-alive");
            // connection.setDoOutput(true); // Not setting DoOutput to true if no body is sent
            connection.setConnectTimeout(15000); // 15 seconds
            connection.setReadTimeout(15000);    // 15 seconds

            int responseCode = connection.getResponseCode();
            Log.d("AppSettings.Gofile", "Create account response code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream inputStream = connection.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                inputStream.close();

                String jsonResponse = response.toString();
                Log.d("AppSettings.Gofile", "Create account response JSON: " + jsonResponse);
                // Example: {"status":"ok","data":{"token":"someTokenValue",...}}
                // More robust parsing:
                try {
                    JSONObject jsonObj = new JSONObject(jsonResponse);
                    if ("ok".equals(jsonObj.optString("status"))) {
                        JSONObject dataObj = jsonObj.optJSONObject("data");
                        if (dataObj != null) {
                            token = dataObj.optString("token", null);
                            if (token != null && !token.isEmpty()) {
                                setGofileAccountToken(context, token); // Save the fetched token
                                Log.i("AppSettings.Gofile", "New Gofile token fetched and saved: " + token);
                            } else {
                                Log.e("AppSettings.Gofile", "Fetched Gofile token is null or empty from JSON data.");
                            }
                        } else {
                            Log.e("AppSettings.Gofile", "Gofile 'data' object is missing in JSON response.");
                        }
                    } else {
                         Log.e("AppSettings.Gofile", "Gofile account creation status not OK. Status: " + jsonObj.optString("status"));
                    }
                } catch (JSONException e) {
                    Log.e("AppSettings.Gofile", "JSONException while parsing Gofile token response: " + jsonResponse, e);
                }
            } else {
                Log.e("AppSettings.Gofile", "Failed to fetch Gofile token. HTTP Code: " + responseCode + ", Message: " + connection.getResponseMessage());
            }
        } catch (MalformedURLException e) {
            Log.e("AppSettings.Gofile", "MalformedURLException for Gofile account URL: " + GOFILE_CREATE_ACCOUNT_URL, e);
            throw e;
        } catch (IOException e) {
            Log.e("AppSettings.Gofile", "IOException during Gofile token fetch", e);
            throw e;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return token;
    }
}
