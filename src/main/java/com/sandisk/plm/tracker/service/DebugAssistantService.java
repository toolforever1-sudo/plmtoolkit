package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DebugAssistantService {

    private static final Logger logger = Logger.getLogger(DebugAssistantService.class.getName());

    @Value("${bitbucket.url:}")
    private String bitbucketUrl;

    @Value("${bitbucket.token:}")
    private String bitbucketToken;

    @Value("${bitbucket.project:PLM}")
    private String defaultProject;

    @Value("${ai.help.api-key:}")
    private String claudeApiKey;

    @Autowired
    private PortkeyClient portkeyClient;

    @Value("${portkey.api-key:}")
    private String portkeyApiKey;

    @Value("${portkey.provider:@anthropic-eastus2}")
    private String portkeyProvider;

    @Value("${portkey.model:claude-sonnet-4-6}")
    private String portkeyModel;

    @Value("${portkey.enabled:false}")
    private boolean portkeyEnabled;

    @Value("${portkey.base-url:https://api.portkey.ai/v1/chat/completions}")
    private String portkeyBaseUrl;

    // =========================================================================
    // 1. Parse stack traces from various inputs
    // =========================================================================

    public List<String> parseStackTraces(String text) {
        List<String> traces = new ArrayList<>();
        if (text == null || text.isEmpty()) return traces;

        // Line-by-line approach avoids catastrophic regex backtracking on large inputs
        String[] lines = text.split("\n");
        StringBuilder current = null;
        int blankCount = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            boolean isExceptionLine = trimmed.startsWith("Caused by:")
                || trimmed.startsWith("Root Cause")
                || ((trimmed.contains("Exception") || trimmed.contains("Error"))
                    && !trimmed.startsWith("at ")
                    && !trimmed.startsWith("Error at line") // log metadata, not an exception
                    && (trimmed.startsWith("java.") || trimmed.startsWith("javax.")
                        || trimmed.startsWith("com.") || trimmed.startsWith("org.")
                        || trimmed.contains(": ")));
            boolean isAtLine = trimmed.startsWith("at ");

            if (isExceptionLine) {
                if (current != null && current.length() > 20) traces.add(current.toString().trim());
                current = new StringBuilder(trimmed).append('\n');
                blankCount = 0;
            } else if (isAtLine) {
                if (current == null) {
                    // Standalone "at" block without a preceding exception — still capture it
                    current = new StringBuilder();
                }
                current.append(trimmed).append('\n');
                blankCount = 0;
            } else if (trimmed.isEmpty()) {
                // Allow up to 2 blank lines within a trace (common in logs)
                blankCount++;
                if (blankCount > 2 && current != null && current.length() > 20) {
                    traces.add(current.toString().trim());
                    current = null;
                }
            } else {
                // Non-exception, non-at, non-blank line — close current trace
                if (current != null && current.length() > 20) {
                    traces.add(current.toString().trim());
                }
                current = null;
                blankCount = 0;
            }
        }
        if (current != null && current.length() > 20) traces.add(current.toString().trim());

        // Merge all traces into one comprehensive block for better AI analysis
        if (traces.size() > 1) {
            StringBuilder merged = new StringBuilder();
            for (String t : traces) {
                merged.append(t).append('\n');
            }
            traces.add(0, merged.toString().trim());
        }

        return traces;
    }

    // =========================================================================
    // 2. Extract class + line references from stack trace
    // =========================================================================

    public static class SourceRef {
        public String fullClass;    // com.sandisk.agile.fio.extracts.ORCADPartInfoExtractCSV
        public String fileName;     // ORCADPartInfoExtractCSV.java
        public int lineNumber;      // 132
        public String packagePath;  // com/sandisk/agile/fio/extracts

        public SourceRef(String fullClass, String fileName, int lineNumber) {
            this.fullClass = fullClass;
            this.fileName = fileName;
            this.lineNumber = lineNumber;
            int lastDot = fullClass.lastIndexOf('.');
            this.packagePath = lastDot > 0 ? fullClass.substring(0, lastDot).replace('.', '/') : "";
        }
    }

    public List<SourceRef> extractSourceRefs(String stackTrace) {
        List<SourceRef> refs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // Match: at com.sandisk.xxx.ClassName.method(FileName.java:123)
        Pattern p = Pattern.compile("at\\s+(com\\.(?:sandisk|agile)\\.[\\w.]+)\\.\\w+\\((\\w+\\.java):(\\d+)\\)");
        Matcher m = p.matcher(stackTrace);
        while (m.find()) {
            String fullClass = m.group(1);
            String fileName = m.group(2);
            int line = Integer.parseInt(m.group(3));
            String key = fullClass + ":" + line;
            if (seen.add(key)) {
                refs.add(new SourceRef(fullClass, fileName, line));
            }
        }
        return refs;
    }

    // =========================================================================
    // 3. Bitbucket API — fetch source file content
    // =========================================================================

    public String fetchSourceFromBitbucket(String project, String repo, String filePath) {
        if (bitbucketUrl.isEmpty() || bitbucketToken.isEmpty()) return null;

        // Bitbucket REST API: GET /rest/api/1.0/projects/{proj}/repos/{repo}/browse/{path}
        String url = bitbucketUrl + "/rest/api/1.0/projects/" + project +
                     "/repos/" + repo + "/browse/" + filePath + "?limit=5000";

        try {
            String json = httpGet(url);
            if (json == null) return null;

            // Parse the JSON lines array
            StringBuilder sb = new StringBuilder();
            Pattern lineP = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]*)\"");
            Matcher lineM = lineP.matcher(json);
            while (lineM.find()) {
                sb.append(unescapeJson(lineM.group(1))).append('\n');
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            logger.warning("[DEBUG] Bitbucket fetch failed for " + project + "/" + repo + "/" + filePath + ": " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // 4. Bitbucket API — list repos in project
    // =========================================================================

    public List<String> listRepos(String project) {
        if (bitbucketUrl.isEmpty() || bitbucketToken.isEmpty()) return Collections.emptyList();

        String url = bitbucketUrl + "/rest/api/1.0/projects/" + project + "/repos?limit=200";
        try {
            String json = httpGet(url);
            if (json == null) return Collections.emptyList();

            List<String> repos = new ArrayList<>();
            Pattern p = Pattern.compile("\"slug\"\\s*:\\s*\"([^\"]+)\"");
            Matcher m = p.matcher(json);
            while (m.find()) repos.add(m.group(1));
            return repos;
        } catch (Exception e) {
            logger.warning("[DEBUG] Failed to list repos: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // 5. Search for source file across repos
    // =========================================================================

    public Map<String, String> findSourceAcrossRepos(String project, SourceRef ref) {
        List<String> repos = listRepos(project);
        // Try to guess repo from package name
        String pkg = ref.packagePath.toLowerCase();
        // Sort repos: ones whose name appears in the package come first
        repos.sort((a, b) -> {
            boolean aMatch = pkg.contains(a.toLowerCase().replace("-", ""));
            boolean bMatch = pkg.contains(b.toLowerCase().replace("-", ""));
            return Boolean.compare(bMatch, aMatch);
        });

        for (String repo : repos) {
            // Try common source paths
            String[] prefixes = {"src/", "src/main/java/", ""};
            for (String prefix : prefixes) {
                String path = prefix + ref.packagePath + "/" + ref.fileName;
                String content = fetchSourceFromBitbucket(project, repo, path);
                if (content != null && content.length() > 10) {
                    Map<String, String> result = new LinkedHashMap<>();
                    result.put("repo", repo);
                    result.put("path", path);
                    result.put("content", content);
                    return result;
                }
            }
        }
        return null;
    }

    // =========================================================================
    // 6. AI Analysis — send to Claude
    // =========================================================================

    public String analyzeWithAI(String stackTrace, Map<String, String> sourceFiles, String repoInfo) {
        boolean usePortkey = portkeyEnabled && portkeyApiKey != null && !portkeyApiKey.isEmpty();
        if (!usePortkey && (claudeApiKey == null || claudeApiKey.isEmpty())) {
            return "AI analysis unavailable — no API key configured.";
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a Java debugging expert for Agile PLM (Oracle) integrations at SanDisk/Western Digital.\n\n");
        prompt.append("## Stack Trace\n```\n").append(stackTrace).append("\n```\n\n");

        if (sourceFiles != null && !sourceFiles.isEmpty()) {
            prompt.append("## Source Code\n");
            for (Map.Entry<String, String> entry : sourceFiles.entrySet()) {
                prompt.append("### ").append(entry.getKey()).append("\n```java\n").append(entry.getValue()).append("\n```\n\n");
            }
        }

        if (repoInfo != null && !repoInfo.isEmpty()) {
            prompt.append("## Repository Info\n").append(repoInfo).append("\n\n");
        }

        prompt.append("## Instructions\n");
        prompt.append("1. Identify the root cause of this exception\n");
        prompt.append("2. Explain what the code was trying to do when it failed\n");
        prompt.append("3. Recommend a specific fix with code changes if applicable\n");
        prompt.append("4. If the source line numbers don't match the stack trace, note that the deployed code may be out of sync with the repository\n");
        prompt.append("5. Rate the severity: Critical / High / Medium / Low\n");
        prompt.append("6. Format your response with clear sections using markdown\n");

        try {
            String model = portkeyProvider + "/" + portkeyModel;
            return portkeyClient.chat(model, null, prompt.toString(), 2000);
        } catch (Exception e) {
            logger.warning("[DEBUG-AI] Analysis failed: " + e.getMessage());
            return "AI analysis failed: " + e.getMessage();
        }
    }

    /** Extract content from Anthropic native response: {"content":[{"text":"..."}]} */
    private String extractAnthropicContent(String response) {
        int textIdx = response.indexOf("\"text\"");
        if (textIdx < 0) return "AI analysis returned an unexpected format.";
        int colonIdx = response.indexOf(":", textIdx + 5);
        if (colonIdx < 0) return "AI analysis returned an unexpected format.";
        int startQuote = response.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return "AI analysis returned an unexpected format.";
        int endQuote = startQuote + 1;
        while (endQuote < response.length()) {
            char c = response.charAt(endQuote);
            if (c == '\\') { endQuote += 2; continue; }
            if (c == '"') break;
            endQuote++;
        }
        if (endQuote <= startQuote + 1) return "AI analysis returned an unexpected format.";
        return unescapeJson(response.substring(startQuote + 1, endQuote));
    }

    /** Extract content from OpenAI-compatible response: {"choices":[{"message":{"content":"..."}}]} */
    private String extractOpenAIContent(String response) {
        // Find "content" inside "message" inside "choices"
        int msgIdx = response.indexOf("\"message\"");
        if (msgIdx < 0) return extractAnthropicContent(response); // fallback
        int contentIdx = response.indexOf("\"content\"", msgIdx);
        if (contentIdx < 0) return "AI analysis returned an unexpected format (OpenAI).";
        int colonIdx = response.indexOf(":", contentIdx + 9);
        if (colonIdx < 0) return "AI analysis returned an unexpected format (OpenAI).";
        int startQuote = response.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return "AI analysis returned an unexpected format (OpenAI).";
        int endQuote = startQuote + 1;
        while (endQuote < response.length()) {
            char c = response.charAt(endQuote);
            if (c == '\\') { endQuote += 2; continue; }
            if (c == '"') break;
            endQuote++;
        }
        if (endQuote <= startQuote + 1) return "AI analysis returned an unexpected format (OpenAI).";
        return unescapeJson(response.substring(startQuote + 1, endQuote));
    }

    // =========================================================================
    // 7. Parse .eml file
    // =========================================================================

    public String parseEmlFile(byte[] emlData) {
        // Use javax.mail to parse .eml properly — avoids regex on large MIME content
        try {
            javax.mail.Session mailSession = javax.mail.Session.getDefaultInstance(new java.util.Properties());
            javax.mail.internet.MimeMessage msg = new javax.mail.internet.MimeMessage(mailSession,
                    new ByteArrayInputStream(emlData));

            StringBuilder text = new StringBuilder();
            parseMimePart(msg, text);
            return text.toString();
        } catch (Exception e) {
            logger.warning("[DEBUG] javax.mail parse failed, falling back to text extraction: " + e.getMessage());
            // Fallback: just treat as raw text
            return stripHtml(new String(emlData, StandardCharsets.UTF_8)
                    .replace("=\r\n", "").replace("=\n", "").replace("=3D", "="));
        }
    }

    private void parseMimePart(javax.mail.Part part, StringBuilder text) throws Exception {
        String contentType = part.getContentType().toLowerCase();

        if (part.isMimeType("multipart/*")) {
            javax.mail.Multipart mp = (javax.mail.Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                parseMimePart(mp.getBodyPart(i), text);
            }
        } else if (part.isMimeType("text/plain")) {
            String body = (String) part.getContent();
            text.append(body).append('\n');
        } else if (part.isMimeType("text/html")) {
            String html = (String) part.getContent();
            // Strip HTML tags
            String plain = stripHtml(html);
            text.append(plain).append('\n');
        } else {
            // Check for .log attachments
            String filename = part.getFileName();
            if (filename != null && filename.toLowerCase().endsWith(".log")) {
                try (InputStream is = part.getInputStream()) {
                    byte[] data = is.readAllBytes();
                    text.append("\n--- Attachment: ").append(filename).append(" ---\n");
                    text.append(new String(data, StandardCharsets.UTF_8));
                }
            }
        }
    }

    // =========================================================================
    // 8. Extract .java sources from a JAR (ZIP) archive
    // =========================================================================

    /**
     * Scans a JAR for {@code .java} source entries.
     * @return map of entry-path → source content (empty if none found)
     */
    public Map<String, String> extractJavaSources(byte[] jarBytes) {
        Map<String, String> sources = new LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new ByteArrayInputStream(jarBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".java")) {
                    byte[] data = zis.readAllBytes();
                    sources.put(entry.getName(), new String(data, StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            logger.warning("[DEBUG] Failed to read JAR: " + e.getMessage());
        }
        return sources;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String httpGet(String urlStr) {
        try {
            // Trust all certs for internal Bitbucket
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }}, new java.security.SecureRandom());

            HttpsURLConnection conn = (HttpsURLConnection) new URL(urlStr).openConnection();
            conn.setSSLSocketFactory(sc.getSocketFactory());
            conn.setHostnameVerifier((h, s) -> true);
            conn.setRequestProperty("Authorization", "Bearer " + bitbucketToken);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            int status = conn.getResponseCode();
            if (status == 404) return null;
            if (status >= 400) {
                logger.warning("[BITBUCKET] HTTP " + status + " for " + urlStr);
                return null;
            }

            return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warning("[BITBUCKET] Request failed: " + e.getMessage());
            return null;
        }
    }

    private String escapeJsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    /** Strip HTML tags without regex — safe on any input size */
    private static String stripHtml(String html) {
        StringBuilder sb = new StringBuilder(html.length());
        boolean inTag = false;
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '<') { inTag = true; continue; }
            if (c == '>') { inTag = false; sb.append(' '); continue; }
            if (!inTag) sb.append(c);
        }
        return sb.toString();
    }

    private String unescapeJson(String s) {
        return s.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                .replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
