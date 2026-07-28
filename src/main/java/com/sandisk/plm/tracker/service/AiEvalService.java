package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandisk.plm.tracker.model.AiEvalRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    // Lazy to avoid circular bean dependency with AiHelpController, which
    // also depends on services in this package.
    @Autowired
    @Lazy
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

    /** Full list (newest first), including results. Used by the Excel export. */
    public synchronized List<AiEvalRun> listRunsFull() {
        List<AiEvalRun> out = new ArrayList<>(runs.size());
        for (int i = runs.size() - 1; i >= 0; i--) out.add(runs.get(i));
        return out;
    }

    /** List view (newest first), with `results[]` cleared to keep payloads light. */
    public synchronized List<AiEvalRun> listRunsLight() {
        List<AiEvalRun> out = new ArrayList<>(runs.size());
        for (int i = runs.size() - 1; i >= 0; i--) {
            AiEvalRun src = runs.get(i);
            // Backfill avgAnswerLatencyMs for older runs that pre-date the field.
            if (src.summary != null && src.summary.avgAnswerLatencyMs == 0
                    && src.results != null && !src.results.isEmpty()) {
                long total = 0; int count = 0;
                for (AiEvalRun.Result r : src.results) {
                    if (!"ERR".equals(r.grade) && r.answerLatencyMs > 0) {
                        total += r.answerLatencyMs;
                        count++;
                    }
                }
                if (count > 0) src.summary.avgAnswerLatencyMs = total / count;
            }
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
        c.regradeOfRunId = src.regradeOfRunId;
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

    /**
     * Asks the Tester model to generate `count` distinct questions for the given persona.
     * Returns the list. On malformed JSON, retries once with a stricter prompt.
     * Throws on persistent failure or transport error.
     */
    List<String> generateQuestions(AiEvalRun.RunConfig config, String runId) throws Exception {
        String sys;
        AiEvalRun.Persona p = config.persona;
        if (p.simulatedUsername != null && !p.simulatedUsername.isEmpty()) {
            // V2: enriched prompt with verbatim AD demographics for a real user.
            StringBuilder demo = new StringBuilder();
            demo.append("DEMOGRAPHICS:\n");
            demo.append("- Display name: ").append(p.simulatedDisplayName == null ? p.simulatedUsername : p.simulatedDisplayName).append("\n");
            if (p.realTitle != null && !p.realTitle.isEmpty()) demo.append("- Title: ").append(p.realTitle).append("\n");
            if (p.realDepartment != null && !p.realDepartment.isEmpty()) demo.append("- Department: ").append(p.realDepartment).append("\n");
            if (p.realAccountAge != null && !p.realAccountAge.isEmpty()) demo.append("- Account age at SanDisk: ").append(p.realAccountAge).append("\n");
            if (p.realLoginCount90d != null) demo.append("- Tool usage: ").append(p.realLoginCount90d).append(" logins in the last 90 days\n");
            if (p.realIsPlmAdmin != null) demo.append("- PLM admin group membership: ").append(p.realIsPlmAdmin ? "YES" : "NO").append("\n");
            demo.append("- Closest abstract bucket: ").append(p.role).append(" / ").append(p.team).append(" / ").append(p.experience).append("\n");

            sys = "You are simulating a real PLM Toolkit user.\n\n"
                + demo.toString()
                + "\nGOAL THIS SESSION: " + p.goal + "\n\n"
                + "Generate exactly " + config.questionCount + " distinct, realistic questions you would ask "
                + "the AI Help chatbot inside this app. Use the demographics above to shape your questions in "
                + "the actual voice and concern level of THIS specific person — not just the abstract role. "
                + "For example, a hands-on senior PLM admin asks operational/edge-case questions about features "
                + "they use daily; a 6-month-tenure analyst in Operations asks discoverability questions about "
                + "features they're new to.\n\n"
                + "OUTPUT REQUIREMENTS: Return ONLY a JSON array of " + config.questionCount + " strings. "
                + "No prose, no markdown fences, no preamble. Example: [\"q1\", \"q2\", \"q3\"]";
        } else {
            // V1: abstract-bucket prompt unchanged.
            sys = "You are simulating a " + p.role
                + " on the " + p.team + " team"
                + " with " + p.experience + " experience"
                + " using a PLM data toolkit web app, and you are trying to: " + p.goal + ". "
                + "Generate exactly " + config.questionCount + " distinct, realistic questions"
                + " you would ask the AI Help chatbot inside this app. Vary the questions to cover"
                + " happy-path tasks, edge cases, and things you might be confused about as someone"
                + " with that experience level. "
                + "OUTPUT REQUIREMENTS: Return ONLY a JSON array of " + config.questionCount + " strings."
                + " No prose, no markdown fences, no preamble. Example: [\"q1\", \"q2\", \"q3\"]";
        }

        long t0 = System.currentTimeMillis();
        int budget = budgetForTester(config.testerModel, config.questionCount);
        String raw;
        try {
            raw = portkeyClient.chat(config.testerModel, sys, "Generate the questions now.", budget);
        } catch (Exception e) {
            logger.warning("[AI EVAL] runId=" + runId + " stage=tester model=" + config.testerModel
                + " ms=" + (System.currentTimeMillis() - t0) + " status=err err=" + e.getMessage());
            throw e;
        }
        logger.info("[AI EVAL] runId=" + runId + " stage=tester model=" + config.testerModel
            + " ms=" + (System.currentTimeMillis() - t0) + " budget=" + budget + " status=ok");

        List<String> qs = tryParseStringArray(raw);
        if (qs == null) {
            String stricter = sys + "\n\nPREVIOUS ATTEMPT WAS REJECTED FOR NON-JSON OUTPUT. YOU MUST RETURN ONLY A JSON ARRAY OF STRINGS, NOTHING ELSE.";
            raw = portkeyClient.chat(config.testerModel, stricter, "Generate the questions now.", budget);
            qs = tryParseStringArray(raw);
        }
        if (qs == null) {
            logger.warning("[AI EVAL] runId=" + runId + " stage=tester model=" + config.testerModel
                + " budget=" + budget + " malformedRaw=" + raw);
            throw new RuntimeException("Tester returned malformed or truncated JSON twice. "
                + "Try a different tester model, or rerun (raw output logged server-side).");
        }
        if (qs.size() > config.questionCount) qs = qs.subList(0, config.questionCount);
        return qs;
    }

    private List<String> tryParseStringArray(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        // Strip any markdown code fence — handles both multi-line (```json\n[...]\n```)
        // and single-line (```json [...] ```) wrappers, plus optional language tag.
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3);
            // Drop a leading language tag like "json" or "JSON" (with optional whitespace).
            trimmed = trimmed.replaceFirst("^[a-zA-Z]+\\s*", "").trim();
        }
        // Last resort: locate the JSON array within whatever surrounding noise remains.
        if (!trimmed.startsWith("[")) {
            int lb = trimmed.indexOf('[');
            int rb = trimmed.lastIndexOf(']');
            if (lb >= 0 && rb > lb) trimmed = trimmed.substring(lb, rb + 1);
        }
        if (!trimmed.startsWith("[")) return null;
        try {
            return om.readValue(trimmed, om.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * V3 — start a Regrade. Creates a new run that REUSES the parent's questions
     * and (when {@code newChatbotModel} matches the parent's chatbot) AI Help answers,
     * only re-running the Evaluator step with a new model.
     *
     * <p>If {@code newChatbotModel} differs from the parent's chatbot, AI Help is
     * re-run for each question with the new chatbot, then the evaluator grades the
     * new answers. Useful for asking "what if AI Help used model X instead?"
     *
     * @param newChatbotModel  null = keep parent's chatbot (pure regrade); non-null = override and re-run AI Help.
     *                         Special sentinel "" or "__default__" treated as null.
     * @param forceRerunAiHelp when true, re-runs AI Help even if the chatbot model is unchanged.
     *                         Use this after the AI Help system prompt or knowledge base has been edited
     *                         and you want fresh answers from the same chatbot model.
     */
    public AiEvalRun startRegrade(String parentRunId, String newEvaluatorModel, String newChatbotModel,
                                   boolean forceRerunAiHelp, String createdBy) {
        if (isBlank(parentRunId)) throw new IllegalArgumentException("parentRunId is required");
        if (isBlank(newEvaluatorModel)) throw new IllegalArgumentException("evaluatorModel is required");

        AiEvalRun parent = getRun(parentRunId);
        if (parent == null) throw new IllegalArgumentException("parent run not found: " + parentRunId);
        if (!"DONE".equals(parent.status)) {
            throw new IllegalArgumentException("parent run must be DONE (status: " + parent.status + ")");
        }
        if (parent.results == null || parent.results.isEmpty()) {
            throw new IllegalArgumentException("parent run has no results to regrade");
        }

        // Normalize the chatbot override. Treat blank / sentinel as "keep parent's chatbot".
        String chatbotOverride = (isBlank(newChatbotModel) || "__default__".equals(newChatbotModel))
            ? null : newChatbotModel;
        boolean chatbotChanged = chatbotOverride != null
            && !chatbotOverride.equals(parent.config.chatbotModel);
        boolean rerunAiHelp = chatbotChanged || forceRerunAiHelp;

        if (!rerunAiHelp && newEvaluatorModel.equals(parent.config.evaluatorModel)) {
            throw new IllegalArgumentException("Choose a different evaluator than the original ("
                + parent.config.evaluatorModel + "), or change the chatbot, or check Re-ask AI Help.");
        }
        // Tester isn't running during regrade (questions are frozen), so Tester-vs-Chatbot
        // and Tester-vs-Evaluator collisions don't matter. Only Chatbot-vs-Evaluator does
        // (we don't want a model grading itself).
        if (chatbotOverride != null && chatbotOverride.equals(newEvaluatorModel)) {
            throw new IllegalArgumentException("Chatbot and Evaluator must use different models.");
        }

        // Build new RunConfig: same persona/tester/QC, NEW evaluator, optional NEW chatbot.
        AiEvalRun.RunConfig cfg = new AiEvalRun.RunConfig();
        cfg.persona = parent.config.persona;
        cfg.chatbotModel = chatbotChanged ? chatbotOverride : parent.config.chatbotModel;
        cfg.testerModel = parent.config.testerModel;
        cfg.evaluatorModel = newEvaluatorModel;
        cfg.questionCount = parent.config.questionCount;
        // Persist the "AI Help re-ran with same chatbot" intent so downstream UI can adapt the banner.
        cfg.forceRerunAiHelp = forceRerunAiHelp && !chatbotChanged;

        AiEvalRun run = new AiEvalRun();
        run.runId = UUID.randomUUID().toString();
        run.createdAt = Instant.now().toString();
        run.createdBy = createdBy;
        run.status = "RUNNING";
        run.parentRunId = parentRunId;
        run.regradeOfRunId = parentRunId;
        run.config = cfg;

        synchronized (this) {
            runs.add(run);
            save();
        }

        // Worker reuses parent's results (question + answer) only when AI Help isn't being re-run.
        final AiEvalRun parentSnapshot = parent;
        final boolean rerunFinal = rerunAiHelp;
        Thread w = new Thread(() -> executeRegrade(run, parentSnapshot, rerunFinal),
                              "ai-eval-regrade-" + run.runId.substring(0, 8));
        w.setDaemon(true);
        w.start();

        return run;
    }

    private void executeRegrade(AiEvalRun run, AiEvalRun parent, boolean rerunAiHelp) {
        long t0 = System.currentTimeMillis();
        // When AI Help should re-run (chatbot swap, OR same chatbot but the prompt/KB
        // changed and the user checked the Re-ask box), call AI Help fresh per question.
        // Otherwise reuse the parent's stored answers (original V3 pure-regrade path).
        boolean chatbotChanged = rerunAiHelp;
        try {
            emit(run.runId, "questions-ready", Map.of("count", parent.results.size()));

            for (AiEvalRun.Result parentR : parent.results) {
                int qIdx = parentR.qIndex;
                String q = parentR.question;

                AiEvalRun.Result r = new AiEvalRun.Result();
                r.qIndex = qIdx;
                r.question = q;

                // Emit the question first regardless (UI renders the spinner).
                Map<String, Object> qAsked = new LinkedHashMap<>();
                qAsked.put("qIndex", qIdx); qAsked.put("question", q);
                emit(run.runId, "question-asked", qAsked);

                String a;
                if (chatbotChanged) {
                    // Re-run AI Help with the new chatbot model.
                    long ta = System.currentTimeMillis();
                    try {
                        a = aiHelpController.askAiHelpForEval(q, "ai-eval", run.createdBy,
                                personaIsAdmin(run.config),
                                run.config.chatbotModel, personaGoal(run.config));
                        r.answer = a;
                        r.answerLatencyMs = System.currentTimeMillis() - ta;
                    } catch (Exception e) {
                        r.answer = "";
                        r.answerLatencyMs = System.currentTimeMillis() - ta;
                        r.grade = "ERR";
                        r.reason = "AI Help (" + run.config.chatbotModel + ") failed: " + e.getMessage();
                        synchronized (this) { run.results.add(r); save(); }
                        Map<String, Object> grEvt0 = new LinkedHashMap<>();
                        grEvt0.put("qIndex", qIdx); grEvt0.put("question", q); grEvt0.put("answer", "");
                        grEvt0.put("grade", "ERR"); grEvt0.put("reason", r.reason);
                        emit(run.runId, "graded", grEvt0);
                        continue;
                    }
                } else {
                    a = parentR.answer;
                    r.answer = a;
                    // Preserve parent's AI Help latency (no new AI Help call happened).
                    r.answerLatencyMs = parentR.answerLatencyMs;
                }

                Map<String, Object> ansEvt = new LinkedHashMap<>();
                ansEvt.put("qIndex", qIdx); ansEvt.put("question", q); ansEvt.put("answer", a == null ? "" : a);
                emit(run.runId, "answer-received", ansEvt);

                // If we ended up with no answer (parent errored OR the new chatbot returned empty), can't grade.
                if (a == null || a.isEmpty()) {
                    r.grade = "ERR";
                    r.reason = chatbotChanged ? "New chatbot returned empty answer." : "Parent run had no AI Help answer to regrade.";
                    r.gradeLatencyMs = 0;
                    synchronized (this) { run.results.add(r); save(); }
                    Map<String, Object> grEvt = new LinkedHashMap<>();
                    grEvt.put("qIndex", qIdx); grEvt.put("question", q); grEvt.put("answer", "");
                    grEvt.put("grade", "ERR"); grEvt.put("reason", r.reason);
                    emit(run.runId, "graded", grEvt);
                    continue;
                }

                long tg = System.currentTimeMillis();
                try {
                    Map<String, String> graded = gradeAnswer(run.config, q, a);
                    r.grade = graded.get("grade");
                    r.reason = graded.get("reason");
                    r.gradeLatencyMs = System.currentTimeMillis() - tg;
                    logger.info("[AI EVAL] runId=" + run.runId + " stage=regrade qIndex=" + qIdx
                        + " ms=" + r.gradeLatencyMs + " status=ok grade=" + r.grade);
                } catch (Exception e) {
                    r.grade = "ERR";
                    r.reason = "Evaluator failed: " + e.getMessage();
                    r.gradeLatencyMs = System.currentTimeMillis() - tg;
                    logger.warning("[AI EVAL] runId=" + run.runId + " stage=regrade qIndex=" + qIdx
                        + " ms=" + r.gradeLatencyMs + " status=err err=" + e.getMessage());
                }
                synchronized (this) { run.results.add(r); save(); }
                Map<String, Object> grEvt = new LinkedHashMap<>();
                grEvt.put("qIndex", qIdx); grEvt.put("question", q); grEvt.put("answer", a);
                grEvt.put("grade", r.grade); grEvt.put("reason", r.reason == null ? "" : r.reason);
                emit(run.runId, "graded", grEvt);
            }

            finalizeSummary(run);
            run.status = "DONE";
            run.summary.totalLatencyMs = System.currentTimeMillis() - t0;
            synchronized (this) { save(); }
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("avgGradeNumeric", run.summary.avgGradeNumeric);
            done.put("avgGradeLetter", run.summary.avgGradeLetter);
            done.put("failureCount", run.summary.failureCount);
            emit(run.runId, "run-complete", done);
        } catch (Exception fatal) {
            run.status = "FAILED";
            run.errors.add(fatal.getMessage() == null ? fatal.getClass().getSimpleName() : fatal.getMessage());
            synchronized (this) { save(); }
            logger.warning("[AI EVAL] regrade runId=" + run.runId + " failed: " + fatal.getMessage());
            emit(run.runId, "run-failed", Map.of("error", fatal.getMessage() == null ? "unknown" : fatal.getMessage()));
        } finally {
            SseEmitter em = emitters.get(run.runId);
            if (em != null) try { em.complete(); } catch (Exception ignored) {}
        }
    }

    public AiEvalRun startRun(AiEvalRun.RunConfig config, String createdBy, String parentRunId) {
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
        if (!isBlank(config.chatbotModel)) {
            if (config.chatbotModel.equals(config.testerModel)) {
                throw new IllegalArgumentException("Chatbot and Tester must use different models.");
            }
            if (config.chatbotModel.equals(config.evaluatorModel)) {
                throw new IllegalArgumentException("Chatbot and Evaluator must use different models.");
            }
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

        Thread w = new Thread(() -> executeRun(run), "ai-eval-" + run.runId.substring(0, 8));
        w.setDaemon(true);
        w.start();

        return run;
    }

    private void executeRun(AiEvalRun run) {
        long t0 = System.currentTimeMillis();
        try {
            List<String> questions = generateQuestions(run.config, run.runId);
            emit(run.runId, "questions-ready", Map.of("count", questions.size()));

            for (int i = 0; i < questions.size(); i++) {
                int qIdx = i + 1;
                String q = questions.get(i);
                AiEvalRun.Result r = new AiEvalRun.Result();
                r.qIndex = qIdx;
                r.question = q;

                // Emit the question first so the UI can show it before the AI Help call returns.
                Map<String, Object> qAsked = new LinkedHashMap<>();
                qAsked.put("qIndex", qIdx); qAsked.put("question", q);
                emit(run.runId, "question-asked", qAsked);

                long ta = System.currentTimeMillis();
                try {
                    r.answer = aiHelpController.askAiHelpForEval(q, "ai-eval", run.createdBy,
                            personaIsAdmin(run.config),
                            run.config.chatbotModel, personaGoal(run.config));
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
                    Map<String, Object> evt = new LinkedHashMap<>();
                    evt.put("qIndex", qIdx); evt.put("question", q); evt.put("answer", "");
                    evt.put("grade", "ERR"); evt.put("reason", r.reason);
                    emit(run.runId, "graded", evt);
                    continue;
                }
                Map<String, Object> ansEvt = new LinkedHashMap<>();
                ansEvt.put("qIndex", qIdx); ansEvt.put("question", q); ansEvt.put("answer", r.answer);
                emit(run.runId, "answer-received", ansEvt);

                long tg = System.currentTimeMillis();
                try {
                    Map<String, String> graded = gradeAnswer(run.config, q, r.answer);
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
                Map<String, Object> grEvt = new LinkedHashMap<>();
                grEvt.put("qIndex", qIdx); grEvt.put("question", q); grEvt.put("answer", r.answer);
                grEvt.put("grade", r.grade); grEvt.put("reason", r.reason == null ? "" : r.reason);
                emit(run.runId, "graded", grEvt);
            }

            finalizeSummary(run);
            run.status = "DONE";
            run.summary.totalLatencyMs = System.currentTimeMillis() - t0;
            synchronized (this) { save(); }
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("avgGradeNumeric", run.summary.avgGradeNumeric);
            done.put("avgGradeLetter", run.summary.avgGradeLetter);
            done.put("failureCount", run.summary.failureCount);
            emit(run.runId, "run-complete", done);

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

    private Map<String, String> gradeAnswer(AiEvalRun.RunConfig config, String q, String a) throws Exception {
        String sys = "You are grading a chatbot answer for a " + config.persona.role
            + " on the " + config.persona.team + " team"
            + " with " + config.persona.experience + " experience, who is trying to: " + config.persona.goal + ". "
            + "Grade the answer on a 5-point scale: A (excellent, exactly what they need), "
            + "B (good, addresses the question with minor gaps), C (mediocre, partially addresses), "
            + "D (poor, mostly misses the point), F (wrong or unhelpful). "
            + "OUTPUT REQUIREMENTS: Return ONLY a JSON object of the form {\"grade\":\"A\",\"reason\":\"one short sentence\"}. "
            + "Start your response with the literal { character. "
            + "Do NOT wrap the response in markdown ``` code fences. "
            + "Do NOT include any text before or after the JSON. Keep reason under 25 words.";
        String user = "QUESTION: " + q + "\n\nANSWER: " + a;

        // Reasoning models (Gemini 2.5 Pro especially) burn most of the token budget on
        // hidden chain-of-thought before producing any visible output. With a 600-token
        // cap we observed Gemini emit ONLY "```json" and run out. Bump for those models.
        int budget = budgetForModel(config.evaluatorModel);

        String raw = portkeyClient.chat(config.evaluatorModel, sys, user, budget);

        Map<String, String> parsed = tryParseGrade(raw);
        if (parsed == null) {
            String stricter = sys + "\n\nPREVIOUS ATTEMPT WAS REJECTED. Output ONLY: {\"grade\":\"<letter>\",\"reason\":\"<one short sentence>\"} starting with { and ending with }. No prefix, no fence, no suffix.";
            raw = portkeyClient.chat(config.evaluatorModel, stricter, user, budget);
            parsed = tryParseGrade(raw);
        }
        if (parsed == null) {
            // Last-ditch recovery: try to extract just the grade letter from whatever the
            // model returned. Better to record A/B/C/D/F with a synthesized reason than to
            // fail the question outright.
            String letter = extractGradeLetterFallback(raw);
            if (letter != null) {
                Map<String, String> out = new LinkedHashMap<>();
                out.put("grade", letter);
                out.put("reason", "(grade extracted from malformed evaluator response)");
                return out;
            }
            logger.warning("[AI EVAL] evaluator returned malformed JSON twice; raw=" + (raw == null ? "null" : raw.substring(0, Math.min(500, raw.length()))));
            throw new RuntimeException("Evaluator returned malformed JSON. Try a different evaluator model or rerun.");
        }
        return parsed;
    }

    /**
     * Token budget for a single evaluator response. Reasoning models (Gemini 2.5 Pro,
     * Vertex AI's thinking variants in general) consume most of their budget on hidden
     * chain-of-thought BEFORE producing visible tokens — verified locally with a "say
     * hi in 3 words" probe that consumed 95 reasoning tokens of 96 total. So they need
     * a much larger cap than non-reasoning models.
     */
    private static int budgetForModel(String model) {
        if (model == null) return 600;
        String m = model.toLowerCase();
        if (m.contains("vertexai") || m.contains("gemini")) return 4000;
        return 600;
    }

    /**
     * Token budget for the Tester's question-generation call. Scales with the requested
     * question count (~80 tokens per question of visible output: ~50 chars/word ratios + JSON
     * brackets/commas), with a floor that absorbs prompt-shaping overhead. Reasoning models
     * (Gemini 2.5 Pro etc.) get a large additive overhead because they burn most of the budget
     * on hidden chain-of-thought before producing visible tokens — we saw a 5-question Gemini
     * call truncate at 1500 tokens, which left only ~150 visible tokens after reasoning.
     */
    /** Persona goal text for AI Help eval-mode framing. Empty/null safe. */
    private static String personaGoal(AiEvalRun.RunConfig cfg) {
        if (cfg == null || cfg.persona == null || cfg.persona.goal == null) return null;
        String g = cfg.persona.goal.trim();
        return g.isEmpty() ? null : g;
    }

    /**
     * Admin status to feed to AI Help during an eval. We must use the PERSONA's status,
     * not the session user's, otherwise AI Help opens with "as a PLM admin you have full access"
     * even when the persona is explicitly a non-admin asking what they can self-serve.
     *
     * Simulated-real-person personas carry the actual AD admin flag in {@code realIsPlmAdmin};
     * abstract-bucket personas default to NON-admin (most users aren't admins, and the eval
     * should test the harder/more common case by default).
     */
    private static boolean personaIsAdmin(AiEvalRun.RunConfig cfg) {
        if (cfg == null || cfg.persona == null) return false;
        Boolean real = cfg.persona.realIsPlmAdmin;
        return real != null && real;
    }

    private static int budgetForTester(String model, int questionCount) {
        int visible = Math.max(5, questionCount) * 80 + 400;
        if (model == null) return Math.max(1500, visible);
        String m = model.toLowerCase();
        if (m.contains("vertexai") || m.contains("gemini")) return Math.max(4000, visible + 2500);
        return Math.max(1500, visible);
    }

    /**
     * When the evaluator returns truncated or otherwise broken JSON, look for the first
     * "grade":"X" pattern and pluck the letter. Returns null if no letter can be found.
     */
    private static String extractGradeLetterFallback(String raw) {
        if (raw == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "\"grade\"\\s*:\\s*\"([ABCDF])\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(raw);
        if (m.find()) return m.group(1).toUpperCase();
        return null;
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

    /**
     * V2: apply a human override to a single question's grade. Preserves the original
     * AI grade/reason on the first override (subsequent overrides keep the AI value
     * frozen). Recomputes the run summary so avg + failureCount reflect the new
     * effective grade.
     *
     * @return the updated run, never null. Throws IllegalArgumentException if invalid.
     */
    public synchronized AiEvalRun applyOverride(String runId, int qIndex, String newGrade,
                                                 String newReason, String note, String byUsername) {
        if (newGrade == null) throw new IllegalArgumentException("grade is required");
        String g = newGrade.trim().toUpperCase();
        if (!Arrays.asList("A", "B", "C", "D", "F").contains(g)) {
            throw new IllegalArgumentException("grade must be one of A, B, C, D, F");
        }
        AiEvalRun run = getRun(runId);
        if (run == null) throw new IllegalArgumentException("run not found: " + runId);
        AiEvalRun.Result target = null;
        for (AiEvalRun.Result r : run.results) {
            if (r.qIndex == qIndex) { target = r; break; }
        }
        if (target == null) throw new IllegalArgumentException("question " + qIndex + " not found in run");

        // Preserve the original AI grade only on the first override.
        if (target.aiGrade == null) {
            target.aiGrade = target.grade;
            target.aiReason = target.reason;
        }
        target.grade = g;
        target.reason = newReason == null ? "" : newReason;
        target.overriddenBy = byUsername == null ? "unknown" : byUsername;
        target.overriddenAt = Instant.now().toString();
        target.overrideNote = (note == null || note.trim().isEmpty()) ? null : note.trim();

        finalizeSummary(run);
        save();
        logger.info("[AI EVAL] runId=" + runId + " override qIndex=" + qIndex
            + " " + (target.aiGrade != null ? target.aiGrade : "?") + "->" + g
            + " by=" + byUsername);
        return run;
    }

    private void finalizeSummary(AiEvalRun run) {
        int graded = 0;
        int sum = 0;
        int fails = 0;
        long answerMsTotal = 0;
        int answerMsCount = 0;
        for (AiEvalRun.Result r : run.results) {
            // Count AI Help latency for any non-ERR result (the chatbot did answer; the
            // grade just reflects answer quality). Skip ERR rows because their answerLatencyMs
            // may include a timeout that would skew the avg.
            if (!"ERR".equals(r.grade) && r.answerLatencyMs > 0) {
                answerMsTotal += r.answerLatencyMs;
                answerMsCount++;
            }
            if ("ERR".equals(r.grade)) { fails++; continue; }
            int v = letterToNumeric(r.grade);
            if (v < 0) continue;
            sum += v;
            graded++;
            if (v < 3) fails++; // C/D/F count as failures (B = 3 = passing)
        }
        run.summary.avgGradeNumeric = graded == 0 ? 0.0 : Math.round((sum * 10.0 / graded)) / 10.0;
        run.summary.avgGradeLetter = numericToLetter(run.summary.avgGradeNumeric);
        run.summary.failureCount = fails;
        run.summary.avgAnswerLatencyMs = answerMsCount == 0 ? 0L : answerMsTotal / answerMsCount;
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
}
