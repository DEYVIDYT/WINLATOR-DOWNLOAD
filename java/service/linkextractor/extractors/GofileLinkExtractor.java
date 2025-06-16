package com.winlator.Download.service.linkextractor.extractors;

import com.winlator.Download.service.linkextractor.LinkExtractor;
import java.io.IOException;

public class GofileLinkExtractor implements LinkExtractor {
    @Override
    public String extractDirectLink(String url) throws IOException {
        // Placeholder: Direct extraction logic for gofile.io is not yet implemented.
        System.err.println("GofileLinkExtractor: Not yet implemented for URL: " + url);
        return null;
    }
}
