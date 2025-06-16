package com.winlator.Download.service.linkextractor;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import com.winlator.Download.service.linkextractor.extractors.MediafireLinkExtractor;
import com.winlator.Download.service.linkextractor.extractors.GoogleDriveLinkExtractor;
import com.winlator.Download.service.linkextractor.extractors.ArchiveOrgLinkExtractor;
import com.winlator.Download.service.linkextractor.extractors.BuzzheavierLinkExtractor;
import com.winlator.Download.service.linkextractor.extractors.GofileLinkExtractor;
import com.winlator.Download.service.linkextractor.extractors.DatanodesToLinkExtractor;
import com.winlator.Download.service.linkextractor.extractors.PixeldrainLinkExtractor;

public class DirectLinkService {

    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
    private final Map<String, LinkExtractor> extractors;

    public DirectLinkService() {
        extractors = new HashMap<>();
        extractors.put("www.mediafire.com", new MediafireLinkExtractor());
        extractors.put("drive.google.com", new GoogleDriveLinkExtractor());
        extractors.put("docs.google.com", new GoogleDriveLinkExtractor()); // For URLs like docs.google.com/uc?...
        extractors.put("archive.org", new ArchiveOrgLinkExtractor());
        extractors.put("dn721601.ca.archive.org", new ArchiveOrgLinkExtractor());
        extractors.put("buzzheavier.com", new BuzzheavierLinkExtractor());
        extractors.put("gofile.io", new GofileLinkExtractor());
        extractors.put("datanodes.to", new DatanodesToLinkExtractor());
        extractors.put("pixeldrain.com", new PixeldrainLinkExtractor());
        // In future steps, we will populate this map:
        // ... etc.
    }

    /**
     * Registers a LinkExtractor for a specific host.
     * @param host The host (e.g., "www.mediafire.com").
     * @param extractor The LinkExtractor implementation.
     */
    public void registerExtractor(String host, LinkExtractor extractor) {
        extractors.put(host.toLowerCase(), extractor);
    }

    /**
     * Attempts to get a direct download link for the given original URL.
     * @param originalUrl The URL from the file hosting service.
     * @return The direct download link, or the original URL if no suitable extractor is found or an error occurs.
     */
    public String getDirectLink(String originalUrl) {
        try {
            URI uri = new URI(originalUrl);
            String host = uri.getHost();

            if (host != null) {
                host = host.toLowerCase();
                LinkExtractor extractor = extractors.get(host);
                if (extractor != null) {
                    // Each extractor will call HttpUtils.fetchHtml internally
                    return extractor.extractDirectLink(originalUrl);
                }
            }
        } catch (URISyntaxException e) {
            // Log error: Invalid URL syntax
            System.err.println("Invalid URL syntax: " + originalUrl + " - " + e.getMessage());
            return originalUrl; // Or throw an exception / return null
        } catch (IOException e) {
            // Log error: Network error during extraction
            System.err.println("Network error extracting link for: " + originalUrl + " - " + e.getMessage());
            return originalUrl; // Or throw an exception / return null
        } catch (Exception e) {
            // Catch any other unexpected errors from extractors
            System.err.println("Unexpected error extracting link for: " + originalUrl + " - " + e.getMessage());
            return originalUrl; // Or throw an exception / return null
        }

        // If no extractor is found or an error occurred, return the original URL
        // Or, could return null to indicate failure more explicitly
        return originalUrl;
    }

    // The HttpUtils.fetchHtml method is now in its own class.
    // Specific extractors will use HttpUtils.fetchHtml(url, DEFAULT_USER_AGENT).
}
