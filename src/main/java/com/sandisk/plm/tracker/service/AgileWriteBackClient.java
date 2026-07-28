package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * HTTP client over the IMS Review Agile write-back endpoints exposed by
 * plm-agile-service (see {@code AgileWriteBackController} there + the design
 * doc at {@code docs/superpowers/specs/2026-05-20-ims-review-agile-writeback.md}).
 *
 * <p>Every call:
 * <ul>
 *   <li>Generates a correlation UUID (caller may override) and sends it as the
 *       {@code X-Toolkit-Action-Id} header — the service tags every log line
 *       with it.</li>
 *   <li>Returns a {@link Result} carrying the corrId, ok flag, and either the
 *       parsed response body (Map) or the error reason.</li>
 *   <li>Logs one structured line per call so the toolkit's own activity log
 *       can be correlated with the service's {@code [AGILE-WRITE]} lines.</li>
 * </ul>
 *
 * <p>Retry policy: idempotent endpoints (pending-drr) retry 3× with 2/4/8s
 * backoff on 5xx / connect-refused. Non-idempotent endpoints (attach, history,
 * status, create-dco) DO NOT retry automatically — the caller decides based on
 * whether the action is safe to repeat. The service is also de-duping
 * (idempotency check on create-dco).
 */
@Service
public class AgileWriteBackClient {

    private static final Logger LOG = Logger.getLogger(AgileWriteBackClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${agile.service.url:http://localhost:8081}")
    private String serviceUrl;

    // Default 120s (not 60s): the first per-user PX login of the JVM's life
    // cold-starts in ~90s, and prod's external config may not override this —
    // a 60s default times out the sign-in verify (SocketTimeoutException).
    @Value("${app.ims-review.writeback-timeout-ms:120000}")
    private int timeoutMs;

    /** Carries the result of a single HTTP call. */
    public static final class Result {
        public final String corrId;
        public final int httpStatus;
        public final boolean ok;
        /** parsed JSON body (the inner "body" object from the service's wrapper). */
        public final Map<String, Object> body;
        /** non-null on error paths. */
        public final String errorReason;

        public Result(String corrId, int httpStatus, boolean ok,
                      Map<String, Object> body, String errorReason) {
            this.corrId = corrId; this.httpStatus = httpStatus; this.ok = ok;
            this.body = body; this.errorReason = errorReason;
        }
    }

    // ------------------------------------------------------------------
    // 1) GET /api/document/{doc}/pending-drr  (idempotent, retries OK)
    // ------------------------------------------------------------------
    public Result findPendingDrr(String docNumber, String corrId) {
        String cid = orNew(corrId);
        String url = serviceUrl + "/api/document/"
                + URLEncoder.encode(docNumber, StandardCharsets.UTF_8) + "/pending-drr";
        return getWithRetry(url, cid, "pending-drr");
    }

    // ------------------------------------------------------------------
    // 2) POST /api/drr/{drr}/attach-file (multipart, NO retry)
    // ------------------------------------------------------------------
    public Result attachFile(String drrNumber, byte[] fileBytes, String filename,
                             String description, String corrId) {
        String cid = orNew(corrId);
        String url = serviceUrl + "/api/drr/"
                + URLEncoder.encode(drrNumber, StandardCharsets.UTF_8) + "/attach-file";
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            String boundary = "----PLMToolkitBoundary-" + System.nanoTime();
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("X-Toolkit-Action-Id", cid);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                // file part
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\""
                        + (filename == null ? "upload.bin" : filename) + "\"\r\n");
                out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
                out.write(fileBytes);
                out.writeBytes("\r\n");
                // optional description
                if (description != null && !description.trim().isEmpty()) {
                    out.writeBytes("--" + boundary + "\r\n");
                    out.writeBytes("Content-Disposition: form-data; name=\"description\"\r\n\r\n");
                    out.write(description.getBytes(StandardCharsets.UTF_8));
                    out.writeBytes("\r\n");
                }
                out.writeBytes("--" + boundary + "--\r\n");
            }
            return readResponse(conn, cid, "attach-file", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return clientError(cid, "attach-file", e, System.currentTimeMillis() - t0);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Alias for {@link #attachFile} — the underlying agile-service endpoint
     *  {@code /api/drr/{x}/attach-file} resolves any IChange by number, not
     *  just DRRs. Use this name when attaching to a DCO so call sites read
     *  correctly. Keeping the URL stable to avoid an agile-service redeploy. */
    public Result attachFileToChange(String changeNumber, byte[] fileBytes, String filename,
                                     String description, String corrId) {
        return attachFile(changeNumber, fileBytes, filename, description, corrId);
    }

    // 2c) POST /api/change/{ecn}/add-restart-attachment (multipart, per-user, NO retry)
    // ------------------------------------------------------------------
    public Result addRestartAttachment(String ecn, String asUsername, String asPassword,
                                       byte[] fileBytes, String filename, String description,
                                       String attachmentType, String corrId) {
        String cid = orNew(corrId);
        String url = serviceUrl + "/api/change/"
                + URLEncoder.encode(ecn, StandardCharsets.UTF_8) + "/add-restart-attachment";
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            String boundary = "----PLMToolkitBoundary-" + System.nanoTime();
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("X-Toolkit-Action-Id", cid);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\""
                        + (filename == null ? "upload.bin" : filename) + "\"\r\n");
                out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
                out.write(fileBytes);
                out.writeBytes("\r\n");
                writeTextPart(out, boundary, "asUsername", asUsername);
                writeTextPart(out, boundary, "asPassword", asPassword);   // never logged
                if (description != null && !description.trim().isEmpty())
                    writeTextPart(out, boundary, "description", description);
                if (attachmentType != null && !attachmentType.trim().isEmpty())
                    writeTextPart(out, boundary, "attachmentType", attachmentType);
                out.writeBytes("--" + boundary + "--\r\n");
            }
            return readResponse(conn, cid, "add-restart-attachment", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return clientError(cid, "add-restart-attachment", e, System.currentTimeMillis() - t0);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // 3) POST /api/drr/{drr}/history (NO retry)
    // ------------------------------------------------------------------
    public Result appendHistory(String drrNumber, String comment,
                                List<String> recipientEmails, List<String> recipientGroups,
                                String corrId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("comment", comment);
        body.put("recipientEmails", recipientEmails == null ? Collections.emptyList() : recipientEmails);
        body.put("recipientGroups", recipientGroups == null ? Collections.emptyList() : recipientGroups);
        return postJson("/api/drr/" + URLEncoder.encode(drrNumber, StandardCharsets.UTF_8) + "/history",
                body, orNew(corrId), "history");
    }

    // ------------------------------------------------------------------
    // 3b) POST /api/item/{item}/history (NO retry)
    //
    // Writes a History row onto a document ITEM via IItem.logAction(comment)
    // (no email, no recipients). Used to record who downloaded a document's
    // attachments from the IMS response link.
    // ------------------------------------------------------------------
    public Result appendItemHistory(String itemNumber, String comment, String corrId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("comment", comment);
        return postJson("/api/item/" + URLEncoder.encode(itemNumber, StandardCharsets.UTF_8) + "/history",
                body, orNew(corrId), "item-history");
    }

    // ------------------------------------------------------------------
    // 4) POST /api/drr/{drr}/status (NO retry; service idempotent on no-op)
    // ------------------------------------------------------------------
    public Result changeStatus(String drrNumber, String newStatus, String comment, String corrId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("newStatus", newStatus);
        body.put("comment", comment == null ? "" : comment);
        return postJson("/api/drr/" + URLEncoder.encode(drrNumber, StandardCharsets.UTF_8) + "/status",
                body, orNew(corrId), "status");
    }

    // ------------------------------------------------------------------
    // 4b) POST /api/drr/{drr}/close-no-change (DM Approve cascade)
    // ------------------------------------------------------------------
    public Result closeNoChange(String drrNumber, String docNumber, String requestorEmail,
                                String comment, String corrId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (docNumber != null) body.put("docNumber", docNumber);
        if (requestorEmail != null && !requestorEmail.trim().isEmpty()) {
            body.put("requestorEmail", requestorEmail.trim());
        }
        body.put("comment", comment == null ? "" : comment);
        return postJson("/api/drr/" + URLEncoder.encode(drrNumber, StandardCharsets.UTF_8) + "/close-no-change",
                body, orNew(corrId), "close-no-change");
    }

    // ------------------------------------------------------------------
    // 5) POST /api/drr/{drr}/create-dco (NO retry; service idempotency-checks)
    // ------------------------------------------------------------------
    public Result createDco(String drrNumber, String docNumber, String currentRev,
                            String description, String reasonForChange,
                            List<String> notifyEmails, List<String> notifyGroups,
                            boolean autoSubmit, String requestorEmail, String corrId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docNumber", docNumber);
        body.put("currentRev", currentRev == null ? "" : currentRev);
        body.put("description", description == null ? "" : description);
        body.put("reasonForChange", reasonForChange == null ? "" : reasonForChange);
        body.put("notifyEmails", notifyEmails == null ? Collections.emptyList() : notifyEmails);
        body.put("notifyGroups", notifyGroups == null ? Collections.emptyList() : notifyGroups);
        body.put("autoSubmit", autoSubmit);
        if (requestorEmail != null && !requestorEmail.trim().isEmpty()) {
            body.put("requestorEmail", requestorEmail.trim());
        }
        return postJson("/api/drr/" + URLEncoder.encode(drrNumber, StandardCharsets.UTF_8) + "/create-dco",
                body, orNew(corrId), "create-dco");
    }

    /** Create a DRR for a document (no DRR yet). Returns the standard Result
     *  whose body carries {ok, drrNumber, alreadyExisted, ...}. */
    public Result createDrr(String docNumber, List<String> ownerLogins,
                            String requestorEmail, String corrId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docNumber", docNumber);
        body.put("ownerLogins", ownerLogins == null ? Collections.emptyList() : ownerLogins);
        if (requestorEmail != null && !requestorEmail.trim().isEmpty()) {
            body.put("requestorEmail", requestorEmail.trim());
        }
        return postJson("/api/document/" + URLEncoder.encode(docNumber, StandardCharsets.UTF_8) + "/create-drr",
                body, orNew(corrId), "create-drr");
    }

    /** Create a Restart (deployment) ECN under the acting user's Agile session.
     *  Body carries {ok, ecnNumber, submitted, relatedOk, relatedFailed, ...}. */
    public Result createRestartEcn(String asUsername, String asPassword,
                                   String proposalText, String problemStatement,
                                   String itOwnerLogin, List<String> relatedEcns,
                                   String corrId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("asUsername", asUsername);
        body.put("asPassword", asPassword);           // never logged
        body.put("proposalText", proposalText == null ? "" : proposalText);
        body.put("problemStatement", problemStatement == null ? "" : problemStatement);
        body.put("itOwnerLogin", itOwnerLogin);
        body.put("relatedEcns", relatedEcns == null ? Collections.emptyList() : relatedEcns);
        return postJson("/api/change/create-restart-ecn", body, orNew(corrId), "create-restart-ecn");
    }

    /** Batch live status read for the Restart-history list. Body carries {ok, statuses:{ecn:status}}. */
    public Result restartStatuses(List<String> ecns, String corrId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ecns", ecns == null ? Collections.emptyList() : ecns);
        return postJson("/api/change/restart-statuses", body, orNew(corrId), "restart-statuses");
    }

    /** Live snapshot of a Restart ECN's Agile state (status/itOwner/relationships/attachments). */
    public Result restartSnapshot(String ecn, String corrId) {
        String url = serviceUrl + "/api/change/" + URLEncoder.encode(ecn, StandardCharsets.UTF_8) + "/restart-snapshot";
        return getWithRetry(url, orNew(corrId), "restart-snapshot");
    }

    /** Add relationship rows to an existing Restart ECN (§4 "Add ECNs"). Body carries
     *  {ok, relatedOk, relatedFailed, ...}. */
    public Result addRelationships(String ecn, String asUsername, String asPassword,
                                   List<String> relatedEcns, String proposalText, String problemStatement,
                                   String corrId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("asUsername", asUsername);
        body.put("asPassword", asPassword);            // never logged
        body.put("relatedEcns", relatedEcns == null ? Collections.emptyList() : relatedEcns);
        if (proposalText != null) body.put("proposalText", proposalText);
        if (problemStatement != null) body.put("problemStatement", problemStatement);
        return postJson("/api/change/" + URLEncoder.encode(ecn, StandardCharsets.UTF_8) + "/add-relationships",
                body, orNew(corrId), "add-relationships");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------
    /**
     * GET with up-to-3 retries on <b>transient</b> failure only.
     *
     * <p>Transient means the request never got a usable answer: a transport
     * error (connect/read timeout, connection refused) or a 5xx. Anything else
     * — including a 2xx whose body carries {@code ok:false} — is a real answer
     * from the service and is returned immediately.
     *
     * <p>Why: {@code Result.ok} is just {@code body.ok}, so a semantic "partial
     * success" flag used to look identical to a transport fault. A
     * {@code /dco/list-values} 200 that reported one missing optional admin list
     * set {@code ok:false}, and this method dutifully retried it with backoff —
     * 2s + 8s + 18s = <b>28 seconds added to every change-order drawer open</b>
     * (QA, 2026-07-09). Retrying a 200 cannot change its outcome.
     *
     * <p>Also: never sleep after the final attempt — that trailing 18s bought
     * nothing and was the single largest slice of the 28s above.
     */
    private Result getWithRetry(String url, String cid, String action) {
        final int MAX_ATTEMPTS = 3;
        int attempts = 0;
        Result last = null;
        while (attempts < MAX_ATTEMPTS) {
            attempts++;
            long t0 = System.currentTimeMillis();
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setRequestProperty("X-Toolkit-Action-Id", cid);
                last = readResponse(conn, cid, action, System.currentTimeMillis() - t0);
                if (last.ok) return last;
                // A response arrived. Only a server-side fault (5xx) is worth
                // retrying; 2xx/3xx/4xx are answers, not faults.
                if (last.httpStatus < 500) return last;
            } catch (Exception e) {
                last = clientError(cid, action, e, System.currentTimeMillis() - t0);   // transport → retry
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (attempts >= MAX_ATTEMPTS) break;   // don't sleep after the last attempt
            // backoff: 2s, 8s
            try { Thread.sleep(2000L * attempts * attempts); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        return last;
    }

    private Result postJson(String path, Map<String, Object> body, String cid, String action) {
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(serviceUrl + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("X-Toolkit-Action-Id", cid);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] payload = MAPPER.writeValueAsBytes(body);
            try (OutputStream os = conn.getOutputStream()) { os.write(payload); }
            return readResponse(conn, cid, action, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return clientError(cid, action, e, System.currentTimeMillis() - t0);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private Result readResponse(HttpURLConnection conn, String cid, String action, long elapsedMs) throws IOException {
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        byte[] bytes = readAll(is);
        Map<String, Object> wrapper = null;
        Map<String, Object> innerBody = null;
        if (bytes != null && bytes.length > 0) {
            try {
                wrapper = MAPPER.readValue(bytes, Map.class);
                Object b = wrapper.get("body");
                if (b instanceof Map) innerBody = (Map<String, Object>) b;
            } catch (Exception parseErr) {
                LOG.warning("[AGILE-WB] corrId=" + cid + " action=" + action
                        + " parseError=" + parseErr.getMessage() + " raw=" + truncate(new String(bytes), 200));
            }
        }
        boolean ok = innerBody != null && Boolean.TRUE.equals(innerBody.get("ok"));
        String errorReason = innerBody == null ? null : (String) innerBody.get("errorReason");
        LOG.info("[AGILE-WB] corrId=" + cid + " action=" + action
                + " http=" + code + " ok=" + ok + " elapsedMs=" + elapsedMs
                + (errorReason == null ? "" : " err=" + errorReason));
        return new Result(cid, code, ok, innerBody, errorReason);
    }

    private Result clientError(String cid, String action, Exception e, long elapsedMs) {
        String msg = e.getClass().getSimpleName() + ":" + e.getMessage();
        LOG.warning("[AGILE-WB] corrId=" + cid + " action=" + action
                + " transportFailure=" + msg + " elapsedMs=" + elapsedMs);
        return new Result(cid, 0, false, null, msg);
    }

    private static byte[] readAll(InputStream is) throws IOException {
        if (is == null) return new byte[0];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
        return baos.toByteArray();
    }

    private static String orNew(String corrId) {
        return (corrId == null || corrId.trim().isEmpty()) ? UUID.randomUUID().toString() : corrId.trim();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    // ==================================================================
    // Phase 5 — DCO form endpoints
    // ==================================================================

    /** GET /api/agile/dco/list-values — fetch the 6 admin-list dropdowns
     *  (cached server-side, 1h TTL). Idempotent, retries on 5xx. */
    public Result listValues(String corrId) {
        return getWithRetry(serviceUrl + "/api/agile/dco/list-values",
                orNew(corrId), "list-values");
    }

    /** GET /api/agile/item/{number}/cells — read a document item's cells.
     *  Used to pre-fill the DCO form's Product Line / Subcontractors with the
     *  document's current values (a suggestion the responder can clear).
     *  Idempotent, retries on 5xx. */
    public Result itemCells(String itemNumber, String corrId) {
        return getWithRetry(serviceUrl + "/api/agile/item/"
                + URLEncoder.encode(itemNumber, StandardCharsets.UTF_8) + "/cells",
                orNew(corrId), "item-cells");
    }

    /** GET /api/agile/item/{n}/cell-values?baseIds=… — hot-path read of specific
     *  cell VALUES. Prefer this over {@link #itemCells(String,String)} anywhere a
     *  user is waiting: {@code /cells} is a diagnostic that also pulls every list
     *  cell's available-values catalog over the SDK (tens of seconds on an IMS
     *  document, uncached, per drawer open). Same {@code cells[]} response shape. */
    public Result itemCellValues(String itemNumber, String baseIdsCsv, String corrId) {
        return getWithRetry(serviceUrl + "/api/agile/item/"
                + URLEncoder.encode(itemNumber, StandardCharsets.UTF_8) + "/cell-values"
                + "?baseIds=" + URLEncoder.encode(baseIdsCsv, StandardCharsets.UTF_8),
                orNew(corrId), "item-cell-values");
    }

    /** GET /api/cell-options?ecn=…&cells=… — pulls the Agile-defined available
     *  values for a list of cells on a sample change. Used by the IT
     *  Enhancements grid so dropdowns (Assigned IT Owner / Project / IT
     *  Actions Taken) reflect Agile's list, not just the values that happen
     *  to appear in the current grid rows. {@code cellsPipe} is pipe-
     *  separated so cell names that contain commas survive intact.
     *
     *  <p>Routes through {@link #getJsonFlat} (not {@link #getWithRetry})
     *  because the /cell-options endpoint returns a FLAT JSON body —
     *  <code>{ecn, ok, cellOptions, elapsedMs}</code> — not the
     *  <code>{body:{…}}</code> envelope every other write-back endpoint
     *  produces. Without this, readResponse looked for body.body and
     *  always saw null, marking the call as failed even when the agile-
     *  service returned a perfectly good 200. */
    public Result cellOptions(String ecn, String cellsPipe, String corrId) {
        String cid = orNew(corrId);
        try {
            String url = serviceUrl + "/api/cell-options?ecn="
                    + URLEncoder.encode(ecn == null ? "" : ecn, StandardCharsets.UTF_8)
                    + "&cells="
                    + URLEncoder.encode(cellsPipe == null ? "" : cellsPipe, StandardCharsets.UTF_8);
            return getJsonFlat(url, cid, "cell-options", ecn);
        } catch (Exception e) {
            return clientError(cid, "cell-options", e, 0);
        }
    }

    /** GET with a flat-shaped response body (not the {body:{...}} wrapper).
     *  Mirrors {@link #postJsonFlat} on the GET side. No retry — used today
     *  only by /cell-options which is fast and idempotent. */
    private Result getJsonFlat(String url, String cid, String action, String tag) {
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("X-Toolkit-Action-Id", cid);
            return readResponseFlat(conn, cid, action, tag, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return clientError(cid, action, e, System.currentTimeMillis() - t0);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** GET /api/agile/users/search?q=…&limit=… — typeahead for People pickers.
     *  Service caps limit at 50. Returns active users only. */
    public Result searchUsers(String q, int limit, String corrId) {
        String cid = orNew(corrId);
        try {
            String url = serviceUrl + "/api/agile/users/search?q="
                    + URLEncoder.encode(q == null ? "" : q, StandardCharsets.UTF_8)
                    + "&limit=" + Math.max(1, Math.min(50, limit));
            return getWithRetry(url, cid, "users-search");
        } catch (Exception e) {
            return clientError(cid, "users-search", e, 0);
        }
    }

    /** GET /api/agile/items/search?q=…&limit=… — live item typeahead for the
     *  DCO form's Product field (SDSM IMS Document — Edit). Returns
     *  {hits:[{itemNumber, description}]}. Idempotent, retries on 5xx. */
    public Result itemSearch(String q, int limit, String corrId) {
        String cid = orNew(corrId);
        try {
            String url = serviceUrl + "/api/agile/items/search?q="
                    + URLEncoder.encode(q == null ? "" : q, StandardCharsets.UTF_8)
                    + "&limit=" + Math.max(1, Math.min(50, limit));
            return getWithRetry(url, cid, "items-search");
        } catch (Exception e) {
            return clientError(cid, "items-search", e, 0);
        }
    }

    /** POST /api/agile/dco/validate-form — pre-validate the DCO form. No
     *  Agile writes; returns field-level errors if any. Token NOT burned
     *  on the toolkit side either (that gate lives in ImsReviewService). */
    public Result validateForm(Map<String, Object> form, String corrId) {
        return postJson("/api/agile/dco/validate-form", form,
                orNew(corrId), "validate-form");
    }

    /** POST /api/agile/item/{item}/owners — replace the Document Owners cell
     *  on an Item. Body: {loginIds: [...], notedBy?}. Non-idempotent in the
     *  sense that it overwrites whatever was there; safe to retry but caller
     *  usually doesn't need to.
     *
     *  <p>{@code notedBy} (optional but strongly recommended) is the
     *  human-readable identity of the toolkit user who initiated the change,
     *  e.g. {@code "Vikas Singh (vikas.singh3)"}. agile-service stamps this
     *  into the item's history via {@code logActionWOVersionChange()} so the
     *  Owner Change Audit tab can match Agile history rows (where the actor
     *  is always the SDK service account) back to the real instigator. */
    public Result updateItemOwners(String itemNumber, java.util.List<String> loginIds,
                                    String notedBy, String corrId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("loginIds", loginIds == null ? java.util.Collections.emptyList() : loginIds);
        if (notedBy != null && !notedBy.isEmpty()) body.put("notedBy", notedBy);
        return postJson("/api/agile/item/"
                + URLEncoder.encode(itemNumber, StandardCharsets.UTF_8) + "/owners",
                body, orNew(corrId), "update-item-owners");
    }

    /** Back-compat overload without notedBy — calls of this shape get a
     *  generic "PLM Toolkit IMS Dashboard" marker in Agile history. Prefer
     *  the 4-arg form. */
    public Result updateItemOwners(String itemNumber, java.util.List<String> loginIds, String corrId) {
        return updateItemOwners(itemNumber, loginIds, null, corrId);
    }

    /** POST /api/drr/{drr}/create-dco-rich — the meaty one. Multipart with
     *  the form JSON + per-slot file parts. Read timeout extended to 5x
     *  the normal timeout since the cascade can take 30+ seconds. No
     *  retry — failures are handled by the orchestrator's rollback path. */
    public Result createDcoRich(String drrNumber,
                                String formJson,
                                String currentRev,
                                java.util.List<DcoNamedBlob> attachments,
                                String corrId) {
        String cid = orNew(corrId);
        String url = serviceUrl + "/api/drr/"
                + URLEncoder.encode(drrNumber, StandardCharsets.UTF_8) + "/create-dco-rich";
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            String boundary = "----PLMToolkitBoundary-" + System.nanoTime();
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs * 5);   // create-dco-rich can take 30s+
            conn.setRequestProperty("X-Toolkit-Action-Id", cid);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                writeTextPart(out, boundary, "form", formJson);
                if (currentRev != null) writeTextPart(out, boundary, "currentRev", currentRev);
                if (attachments != null) {
                    for (DcoNamedBlob a : attachments) {
                        if (a == null || a.bytes == null) continue;
                        writeFilePart(out, boundary, a.partName, a.filename, a.bytes);
                    }
                }
                out.writeBytes("--" + boundary + "--\r\n");
            }
            return readResponse(conn, cid, "create-dco-rich",
                    System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return clientError(cid, "create-dco-rich", e, System.currentTimeMillis() - t0);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** File blob with a multipart-part-name + filename — the part name
     *  ({@code file_redline} / {@code file_final} / {@code file_others})
     *  drives which Attachment Type slot the server assigns. */
    public static final class DcoNamedBlob {
        public final String partName;
        public final String filename;
        public final byte[] bytes;
        public DcoNamedBlob(String partName, String filename, byte[] bytes) {
            this.partName = partName; this.filename = filename; this.bytes = bytes;
        }
    }

    private static void writeTextPart(DataOutputStream out, String boundary,
                                      String name, String value) throws IOException {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private static void writeFilePart(DataOutputStream out, String boundary,
                                      String name, String filename, byte[] bytes) throws IOException {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + (filename == null ? "file.bin" : filename) + "\"\r\n");
        out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
        out.write(bytes);
        out.writeBytes("\r\n");
    }

    // ==================================================================
    // IT Enhancements — per-user write-back (transient SDK session)
    // ==================================================================

    /** POST /api/change/{ecn}/update-cell-as-user — writes one cell on one
     *  change using the caller's own AD credentials so the History tab
     *  attributes the modification to the user (not "Administrator").
     *
     *  <p>NEVER logs the password. The plaintext is forwarded as a JSON field
     *  and read back into a local var here for the body; the {@link #postJsonFlat}
     *  helper does not echo the body in any log line.
     *
     *  <p>Returns a {@link Result} with {@code body} set to the flat response
     *  map: {@code {ecn, corrId, cellName, ok, oldValue, newValue, elapsedMs}}
     *  on success, or {@code {ok:false, error:"..."}} on any failure. The
     *  service always returns HTTP 200 (per-row outcomes drive the UI). */
    public Result updateChangeCellAsUser(String ecn, String asUsername, String asPassword,
                                         String cellName, String isoDateValue, String corrId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("asUsername", asUsername);
        body.put("asPassword", asPassword);   // never logged
        body.put("cellName", cellName);
        body.put("value", isoDateValue == null ? "" : isoDateValue);
        body.put("corrId", corrId);
        return postJsonFlat("/api/change/"
                + URLEncoder.encode(ecn, StandardCharsets.UTF_8) + "/update-cell-as-user",
                body, orNew(corrId), "update-cell-as-user", ecn);
    }

    /** POST /api/agile/verify-user-credentials — cheap "is this Agile password
     *  right" check. Opens + closes a transient SDK session and reports back.
     *  The toolkit's Agile-signin modal uses this before stashing the password
     *  on the HttpSession.
     *
     *  <p>NEVER logs the password. */
    public Result verifyUserCredentials(String asUsername, String asPassword, String corrId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("asUsername", asUsername);
        body.put("asPassword", asPassword);   // never logged
        body.put("corrId", corrId);
        return postJsonFlat("/api/agile/verify-user-credentials",
                body, orNew(corrId), "verify-user-credentials", asUsername);
    }

    /** POST with a flat response (not the {body:{...}} wrapper). Mirrors
     *  {@link #postJson} but reads the response map as the body directly,
     *  and never logs the request payload. */
    private Result postJsonFlat(String path, Map<String, Object> body, String cid,
                                String action, String tag) {
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(serviceUrl + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("X-Toolkit-Action-Id", cid);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] payload = MAPPER.writeValueAsBytes(body);
            try (OutputStream os = conn.getOutputStream()) { os.write(payload); }
            return readResponseFlat(conn, cid, action, tag, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return clientError(cid, action, e, System.currentTimeMillis() - t0);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private Result readResponseFlat(HttpURLConnection conn, String cid, String action,
                                    String tag, long elapsedMs) throws IOException {
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        byte[] bytes = readAll(is);
        Map<String, Object> respMap = null;
        if (bytes != null && bytes.length > 0) {
            try {
                respMap = MAPPER.readValue(bytes, Map.class);
            } catch (Exception parseErr) {
                LOG.warning("[AGILE-WB] corrId=" + cid + " action=" + action
                        + " parseError=" + parseErr.getMessage());
            }
        }
        boolean ok = respMap != null && Boolean.TRUE.equals(respMap.get("ok"));
        String errorReason = respMap == null ? null : (String) respMap.get("error");
        LOG.info("[AGILE-WB] corrId=" + cid + " action=" + action
                + " ecn=" + tag + " http=" + code + " ok=" + ok + " elapsedMs=" + elapsedMs
                + (errorReason == null ? "" : " err=" + errorReason));
        return new Result(cid, code, ok, respMap, errorReason);
    }
}
