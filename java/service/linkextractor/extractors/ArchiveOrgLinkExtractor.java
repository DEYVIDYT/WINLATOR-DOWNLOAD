package com.winlator.Download.service.linkextractor.extractors;

import com.winlator.Download.service.linkextractor.HttpUtils;
import com.winlator.Download.service.linkextractor.LinkExtractor;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

public class ArchiveOrgLinkExtractor implements LinkExtractor {

    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";

    // Pattern to extract the item identifier from a details URL, e.g. https://archive.org/details/IDENTIFIER
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("archive\\.org/details/([^/?#]+)");

    // Pattern to find download links on a details page.
    // Looks for links like:
    // href="//archive.org/download/IDENTIFIER/FILENAME.EXT"
    // href="https://archive.org/download/IDENTIFIER/FILENAME.EXT"
    // href="/download/IDENTIFIER/FILENAME.EXT"
    // Group 1: Full link prefix (optional scheme, optional host)
    // Group 2: (https?:)?//archive.org - captures optional scheme and host for archive.org links
    // Group 3: Identifier from the download link
    // Group 4: Filename
    private static final Pattern DOWNLOAD_LINK_PATTERN = Pattern.compile(
        "href\\s*=\\s*\"((https?:)?//archive\\.org)?/download/([^/\"?#]+)/([^\"?#]+\\.[a-zA-Z0-9_\\-]+[a-zA-Z0-9]{1,5})\"", Pattern.CASE_INSENSITIVE);


    @Override
    public String extractDirectLink(String url) throws IOException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            System.err.println("ArchiveOrgLinkExtractor: Invalid URL syntax " + url + " - " + e.getMessage());
            return null; // Invalid URL
        }
        String host = uri.getHost().toLowerCase();
        String path = uri.getPath();

        // If it's already a download link from a known archive download server or path structure
        // Example: https://dn721601.ca.archive.org/0/0/winlator_v1.zip
        // Example: https://archive.org/download/Winlator_v1.0/Winlator_v1.zip
        if ((host.endsWith("archive.org") && path.startsWith("/download/")) || host.equals("dn721601.ca.archive.org")) {
            // Check if it seems to point to a file directly using a regex for common file extensions
            if (path.matches(".*/[^/]+\\.[a-zA-Z0-9_\\-]+[a-zA-Z0-9]{1,5}$")) {
                 // It already looks like a direct file link
                return url;
            }
        }

        // If it's a details page, we need to parse it
        // Example: https://archive.org/details/Winlator_v1.0
        if (host.equals("archive.org") && path.startsWith("/details/")) {
            String pageIdentifier = extractIdentifier(url);
            if (pageIdentifier == null) {
                System.err.println("ArchiveOrgLinkExtractor: Could not find identifier in details URL " + url);
                return null;
            }

            String htmlContent = HttpUtils.fetchHtml(url, DEFAULT_USER_AGENT);
            Matcher matcher = DOWNLOAD_LINK_PATTERN.matcher(htmlContent);

            List<String> potentialLinks = new ArrayList<>();
            while (matcher.find()) {
                String linkIdentifier = matcher.group(3); // Identifier found in the download link
                if (!pageIdentifier.equalsIgnoreCase(linkIdentifier)) {
                    // This download link is for a different item (e.g., related items), skip.
                    continue;
                }

                String linkHref = matcher.group(1); // The start of the href attribute value
                String linkPathPart = "/download/" + matcher.group(3) + "/" + matcher.group(4);

                String fullLink;
                if (linkHref == null || linkHref.isEmpty() || linkHref.startsWith("/")) { // Relative link like /download/...
                    fullLink = "https://archive.org" + linkPathPart;
                } else if (linkHref.startsWith("//")) { // Scheme-relative link like //archive.org/download/...
                    fullLink = "https:" + linkHref; // Assume https
                } else { // Absolute link like https://archive.org/download/...
                    fullLink = linkHref;
                }
                potentialLinks.add(fullLink);
            }

            if (!potentialLinks.isEmpty()) {
                // Simple strategy: return the first one found that matches the item's identifier.
                // More advanced: prioritize based on file type (e.g. .zip, .obb over .xml, .txt)
                // For now, let's prefer common archive/image types.
                for (String potentialLink : potentialLinks) {
                    if (potentialLink.matches(".*\\.(zip|rar|7z|obb|apk|exe|iso|img|tar\\.gz|tar\\.bz2|tar\\.xz)$")) {
                        return potentialLink;
                    }
                }
                // If no preferred types found, return the first link from the list.
                return potentialLinks.get(0);
            }
        }

        System.err.println("ArchiveOrgLinkExtractor: No direct link found for " + url);
        return null;
    }

    private String extractIdentifier(String url) {
        Matcher matcher = IDENTIFIER_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
