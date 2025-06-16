package com.winlator.Download.service;

import android.util.Log;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PixeldrainLinkResolver {

    private static final String TAG = "PixeldrainResolver";
    private static final String API_DOWNLOAD_URL_BASE = "https://pixeldrain.com/api/file/";

    // Pattern to extract file ID from URLs like:
    // https://pixeldrain.com/u/FILE_ID
    // https://pixeldrain.com/l/FILE_ID (list, but might contain single file ID if script handles it that way)
    // Or if URL is already api.pixeldrain.com/v1/files/FILE_ID/info or /FILE_ID
    // The Python script uses link.split('/')[-1], so we'll primarily target that.
    // A more robust regex could be: pixeldrain.com/(?:u/|l/|api/file/)?([A-Za-z0-9_-]+)
    private static final Pattern PIXELDRAIN_ID_PATTERN = Pattern.compile("pixeldrain\\.com/(?:u/|l/|api/file/)?([A-Za-z0-9_-]+)");

    /**
     * Extracts the PixelDrain file ID from various URL formats.
     * The Python script simply takes the last part of the path.
     * This method tries to be a bit more specific.
     * @param pageUrl The PixelDrain URL.
     * @return The extracted file ID, or null if not found.
     */
    public static String extractFileId(String pageUrl) {
        if (pageUrl == null || pageUrl.isEmpty()) {
            return null;
        }

        Matcher matcher = PIXELDRAIN_ID_PATTERN.matcher(pageUrl);
        if (matcher.find()) {
            // Group 1 should be the ID
            String potentialId = matcher.group(1);
            if (potentialId != null && !potentialId.isEmpty() && !potentialId.equals("file")) { // ensure it's not the word 'file' from api/file/
                 Log.d(TAG, "Extracted PixelDrain ID using regex: " + potentialId);
                return potentialId;
            }
        }

        // Fallback to Python script's logic: last part of the path
        // This is more general.
        try {
            String path = new java.net.URL(pageUrl).getPath();
            if (path != null && !path.isEmpty()) {
                String[] segments = path.split("/");
                if (segments.length > 0) {
                    String lastSegment = segments[segments.length - 1];
                    if (!lastSegment.isEmpty()) {
                         Log.d(TAG, "Extracted PixelDrain ID using fallback (last path segment): " + lastSegment);
                        return lastSegment;
                    }
                }
            }
        } catch (java.net.MalformedURLException e) {
            Log.w(TAG, "Malformed URL for ID extraction: " + pageUrl, e);
        }

        Log.w(TAG, "Could not extract PixelDrain ID from URL: " + pageUrl);
        return null;
    }

    /**
     * Resolves a PixelDrain page URL to a DownloadItem.
     *
     * @param pageUrl The PixelDrain page URL (e.g., https://pixeldrain.com/u/FILE_ID).
     * @return A DownloadItem containing the direct API URL and filename (which is the ID), or null if resolution fails.
     */
    public DownloadItem resolvePixeldrainUrl(String pageUrl) {
        Log.d(TAG, "Attempting to resolve PixelDrain URL: " + pageUrl);

        String fileId = extractFileId(pageUrl);

        if (fileId == null || fileId.isEmpty()) {
            Log.e(TAG, "Could not extract File ID from PixelDrain URL: " + pageUrl);
            return null;
        }

        String directDownloadUrl = API_DOWNLOAD_URL_BASE + fileId;

        // Filename is the fileId itself, as per Python script's behavior.
        // Extension is unknown at this stage; DownloadTask might get it from Content-Type if available,
        // or user might need to rename.
        String filename = fileId;

        Log.i(TAG, "PixelDrain link resolved. Filename: " + filename + ", URL: " + directDownloadUrl);
        // Size is set to -1; DownloadService/DownloadTask will determine it from Content-Length.
        return new DownloadItem(filename, directDownloadUrl, -1, null); // gofileContentId is null
    }
}
