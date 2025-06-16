package com.winlator.Download.service.linkextractor;

import java.io.IOException;

public interface LinkExtractor {
    /**
     * Attempts to extract a direct download link from the given URL.
     * @param url The URL of the download page (e.g., a MediaFire page).
     * @return The direct download link, or null if it cannot be extracted.
     * @throws IOException If an error occurs during network operations (e.g., fetching the page).
     */
    String extractDirectLink(String url) throws IOException;
}
