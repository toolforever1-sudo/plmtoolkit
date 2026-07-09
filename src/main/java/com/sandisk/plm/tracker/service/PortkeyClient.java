package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Single Vortex/Portkey chat helper. Replaces the 7 copies of the same
 * HttpURLConnection + JSON-build + parse code that used to live in each
 * AI-using controller and service. OpenAI-compatible chat completions API.
 *
 * The model string MUST be the full Portkey/Vortex slug, e.g.
 *   "@anthropic-eastus2/claude-sonnet-4-6"
 *   "@openai-eastus2/gpt-4o"
 *   "@vertexai-global/gemini-2.5-pro"
 */
@Service
public class PortkeyClient {

    private static final Logger logger = Logger.getLogger(PortkeyClient.class.getName());

    @Value("${portkey.api-key:}")
    private String apiKey;

    @Value("${portkey.base-url:https://api.portkey.ai/v1/chat/completions}")
    private String baseUrl;

    @Value("${portkey.enabled:false}")
    private boolean enabled;

    /** True when the gateway is configured and ready to call. */
    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isEmpty();
    }

    /**
     * Convenience: single-user-message chat. Returns the assistant's plain text content.
     * Throws on transport error or non-200 response.
     */
    public String chat(String model, String systemPrompt, String userMessage, int maxTokens) throws Exception {
        Map<String, String> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        return chatWithHistory(model, systemPrompt, Collections.singletonList(userMsg), maxTokens);
    }

    /**
     * Full conversation chat. `messages` is a list of {role, content} maps in chronological
     * order. The system prompt is prepended automatically. Returns the assistant's text content.
     */
    public String chatWithHistory(String model, String systemPrompt, List<Map<String, String>> messages, int maxTokens) throws Exception {
        if (!isEnabled()) {
            throw new IllegalStateException("PortkeyClient called but portkey.enabled=false or api-key missing");
        }

        // Azure OpenAI's newer chat models reject `max_tokens` and require
        // `max_completion_tokens` instead. Anthropic + Vertex AI both still
        // accept `max_tokens`. Pick by provider slug prefix.
        String tokenParamName = model != null && model.contains("openai")
            ? "max_completion_tokens" : "max_tokens";
        // Omit the system block entirely when no system prompt is provided.
        // Azure-AI's content-safety pipeline rejects empty system blocks with
        // "azure-ai error: system: text content blocks must be non-empty",
        // so an empty string is NOT equivalent to "no system message".
        boolean hasSystem = systemPrompt != null && !systemPrompt.isEmpty();
        StringBuilder body = new StringBuilder("{");
        body.append("\"model\":\"").append(esc(model)).append("\",");
        body.append("\"").append(tokenParamName).append("\":").append(maxTokens).append(",");
        body.append("\"messages\":[");
        boolean firstMsg = true;
        if (hasSystem) {
            body.append("{\"role\":\"system\",\"content\":\"").append(esc(systemPrompt)).append("\"}");
            firstMsg = false;
        }
        for (Map<String, String> m : messages) {
            if (!firstMsg) body.append(",");
            body.append("{\"role\":\"").append(esc(m.getOrDefault("role", "user")))
                .append("\",\"content\":\"").append(esc(m.getOrDefault("content", ""))).append("\"}");
            firstMsg = false;
        }
        body.append("]}");
        String requestBody = body.toString();

        // One retry on transient transport errors only — EOF from server, broken
        // socket, read timeout. Non-2xx HTTP responses (including 429 rate-limits)
        // are NOT retried — retrying a 429 makes the problem worse. Each attempt
        // gets its own fresh connection because HttpURLConnection isn't reusable
        // after a failure.
        Response r = null;
        Exception lastTransient = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                r = executeChatOnce(model, requestBody);
                break;
            } catch (Exception e) {
                if (attempt < 2 && isTransientTransportError(e)) {
                    lastTransient = e;
                    logger.warning("[PORTKEY] " + model + " transient error on attempt " + attempt
                        + " (" + classifyTransientReason(e) + "), retrying once: " + e.getMessage());
                    try { Thread.sleep(1500); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
        if (r == null) {
            // Defensive — the loop above always either assigns r or throws.
            throw new RuntimeException("Portkey call failed: " + (lastTransient == null ? "unknown" : lastTransient.getMessage()));
        }
        int status = r.status;
        String resp = r.body;
        long ms = r.ms;

        // Accept any 2xx as success. The Portkey/Azure-AI gateway occasionally
        // returns non-standard 2xx codes (e.g. 246) for fully valid chat-completion
        // bodies; treating those as errors burned a team-report run on 2026-05-11
        // where AME's "Top Themes" cell rendered the raw envelope JSON.
        if (status < 200 || status >= 300) {
            logger.warning("[PORTKEY] " + model + " status=" + status + " ms=" + ms + " body=" + resp);
            throw new RuntimeException("Portkey/Vortex returned " + status + ": " + truncate(resp, 200));
        }

        // OpenAI-compatible response: {"choices":[{"message":{"content":"..."}}]}
        int msgIdx = resp.indexOf("\"message\"");
        int contentIdx = msgIdx >= 0 ? resp.indexOf("\"content\":", msgIdx) : -1;
        if (contentIdx < 0) {
            throw new RuntimeException("Unexpected response format: " + truncate(resp, 200));
        }
        int valStart = resp.indexOf('"', contentIdx + 10) + 1;
        StringBuilder text = new StringBuilder();
        boolean escaped = false;
        for (int i = valStart; i < resp.length(); i++) {
            char c = resp.charAt(i);
            if (escaped) {
                if (c == 'n') text.append('\n');
                else if (c == 't') text.append('\t');
                else if (c == '"') text.append('"');
                else if (c == '\\') text.append('\\');
                else if (c == 'u' && i + 4 < resp.length()) {
                    text.append((char) Integer.parseInt(resp.substring(i + 1, i + 5), 16));
                    i += 4;
                } else text.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                text.append(c);
            }
        }
        logger.info("[PORTKEY] " + model + " ok ms=" + ms);
        return text.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }

    /** Single Portkey HTTP round-trip. Returns status + body + elapsed ms. */
    private Response executeChatOnce(String model, String requestBody) throws IOException {
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-portkey-api-key", apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String resp = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        long ms = System.currentTimeMillis() - t0;
        Response r = new Response();
        r.status = status; r.body = resp; r.ms = ms;
        return r;
    }

    private static class Response { int status; String body; long ms; }

    /**
     * True iff the exception is the kind we should retry — a TCP/TLS/HTTP transport
     * hiccup, not a programming error or a real server-side rejection. Anything
     * that produced an HTTP status code (4xx / 5xx) is NOT transient by this
     * definition; those are surfaced to the caller as before.
     */
    private static boolean isTransientTransportError(Exception e) {
        if (e instanceof EOFException) return true;
        if (e instanceof SocketTimeoutException) return true;
        if (e instanceof SocketException) return true;
        // sun.net.www.protocol.http.HttpURLConnection wraps the underlying EOF
        // in a plain IOException whose message is "Unexpected end of file from
        // server" — that's exactly the case that burned Noraida's team report.
        if (e instanceof IOException) {
            String msg = e.getMessage();
            if (msg != null) {
                String low = msg.toLowerCase();
                if (low.contains("unexpected end of file")) return true;
                if (low.contains("connection reset")) return true;
                if (low.contains("premature")) return true;
                if (low.contains("broken pipe")) return true;
            }
        }
        return false;
    }

    private static String classifyTransientReason(Exception e) {
        if (e instanceof EOFException) return "EOF";
        if (e instanceof SocketTimeoutException) return "socket-timeout";
        if (e instanceof SocketException) return "socket-error";
        return "transient-io";
    }
}
