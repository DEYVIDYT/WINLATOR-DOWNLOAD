package com.winlator.Download.service.linkextractor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class HttpUtils {
    /**
     * Fetches the HTML content of a given URL.
     * @param urlString The URL to fetch.
     * @param userAgent The User-Agent string to use for the request.
     * @return The HTML content as a String.
     * @throws IOException If an error occurs during the network request.
     */
    public static String fetchHtml(String urlString, String userAgent) throws IOException {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        StringBuilder response = new StringBuilder();

        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000); // 15 seconds
            connection.setReadTimeout(15000);    // 15 seconds
            if (userAgent != null && !userAgent.isEmpty()) {
                connection.setRequestProperty("User-Agent", userAgent);
            }
            // Add other headers like Accept-Language if necessary, e.g.
            // connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");


            int statusCode = connection.getResponseCode();
            if (statusCode >= 200 && statusCode < 300) {
                reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            } else {
                // Consider reading the error stream for more details if needed
                throw new IOException("HTTP Error: " + statusCode + " " + connection.getResponseMessage());
            }
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // Log error or ignore
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
        return response.toString();
    }
}
