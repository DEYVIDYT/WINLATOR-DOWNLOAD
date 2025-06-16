package com.winlator.Download.service.linkextractor.extractors;

import com.winlator.Download.service.linkextractor.HttpUtils;
import com.winlator.Download.service.linkextractor.LinkExtractor;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MediafireLinkExtractor implements LinkExtractor {

    // User agent will be passed from DirectLinkService or a shared constant
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";

    // Regex to find the download button's link.
    // This looks for an href attribute starting with http(s)://download...mediafire.com/...
    private static final Pattern DIRECT_LINK_PATTERN = Pattern.compile(
        "href\\s*=\\s*\"(https?://download[^\"]*mediafire\\.com/[^\"]+)\"", Pattern.CASE_INSENSITIVE);
    // A more specific pattern targeting the download button ID could be:
    // Pattern.compile("<a[^>]+id\\s*=\\s*\"downloadButton\"[^>]+href\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);


    @Override
    public String extractDirectLink(String url) throws IOException {
        String htmlContent = HttpUtils.fetchHtml(url, DEFAULT_USER_AGENT);

        Matcher matcher = DIRECT_LINK_PATTERN.matcher(htmlContent);

        if (matcher.find()) {
            String directLink = matcher.group(1);
            // MediaFire links sometimes have escaped ampersands, replace them.
            return directLink.replace("&amp;", "&");
        }

        // If no link is found, returning null indicates failure.
        return null;
    }
}
