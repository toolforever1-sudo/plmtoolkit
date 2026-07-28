# AI Eval Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the admin-only AI Eval tab per spec `docs/superpowers/specs/2026-05-03-ai-eval-tab-design.md` (commit `43b9b82`) — a POC that auto-generates persona-driven questions, fires them at the AI Help chatbot, grades each answer A–F via a different evaluator model, and exports failure-only markdown briefs for Claude in VS Code.

**Architecture:** New Spring Boot controller + service + cache file pair, fronted by a single new tab inlined into `index.html`. Backend uses SSE for live progress streaming. Three Vortex provider routes (`@anthropic-eastus2/claude-sonnet-4-6`, `@openai-eastus2/gpt-4o`, `@vertexai-global/gemini-2.5-pro`). Persistence is one append-only JSON file at `./cache/ai-eval-runs.json`. A targeted refactor extracts the duplicated Vortex call code from 7 existing service classes into one `PortkeyClient` helper.

**Tech Stack:** Java 11, Spring Boot 2.7.18, Jackson (already on classpath), `HttpURLConnection` (existing pattern in this repo), `SseEmitter` (already used by `DebugAssistantController`), vanilla JS + `EventSource` on the frontend, no new Maven dependencies.

---

## File Structure

### New backend files
| Path | Responsibility |
|---|---|
| `src/main/java/com/sandisk/plm/tracker/service/PortkeyClient.java` | Single Vortex/Portkey HTTP helper. `chat()` and `chatWithHistory()` overloads. |
| `src/main/java/com/sandisk/plm/tracker/model/AiEvalRun.java` | Plain Jackson POJOs: `AiEvalRun`, `Persona`, `RunConfig`, `Result`, `Summary`. |
| `src/main/java/com/sandisk/plm/tracker/service/AiEvalService.java` | Orchestrator. Loads cache, runs the generate→answer→grade loop, persists, emits SSE events, exports markdown. |
| `src/main/java/com/sandisk/plm/tracker/controller/AiEvalController.java` | HTTP endpoints (POST `/runs`, GET `/runs`, GET `/runs/{id}`, GET `/runs/{id}/stream`, GET `/runs/{id}/results`, POST `/runs/{id}/export`). Admin-gated. |

### New frontend files
| Path | Responsibility |
|---|---|
| `src/main/resources/static/ai-eval.js` | Page controller. Form submit, EventSource handling, render of all 3 sections, Rerun + Export actions. |

### Modified files
| Path | What changes |
|---|---|
| `src/main/resources/static/index.html` | Add tab button + panel markup (Sections A/B/C). Add `<script src="ai-eval.js"></script>`. |
| `src/main/resources/static/app.js` | Add panel toggle in `switchTab()` + entry in `TAB_CONFIG` (admin-only). |
| `src/main/resources/static/whats-new.js` | Prepend a release entry. |
| `.gitignore` | Add `debug-output/` (and ensure `cache/` is already covered). |
| `src/main/java/com/sandisk/plm/tracker/controller/AiHelpController.java` | Replace inline Portkey HTTP code with `PortkeyClient.chatWithHistory(...)`. |
| `src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerEmailService.java` | Replace with `PortkeyClient.chat(...)`. |
| `src/main/java/com/sandisk/plm/tracker/service/ReportService.java` | Same. |
| `src/main/java/com/sandisk/plm/tracker/service/MonitorAnalysisService.java` | Same. |
| `src/main/java/com/sandisk/plm/tracker/service/WhatsNewDigestService.java` | Same. |
| `src/main/java/com/sandisk/plm/tracker/service/DeltaReportService.java` | Same. |
| `src/main/java/com/sandisk/plm/tracker/service/DebugAssistantService.java` | Same. |

### New test file
| Path | Responsibility |
|---|---|
| `src/test/java/com/sandisk/plm/tracker/service/AiEvalRunJsonTest.java` | JUnit 5 test: serialize a sample `AiEvalRun` to JSON via Jackson, deserialize back, assert equality across all fields. |

---

## Build/run conventions

All build, deploy, and test invocations follow `CLAUDE.md`:

- Build: `cd ~/git/plm-field-tracker && mvn -q -DskipTests package`
  *(skip tests during dev iteration; the one new JUnit test runs as part of the smoke task at the end)*
- Local deploy: `cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar`
- Restart local: kill the existing PID listening on 8090, then `cd ~/Documents/plm-toolkit\ 2 && nohup java -Xmx2g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties > /tmp/plm-toolkit-local.log 2>&1 &`
- Local URL: `http://localhost:8090`
- Test creds: `plmadmin` / `newworld`
- Auth pattern: `curl -sS -c /tmp/cookies.txt -H "Content-Type: application/json" -d '{"username":"plmadmin","password":"newworld"}' http://localhost:8090/api/auth/login`

---

## Tasks

### Task 1: Create `PortkeyClient` helper

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/PortkeyClient.java`

- [ ] **Step 1: Create the file with the full class body**

```java
package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
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

        StringBuilder body = new StringBuilder("{");
        body.append("\"model\":\"").append(esc(model)).append("\",");
        body.append("\"max_tokens\":").append(maxTokens).append(",");
        body.append("\"messages\":[");
        body.append("{\"role\":\"system\",\"content\":\"").append(esc(systemPrompt)).append("\"}");
        for (Map<String, String> m : messages) {
            body.append(",{\"role\":\"").append(esc(m.getOrDefault("role", "user")))
                .append("\",\"content\":\"").append(esc(m.getOrDefault("content", ""))).append("\"}");
        }
        body.append("]}");

        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-portkey-api-key", apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String resp = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        long ms = System.currentTimeMillis() - t0;

        if (status != 200) {
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
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `cd ~/git/plm-field-tracker && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS, no errors. Spring autowires the new bean automatically.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/PortkeyClient.java
git commit -m "feat: add PortkeyClient — single helper for all Vortex calls

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Refactor `AiHelpController` to use `PortkeyClient`

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/AiHelpController.java`

`AiHelpController.callHaiku(...)` (line ~1454) and the second `[AI HELP/SQL]` Portkey caller (line ~2889) currently inline the Portkey HTTP code with a fallback to the direct Anthropic API. We're now fully on Vortex (per the prior session's cutover), so the Anthropic fallback is dead — replace both call sites with `PortkeyClient`.

- [ ] **Step 1: Inject `PortkeyClient`**

Find the `@Autowired` block near line 54 and add (alongside the existing autowires):

```java
@Autowired
private com.sandisk.plm.tracker.service.PortkeyClient portkeyClient;
```

- [ ] **Step 2: Replace the first Portkey call site in `callHaiku`**

Find the block starting `boolean usePortkey = portkeyEnabled && portkeyApiKey != null && ...` (line ~1485). Replace from that line through the end of the response-parsing block (line ~1565 — the line just before the function returns the parsed text) with:

```java
String model = portkeyProvider + "/" + portkeyModel;
logger.info("[AI HELP] Using Portkey gateway (" + model + ") via " + portkeyBaseUrl);

// Build messages list (system prompt is added inside PortkeyClient)
java.util.List<java.util.Map<String, String>> chatMessages = new java.util.ArrayList<>();
int startIdx = Math.max(0, history.size() - 8);
for (int i = startIdx; i < history.size(); i++) {
    java.util.Map<String, String> msg = history.get(i);
    java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
    m.put("role", msg.getOrDefault("role", "user"));
    m.put("content", msg.getOrDefault("content", ""));
    chatMessages.add(m);
}
java.util.Map<String, String> userMsg = new java.util.LinkedHashMap<>();
userMsg.put("role", "user");
userMsg.put("content", question);
chatMessages.add(userMsg);

return portkeyClient.chatWithHistory(model, systemPrompt, chatMessages, 300);
```

Keep the function's existing signature and the surrounding try/catch.

- [ ] **Step 3: Replace the second Portkey call site (`callHaikuForSql`, line ~2889)**

Find the analogous Portkey block in the SQL helper. Replace it with:

```java
String model = portkeyProvider + "/" + portkeyModel;
logger.info("[AI HELP/SQL] Using Portkey gateway (" + model + ") via " + portkeyBaseUrl);
return portkeyClient.chat(model, systemPrompt, prompt, 800);
```

(The SQL caller already uses a single user message — no history.)

- [ ] **Step 4: Build to verify**

Run: `cd ~/git/plm-field-tracker && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS. Unused imports (`HttpURLConnection`, `URL`, `InputStream`, `OutputStream`, `StandardCharsets`) may now be flagged — leave them; they're used elsewhere in the file.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/AiHelpController.java
git commit -m "refactor: AiHelpController uses PortkeyClient

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Refactor the 6 other AI-using services

**Files (all modified):**
- `src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerEmailService.java`
- `src/main/java/com/sandisk/plm/tracker/service/ReportService.java`
- `src/main/java/com/sandisk/plm/tracker/service/MonitorAnalysisService.java`
- `src/main/java/com/sandisk/plm/tracker/service/WhatsNewDigestService.java`
- `src/main/java/com/sandisk/plm/tracker/service/DeltaReportService.java`
- `src/main/java/com/sandisk/plm/tracker/service/DebugAssistantService.java`

Each of these currently has the same ~30-line Portkey HTTP block (search for `new URL(portkeyBaseUrl)` to find it in each file). The pattern is uniform: build JSON body, fire HTTP, parse response. Replace each with one `portkeyClient.chat(...)` call.

- [ ] **Step 1: For each of the 6 files, do the following 3 sub-edits**

For file `<File>.java`:

(a) Add the autowire near the existing `@Autowired` block:

```java
@Autowired
private PortkeyClient portkeyClient;
```

(b) Find the Portkey block (search `new URL(portkeyBaseUrl)`). Replace from just after the `requestBody = "{...}"` JSON-build through the response-parse logic with:

```java
String model = portkeyProvider + "/" + portkeyModel;
String content = portkeyClient.chat(model, systemPrompt, userPrompt, <maxTokens>);
// `content` now holds what the old code parsed out of the JSON.
```

The exact `<maxTokens>` value is what the existing code currently sends (grep for `\"max_tokens\"` in the same file).

(c) The JSON-build code that sat right above the HTTP call is now also dead — delete the `requestBody` StringBuilder block. The "system prompt" and "user prompt" strings were what got embedded into that JSON; rename the local variables to `systemPrompt` and `userPrompt` if they aren't already so the call above reads naturally.

- [ ] **Step 2: Build all 6 to verify**

Run: `cd ~/git/plm-field-tracker && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: One commit for all 6 file refactors**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerEmailService.java \
        src/main/java/com/sandisk/plm/tracker/service/ReportService.java \
        src/main/java/com/sandisk/plm/tracker/service/MonitorAnalysisService.java \
        src/main/java/com/sandisk/plm/tracker/service/WhatsNewDigestService.java \
        src/main/java/com/sandisk/plm/tracker/service/DeltaReportService.java \
        src/main/java/com/sandisk/plm/tracker/service/DebugAssistantService.java
git commit -m "refactor: 6 AI services use PortkeyClient instead of inline HTTP

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 4: Smoke-test the refactor before adding new feature code**

Build and bounce the local instance, then exercise one existing AI feature (AI Help) to confirm the refactor didn't break anything.

```bash
cd ~/git/plm-field-tracker && mvn -q -DskipTests package
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
# kill old instance
PID=$(lsof -ti:8090 || true); [ -n "$PID" ] && kill "$PID" && sleep 2
cd ~/Documents/plm-toolkit\ 2 && nohup java -Xmx2g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties > /tmp/plm-toolkit-local.log 2>&1 &
sleep 8
# log in + ask AI Help one question
curl -sS -c /tmp/cookies.txt -H "Content-Type: application/json" -d '{"username":"plmadmin","password":"newworld"}' http://localhost:8090/api/auth/login
curl -sS -b /tmp/cookies.txt -H "Content-Type: application/json" -d '{"question":"what does the BOM Explorer tab do?","currentTab":"bom","history":[]}' http://localhost:8090/api/help/chat
```

Expected: a JSON response with a non-empty `answer` field, AND `/tmp/plm-toolkit-local.log` contains a line `[PORTKEY] @anthropic-eastus2/claude-sonnet-4-6 ok ms=...`. If you see "PortkeyClient called but portkey.enabled=false", the service-level config wasn't read — recheck `application.properties` in the local `config/`.

---

### Task 4: Create `AiEvalRun` data model

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/model/AiEvalRun.java`

One file holds all the small POJOs as static nested classes. Field names match the JSON schema in the spec exactly.

- [ ] **Step 1: Create the file**

```java
package com.sandisk.plm.tracker.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level run record persisted to ./cache/ai-eval-runs.json. Field names
 * match the JSON schema in docs/superpowers/specs/2026-05-03-ai-eval-tab-design.md.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class AiEvalRun {

    public String runId;
    public String createdAt;       // ISO-8601 UTC, e.g. "2026-05-03T07:42:11Z"
    public String createdBy;       // username
    public String status;          // "RUNNING" | "DONE" | "FAILED"
    public String parentRunId;     // null unless this run was started via Rerun

    public RunConfig config = new RunConfig();
    public List<Result> results = new ArrayList<>();
    public Summary summary = new Summary();
    public List<String> errors = new ArrayList<>();

    public static class RunConfig {
        public Persona persona = new Persona();
        public String testerModel;     // "@anthropic-eastus2/claude-sonnet-4-6"
        public String evaluatorModel;  // "@openai-eastus2/gpt-4o"
        public int questionCount;      // 5 | 10 | 20 | 50
    }

    public static class Persona {
        public String role;        // CIO | Director | Peer engineer | New hire | Power user
        public String team;        // PLM IT | Quality | Engineering | Operations | Other-text
        public String experience;  // None | Some | Daily user
        public String goal;        // free text, max 200 chars
    }

    public static class Result {
        public int qIndex;             // 1-based
        public String question;
        public String answer;
        public String grade;           // A | B | C | D | F | ERR
        public String reason;
        public long answerLatencyMs;
        public long gradeLatencyMs;
    }

    public static class Summary {
        public double avgGradeNumeric; // A=4 B=3 C=2 D=1 F=0; 0 if all errored
        public String avgGradeLetter;  // A | B | C | D | F
        public int failureCount;       // count where grade ∈ {C,D,F,ERR}
        public long totalLatencyMs;
    }
}
```

- [ ] **Step 2: Build to verify**

Run: `cd ~/git/plm-field-tracker && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/model/AiEvalRun.java
git commit -m "feat: AiEvalRun data model for AI eval cache

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: JSON round-trip test for `AiEvalRun`

**Files:**
- Create: `src/test/java/com/sandisk/plm/tracker/service/AiEvalRunJsonTest.java`

Write the test first (TDD), watch it fail (it shouldn't, since the model already exists — but run it to confirm Jackson can serialize/deserialize without surprises), then commit.

- [ ] **Step 1: Write the test**

```java
package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandisk.plm.tracker.model.AiEvalRun;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class AiEvalRunJsonTest {

    @Test
    public void roundTripPreservesAllFields() throws Exception {
        AiEvalRun original = new AiEvalRun();
        original.runId = "11111111-2222-3333-4444-555555555555";
        original.createdAt = "2026-05-03T07:42:11Z";
        original.createdBy = "plmadmin";
        original.status = "DONE";
        original.parentRunId = "00000000-0000-0000-0000-000000000000";

        original.config.persona.role = "CIO";
        original.config.persona.team = "PLM IT";
        original.config.persona.experience = "None";
        original.config.persona.goal = "trying to find what changed on ECN-12345";
        original.config.testerModel = "@anthropic-eastus2/claude-sonnet-4-6";
        original.config.evaluatorModel = "@openai-eastus2/gpt-4o";
        original.config.questionCount = 10;

        AiEvalRun.Result r = new AiEvalRun.Result();
        r.qIndex = 1;
        r.question = "How do I see all changes by Peter Zhu last week?";
        r.answer = "Use the Activity tab and filter by user...";
        r.grade = "B";
        r.reason = "Mentioned Activity tab but not the date-range picker.";
        r.answerLatencyMs = 2340;
        r.gradeLatencyMs = 1820;
        original.results.add(r);

        original.summary.avgGradeNumeric = 3.0;
        original.summary.avgGradeLetter = "B";
        original.summary.failureCount = 0;
        original.summary.totalLatencyMs = 4160;

        original.errors.addAll(Arrays.asList());

        ObjectMapper om = new ObjectMapper();
        String json = om.writeValueAsString(original);
        AiEvalRun back = om.readValue(json, AiEvalRun.class);

        assertEquals(original.runId, back.runId);
        assertEquals(original.createdAt, back.createdAt);
        assertEquals(original.createdBy, back.createdBy);
        assertEquals(original.status, back.status);
        assertEquals(original.parentRunId, back.parentRunId);
        assertEquals(original.config.persona.role, back.config.persona.role);
        assertEquals(original.config.persona.team, back.config.persona.team);
        assertEquals(original.config.persona.experience, back.config.persona.experience);
        assertEquals(original.config.persona.goal, back.config.persona.goal);
        assertEquals(original.config.testerModel, back.config.testerModel);
        assertEquals(original.config.evaluatorModel, back.config.evaluatorModel);
        assertEquals(original.config.questionCount, back.config.questionCount);
        assertEquals(1, back.results.size());
        assertEquals("B", back.results.get(0).grade);
        assertEquals(2340L, back.results.get(0).answerLatencyMs);
        assertEquals(3.0, back.summary.avgGradeNumeric, 0.0001);
        assertEquals("B", back.summary.avgGradeLetter);
        assertNotNull(back.errors);
    }
}
```

- [ ] **Step 2: Run the test**

Run: `cd ~/git/plm-field-tracker && mvn -q test -Dtest=AiEvalRunJsonTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`. If it fails because Jackson can't deserialize, add `@JsonInclude(JsonInclude.Include.ALWAYS)` to nested classes too — but the `JsonInclude` annotation on `AiEvalRun` should be sufficient for this test.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/sandisk/plm/tracker/service/AiEvalRunJsonTest.java
git commit -m "test: JSON round-trip for AiEvalRun

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: `AiEvalService` — skeleton + cache load/save + orphan cleanup

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/AiEvalService.java`

Build the service in three slices: (6) skeleton + persistence + orphan handling, (7) question generation, (8) answer+grade loop + summary + export.

- [ ] **Step 1: Create the file with skeleton, cache load/save, and orphan cleanup**

```java
package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandisk.plm.tracker.model.AiEvalRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Orchestrates AI eval runs. Single instance per JVM. Writes are guarded
 * by `synchronized (this)`. SSE emitters for in-flight runs are tracked
 * by runId so the controller can subscribe after the worker has started.
 */
@Service
public class AiEvalService {

    private static final Logger logger = Logger.getLogger(AiEvalService.class.getName());

    @Value("${app.ai-eval.cache-file:./cache/ai-eval-runs.json}")
    private String cacheFilePath;

    @Value("${app.ai-eval.export-dir:./debug-output}")
    private String exportDir;

    @Autowired
    private PortkeyClient portkeyClient;

    @Autowired
    private com.sandisk.plm.tracker.controller.AiHelpController aiHelpController;

    private final ObjectMapper om = new ObjectMapper();

    /** Loaded into memory at startup; mutated under `synchronized (this)`. */
    private final List<AiEvalRun> runs = new ArrayList<>();

    /** Live SSE emitters keyed by runId. */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    @PostConstruct
    public synchronized void load() {
        File f = new File(cacheFilePath);
        if (!f.exists()) {
            logger.info("[AI EVAL] cache file not found, starting fresh: " + cacheFilePath);
            return;
        }
        try {
            CacheFile cf = om.readValue(f, CacheFile.class);
            if (cf.runs != null) runs.addAll(cf.runs);
            logger.info("[AI EVAL] loaded " + runs.size() + " runs from " + cacheFilePath);
        } catch (Exception e) {
            // Corrupted file — back up and start fresh
            try {
                String stamp = String.valueOf(System.currentTimeMillis());
                Files.move(f.toPath(), f.toPath().resolveSibling(f.getName() + ".broken-" + stamp));
                logger.warning("[AI EVAL] cache file corrupt; backed up and starting fresh: " + e.getMessage());
            } catch (Exception mvErr) {
                logger.warning("[AI EVAL] cache file corrupt AND backup failed: " + mvErr.getMessage());
            }
        }
        // Mark any RUNNING runs as orphaned (server restart mid-run)
        int orphans = 0;
        for (AiEvalRun r : runs) {
            if ("RUNNING".equals(r.status)) {
                r.status = "FAILED";
                r.errors.add("server-restart-orphan");
                orphans++;
            }
        }
        if (orphans > 0) {
            save();
            logger.info("[AI EVAL] marked " + orphans + " orphaned runs as FAILED");
        }
    }

    /** Atomic write: temp file + rename. */
    private synchronized void save() {
        try {
            File f = new File(cacheFilePath);
            f.getParentFile().mkdirs();
            CacheFile cf = new CacheFile();
            cf.version = 1;
            cf.runs = runs;
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            om.writerWithDefaultPrettyPrinter().writeValue(tmp, cf);
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.warning("[AI EVAL] failed to persist cache: " + e.getMessage());
        }
    }

    /** Returns the run or null. */
    public synchronized AiEvalRun getRun(String runId) {
        for (AiEvalRun r : runs) if (runId.equals(r.runId)) return r;
        return null;
    }

    /** List view (newest first), with `results[]` cleared to keep payloads light. */
    public synchronized List<AiEvalRun> listRunsLight() {
        List<AiEvalRun> out = new ArrayList<>(runs.size());
        for (int i = runs.size() - 1; i >= 0; i--) {
            AiEvalRun src = runs.get(i);
            AiEvalRun copy = shallowCopyForList(src);
            out.add(copy);
        }
        return out;
    }

    private AiEvalRun shallowCopyForList(AiEvalRun src) {
        AiEvalRun c = new AiEvalRun();
        c.runId = src.runId;
        c.createdAt = src.createdAt;
        c.createdBy = src.createdBy;
        c.status = src.status;
        c.parentRunId = src.parentRunId;
        c.config = src.config;
        c.summary = src.summary;
        c.errors = src.errors;
        // results intentionally omitted in list view
        return c;
    }

    public SseEmitter registerEmitter(String runId) {
        SseEmitter em = new SseEmitter(180_000L);
        emitters.put(runId, em);
        em.onCompletion(() -> emitters.remove(runId));
        em.onTimeout(() -> { emitters.remove(runId); em.complete(); });
        em.onError(t -> emitters.remove(runId));
        // If the run already finished, send the terminal event right away.
        AiEvalRun done = getRun(runId);
        if (done != null && !"RUNNING".equals(done.status)) {
            emit(runId, "DONE".equals(done.status) ? "run-complete" : "run-failed",
                 Map.of("status", done.status));
            em.complete();
        }
        return em;
    }

    void emit(String runId, String event, Map<String, ?> data) {
        SseEmitter em = emitters.get(runId);
        if (em == null) return;
        try {
            em.send(SseEmitter.event().name(event).data(data, org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (Exception ignored) { /* client disconnected */ }
    }

    /** Holds the list during JSON serialization so we can carry a top-level `version`. */
    public static class CacheFile {
        public int version;
        public List<AiEvalRun> runs;
    }

    // --- methods added in Tasks 7, 8, 11 below ---

    /** Public API used by the controller. Throws IllegalArgumentException for invalid input. */
    public AiEvalRun startRun(AiEvalRun.RunConfig config, String createdBy, String parentRunId) {
        // wired in Task 11
        throw new UnsupportedOperationException("wired in Task 11");
    }

    /** Writes ./debug-output/eval-{runId}.md and overwrites ./debug-output/eval-latest.md. */
    public String exportForClaude(String runId, String currentAiHelpSystemPrompt) throws IOException {
        // wired in Task 10
        throw new UnsupportedOperationException("wired in Task 10");
    }
}
```

Note: `aiHelpController` is autowired so the service can call `aiHelpController.getCurrentSystemPromptForExport()` (a small accessor we add in Task 10) when generating export markdown. The Spring bean cycle is fine because `AiHelpController` doesn't depend on `AiEvalService`.

- [ ] **Step 2: Build to verify**

Run: `cd ~/git/plm-field-tracker && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/AiEvalService.java
git commit -m "feat: AiEvalService skeleton with cache load/save + orphan cleanup

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: `AiEvalService` — question generation step

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/AiEvalService.java`

Add the Tester call that generates the question batch.

- [ ] **Step 1: Add `generateQuestions` and JSON-array parser**

Inside `AiEvalService`, after `emit(...)` and before the `--- methods added in Tasks 7, 8, 11 below ---` comment, paste:

```java
/**
 * Asks the Tester model to generate `count` distinct questions for the given persona.
 * Returns the list. On malformed JSON, retries once with a stricter prompt.
 * Throws on persistent failure or transport error.
 */
List<String> generateQuestions(AiEvalRun.RunConfig config, String runId) throws Exception {
    String sys = "You are simulating a " + config.persona.role
        + " on the " + config.persona.team + " team"
        + " with " + config.persona.experience + " experience"
        + " using a PLM data toolkit web app, and you are trying to: " + config.persona.goal + ". "
        + "Generate exactly " + config.questionCount + " distinct, realistic questions"
        + " you would ask the AI Help chatbot inside this app. Vary the questions to cover"
        + " happy-path tasks, edge cases, and things you might be confused about as someone"
        + " with that experience level. "
        + "OUTPUT REQUIREMENTS: Return ONLY a JSON array of " + config.questionCount + " strings."
        + " No prose, no markdown fences, no preamble. Example: [\"q1\", \"q2\", \"q3\"]";

    long t0 = System.currentTimeMillis();
    String raw;
    try {
        raw = portkeyClient.chat(config.testerModel, sys, "Generate the questions now.", 1500);
    } catch (Exception e) {
        logger.warning("[AI EVAL] runId=" + runId + " stage=tester model=" + config.testerModel
            + " ms=" + (System.currentTimeMillis() - t0) + " status=err err=" + e.getMessage());
        throw e;
    }
    logger.info("[AI EVAL] runId=" + runId + " stage=tester model=" + config.testerModel
        + " ms=" + (System.currentTimeMillis() - t0) + " status=ok");

    List<String> qs = tryParseStringArray(raw);
    if (qs == null) {
        // One retry with stricter wording
        String stricter = sys + "\n\nPREVIOUS ATTEMPT WAS REJECTED FOR NON-JSON OUTPUT. YOU MUST RETURN ONLY A JSON ARRAY OF STRINGS, NOTHING ELSE.";
        raw = portkeyClient.chat(config.testerModel, stricter, "Generate the questions now.", 1500);
        qs = tryParseStringArray(raw);
    }
    if (qs == null) {
        throw new RuntimeException("Tester returned malformed JSON twice: " + raw.substring(0, Math.min(200, raw.length())));
    }
    if (qs.size() > config.questionCount) qs = qs.subList(0, config.questionCount);
    return qs;
}

private List<String> tryParseStringArray(String raw) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    // Strip ``` or ```json fences if present
    if (trimmed.startsWith("```")) {
        int firstNl = trimmed.indexOf('\n');
        if (firstNl > 0) trimmed = trimmed.substring(firstNl + 1);
        if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3);
    }
    trimmed = trimmed.trim();
    if (!trimmed.startsWith("[")) return null;
    try {
        return om.readValue(trimmed, om.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (Exception e) {
        return null;
    }
}
```

- [ ] **Step 2: Build to verify**

Run: `cd ~/git/plm-field-tracker && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/AiEvalService.java
git commit -m "feat: AiEvalService.generateQuestions with retry-on-malformed

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: `AiEvalService` — answer + grade loop, summary, public `startRun`

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/AiEvalService.java`
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/AiHelpController.java` (expose two helpers)

The eval loop calls AI Help and the Evaluator per question. AI Help isn't currently callable from another Spring service in a clean way — its `callHaiku` is private. Add two public helpers in `AiHelpController` that the eval service can call: one for the system prompt accessor (used both by grading and by export), and one for the answer call.

- [ ] **Step 1: Expose helpers from `AiHelpController`**

In `AiHelpController.java`, add two new public methods anywhere convenient (e.g., right above `callHaiku`):

```java
/**
 * Returns the system prompt currently used by the AI Help chatbot for a given
 * tab + admin context. Used by AiEvalService for grading and for export.
 * NOT used by the chat endpoint itself — that one builds it inline.
 */
public String getSystemPromptForEval(String currentTab, String userName, boolean isAdmin) {
    String kb = getFilteredKnowledgeBase(isAdmin);
    String adminNote = isAdmin ? "The user is a PLM admin with full access." :
        "The user is NOT an admin. If they ask about admin-only features (uploading scripts, editing environment variables, deleting utilities, managing dependencies), " +
        "tell them that feature is available to PLM admins only and suggest contacting pdl-plm-admin@sandisk.com for help.";
    return "You are a helpful assistant for the PLM Toolkit web application. " +
        "Answer questions concisely based on the knowledge base below. " +
        "The user is currently on the '" + (currentTab != null ? currentTab : "unknown") + "' tab. " +
        "Keep answers short (2-4 sentences). If you don't know, say so and suggest contacting pdl-plm-admin@sandisk.com. " +
        "The user's name is " + (userName != null ? userName : "User") + ". " + adminNote + "\n\n" +
        "KNOWLEDGE BASE:\n" + kb;
}

/**
 * Public single-shot AI Help call for the eval harness. Takes one user question,
 * uses the same system prompt the chat endpoint would build, and returns
 * the assistant's answer. No history.
 */
public String askAiHelpForEval(String question, String currentTab, String userName, boolean isAdmin) throws Exception {
    String systemPrompt = getSystemPromptForEval(currentTab, userName, isAdmin);
    String model = portkeyProvider + "/" + portkeyModel;
    return portkeyClient.chat(model, systemPrompt, question, 300);
}
```

(`getFilteredKnowledgeBase`, `portkeyProvider`, `portkeyModel`, and `portkeyClient` are already members of `AiHelpController`.)

- [ ] **Step 2: Add the eval loop and `startRun` to `AiEvalService`**

Replace the placeholder `startRun` body (the one that throws `UnsupportedOperationException`) with:

```java
@Override
public AiEvalRun startRun(AiEvalRun.RunConfig config, String createdBy, String parentRunId) {
    // Validation
    if (config == null) throw new IllegalArgumentException("config required");
    if (config.persona == null || isBlank(config.persona.role) || isBlank(config.persona.team)
        || isBlank(config.persona.experience) || isBlank(config.persona.goal)) {
        throw new IllegalArgumentException("All persona fields are required.");
    }
    if (config.persona.goal.length() > 200) {
        throw new IllegalArgumentException("Goal must be 200 characters or fewer.");
    }
    if (isBlank(config.testerModel) || isBlank(config.evaluatorModel)) {
        throw new IllegalArgumentException("Tester and Evaluator models are required.");
    }
    if (config.testerModel.equals(config.evaluatorModel)) {
        throw new IllegalArgumentException("Tester and Evaluator must use different models.");
    }
    if (!Arrays.asList(5, 10, 20, 50).contains(config.questionCount)) {
        throw new IllegalArgumentException("Question count must be one of 5, 10, 20, 50.");
    }

    AiEvalRun run = new AiEvalRun();
    run.runId = UUID.randomUUID().toString();
    run.createdAt = Instant.now().toString();
    run.createdBy = createdBy;
    run.status = "RUNNING";
    run.parentRunId = parentRunId;
    run.config = config;

    synchronized (this) {
        runs.add(run);
        save();
    }

    // Worker thread — controller returns the SSE emitter without blocking
    Thread w = new Thread(() -> executeRun(run), "ai-eval-" + run.runId.substring(0, 8));
    w.setDaemon(true);
    w.start();

    return run;
}

@Override
public String exportForClaude(String runId, String currentAiHelpSystemPrompt) throws IOException {
    // wired in Task 10
    throw new UnsupportedOperationException("wired in Task 10");
}

private void executeRun(AiEvalRun run) {
    long t0 = System.currentTimeMillis();
    try {
        // Step A: generate questions
        List<String> questions = generateQuestions(run.config, run.runId);
        emit(run.runId, "questions-ready", Map.of("count", questions.size()));

        // Step B: per question — answer + grade
        for (int i = 0; i < questions.size(); i++) {
            int qIdx = i + 1;
            String q = questions.get(i);
            AiEvalRun.Result r = new AiEvalRun.Result();
            r.qIndex = qIdx;
            r.question = q;

            // Answer
            long ta = System.currentTimeMillis();
            try {
                r.answer = aiHelpController.askAiHelpForEval(q, "ai-eval", run.createdBy, true);
                r.answerLatencyMs = System.currentTimeMillis() - ta;
                logger.info("[AI EVAL] runId=" + run.runId + " stage=help qIndex=" + qIdx
                    + " ms=" + r.answerLatencyMs + " status=ok");
            } catch (Exception e) {
                r.answer = "";
                r.answerLatencyMs = System.currentTimeMillis() - ta;
                r.grade = "ERR";
                r.reason = "AI Help failed: " + e.getMessage();
                logger.warning("[AI EVAL] runId=" + run.runId + " stage=help qIndex=" + qIdx
                    + " ms=" + r.answerLatencyMs + " status=err err=" + e.getMessage());
                synchronized (this) { run.results.add(r); save(); }
                emit(run.runId, "graded", Map.of("qIndex", qIdx, "question", q, "answer", "",
                    "grade", "ERR", "reason", r.reason));
                continue;
            }
            emit(run.runId, "answer-received", Map.of("qIndex", qIdx, "question", q, "answer", r.answer));

            // Grade
            long tg = System.currentTimeMillis();
            try {
                Map<String, String> graded = gradeAnswer(run.config, q, r.answer, run.runId, qIdx);
                r.grade = graded.get("grade");
                r.reason = graded.get("reason");
                r.gradeLatencyMs = System.currentTimeMillis() - tg;
                logger.info("[AI EVAL] runId=" + run.runId + " stage=grade qIndex=" + qIdx
                    + " ms=" + r.gradeLatencyMs + " status=ok grade=" + r.grade);
            } catch (Exception e) {
                r.grade = "ERR";
                r.reason = "Evaluator failed: " + e.getMessage();
                r.gradeLatencyMs = System.currentTimeMillis() - tg;
                logger.warning("[AI EVAL] runId=" + run.runId + " stage=grade qIndex=" + qIdx
                    + " ms=" + r.gradeLatencyMs + " status=err err=" + e.getMessage());
            }
            synchronized (this) { run.results.add(r); save(); }
            emit(run.runId, "graded", Map.of("qIndex", qIdx, "question", q, "answer", r.answer,
                "grade", r.grade, "reason", r.reason == null ? "" : r.reason));
        }

        // Step C: summary
        finalizeSummary(run);
        run.status = "DONE";
        run.summary.totalLatencyMs = System.currentTimeMillis() - t0;
        synchronized (this) { save(); }
        emit(run.runId, "run-complete", Map.of(
            "avgGradeNumeric", run.summary.avgGradeNumeric,
            "avgGradeLetter", run.summary.avgGradeLetter,
            "failureCount", run.summary.failureCount));

    } catch (Exception fatal) {
        run.status = "FAILED";
        run.errors.add(fatal.getMessage() == null ? fatal.getClass().getSimpleName() : fatal.getMessage());
        synchronized (this) { save(); }
        logger.warning("[AI EVAL] runId=" + run.runId + " run-failed: " + fatal.getMessage());
        emit(run.runId, "run-failed", Map.of("error", fatal.getMessage() == null ? "unknown" : fatal.getMessage()));
    } finally {
        SseEmitter em = emitters.get(run.runId);
        if (em != null) try { em.complete(); } catch (Exception ignored) {}
    }
}

private Map<String, String> gradeAnswer(AiEvalRun.RunConfig config, String q, String a, String runId, int qIdx) throws Exception {
    String sys = "You are grading a chatbot answer for a " + config.persona.role
        + " on the " + config.persona.team + " team"
        + " with " + config.persona.experience + " experience, who is trying to: " + config.persona.goal + ". "
        + "Grade the answer on a 5-point scale: A (excellent, exactly what they need), "
        + "B (good, addresses the question with minor gaps), C (mediocre, partially addresses), "
        + "D (poor, mostly misses the point), F (wrong or unhelpful). "
        + "OUTPUT REQUIREMENTS: Return ONLY a JSON object of the form {\"grade\":\"A|B|C|D|F\",\"reason\":\"one sentence\"}. "
        + "No prose, no markdown fences, no preamble.";
    String user = "QUESTION: " + q + "\n\nANSWER: " + a;

    String raw;
    try {
        raw = portkeyClient.chat(config.evaluatorModel, sys, user, 200);
    } catch (Exception e) {
        throw e;
    }

    Map<String, String> parsed = tryParseGrade(raw);
    if (parsed == null) {
        // One retry with stricter wording
        String stricter = sys + "\n\nPREVIOUS ATTEMPT WAS REJECTED FOR NON-JSON OUTPUT. RETURN ONLY THE JSON OBJECT.";
        raw = portkeyClient.chat(config.evaluatorModel, stricter, user, 200);
        parsed = tryParseGrade(raw);
    }
    if (parsed == null) {
        throw new RuntimeException("Evaluator returned malformed JSON twice: " + raw.substring(0, Math.min(200, raw.length())));
    }
    return parsed;
}

private Map<String, String> tryParseGrade(String raw) {
    if (raw == null) return null;
    String t = raw.trim();
    if (t.startsWith("```")) {
        int firstNl = t.indexOf('\n');
        if (firstNl > 0) t = t.substring(firstNl + 1);
        if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
    }
    t = t.trim();
    if (!t.startsWith("{")) return null;
    try {
        Map<String, Object> m = om.readValue(t, om.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        Object g = m.get("grade");
        Object r = m.get("reason");
        if (g == null) return null;
        String grade = String.valueOf(g).trim().toUpperCase();
        if (!Arrays.asList("A", "B", "C", "D", "F").contains(grade)) return null;
        Map<String, String> out = new LinkedHashMap<>();
        out.put("grade", grade);
        out.put("reason", r == null ? "" : String.valueOf(r));
        return out;
    } catch (Exception e) {
        return null;
    }
}

private void finalizeSummary(AiEvalRun run) {
    int graded = 0;
    int sum = 0;
    int fails = 0;
    for (AiEvalRun.Result r : run.results) {
        if ("ERR".equals(r.grade)) { fails++; continue; }
        int v = letterToNumeric(r.grade);
        if (v < 0) continue; // unknown; skip
        sum += v;
        graded++;
        if (v < 3) fails++; // C/D/F count as failures (B = 3 = passing)
    }
    run.summary.avgGradeNumeric = graded == 0 ? 0.0 : Math.round((sum * 10.0 / graded)) / 10.0;
    run.summary.avgGradeLetter = numericToLetter(run.summary.avgGradeNumeric);
    run.summary.failureCount = fails;
}

private static int letterToNumeric(String g) {
    if (g == null) return -1;
    switch (g) {
        case "A": return 4;
        case "B": return 3;
        case "C": return 2;
        case "D": return 1;
        case "F": return 0;
        default: return -1;
    }
}

private static String numericToLetter(double v) {
    if (v >= 3.5) return "A";
    if (v >= 2.5) return "B";
    if (v >= 1.5) return "C";
    if (v >= 0.5) return "D";
    return "F";
}

private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
```

Note: remove the `@Override` annotations on `startRun` and `exportForClaude` — there's no interface. (They were in the placeholder skeleton as a hint; delete them.)

- [ ] **Step 3: Build to verify**

Run: `cd ~/git/plm-field-tracker && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS. If a circular bean dependency error appears (`AiEvalService` ↔ `AiHelpController`), break it by autowiring `AiHelpController` lazily in `AiEvalService`:
```java
@Autowired @org.springframework.context.annotation.Lazy
private com.sandisk.plm.tracker.controller.AiHelpController aiHelpController;
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/AiEvalService.java \
        src/main/java/com/sandisk/plm/tracker/controller/AiHelpController.java
git commit -m "feat: AiEvalService eval loop + AiHelpController eval helpers

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: `AiEvalService.exportForClaude` — markdown brief

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/AiEvalService.java`

- [ ] **Step 1: Replace the placeholder `exportForClaude` body**

```java
public String exportForClaude(String runId, String currentAiHelpSystemPrompt) throws IOException {
    AiEvalRun run = getRun(runId);
    if (run == null) throw new IllegalArgumentException("run not found: " + runId);

    StringBuilder md = new StringBuilder();
    md.append("# AI Help Eval — Failures from run ").append(run.runId).append("\n\n");
    md.append("**Run date:** ").append(run.createdAt).append("\n");
    md.append("**Persona:** ").append(run.config.persona.role)
        .append(" · ").append(run.config.persona.team)
        .append(" · ").append(run.config.persona.experience).append("\n");
    md.append("**Goal:** ").append(run.config.persona.goal).append("\n");
    md.append("**Tester model:** ").append(run.config.testerModel).append("\n");
    md.append("**Evaluator model:** ").append(run.config.evaluatorModel).append("\n");
    md.append("**Score:** avg ").append(run.summary.avgGradeLetter)
        .append(" (").append(run.summary.avgGradeNumeric).append("), ")
        .append(run.summary.failureCount).append(" of ").append(run.results.size())
        .append(" failed (≤B)\n\n");

    md.append("## Current AI Help system prompt (snapshotted at export time)\n\n");
    md.append("```\n").append(currentAiHelpSystemPrompt == null ? "(unavailable)" : currentAiHelpSystemPrompt).append("\n```\n\n");

    md.append("## Failed questions (grades C, D, F, or ERR)\n\n");
    int included = 0;
    for (AiEvalRun.Result r : run.results) {
        boolean failed = "ERR".equals(r.grade) ||
            (r.grade != null && Arrays.asList("C", "D", "F").contains(r.grade));
        if (!failed) continue;
        included++;
        md.append("### Q").append(r.qIndex).append(" — Grade ").append(r.grade).append("\n");
        md.append("**Question:** ").append(r.question).append("\n");
        md.append("**Answer:** ").append(r.answer == null ? "(none)" : r.answer).append("\n");
        md.append("**Reason:** ").append(r.reason == null ? "" : r.reason).append("\n\n");
    }
    if (included == 0) md.append("(no failures in this run — all grades A or B)\n\n");

    md.append("## What I'd like you to do\n\n");
    md.append("Identify systemic issues across these failures (not one-off fixes). Propose changes\n");
    md.append("to the AI Help system prompt or controller logic. After my fix, I'll click Rerun\n");
    md.append("in the AI Eval tab — `parentRunId: ").append(run.runId).append("` — and the Δ-grade\n");
    md.append("column will tell us whether the change helped.\n");

    Path dir = new File(exportDir).toPath();
    Files.createDirectories(dir);
    Path full = dir.resolve("eval-" + run.runId + ".md");
    Path latest = dir.resolve("eval-latest.md");
    Files.writeString(full, md.toString());
    Files.writeString(latest, md.toString());
    return latest.toString();
}
```

- [ ] **Step 2: Build to verify**

Run: `cd ~/git/plm-field-tracker && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/AiEvalService.java
git commit -m "feat: AiEvalService.exportForClaude writes markdown brief

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: `AiEvalController` — HTTP endpoints

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/controller/AiEvalController.java`

- [ ] **Step 1: Create the file**

```java
package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.AiEvalRun;
import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.AiEvalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpSession;
import java.util.*;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/ai-eval")
public class AiEvalController {

    private static final Logger logger = Logger.getLogger(AiEvalController.class.getName());

    @Autowired
    private AiEvalService evalService;

    @Autowired
    private AiHelpController aiHelpController;

    @Autowired
    private ActivityLogger activityLogger;

    /** Start a new run. Body: {persona:{...}, testerModel, evaluatorModel, questionCount, parentRunId?} */
    @PostMapping("/runs")
    public ResponseEntity<?> startRun(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));

        AiEvalRun.RunConfig cfg = new AiEvalRun.RunConfig();
        try {
            Map<String, Object> p = castMap(body.get("persona"));
            if (p == null) throw new IllegalArgumentException("persona is required");
            cfg.persona.role = stringOrNull(p.get("role"));
            cfg.persona.team = stringOrNull(p.get("team"));
            cfg.persona.experience = stringOrNull(p.get("experience"));
            cfg.persona.goal = stringOrNull(p.get("goal"));
            cfg.testerModel = stringOrNull(body.get("testerModel"));
            cfg.evaluatorModel = stringOrNull(body.get("evaluatorModel"));
            Object qc = body.get("questionCount");
            cfg.questionCount = qc instanceof Number ? ((Number) qc).intValue() : 0;
        } catch (Exception parseErr) {
            return ResponseEntity.badRequest().body(Map.of("error", "Bad request body: " + parseErr.getMessage()));
        }

        String parentRunId = stringOrNull(body.get("parentRunId"));
        String username = (String) session.getAttribute("username");

        try {
            AiEvalRun run = evalService.startRun(cfg, username == null ? "unknown" : username, parentRunId);
            activityLogger.log(username, (String) session.getAttribute("displayName"),
                "AI_EVAL_START", "runId=" + run.runId + " persona=" + cfg.persona.role
                    + " count=" + cfg.questionCount);
            return ResponseEntity.ok(Map.of("runId", run.runId));
        } catch (IllegalArgumentException valErr) {
            return ResponseEntity.badRequest().body(Map.of("error", valErr.getMessage()));
        }
    }

    /** SSE stream for one run's progress. Closes on run-complete or run-failed. */
    @GetMapping(value = "/runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId, HttpSession session) {
        SseEmitter em = new SseEmitter(180_000L);
        if (!isAdmin(session)) {
            try { em.send(SseEmitter.event().name("error").data(Map.of("error", "Admin access required."))); } catch (Exception ignored) {}
            em.complete();
            return em;
        }
        return evalService.registerEmitter(runId);
    }

    /** Snapshot of one run (used for SSE-fallback polling). */
    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> getRun(@PathVariable String runId, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));
        AiEvalRun r = evalService.getRun(runId);
        if (r == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(r);
    }

    /** Light list view (no `results[]`). */
    @GetMapping("/runs")
    public ResponseEntity<?> listRuns(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));
        return ResponseEntity.ok(evalService.listRunsLight());
    }

    /** Full results for one run (powering the expand-row view). */
    @GetMapping("/runs/{runId}/results")
    public ResponseEntity<?> getResults(@PathVariable String runId, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));
        AiEvalRun r = evalService.getRun(runId);
        if (r == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("runId", r.runId, "results", r.results, "summary", r.summary));
    }

    /** Write the markdown brief and return its path. */
    @PostMapping("/runs/{runId}/export")
    public ResponseEntity<?> export(@PathVariable String runId, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));
        try {
            String currentSystemPrompt = aiHelpController.getSystemPromptForEval("ai-eval",
                (String) session.getAttribute("displayName"), true);
            String path = evalService.exportForClaude(runId, currentSystemPrompt);
            activityLogger.log((String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "AI_EVAL_EXPORT", "runId=" + runId + " path=" + path);
            return ResponseEntity.ok(Map.of("path", path));
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.warning("[AI EVAL] export failed for " + runId + ": " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private static boolean isAdmin(HttpSession session) {
        Boolean v = (Boolean) session.getAttribute("isPlmAdmin");
        return v != null && v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
```

- [ ] **Step 2: Build to verify**

Run: `cd ~/git/plm-field-tracker && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/AiEvalController.java
git commit -m "feat: AiEvalController endpoints with admin gate

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Backend smoke test — fire one run end-to-end

Rebuild, restart local, and run a full 5-question eval via curl. Verify the run finishes and the cache file is created.

- [ ] **Step 1: Build, deploy, restart local**

```bash
cd ~/git/plm-field-tracker && mvn -q -DskipTests package
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
PID=$(lsof -ti:8090 || true); [ -n "$PID" ] && kill "$PID" && sleep 2
cd ~/Documents/plm-toolkit\ 2 && nohup java -Xmx2g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties > /tmp/plm-toolkit-local.log 2>&1 &
sleep 8
```

- [ ] **Step 2: Log in and start a 5-Q run**

```bash
curl -sS -c /tmp/cookies.txt -H "Content-Type: application/json" \
     -d '{"username":"plmadmin","password":"newworld"}' \
     http://localhost:8090/api/auth/login

RUN=$(curl -sS -b /tmp/cookies.txt -H "Content-Type: application/json" \
     -d '{"persona":{"role":"CIO","team":"PLM IT","experience":"None","goal":"trying to find what changed on ECN-12345"},
          "testerModel":"@anthropic-eastus2/claude-sonnet-4-6",
          "evaluatorModel":"@openai-eastus2/gpt-4o",
          "questionCount":5}' \
     http://localhost:8090/api/ai-eval/runs)
echo "$RUN"
RID=$(echo "$RUN" | sed -n 's/.*"runId":"\([^"]*\)".*/\1/p')
echo "Run ID: $RID"
```

Expected: a JSON `{"runId":"..."}` response.

- [ ] **Step 3: Wait for completion (~60s for 5 questions)**

Use Monitor to poll until status is no longer RUNNING:

```bash
until [ "$(curl -sS -b /tmp/cookies.txt http://localhost:8090/api/ai-eval/runs/$RID | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')" != "RUNNING" ]; do sleep 5; done
curl -sS -b /tmp/cookies.txt http://localhost:8090/api/ai-eval/runs/$RID | head -c 500; echo
```

Expected: final status is `DONE`. Summary contains an `avgGradeLetter` and `failureCount`. `~/Documents/plm-toolkit\ 2/cache/ai-eval-runs.json` exists and contains 1 run. Log lines `[AI EVAL] runId=... stage=tester|help|grade ... status=ok` appear in `/tmp/plm-toolkit-local.log`.

- [ ] **Step 4: Test the export endpoint**

```bash
curl -sS -b /tmp/cookies.txt -X POST http://localhost:8090/api/ai-eval/runs/$RID/export
ls -la ~/Documents/plm-toolkit\ 2/debug-output/
head -30 ~/Documents/plm-toolkit\ 2/debug-output/eval-latest.md
```

Expected: `eval-{runId}.md` and `eval-latest.md` both exist; markdown shows the persona, model names, and (depending on grading) a "Failed questions" section or the no-failures placeholder.

- [ ] **Step 5: Test the same-model rejection**

```bash
curl -sS -b /tmp/cookies.txt -H "Content-Type: application/json" \
     -d '{"persona":{"role":"CIO","team":"PLM IT","experience":"None","goal":"x"},
          "testerModel":"@anthropic-eastus2/claude-sonnet-4-6",
          "evaluatorModel":"@anthropic-eastus2/claude-sonnet-4-6",
          "questionCount":5}' \
     http://localhost:8090/api/ai-eval/runs
```

Expected: HTTP 400 + `{"error":"Tester and Evaluator must use different models."}`.

- [ ] **Step 6: Commit if any code adjustments were needed during smoke**

If you had to fix anything (circular dependency, JSON parse edge case, etc.), commit the fix. Otherwise skip.

```bash
git status
# if anything modified, stage and commit
```

---

### Task 12: Frontend HTML — tab button + 3 panel sections

**Files:**
- Modify: `src/main/resources/static/index.html`

- [ ] **Step 1: Add the tab button**

Find the tab bar (line ~96, after `tabHelpCenter`). Add a new line just before the closing `</div>` of `tab-bar`:

```html
<button class="tab" id="tabAiEval" onclick="switchTab('aieval')" style="display:none;">AI Eval</button>
```

- [ ] **Step 2: Add the panel section**

Find a sensible spot — directly after the last existing tab panel close (search for `id="panelHelpCenter"` and append after that panel's closing `</div>`). Paste:

```html
<div id="panelAiEval" class="tab-panel" style="display:none;">
    <div style="max-width:1100px; margin:24px auto 80px; font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif; color:#0F1720;">
        <h2 style="font-family:'IBM Plex Serif',Georgia,serif; font-size:22px; margin:0 0 4px 0;">AI Eval (admin)</h2>
        <p style="margin:0 0 24px 0; color:#6B7280; font-size:13px;">
            Generate persona-driven questions, fire them at the AI Help chatbot, grade each answer with a different model.
            Use the <strong>Export for Claude</strong> button on a past run to brief Claude in VS Code about what to fix.
        </p>

        <!-- Section A: Configure run -->
        <div style="background:#fff; border:1px solid #E8E6DF; border-radius:8px; padding:20px; margin-bottom:24px;">
            <div style="display:flex; gap:32px; flex-wrap:wrap;">
                <div style="flex:1; min-width:280px;">
                    <h3 style="margin:0 0 12px; font-size:14px; color:#2c3e50; text-transform:uppercase; letter-spacing:.5px;">Tester</h3>
                    <label style="display:block; margin-bottom:8px; font-size:12px; color:#6B7280;">Role</label>
                    <select id="aieRole" style="width:100%; padding:6px 8px; margin-bottom:12px; border:1px solid #E8E6DF; border-radius:4px;">
                        <option>CIO</option><option>Director</option><option>Peer engineer</option>
                        <option selected>New hire</option><option>Power user</option>
                    </select>
                    <label style="display:block; margin-bottom:8px; font-size:12px; color:#6B7280;">Team</label>
                    <select id="aieTeam" style="width:100%; padding:6px 8px; margin-bottom:12px; border:1px solid #E8E6DF; border-radius:4px;">
                        <option selected>PLM IT</option><option>Quality</option><option>Engineering</option>
                        <option>Operations</option><option value="__other">Other (type below)</option>
                    </select>
                    <input id="aieTeamOther" type="text" placeholder="Other team..." style="display:none; width:100%; padding:6px 8px; margin-bottom:12px; border:1px solid #E8E6DF; border-radius:4px;">
                    <label style="display:block; margin-bottom:8px; font-size:12px; color:#6B7280;">Experience with this tool</label>
                    <div style="margin-bottom:12px; font-size:13px;">
                        <label style="margin-right:12px;"><input type="radio" name="aieExp" value="None" checked> None</label>
                        <label style="margin-right:12px;"><input type="radio" name="aieExp" value="Some"> Some</label>
                        <label><input type="radio" name="aieExp" value="Daily user"> Daily user</label>
                    </div>
                    <label style="display:block; margin-bottom:8px; font-size:12px; color:#6B7280;">Goal in this session (≤200 chars)</label>
                    <input id="aieGoal" type="text" maxlength="200" placeholder="e.g. trying to find what changed on ECN-12345"
                           style="width:100%; padding:6px 8px; margin-bottom:12px; border:1px solid #E8E6DF; border-radius:4px;">
                    <label style="display:block; margin-bottom:8px; font-size:12px; color:#6B7280;">Model</label>
                    <select id="aieTesterModel" style="width:100%; padding:6px 8px; border:1px solid #E8E6DF; border-radius:4px;">
                        <option selected value="@anthropic-eastus2/claude-sonnet-4-6">Claude Sonnet 4.6</option>
                        <option value="@openai-eastus2/gpt-4o">GPT-4o</option>
                        <option value="@vertexai-global/gemini-2.5-pro">Gemini 2.5 Pro</option>
                    </select>
                </div>
                <div style="flex:1; min-width:280px;">
                    <h3 style="margin:0 0 12px; font-size:14px; color:#2c3e50; text-transform:uppercase; letter-spacing:.5px;">Evaluator</h3>
                    <label style="display:block; margin-bottom:8px; font-size:12px; color:#6B7280;">Model (must differ from Tester)</label>
                    <select id="aieEvaluatorModel" style="width:100%; padding:6px 8px; margin-bottom:12px; border:1px solid #E8E6DF; border-radius:4px;">
                        <option value="@anthropic-eastus2/claude-sonnet-4-6">Claude Sonnet 4.6</option>
                        <option selected value="@openai-eastus2/gpt-4o">GPT-4o</option>
                        <option value="@vertexai-global/gemini-2.5-pro">Gemini 2.5 Pro</option>
                    </select>
                    <label style="display:block; margin-bottom:8px; font-size:12px; color:#6B7280;">Question count</label>
                    <select id="aieQCount" style="width:100%; padding:6px 8px; border:1px solid #E8E6DF; border-radius:4px;">
                        <option>5</option><option selected>10</option><option>20</option><option>50</option>
                    </select>
                </div>
            </div>
            <div style="margin-top:20px; display:flex; align-items:center; gap:12px;">
                <button id="aieRunBtn" style="background:#4a6fa5; color:#fff; padding:10px 20px; border:none; border-radius:4px; font-size:14px; font-weight:600; cursor:pointer;">Run eval</button>
                <span id="aieRunMsg" style="font-size:12px; color:#6B7280;"></span>
            </div>
        </div>

        <!-- Section B: Live run -->
        <div id="aieLiveSection" style="display:none; background:#fff; border:1px solid #E8E6DF; border-radius:8px; padding:20px; margin-bottom:24px;">
            <div id="aieProgress" style="margin-bottom:12px; font-size:13px; color:#6B7280;"></div>
            <div id="aieSummary" style="display:none; padding:8px 12px; margin-bottom:12px; background:#FAFAF7; border-left:4px solid #4a6fa5; border-radius:0 4px 4px 0; font-size:13px;"></div>
            <table style="width:100%; border-collapse:collapse; font-size:13px;">
                <thead><tr style="background:#2c3e50; color:#fff;">
                    <th style="padding:8px; text-align:left; width:40px;">#</th>
                    <th style="padding:8px; text-align:left;">Question</th>
                    <th style="padding:8px; text-align:left;">Answer</th>
                    <th style="padding:8px; text-align:center; width:70px;">Grade</th>
                    <th style="padding:8px; text-align:left;">Reason</th>
                </tr></thead>
                <tbody id="aieResultsBody"></tbody>
            </table>
        </div>

        <!-- Section C: Past runs -->
        <div style="background:#fff; border:1px solid #E8E6DF; border-radius:8px; padding:20px;">
            <h3 style="margin:0 0 12px; font-size:14px; color:#2c3e50; text-transform:uppercase; letter-spacing:.5px;">Past runs</h3>
            <table style="width:100%; border-collapse:collapse; font-size:13px;">
                <thead><tr style="background:#2c3e50; color:#fff;">
                    <th style="padding:8px; text-align:left;">Date</th>
                    <th style="padding:8px; text-align:left;">Persona</th>
                    <th style="padding:8px; text-align:left;">Eval model</th>
                    <th style="padding:8px; text-align:center; width:60px;">Q's</th>
                    <th style="padding:8px; text-align:center; width:80px;">Avg</th>
                    <th style="padding:8px; text-align:center; width:60px;">Fails</th>
                    <th style="padding:8px; text-align:center; width:80px;">Δ</th>
                    <th style="padding:8px; text-align:center; width:160px;">Actions</th>
                </tr></thead>
                <tbody id="aiePastBody"><tr><td colspan="8" style="padding:16px; text-align:center; color:#6B7280;">Loading…</td></tr></tbody>
            </table>
        </div>
    </div>
</div>
```

- [ ] **Step 3: Add the script tag**

Find the existing `<script src="..."></script>` tags near the bottom of `index.html` and add:

```html
<script src="ai-eval.js"></script>
```

- [ ] **Step 4: Reload local and verify the tab is hidden for non-admin and visible for admin**

(Restart not needed for static-asset edits — Spring serves them from the JAR you already deployed in Task 11. If you deployed a fresh JAR, hard-refresh the browser.) Open `http://localhost:8090`, log in as `plmadmin`, look at the tab bar — there should be a new "AI Eval" button (visibility wired in Task 14 — you may need to manually `display:""` it via DevTools to confirm the panel renders).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat: AI Eval tab markup (config form + live + past runs)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 13: Frontend JS — `ai-eval.js`

**Files:**
- Create: `src/main/resources/static/ai-eval.js`

- [ ] **Step 1: Create the file**

```javascript
// AI Eval tab controller. Admin-only.

(function () {
    var resultsByIdx = {};   // qIndex -> result object (live updates)

    function el(id) { return document.getElementById(id); }
    function gradeColor(g) {
        if (g === 'A' || g === 'B') return '#1F8A4C';
        if (g === 'C') return '#C7801B';
        return '#B8342B'; // D, F, ERR
    }
    function gradePill(g) {
        return '<span style="display:inline-block; min-width:28px; padding:2px 8px; border-radius:10px; '
             + 'background:' + gradeColor(g) + '; color:#fff; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:12px; font-weight:600;">'
             + (g || '?') + '</span>';
    }
    function escHtml(s) {
        if (s == null) return '';
        return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
            .replace(/"/g,'&quot;').replace(/'/g,'&#39;');
    }
    function trunc(s, n) { s = s || ''; return s.length <= n ? s : s.substring(0, n) + '…'; }
    function fmtDate(iso) {
        if (!iso) return '';
        var d = new Date(iso);
        return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});
    }
    function personaCompact(p) {
        if (!p) return '';
        return escHtml(p.role) + ' · ' + escHtml(p.team) + ' · ' + escHtml(p.experience);
    }
    function modelShort(m) {
        if (!m) return '';
        var slash = m.lastIndexOf('/');
        return slash >= 0 ? m.substring(slash + 1) : m;
    }

    // --- Form: enforce different-model rule ---
    function syncModelDropdowns() {
        var t = el('aieTesterModel').value;
        var ev = el('aieEvaluatorModel');
        Array.prototype.forEach.call(ev.options, function (opt) {
            opt.disabled = (opt.value === t);
        });
        if (ev.value === t) {
            // Pick the first non-matching option
            for (var i = 0; i < ev.options.length; i++) {
                if (ev.options[i].value !== t) { ev.value = ev.options[i].value; break; }
            }
        }
    }
    function bindForm() {
        el('aieTesterModel').addEventListener('change', syncModelDropdowns);
        el('aieTeam').addEventListener('change', function () {
            var sel = el('aieTeam').value;
            el('aieTeamOther').style.display = (sel === '__other') ? '' : 'none';
        });
        el('aieRunBtn').addEventListener('click', startRun);
        syncModelDropdowns();
    }

    // --- Start a run ---
    function readPersonaConfig() {
        var teamSel = el('aieTeam').value;
        var team = teamSel === '__other' ? (el('aieTeamOther').value || '').trim() : teamSel;
        var exp = '';
        var radios = document.getElementsByName('aieExp');
        for (var i = 0; i < radios.length; i++) if (radios[i].checked) { exp = radios[i].value; break; }
        return {
            persona: {
                role: el('aieRole').value,
                team: team,
                experience: exp,
                goal: (el('aieGoal').value || '').trim()
            },
            testerModel: el('aieTesterModel').value,
            evaluatorModel: el('aieEvaluatorModel').value,
            questionCount: parseInt(el('aieQCount').value, 10),
            parentRunId: window.__aieParentRunId || null
        };
    }

    function startRun() {
        var cfg = readPersonaConfig();
        if (!cfg.persona.team) { alert('Team is required.'); return; }
        if (!cfg.persona.goal) { alert('Goal is required.'); return; }
        if (cfg.testerModel === cfg.evaluatorModel) { alert('Tester and Evaluator must use different models.'); return; }

        el('aieRunBtn').disabled = true;
        el('aieRunMsg').textContent = 'Starting…';
        resultsByIdx = {};
        el('aieResultsBody').innerHTML = '';
        el('aieSummary').style.display = 'none';
        el('aieLiveSection').style.display = '';
        el('aieProgress').textContent = 'Generating questions…';

        fetch('/api/ai-eval/runs', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(cfg)
        }).then(function (r) {
            return r.json().then(function (j) { return { ok: r.ok, body: j }; });
        }).then(function (resp) {
            if (!resp.ok) throw new Error(resp.body.error || 'failed to start');
            window.__aieParentRunId = null; // consumed
            openStream(resp.body.runId);
        }).catch(function (e) {
            el('aieRunBtn').disabled = false;
            el('aieRunMsg').textContent = 'Error: ' + e.message;
        });
    }

    // --- SSE stream ---
    function openStream(runId) {
        var es = new EventSource('/api/ai-eval/runs/' + runId + '/stream');
        var fellBackToPolling = false;

        es.addEventListener('questions-ready', function (e) {
            var d = JSON.parse(e.data);
            el('aieProgress').textContent = 'Generated ' + d.count + ' questions; running…';
            el('aieRunMsg').textContent = '';
        });
        es.addEventListener('answer-received', function (e) {
            var d = JSON.parse(e.data);
            renderRow(d.qIndex, d.question, d.answer, null, null);
            el('aieProgress').textContent = 'Question ' + d.qIndex + ' — grading…';
        });
        es.addEventListener('graded', function (e) {
            var d = JSON.parse(e.data);
            renderRow(d.qIndex, d.question, d.answer, d.grade, d.reason);
            el('aieProgress').textContent = 'Question ' + d.qIndex + ' graded.';
        });
        es.addEventListener('run-complete', function (e) {
            var d = JSON.parse(e.data);
            es.close();
            el('aieRunBtn').disabled = false;
            el('aieRunMsg').textContent = 'Done.';
            showSummary(d);
            loadPastRuns();
        });
        es.addEventListener('run-failed', function (e) {
            var d = JSON.parse(e.data);
            es.close();
            el('aieRunBtn').disabled = false;
            el('aieRunMsg').textContent = 'Run failed: ' + (d.error || 'unknown');
            loadPastRuns();
        });
        es.onerror = function () {
            if (fellBackToPolling) return;
            fellBackToPolling = true;
            es.close();
            pollUntilDone(runId);
        };
    }

    function pollUntilDone(runId) {
        var iv = setInterval(function () {
            fetch('/api/ai-eval/runs/' + runId, { credentials: 'same-origin' })
                .then(function (r) { return r.json(); })
                .then(function (run) {
                    if (run.status === 'RUNNING') return;
                    clearInterval(iv);
                    el('aieRunBtn').disabled = false;
                    if (run.status === 'DONE') {
                        // Re-render from final state
                        el('aieResultsBody').innerHTML = '';
                        run.results.forEach(function (r) { renderRow(r.qIndex, r.question, r.answer, r.grade, r.reason); });
                        showSummary(run.summary);
                    } else {
                        el('aieRunMsg').textContent = 'Run failed.';
                    }
                    loadPastRuns();
                });
        }, 3000);
    }

    function renderRow(qIdx, q, a, grade, reason) {
        resultsByIdx[qIdx] = { qIndex: qIdx, question: q, answer: a, grade: grade, reason: reason };
        var existing = document.getElementById('aieRow-' + qIdx);
        var html = '<td style="padding:8px; border-bottom:1px solid #E8E6DF;">' + qIdx + '</td>'
                 + '<td style="padding:8px; border-bottom:1px solid #E8E6DF;" title="' + escHtml(q) + '">' + escHtml(trunc(q, 80)) + '</td>'
                 + '<td style="padding:8px; border-bottom:1px solid #E8E6DF;" title="' + escHtml(a || '') + '">' + escHtml(trunc(a || '…', 80)) + '</td>'
                 + '<td style="padding:8px; border-bottom:1px solid #E8E6DF; text-align:center;">' + (grade ? gradePill(grade) : '<span style="color:#6B7280;">…</span>') + '</td>'
                 + '<td style="padding:8px; border-bottom:1px solid #E8E6DF; color:#6B7280;">' + escHtml(reason || '') + '</td>';
        if (existing) {
            existing.innerHTML = html;
        } else {
            var tr = document.createElement('tr');
            tr.id = 'aieRow-' + qIdx;
            tr.innerHTML = html;
            el('aieResultsBody').appendChild(tr);
        }
    }

    function showSummary(s) {
        el('aieSummary').style.display = '';
        el('aieSummary').innerHTML = '<strong>' + (s.failureCount + (Object.keys(resultsByIdx).length - s.failureCount)) + ' questions</strong> · '
            + 'avg grade <strong>' + escHtml(s.avgGradeLetter) + '</strong> (' + escHtml(String(s.avgGradeNumeric)) + ') · '
            + '<strong>' + s.failureCount + '</strong> failures (≤B)';
    }

    // --- Past runs ---
    function loadPastRuns() {
        fetch('/api/ai-eval/runs', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (runs) { renderPastRuns(runs); })
            .catch(function () { el('aiePastBody').innerHTML = '<tr><td colspan="8" style="padding:16px; text-align:center; color:#B8342B;">Failed to load past runs.</td></tr>'; });
    }

    function deltaForRun(run, allRuns) {
        if (!run.summary) return null;
        for (var i = 0; i < allRuns.length; i++) {
            var prev = allRuns[i];
            if (prev.runId === run.runId) continue;
            if (new Date(prev.createdAt) >= new Date(run.createdAt)) continue;
            if (!prev.config || !prev.summary) continue;
            if (configsMatch(prev.config, run.config)) {
                return run.summary.avgGradeNumeric - prev.summary.avgGradeNumeric;
            }
        }
        return null;
    }

    function configsMatch(a, b) {
        return a.testerModel === b.testerModel
            && a.evaluatorModel === b.evaluatorModel
            && a.questionCount === b.questionCount
            && a.persona && b.persona
            && a.persona.role === b.persona.role
            && a.persona.team === b.persona.team
            && a.persona.experience === b.persona.experience
            && a.persona.goal === b.persona.goal;
    }

    function renderPastRuns(runs) {
        if (!runs || !runs.length) {
            el('aiePastBody').innerHTML = '<tr><td colspan="8" style="padding:16px; text-align:center; color:#6B7280;">No runs yet — start one above.</td></tr>';
            return;
        }
        var html = '';
        runs.forEach(function (run) {
            var delta = deltaForRun(run, runs);
            var deltaCell = '—';
            if (delta != null && !isNaN(delta)) {
                var arrow = delta >= 0 ? '↑' : '↓';
                var color = delta >= 0 ? '#1F8A4C' : '#B8342B';
                deltaCell = '<span style="color:' + color + ';">' + arrow + ' ' + (delta >= 0 ? '+' : '') + delta.toFixed(1) + '</span>';
            }
            var statusBadge = run.status === 'DONE' ? '' :
                (run.status === 'FAILED' ? '<span style="color:#B8342B; font-size:11px;"> (failed)</span>' :
                 '<span style="color:#6B7280; font-size:11px;"> (running)</span>');
            html += '<tr>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF;">' + escHtml(fmtDate(run.createdAt)) + statusBadge + '</td>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF;">' + personaCompact(run.config && run.config.persona) + '</td>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px;">' + escHtml(modelShort(run.config && run.config.evaluatorModel)) + '</td>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF; text-align:center;">' + (run.config ? run.config.questionCount : '') + '</td>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF; text-align:center;">' + (run.summary ? gradePill(run.summary.avgGradeLetter) : '—') + '</td>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF; text-align:center;">' + (run.summary ? run.summary.failureCount : '—') + '</td>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF; text-align:center;">' + deltaCell + '</td>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF; text-align:center;">'
                +   '<button onclick="aieRerun(\'' + run.runId + '\')" title="Rerun this config" style="background:#fff; border:1px solid #4a6fa5; color:#4a6fa5; padding:3px 8px; border-radius:3px; font-size:11px; cursor:pointer; margin-right:4px;">Rerun</button>'
                +   '<button onclick="aieExport(\'' + run.runId + '\')" title="Export markdown for Claude" style="background:#fff; border:1px solid #6B7280; color:#6B7280; padding:3px 8px; border-radius:3px; font-size:11px; cursor:pointer; margin-right:4px;">Export</button>'
                +   '<button onclick="aieToggleExpand(\'' + run.runId + '\')" title="Show all questions" style="background:#fff; border:1px solid #E8E6DF; color:#6B7280; padding:3px 8px; border-radius:3px; font-size:11px; cursor:pointer;">▾</button>'
                + '</td>'
                + '</tr>'
                + '<tr id="aieExpand-' + run.runId + '" style="display:none;"><td colspan="8" style="padding:0; background:#FAFAF7;"><div style="padding:12px;"><em style="color:#6B7280;">Loading…</em></div></td></tr>';
        });
        el('aiePastBody').innerHTML = html;
    }

    window.aieRerun = function (runId) {
        fetch('/api/ai-eval/runs/' + runId, { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (run) {
                if (!run.config) return;
                // Pre-fill the form
                el('aieRole').value = run.config.persona.role;
                if (['PLM IT','Quality','Engineering','Operations'].indexOf(run.config.persona.team) >= 0) {
                    el('aieTeam').value = run.config.persona.team;
                    el('aieTeamOther').style.display = 'none';
                } else {
                    el('aieTeam').value = '__other';
                    el('aieTeamOther').style.display = '';
                    el('aieTeamOther').value = run.config.persona.team;
                }
                Array.prototype.forEach.call(document.getElementsByName('aieExp'), function (r) {
                    r.checked = (r.value === run.config.persona.experience);
                });
                el('aieGoal').value = run.config.persona.goal;
                el('aieTesterModel').value = run.config.testerModel;
                el('aieEvaluatorModel').value = run.config.evaluatorModel;
                el('aieQCount').value = String(run.config.questionCount);
                syncModelDropdowns();
                window.__aieParentRunId = runId;
                startRun();
            });
    };

    window.aieExport = function (runId) {
        fetch('/api/ai-eval/runs/' + runId + '/export', { method: 'POST', credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (j) {
                if (j.path) {
                    el('aieRunMsg').textContent = 'Exported to ' + j.path + ' — open in VS Code and ask Claude to review.';
                    setTimeout(function () { el('aieRunMsg').textContent = ''; }, 8000);
                } else {
                    el('aieRunMsg').textContent = 'Export failed: ' + (j.error || 'unknown');
                }
            });
    };

    window.aieToggleExpand = function (runId) {
        var row = document.getElementById('aieExpand-' + runId);
        if (!row) return;
        if (row.style.display !== 'none') { row.style.display = 'none'; return; }
        row.style.display = '';
        fetch('/api/ai-eval/runs/' + runId + '/results', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                var inner = '<div style="padding:12px;"><table style="width:100%; border-collapse:collapse; font-size:12px;">'
                    + '<thead><tr style="color:#6B7280; text-align:left;"><th style="padding:6px;">#</th><th style="padding:6px;">Question</th><th style="padding:6px;">Answer</th><th style="padding:6px; width:60px; text-align:center;">Grade</th><th style="padding:6px;">Reason</th></tr></thead><tbody>';
                (data.results || []).forEach(function (r) {
                    inner += '<tr><td style="padding:6px; vertical-align:top;">' + r.qIndex + '</td>'
                          +  '<td style="padding:6px; vertical-align:top;">' + escHtml(r.question) + '</td>'
                          +  '<td style="padding:6px; vertical-align:top;">' + escHtml(r.answer || '') + '</td>'
                          +  '<td style="padding:6px; vertical-align:top; text-align:center;">' + gradePill(r.grade) + '</td>'
                          +  '<td style="padding:6px; vertical-align:top; color:#6B7280;">' + escHtml(r.reason || '') + '</td></tr>';
                });
                inner += '</tbody></table></div>';
                row.firstChild.innerHTML = inner;
            });
    };

    // --- Init when the tab becomes visible ---
    var initialized = false;
    window.aieInit = function () {
        if (initialized) return;
        initialized = true;
        bindForm();
        loadPastRuns();
    };

    // Also init on DOM ready in case the tab is open by URL deep-link (it isn't, but cheap insurance)
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            if (document.getElementById('panelAiEval') && document.getElementById('panelAiEval').style.display !== 'none') {
                window.aieInit();
            }
        });
    }
})();
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/ai-eval.js
git commit -m "feat: ai-eval.js — form, EventSource, past runs, rerun, export

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 14: Wire the new tab into `app.js`

**Files:**
- Modify: `src/main/resources/static/app.js`

- [ ] **Step 1: Add panel toggle in `switchTab()`**

In `app.js`, find the `switchTab(tab)` function (line ~979). After the `panelHelpCenter` toggle (line ~1003), add:

```javascript
var panelAie = document.getElementById('panelAiEval');
if (panelAie) {
    var show = (tab === 'aieval');
    panelAie.style.display = show ? '' : 'none';
    if (show && typeof window.aieInit === 'function') window.aieInit();
}
```

Then find the active-class-toggle block for tab buttons (line ~1004 onwards). After the `tabHelpCenter` className block (or wherever the conditional ones live), add:

```javascript
var tabAieEl = document.getElementById('tabAiEval');
if (tabAieEl) tabAieEl.className = tab === 'aieval' ? 'tab active' : 'tab';
```

- [ ] **Step 2: Add to `TAB_CONFIG` (admin-only)**

Find `TAB_CONFIG` (around line 1981). Append after the `ecnreport` entry:

```javascript
{ id: 'tabAiEval', label: 'AI Eval', key: 'aieval', adminOnly: true }
```

- [ ] **Step 3: Verify the admin-show logic includes AiEval**

Find the block that shows admin-only tabs after a successful login. It currently lives near line 188 inside the login response handler:

```javascript
if (data.isPlmAdmin) {
    var reportsTab = document.getElementById('tabReports');
    if (reportsTab) reportsTab.style.display = '';
    var extTab = document.getElementById('tabExtensions');
    if (extTab) extTab.style.display = '';
    var ecnReportTab = document.getElementById('tabEcnReport');
    if (ecnReportTab) ecnReportTab.style.display = '';
    // ... add this:
    var aieTab = document.getElementById('tabAiEval');
    if (aieTab) aieTab.style.display = '';
    // ...
}
```

Add the analogous show line for `tabAiEval` to ALL the admin-show locations (search for `tabReports.style.display = ''` to find them — there's typically a primary login handler and a session-restore handler).

Also add `tabAiEval` to any non-admin hide blocks (search for `tabReports.style.display = 'none'`):

```javascript
var aieTab = document.getElementById('tabAiEval');
if (aieTab) aieTab.style.display = 'none';
```

- [ ] **Step 4: Build a fresh JAR and reload**

```bash
cd ~/git/plm-field-tracker && mvn -q -DskipTests package
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
PID=$(lsof -ti:8090 || true); [ -n "$PID" ] && kill "$PID" && sleep 2
cd ~/Documents/plm-toolkit\ 2 && nohup java -Xmx2g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties > /tmp/plm-toolkit-local.log 2>&1 &
sleep 8
```

Open `http://localhost:8090`, log in as `plmadmin`. Verify: tab "AI Eval" is visible. Click it. The config form, empty live section, and "No runs yet — start one above." past-runs row all render. The form's Tester=Claude/Evaluator=GPT-4o defaults are correct, and changing Tester to GPT-4o disables the GPT-4o option in the Evaluator dropdown.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/app.js
git commit -m "feat: wire AI Eval tab into app.js (switchTab, TAB_CONFIG, admin gate)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 15: `.gitignore` + What's New entry

**Files:**
- Modify: `.gitignore`
- Modify: `src/main/resources/static/whats-new.js`

- [ ] **Step 1: Update `.gitignore`**

Read current `.gitignore`:

```bash
cat ~/git/plm-field-tracker/.gitignore
```

Append `debug-output/` if it isn't already present:

```
debug-output/
```

- [ ] **Step 2: Add a What's New entry**

Open `src/main/resources/static/whats-new.js` and prepend a new release object at the top of `WHATS_NEW_RELEASES`:

```javascript
{
    date: 'May 3, 2026',
    title: 'AI Eval tab \u2014 grade the chatbot with a different model (admin-only)',
    items: [
        { badge: 'new', admin: true, text: '<strong>New AI Eval tab</strong> for admins. Configure a Tester persona (role, team, experience, goal) and an Evaluator model, click <em>Run eval</em>, and the Tester auto-generates 5\u201350 questions for that persona, fires them at the AI Help chatbot, and the Evaluator grades each answer A\u2013F with a one-sentence reason. Live progress streams via SSE; runs persist to <code>./cache/ai-eval-runs.json</code>. Past runs table shows \u0394-grade vs the prior run with the same config so you can see whether a fix actually helped.' },
        { badge: 'new', admin: true, text: '<strong>Three Vortex providers</strong> available for both Tester and Evaluator: Claude Sonnet 4.6 (<code>@anthropic-eastus2</code>), GPT-4o (<code>@openai-eastus2</code>), Gemini 2.5 Pro (<code>@vertexai-global</code>). Tester and Evaluator must use different models \u2014 enforced in both UI and backend.' },
        { badge: 'new', admin: true, text: '<strong>\u201CExport for Claude\u201D button</strong> on every past run writes a focused failure-only markdown brief to <code>./debug-output/eval-latest.md</code>. Open it in VS Code and ask Claude to review \u2014 the brief includes the run config, the AI Help system prompt snapshotted at export time, and the failed Q/A/grade/reason rows so Claude can identify systemic issues to fix.' },
        { badge: 'improve', text: '<strong>PortkeyClient refactor</strong> \u2014 the duplicated <code>HttpURLConnection</code> + Vortex-call code that lived in 7 service classes is now one helper. No behavior change for existing AI features.' }
    ]
},
```

- [ ] **Step 3: Commit**

```bash
git add .gitignore src/main/resources/static/whats-new.js
git commit -m "chore: gitignore debug-output/ + What's New entry for AI Eval tab

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 16: End-to-end smoke checklist

Build, deploy, and run through every item from the spec's manual smoke checklist.

- [ ] **Step 1: Final build & deploy**

```bash
cd ~/git/plm-field-tracker && mvn -q test    # runs the JSON round-trip test
cd ~/git/plm-field-tracker && mvn -q -DskipTests package
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
# (skip the /Volumes/uls-ep-aglipccb copy until prod rollout — local-first per CLAUDE.md guidance)
PID=$(lsof -ti:8090 || true); [ -n "$PID" ] && kill "$PID" && sleep 2
cd ~/Documents/plm-toolkit\ 2 && nohup java -Xmx2g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties > /tmp/plm-toolkit-local.log 2>&1 &
sleep 8
```

Expected: JUnit test passes (1 test, 0 failures). Local instance up on `:8090`.

- [ ] **Step 2: Spec smoke checklist (each item from the spec)**

Walk through each box in the spec's `Manual smoke checklist` section. Tick them off here as you verify:

- [ ] Happy path: log in as plmadmin → AI Eval tab → CIO persona, 5 Q's, Tester=Claude, Evaluator=GPT-4o → Run → 5 rows render with grades, summary shows avg + failure count.
- [ ] Same-model rejection (frontend): Tester=Claude → Claude option in Evaluator dropdown becomes disabled.
- [ ] Same-model rejection (backend): bypass via curl (see Task 11 step 5) → HTTP 400.
- [ ] Mid-run Vortex failure: `sudo sh -c 'echo "127.0.0.1 ai.vortex.sandisk.com" >> /etc/hosts'` during a 5-Q run → that question shows `ERR`, run continues, finishes with partial results. Then `sudo sed -i '' '/ai.vortex.sandisk.com/d' /etc/hosts` to undo.
- [ ] Malformed JSON retry: temporarily edit `generateQuestions` system prompt to "Output the questions as a numbered prose list" → run → log shows the retry path triggered (search for `PREVIOUS ATTEMPT WAS REJECTED` in log). Revert the prompt and rebuild.
- [ ] Server restart mid-run: start a 20-Q run, after question ~5 `kill -9 $(lsof -ti:8090)`, restart, hit `/api/ai-eval/runs` → the in-flight run is now `FAILED` with reason `server-restart-orphan`.
- [ ] Rerun: click Rerun on an existing past row → new run appears with `parentRunId` set (verify in `cache/ai-eval-runs.json`), Δ column populated on the new row.
- [ ] Export for Claude: click Export → `~/Documents/plm-toolkit\ 2/debug-output/eval-latest.md` exists, contains failed-only Q/A/reasons + the AI Help system prompt + the "What I'd like you to do" block.
- [ ] SSE drop: kill the browser tab mid-run, reopen tab → past-runs table shows the completed run with full data once polling fallback (or page reload) catches up.

- [ ] **Step 3: Commit any tweaks**

If smoke surfaced bugs, fix them and commit. Otherwise:

```bash
git status     # should be clean
git log --oneline -8    # confirm the AI Eval commits
```

---

## Self-review

After writing this plan, comparing it against the spec sections:

| Spec section | Plan task |
|---|---|
| Architecture: PortkeyClient refactor | Task 1, 2, 3 |
| Architecture: AiEvalService | Task 6, 7, 8, 9 |
| Architecture: AiEvalController | Task 10 |
| Architecture: frontend (HTML + JS) | Task 12, 13, 14 |
| Architecture: no new infra | (no task — implicit) |
| Provider/model strings | Task 12 (HTML dropdown options) |
| Data flow steps A/B/C | Task 7 (A) + Task 8 (B+C) |
| Endpoints (POST /runs, GET /runs, /runs/{id}, /stream, /results, /export) | Task 10 (all six) |
| UI Section A (config form) | Task 12 |
| UI Section B (live run) | Task 12 (markup) + Task 13 (rendering) |
| UI Section C (past runs + rerun + export buttons + Δ column) | Task 12 (markup) + Task 13 (rendering + handlers) |
| Look & feel matches CLAUDE.md palette | Task 12 (inline styles use exact palette) |
| Persistence schema | Task 4 (model) + Task 6 (load/save with version field) |
| Δ-vs-previous (computed at read time) | Task 13 (`deltaForRun` + `configsMatch`) |
| Export-for-Claude markdown format | Task 9 |
| Error handling (Tester fail, AI Help fail, eval fail, malformed JSON, server restart, SSE drop, same-model, invalid persona, cache corrupt) | Task 6 (orphan + corrupt), Task 7 (malformed retry), Task 8 (per-question err), Task 13 (SSE drop fallback), Task 8 + Task 10 (validation) |
| Observability log line | Task 7, Task 8 |
| Testing — manual smoke checklist | Task 16 |
| Testing — JSON round-trip JUnit | Task 5 |
| Testing — production validation plan | not in this plan (deferred to prod rollout later in week) |
| Deployment — local first | Task 11, Task 14, Task 16 |
| Deployment — prod | not in this plan (deferred) |
| Out of scope items | (correctly absent) |

No coverage gaps. Type/method names are consistent across tasks (`startRun`, `getRun`, `listRunsLight`, `registerEmitter`, `exportForClaude`, `generateQuestions`, `gradeAnswer`, `tryParseStringArray`, `tryParseGrade`, `finalizeSummary`, `letterToNumeric`, `numericToLetter`). No "TBD" / "implement later" / unresolved placeholders.

One thing to call out for the executing engineer: **Task 8 step 3** notes a possible Spring circular bean dependency (`AiEvalService` ↔ `AiHelpController`). If it bites, the fix (lazy autowire on `AiHelpController`) is in the same step.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-03-ai-eval-tab.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration. Each subagent gets one Task block to execute and the spec for context. Best when you want me to keep oversight without burning my own context on every line of generated code.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints. I do every task myself. Best when you want me to keep the full context of the running app and adjust code on the fly as smoke tests reveal issues.

Which approach?
