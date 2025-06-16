package com.winlator.Download.service;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GofileLinkResolver {

    private static final String TAG = "GofileLinkResolver";
    private final GofileApiHandler apiHandler;

    public GofileLinkResolver() {
        this.apiHandler = new GofileApiHandler();
    }

    // Constructor for allowing mock/test GofileApiHandler
    public GofileLinkResolver(GofileApiHandler apiHandler) {
        this.apiHandler = apiHandler;
    }

    private String extractContentId(String gofilePageUrl) {
        if (gofilePageUrl == null) return null;
        Pattern pattern = Pattern.compile("gofile\\.io/(?:d|download)/([\\w-]+)");
        Matcher matcher = pattern.matcher(gofilePageUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Log.w(TAG, "Could not extract content ID from URL: " + gofilePageUrl);
        return null;
    }

    // Modified to return GofileResolvedResult
    public GofileResolvedResult resolveGofileUrl(String gofilePageUrl, String password) {
        Log.d(TAG, "Attempting to resolve Gofile URL: " + gofilePageUrl);
        List<DownloadItem> downloadItems = new ArrayList<>();

        String contentId = extractContentId(gofilePageUrl);
        if (contentId == null) {
            Log.e(TAG, "Failed to extract content ID from URL: " + gofilePageUrl);
            return new GofileResolvedResult(downloadItems, null); // Return empty result
        }

        String token = apiHandler.getGuestToken(); // This token will be returned for use in download
        if (token == null) {
            Log.e(TAG, "Failed to obtain Gofile guest token.");
            return new GofileResolvedResult(downloadItems, null); // Return empty result
        }
        Log.d(TAG, "Obtained Gofile guest token: " + token);

        GofileApiHandler.GofileEntry rootEntry = apiHandler.getContentDetails(contentId, token, password);
        if (rootEntry == null) {
            Log.e(TAG, "Failed to get content details for ID: " + contentId);
            return new GofileResolvedResult(downloadItems, token); // Return with token, but empty items
        }

        Log.d(TAG, "Successfully fetched root content entry: " + rootEntry.name);
        flattenGofileEntry(rootEntry, downloadItems, contentId);

        Log.i(TAG, "Resolved " + downloadItems.size() + " downloadable items from " + gofilePageUrl);
        return new GofileResolvedResult(downloadItems, token);
    }

    private void flattenGofileEntry(GofileApiHandler.GofileEntry entry, List<DownloadItem> downloadItems, String originalContentId) {
        if (entry == null) {
            return;
        }
        Log.d(TAG, "Processing entry: " + entry.name + ", type: " + entry.type);
        if ("file".equals(entry.type)) {
            if (entry.directLink != null && !entry.directLink.isEmpty()) {
                Log.d(TAG, "Found file: " + entry.name + " with link: " + entry.directLink);
                downloadItems.add(new DownloadItem(entry.name, entry.directLink, entry.size, originalContentId));
            } else {
                Log.w(TAG, "File entry " + entry.name + " (ID: " + entry.id + ") has no direct download link. Skipping.");
            }
        } else if ("folder".equals(entry.type)) {
            Log.d(TAG, "Entering folder: " + entry.name + " with " + entry.children.size() + " children.");
            for (GofileApiHandler.GofileEntry child : entry.children) {
                flattenGofileEntry(child, downloadItems, originalContentId);
            }
        }
    }
}
