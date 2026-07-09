package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.EcnReportService;
import com.sandisk.plm.tracker.service.EmailTemplateService;
import com.sandisk.plm.tracker.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.*;
import javax.mail.internet.*;
import javax.mail.util.ByteArrayDataSource;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/ecn-report")
public class EcnReportController {

    private static final Logger logger = Logger.getLogger(EcnReportController.class.getName());

    @Autowired
    private EcnReportService ecnService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ActivityLogger activityLogger;

    @Autowired
    private EmailTemplateService emailTemplate;

    @Autowired
    private com.sandisk.plm.tracker.service.LdapAuthService ldapAuthService;

    @Autowired
    private com.sandisk.plm.tracker.service.KpiClassificationService kpiClassifications;

    /** KPI Master — the D029-00006 classification/target/change-type lookup. Read-only;
     *  the single source dashboards derive ECN Classification + Change Type from. */
    @GetMapping("/kpi-classifications")
    public Map<String, Object> kpiClassifications(HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", kpiClassifications.getEntries());
        out.put("loaded", kpiClassifications.isLoaded());
        out.put("source", "D029-00006 (KPI Classifications Master)");
        out.put("canEdit", canEditKpi(session));   // PLM IT + Vikas Singh + Jimmy only
        out.put("isAdmin", isAdmin(session));
        return out;
    }

    @Value("${mail.smtp.host}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Value("${mail.from}")
    private String mailFrom;

    // =========================================================================
    // Session helpers
    // =========================================================================

    private String username(HttpSession session) {
        String u = (String) session.getAttribute("username");
        return u != null ? u : "unknown";
    }

    private String displayName(HttpSession session) {
        return (String) session.getAttribute("displayName");
    }

    private boolean isAdmin(HttpSession session) {
        Object val = session.getAttribute("isPlmAdmin");
        return Boolean.TRUE.equals(val);
    }

    private boolean canEdit(HttpSession session) {
        if (isAdmin(session)) return true;
        Object emailAttr = session != null ? session.getAttribute("email") : null;
        String email = emailAttr != null ? emailAttr.toString() : null;
        return ecnService.isEditor(username(session), email);
    }

    /** KPI Master edit access (Vikas Singh 2026-07-06): PLM IT (PLM admin) plus the
     *  named KPI owners only — deliberately NARROWER than the general editors list so
     *  SLA target editing on the KPI Master tab is restricted to this set. */
    private static final Set<String> KPI_OWNERS = new HashSet<>(Arrays.asList(
        "vikas.singh3", "jimmy.sessumes"));

    private boolean canEditKpi(HttpSession session) {
        if (isAdmin(session)) return true;                 // PLM IT / PLM admin
        String u = username(session);
        return u != null && KPI_OWNERS.contains(u.toLowerCase());
    }

    private Map<String, Object> forbidden() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false);
        r.put("message", "You do not have permission to perform this action.");
        return r;
    }

    private Map<String, Object> ok(String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("message", msg);
        return r;
    }

    // =========================================================================
    // 1. POST /run — Run the ECN report Python script
    // =========================================================================

    @PostMapping("/run")
    public Map<String, Object> runReport(HttpSession session) {
        if (!canEdit(session)) return forbidden();

        final String user = username(session);
        final String display = displayName(session);

        reportService.markBuiltinRunning("ecn-report-tab", "ECN Report");
        activityLogger.log(user, display, "ECN_REPORT_RUN", "Started ECN report generation");

        Thread worker = new Thread(() -> {
            try {
                String python = reportService.getPythonExe();
                ProcessBuilder pb = new ProcessBuilder(
                        python, "ecn_report_generator.py",
                        "--config", "ecn_report.properties",
                        "--output", "output/ECN_Report.xlsx",
                        "--json-output", "ecn_data.json",
                        "--no-email"
                );
                pb.directory(new File("./data/ecn-report"));
                pb.redirectErrorStream(true);
                Map<String, String> env = pb.environment();
                env.put("PYTHONIOENCODING", "utf-8");
                String email = (String) session.getAttribute("email");
                if (email != null) {
                    env.put("PLM_USER_EMAIL", email);
                }

                Process process = pb.start();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    reportService.appendLog("ecn-report-tab", line);
                    logger.info("[ECN-REPORT] " + line);
                }

                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    reportService.setOutputFile("ecn-report-tab",
                            "./data/ecn-report/output/ECN_Report.xlsx");
                    reportService.markBuiltinCompleted("ecn-report-tab");
                    activityLogger.log(user, display, "ECN_REPORT_DONE", "ECN report completed successfully");
                } else {
                    reportService.markBuiltinFailed("ecn-report-tab", "Exit code " + exitCode);
                    activityLogger.log(user, display, "ECN_REPORT_FAIL", "ECN report failed with exit code " + exitCode);
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "[ECN-REPORT] Error running Python script", e);
                reportService.markBuiltinFailed("ecn-report-tab", e.getMessage());
                activityLogger.log(user, display, "ECN_REPORT_FAIL", "ECN report error: " + e.getMessage());
            }
        }, "ecn-report-runner");
        worker.setDaemon(true);
        worker.start();

        return ok("ECN report generation started");
    }

    // =========================================================================
    // 2. GET /data — Return ECN data + active profile + annotations
    // =========================================================================

    @GetMapping("/data")
    public Map<String, Object> getData(HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("ecnData", ecnService.getEcnData());
        response.put("activeProfile", ecnService.getActiveProfile());
        response.put("annotations", ecnService.getAnnotations());
        response.put("canEdit", canEdit(session));
        response.put("isAdmin", isAdmin(session));
        return response;
    }

    // =========================================================================
    // 3. GET /download — Download the Excel report
    // =========================================================================

    @GetMapping("/download")
    public void downloadReport(HttpServletResponse response, HttpSession session) throws IOException {
        File file = new File("./data/ecn-report/output/ECN_Report.xlsx");
        if (!file.exists()) {
            response.setStatus(404);
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"message\":\"Report file not found\"}");
            return;
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=ECN_Report.xlsx");
        activityLogger.log(username(session), displayName(session),
                "ECN_REPORT_DOWNLOAD", "Downloaded ECN_Report.xlsx (" + file.length() + " bytes)");
        Files.copy(file.toPath(), response.getOutputStream());
    }

    // =========================================================================
    // 4. POST /upload-sla — Upload SLA template
    // =========================================================================

    @PostMapping("/upload-sla")
    public Map<String, Object> uploadSla(@RequestParam("file") MultipartFile file,
                                         HttpSession session) {
        if (!canEditKpi(session)) return forbidden();

        try {
            File dest = new File("./data/ecn-report/ecn-kpi-template.xlsx");
            dest.getParentFile().mkdirs();
            file.transferTo(dest);
            activityLogger.log(username(session), displayName(session),
                    "ECN_SLA_UPLOAD", "Uploaded SLA template (" + file.getSize() + " bytes)");
            return ok("SLA template uploaded successfully");
        } catch (Exception e) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "Upload failed: " + e.getMessage());
            return r;
        }
    }

    // =========================================================================
    // 5. GET /profiles — List all profiles
    // =========================================================================

    @GetMapping("/profiles")
    public Map<String, Object> getProfiles() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("profiles", ecnService.getProfiles());
        return response;
    }

    // =========================================================================
    // 6. POST /profiles — Create a new profile
    // =========================================================================

    @SuppressWarnings("unchecked")
    @PostMapping("/profiles")
    public Map<String, Object> createProfile(@RequestBody Map<String, Object> body,
                                             HttpSession session) {
        if (!canEditKpi(session)) return forbidden();   // KPI Master editors only
        body.put("createdBy", username(session));
        Map<String, Object> profile = ecnService.saveProfile(body);

        activityLogger.log(username(session), displayName(session),
                "ECN_PROFILE_CREATE", "Created profile: " + profile.get("name"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("profile", profile);
        return response;
    }

    // =========================================================================
    // 7. PUT /profiles/{id} — Update a profile
    // =========================================================================

    @PutMapping("/profiles/{id}")
    public Map<String, Object> updateProfile(@PathVariable String id,
                                             @RequestBody Map<String, Object> body,
                                             HttpSession session) {
        // Check ownership or editor/admin
        Map<String, Object> existing = findProfileById(id);
        if (existing != null) {
            String owner = (String) existing.get("createdBy");
            if (!username(session).equals(owner) && !canEditKpi(session)) {
                return forbidden();
            }
        }

        Map<String, Object> updated = ecnService.updateProfile(id, body);
        if (updated == null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "Profile not found");
            return r;
        }

        activityLogger.log(username(session), displayName(session),
                "ECN_PROFILE_UPDATE", "Updated profile: " + id);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("profile", updated);
        return response;
    }

    // =========================================================================
    // 8. DELETE /profiles/{id} — Delete a profile
    // =========================================================================

    @DeleteMapping("/profiles/{id}")
    public Map<String, Object> deleteProfile(@PathVariable String id, HttpSession session) {
        Map<String, Object> existing = findProfileById(id);
        if (existing != null) {
            String owner = (String) existing.get("createdBy");
            if (!username(session).equals(owner) && !canEditKpi(session)) {
                return forbidden();
            }
        }

        boolean deleted = ecnService.deleteProfile(id);
        if (!deleted) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "Profile not found");
            return r;
        }

        activityLogger.log(username(session), displayName(session),
                "ECN_PROFILE_DELETE", "Deleted profile: " + id);
        return ok("Profile deleted");
    }

    // =========================================================================
    // 9. PUT /profiles/{id}/activate — Activate a profile
    // =========================================================================

    @PutMapping("/profiles/{id}/activate")
    public Map<String, Object> activateProfile(@PathVariable String id, HttpSession session) {
        if (!canEditKpi(session)) return forbidden();

        Map<String, Object> activated = ecnService.activateProfile(id);
        if (activated == null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "Profile not found");
            return r;
        }

        activityLogger.log(username(session), displayName(session),
                "ECN_PROFILE_ACTIVATE", "Activated profile: " + id);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("profile", activated);
        return response;
    }

    // =========================================================================
    // 10. POST /recalculate — Recalculate with overrides
    // =========================================================================

    @SuppressWarnings("unchecked")
    @PostMapping("/recalculate")
    public Map<String, Object> recalculate(@RequestBody Map<String, Object> body,
                                           HttpSession session) {
        if (!canEditKpi(session)) return forbidden();

        String profileId = (String) body.get("profileId");
        Map<String, Object> overrides = (Map<String, Object>) body.get("overrides");

        if (profileId != null && overrides != null) {
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put("overrides", overrides);
            ecnService.updateProfile(profileId, updates);
        }

        activityLogger.log(username(session), displayName(session),
                "ECN_RECALCULATE", "Recalculated with profile " + profileId);

        return ok("Recalculation complete");
    }

    // =========================================================================
    // 11. POST /email — Send email with HTML + Excel attachment
    // =========================================================================

    @SuppressWarnings("unchecked")
    @PostMapping("/email")
    public Map<String, Object> sendEmail(@RequestBody Map<String, Object> body,
                                         HttpSession session) {
        if (!canEdit(session)) return forbidden();

        List<String> recipients = (List<String>) body.get("recipients");
        String subject = (String) body.get("subject");
        String htmlContent = (String) body.get("htmlContent");

        if (recipients == null || recipients.isEmpty()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "No recipients specified");
            return r;
        }

        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            Session mailSession = Session.getInstance(props);

            for (String recipient : recipients) {
                MimeMessage message = new MimeMessage(mailSession);
                message.setFrom(new InternetAddress(mailFrom));
                message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient.trim()));
                message.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(subject));

                // Build multipart/related for inline CID images
                MimeMultipart multipart = new MimeMultipart("related");

                // HTML body part
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(htmlContent, "text/html; charset=utf-8");
                multipart.addBodyPart(htmlPart);

                // Excel attachment
                File excelFile = new File("./data/ecn-report/output/ECN_Report.xlsx");
                if (excelFile.exists()) {
                    MimeBodyPart attachPart = new MimeBodyPart();
                    byte[] excelBytes = Files.readAllBytes(excelFile.toPath());
                    DataSource ds = new ByteArrayDataSource(excelBytes,
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                    attachPart.setDataHandler(new DataHandler(ds));
                    attachPart.setFileName("ECN_Report.xlsx");
                    multipart.addBodyPart(attachPart);
                }

                // Inline chart PNGs (CID)
                File chartsDir = new File("./data/ecn-report/output/charts");
                if (chartsDir.exists() && chartsDir.isDirectory()) {
                    File[] charts = chartsDir.listFiles((dir, name) ->
                            name.toLowerCase().endsWith(".png"));
                    if (charts != null) {
                        for (File chart : charts) {
                            MimeBodyPart imagePart = new MimeBodyPart();
                            byte[] imgBytes = Files.readAllBytes(chart.toPath());
                            DataSource imgDs = new ByteArrayDataSource(imgBytes, "image/png");
                            imagePart.setDataHandler(new DataHandler(imgDs));
                            String cid = chart.getName().replace(".png", "");
                            imagePart.setHeader("Content-ID", "<" + cid + ">");
                            imagePart.setDisposition(MimeBodyPart.INLINE);
                            multipart.addBodyPart(imagePart);
                        }
                    }
                }

                message.setContent(multipart);
                Transport.send(message);
            }

            activityLogger.log(username(session), displayName(session),
                    "ECN_EMAIL_SENT", "Sent ECN report to " + recipients.size() + " recipients");
            return ok("Email sent to " + recipients.size() + " recipient(s)");

        } catch (Exception e) {
            logger.log(Level.SEVERE, "[ECN-REPORT] Email send failed", e);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "Email failed: " + e.getMessage());
            return r;
        }
    }

    // =========================================================================
    // 12. GET /editors — Admin only
    // =========================================================================

    @GetMapping("/editors")
    public Map<String, Object> getEditors(HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("editors", ecnService.getEditors());
        return response;
    }

    /**
     * Typeahead source for the "Add editor" input. Returns AD users from the
     * site access group (IT-APP-Agile-admin) optionally filtered by `q`.
     * Excludes users already in the editor list. Admin-only.
     */
    @GetMapping("/editors/candidates")
    public Map<String, Object> editorCandidates(@RequestParam(required = false, defaultValue = "") String q,
                                                HttpSession session) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (!isAdmin(session)) { resp.put("success", false); resp.put("message", "Admin access required."); return resp; }

        String query = q.trim().toLowerCase();
        java.util.List<com.sandisk.plm.tracker.service.LdapAuthService.DirectoryUser> all =
                ldapAuthService.listAllGroupMembers();
        java.util.Set<String> already = new java.util.HashSet<>();
        for (String e : ecnService.getEditors()) {
            if (e != null) already.add(e.trim().toLowerCase());
        }

        java.util.List<Map<String, String>> matches = new java.util.ArrayList<>();
        for (com.sandisk.plm.tracker.service.LdapAuthService.DirectoryUser u : all) {
            String emailLower = u.email == null ? "" : u.email.toLowerCase();
            String emailPrefix = emailLower.contains("@") ? emailLower.substring(0, emailLower.indexOf('@')) : "";
            // Skip if any of the user's identifiers is already an editor
            if (already.contains(u.username.toLowerCase())
                || (!emailPrefix.isEmpty() && already.contains(emailPrefix))
                || (!emailLower.isEmpty() && already.contains(emailLower))) continue;
            if (!query.isEmpty()) {
                String hay = (u.username + " " + (u.displayName == null ? "" : u.displayName) + " " + emailLower).toLowerCase();
                if (!hay.contains(query)) continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("username", u.username);
            m.put("displayName", u.displayName == null ? u.username : u.displayName);
            m.put("email", u.email == null ? "" : u.email);
            // emailHandle is the friendliest form to add (e.g. "jimmy.sessumes")
            m.put("handle", emailPrefix.isEmpty() ? u.username : emailPrefix);
            matches.add(m);
            if (matches.size() >= 20) break;
        }
        resp.put("success", true);
        resp.put("candidates", matches);
        return resp;
    }

    // =========================================================================
    // 13. PUT /editors — Admin only
    // =========================================================================

    @SuppressWarnings("unchecked")
    @PutMapping("/editors")
    public Map<String, Object> setEditors(@RequestBody Map<String, Object> body,
                                          HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        List<String> editors = (List<String>) body.get("editors");
        if (editors == null) editors = new ArrayList<>();
        ecnService.setEditors(editors);

        activityLogger.log(username(session), displayName(session),
                "ECN_EDITORS_UPDATE", "Updated editors: " + editors);
        return ok("Editors updated");
    }

    // =========================================================================
    // 14. GET /annotations — Any user
    // =========================================================================

    @GetMapping("/annotations")
    public Map<String, Object> getAnnotations() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("annotations", ecnService.getAnnotations());
        return response;
    }

    // =========================================================================
    // 15. POST /annotations — Any user
    // =========================================================================

    @SuppressWarnings("unchecked")
    @PostMapping("/annotations")
    public Map<String, Object> saveAnnotation(@RequestBody Map<String, Object> body,
                                              HttpSession session) {
        String ecnNumber = (String) body.get("ecnNumber");
        if (ecnNumber == null || ecnNumber.trim().isEmpty()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "ECN number is required");
            return r;
        }

        Map<String, Object> annotation = new LinkedHashMap<>();
        if (body.containsKey("note")) annotation.put("note", body.get("note"));
        if (body.containsKey("teamOverride")) annotation.put("teamOverride", body.get("teamOverride"));
        if (body.containsKey("classificationOverride")) annotation.put("classificationOverride", body.get("classificationOverride"));
        annotation.put("updatedBy", username(session));

        ecnService.saveAnnotation(ecnNumber, annotation);

        activityLogger.log(username(session), displayName(session),
                "ECN_ANNOTATE", "Annotated ECN " + ecnNumber);
        return ok("Annotation saved");
    }

    // =========================================================================
    // 16. GET /distribution-list — Editor/admin
    // =========================================================================

    @GetMapping("/distribution-list")
    public Map<String, Object> getDistributionList(HttpSession session) {
        if (!canEdit(session)) return forbidden();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("distributionList", ecnService.getDistributionList());
        return response;
    }

    // =========================================================================
    // 17. PUT /distribution-list — Admin only
    // =========================================================================

    @PutMapping("/distribution-list")
    public Map<String, Object> setDistributionList(@RequestBody Map<String, Object> body,
                                                   HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        ecnService.setDistributionList(body);

        activityLogger.log(username(session), displayName(session),
                "ECN_DISTLIST_UPDATE", "Updated distribution list");
        return ok("Distribution list updated");
    }

    // =========================================================================
    // 18. GET /status — Report generation status
    // =========================================================================

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        return reportService.getStatus("ecn-report-tab");
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private Map<String, Object> findProfileById(String id) {
        for (Map<String, Object> p : ecnService.getProfiles()) {
            if (id.equals(p.get("id"))) return p;
        }
        return null;
    }
}
