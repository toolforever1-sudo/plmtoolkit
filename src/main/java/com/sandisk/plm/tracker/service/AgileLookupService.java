package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;

/**
 * Proxies Agile Lookup requests to the Java 8 microservice running on a separate port.
 */
@Service
public class AgileLookupService {

    private static final Logger logger = Logger.getLogger(AgileLookupService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${agile.service.url:http://localhost:8081}")
    private String agileServiceUrl;

    @Value("${agile.lookup.max-items:100}")
    private int maxItems;

    public int getMaxItems() {
        return maxItems;
    }

    public boolean isServiceAvailable() {
        String healthUrl = agileServiceUrl + "/api/lookup/health";
        logger.info("[AGILE PROXY] BUILD: v1.0.1-proxy | Checking health at: " + healthUrl);
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(healthUrl).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            String body = readStream(conn.getInputStream());
            conn.disconnect();
            logger.info("[AGILE PROXY] Health check response: " + code + " | " + body);
            return code == 200;
        } catch (Exception e) {
            logger.warning("[AGILE PROXY] Health check FAILED for " + healthUrl + " : " + e.getClass().getName() + ": " + e.getMessage());
            return false;
        }
    }

    public Map<String, Object> forwardLookup(byte[] fileBytes, String filename) throws Exception {
        return forwardLookup(fileBytes, filename, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> forwardLookup(byte[] fileBytes, String filename, Integer itemColumn) throws Exception {
        logger.info("[AGILE PROXY] Forwarding to " + agileServiceUrl + " (" + fileBytes.length
            + " bytes, file: " + filename + (itemColumn == null ? "" : ", itemColumn=" + itemColumn) + ")");
        long start = System.currentTimeMillis();

        String boundary = "----FormBoundary" + System.currentTimeMillis();
        URL url = new URL(agileServiceUrl + "/api/lookup/upload");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(600000); // 10 min for large lookups

        try (OutputStream out = conn.getOutputStream()) {
            out.write(("--" + boundary + "\r\n").getBytes());
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n").getBytes());
            out.write("Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n".getBytes());
            out.write(fileBytes);
            if (itemColumn != null) {
                // Add the itemColumn as a second form field so the companion service's
                // @RequestParam("itemColumn") binds it. Order doesn't matter for multipart.
                out.write(("\r\n--" + boundary + "\r\n").getBytes());
                out.write("Content-Disposition: form-data; name=\"itemColumn\"\r\n\r\n".getBytes());
                out.write(String.valueOf(itemColumn).getBytes());
            }
            out.write(("\r\n--" + boundary + "--\r\n").getBytes());
        }

        int responseCode = conn.getResponseCode();
        long elapsed = System.currentTimeMillis() - start;
        logger.info("[AGILE PROXY] Response: " + responseCode + " in " + elapsed + "ms");

        InputStream is = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readStream(is);
        conn.disconnect();

        if (responseCode != 200) {
            // Include the response body in the exception so the underlying SDK
            // error reaches the user instead of being discarded. Cap to ~300
            // chars so a long Java stack trace from the companion service
            // doesn't overflow the toast / cell where this surfaces.
            String snippet = body == null ? "" : body.trim();
            if (snippet.length() > 300) snippet = snippet.substring(0, 300) + "…";
            throw new RuntimeException("Agile service returned HTTP " + responseCode
                + (snippet.isEmpty() ? "" : ": " + snippet));
        }

        return mapper.readValue(body, Map.class);
    }

    private String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
