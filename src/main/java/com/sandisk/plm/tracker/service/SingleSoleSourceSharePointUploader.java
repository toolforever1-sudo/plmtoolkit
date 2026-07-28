package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Microsoft Graph upload — ROPC (resource-owner-password-credential) flow,
 * ported verbatim from the legacy SSReport.java. Disabled when
 * {@code app.singlesole.sharepoint.enabled=false} (default).
 */
@Service
public class SingleSoleSourceSharePointUploader {

    private static final Logger logger = Logger.getLogger(SingleSoleSourceSharePointUploader.class.getName());

    @Value("${app.singlesole.sharepoint.enabled:false}") private boolean enabled;
    @Value("${app.singlesole.sharepoint.drive.id:}")     private String driveId;
    @Value("${app.singlesole.sharepoint.folder:}")       private String folder;
    @Value("${app.singlesole.graph.client.id:}")         private String clientId;
    @Value("${app.singlesole.graph.client.secret:}")     private String clientSecret;
    @Value("${app.singlesole.graph.username:}")          private String username;
    @Value("${app.singlesole.graph.password:}")          private String password;
    @Value("${app.singlesole.graph.scope:https://graph.microsoft.com/.default}") private String scope;
    @Value("${app.singlesole.graph.authority:}")         private String authority;

    /** Returns the SharePoint URL of the uploaded file, or null if disabled. */
    public String upload(File file) throws Exception {
        if (!enabled) {
            logger.info("[SS-REPORT] SharePoint upload disabled — skipping");
            return null;
        }
        String token = getRopcToken();
        String clean = stripSlashes(folder);
        String url = "https://graph.microsoft.com/v1.0/drives/" + driveId
                + "/root:/" + encodePath(clean) + "/" + encodePath(file.getName())
                + ":/content";
        logger.info("[SS-REPORT] PUT " + url);
        return httpPut(url, token, file);
    }

    private String getRopcToken() throws Exception {
        String body = "grant_type=password"
                + "&client_id=" + enc(clientId)
                + "&username=" + enc(username)
                + "&password=" + enc(password)
                + "&scope=" + enc(scope)
                + "&client_secret=" + enc(clientSecret);
        HttpURLConnection con = (HttpURLConnection) new URL(authority).openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        con.setConnectTimeout(60000);
        con.setReadTimeout(60000);
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = con.getResponseCode();
        String resp = readAll(code >= 200 && code < 300 ? con.getInputStream() : con.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new RuntimeException("Graph token HTTP " + code + " :: " + resp);
        }
        String tok = jsonString(resp, "access_token");
        if (tok == null || tok.trim().isEmpty()) {
            throw new RuntimeException("No access_token in: " + resp);
        }
        return tok;
    }

    private String httpPut(String url, String token, File file) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("PUT");
        con.setDoOutput(true);
        con.setRequestProperty("Authorization", "Bearer " + token);
        con.setRequestProperty("Content-Type", "application/octet-stream");
        con.setConnectTimeout(120000);
        con.setReadTimeout(120000);
        try (OutputStream os = con.getOutputStream();
             InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) >= 0) os.write(buf, 0, n);
        }
        int code = con.getResponseCode();
        String resp = readAll(code >= 200 && code < 300 ? con.getInputStream() : con.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new RuntimeException("Upload HTTP " + code + " :: " + resp);
        }
        return jsonString(resp, "webUrl");
    }

    private static String stripSlashes(String s) {
        if (s == null) return "";
        if (s.startsWith("/")) s = s.substring(1);
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String encodePath(String path) throws UnsupportedEncodingException {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String enc(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static String jsonString(String json, String key) {
        if (json == null) return null;
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + pat.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }
}
