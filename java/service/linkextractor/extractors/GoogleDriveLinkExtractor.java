package com.winlator.Download.service.linkextractor.extractors;

import com.winlator.Download.service.linkextractor.HttpUtils;
import com.winlator.Download.service.linkextractor.LinkExtractor;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class GoogleDriveLinkExtractor implements LinkExtractor {

    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";

    // Pattern to extract FILE_ID from URLs like:
    // https://drive.google.com/file/d/FILE_ID/view?usp=sharing
    // https://drive.google.com/file/d/FILE_ID
    private static final Pattern FILE_ID_PATTERN_PATH = Pattern.compile("drive\\.google\\.com/file/d/([^/?#&]+)");

    // Pattern to extract FILE_ID from URLs like:
    // https://drive.google.com/open?id=FILE_ID
    // https://drive.google.com/uc?id=FILE_ID&export=download
    private static final Pattern FILE_ID_PATTERN_QUERY = Pattern.compile("[?&]id=([^&/?#]+)");

    // Pattern to find the download confirmation link on the virus scan warning page.
    // Example: <form id="downloadForm" action="/uc?export=download&amp;confirm=XXXX&amp;id=YYYY"
    // We are looking for an href attribute in an anchor tag.
    private static final Pattern CONFIRMATION_LINK_PATTERN = Pattern.compile(
        "href\\s*=\\s*\"(/uc\\?export=download&amp;confirm=([a-zA-Z0-9_-]+)[^\"]*)\"", Pattern.CASE_INSENSITIVE);
    // Note: The original regex had an unescaped forward slash. Corrected to \\.
    // "href\s*=\s*\"(/uc\?export=download&amp;confirm=([a-zA-Z0-9_-]+)[^\"]*)\""

    @Override
    public String extractDirectLink(String url) throws IOException {
        String fileId = extractFileId(url);

        if (fileId == null) {
            // Cannot extract file ID, maybe not a valid GDrive file link
            return null;
        }

        // Step 1: Construct the initial download URL
        String initialDownloadUrl = "https://docs.google.com/uc?export=download&id=" + fileId;

        // Fetch the content from this URL. This might be the virus warning page.
        String htmlContent = HttpUtils.fetchHtml(initialDownloadUrl, DEFAULT_USER_AGENT);

        // Step 2: Look for the confirmation link in the fetched HTML (virus warning page)
        Matcher matcher = CONFIRMATION_LINK_PATTERN.matcher(htmlContent);
        if (matcher.find()) {
            String confirmationPath = matcher.group(1).replace("&amp;", "&");
            // The link found is relative, so prepend the scheme and host
            return "https://docs.google.com" + confirmationPath;
        } else {
            // If no confirmation link is found, it's possible that:
            // 1. The file is small and doesn't trigger a warning.
            // 2. The page structure has changed.
            // 3. It's not a file that can be downloaded this way (e.g. a native Google Doc).
            // We return the initialDownloadUrl, which might work for small files or lead to a page
            // that the user can interact with if opened in a browser.
            return initialDownloadUrl;
        }
    }

    private String extractFileId(String url) {
        Matcher matcher = FILE_ID_PATTERN_PATH.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = FILE_ID_PATTERN_QUERY.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
