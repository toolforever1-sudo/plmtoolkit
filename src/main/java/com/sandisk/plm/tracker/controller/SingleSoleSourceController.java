package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.SingleSoleSourceRunResult;
import com.sandisk.plm.tracker.service.SingleSoleSourceEmailService;
import com.sandisk.plm.tracker.service.SingleSoleSourceRunHistory;
import com.sandisk.plm.tracker.service.SingleSoleSourceService;
import com.sandisk.plm.tracker.service.UserPermissionsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpSession;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/single-sole-source")
public class SingleSoleSourceController {

    @Autowired private SingleSoleSourceService service;
    @Autowired private SingleSoleSourceRunHistory runHistory;
    @Autowired(required = false) private SingleSoleSourceEmailService emailService;
    @Autowired private UserPermissionsService userPermissions;

    @Value("${app.singlesole.output.dir}")
    private String outputDir;

    /**
     * Defense-in-depth: gate every endpoint on the same tab-permission check the
     * UI uses, so a non-admin who's been granted the "singlesole" tab can hit
     * these endpoints, but anyone without the grant gets 403 even if they know
     * the URL. Mirrors the canonical "does this user see this tab" decision in
     * {@link UserPermissionsService#getAllowedTabs}.
     */
    private void requireSingleSoleAccess(HttpSession session) {
        String username = (String) session.getAttribute("username");
        boolean isAdmin = Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"));
        @SuppressWarnings("unchecked")
        Set<String> adGroups = (Set<String>) session.getAttribute("adGroups");
        Set<String> allowed = userPermissions.getAllowedTabs(username, isAdmin,
                adGroups == null ? java.util.Collections.<String>emptySet() : adGroups);
        if (!allowed.contains("singlesole")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Single/Sole Source Report access required.");
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpSession session) {
        requireSingleSoleAccess(session);
        Map<String, Object> out = new HashMap<>();
        SingleSoleSourceRunResult last = runHistory.latest();
        out.put("lastRun", last);
        List<SingleSoleSourceRunResult> all = runHistory.readAll();
        out.put("recent", all.subList(0, Math.min(10, all.size())));
        return out;
    }

    /** Body params: uploadSharePoint=true|false, sendEmail=true|false (both default false). */
    @PostMapping("/run")
    public SingleSoleSourceRunResult run(@RequestBody(required = false) Map<String, Object> body,
                                         HttpSession session) {
        requireSingleSoleAccess(session);
        boolean upload = body != null && Boolean.TRUE.equals(body.get("uploadSharePoint"));
        boolean email  = body != null && Boolean.TRUE.equals(body.get("sendEmail"));
        String userId = (String) session.getAttribute("username");
        return service.runReport("ui", userId, upload, email);
    }

    @GetMapping("/download/latest")
    public ResponseEntity<FileSystemResource> downloadLatest(HttpSession session) {
        requireSingleSoleAccess(session);
        File latest = findLatestXlsx();
        if (latest == null) return ResponseEntity.notFound().build();
        HttpHeaders h = new HttpHeaders();
        h.setContentDispositionFormData("attachment", latest.getName());
        return ResponseEntity.ok()
                .headers(h)
                .contentLength(latest.length())
                .contentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(new FileSystemResource(latest));
    }

    /** Sends the LATEST xlsx to a custom recipient (defaults to the logged-in user's email). */
    @PostMapping("/send-test")
    public Map<String, Object> sendTest(@RequestBody(required = false) Map<String, String> body,
                                        HttpSession session) {
        requireSingleSoleAccess(session);
        Map<String, Object> out = new HashMap<>();
        if (emailService == null) {
            out.put("ok", false); out.put("error", "Email service not configured"); return out;
        }
        File latest = findLatestXlsx();
        if (latest == null) {
            out.put("ok", false); out.put("error", "No report file found — run the report first."); return out;
        }
        String to = body != null ? body.get("to") : null;
        if (to == null || to.trim().isEmpty()) {
            to = (String) session.getAttribute("email");
        }
        if (to == null || to.trim().isEmpty()) {
            out.put("ok", false); out.put("error", "No recipient — login session has no email"); return out;
        }
        try {
            SingleSoleSourceRunResult last = runHistory.latest();
            if (last == null) last = new SingleSoleSourceRunResult();
            emailService.send(to, "", latest, last);
            out.put("ok", true); out.put("sentTo", to);
        } catch (Exception e) {
            out.put("ok", false); out.put("error", e.getMessage());
        }
        return out;
    }

    private File findLatestXlsx() {
        File dir = new File(outputDir);
        File[] files = dir.exists() ? dir.listFiles((f) -> f.getName().endsWith(".xlsx")) : null;
        if (files == null || files.length == 0) return null;
        File latest = files[0];
        for (File f : files) if (f.lastModified() > latest.lastModified()) latest = f;
        return latest;
    }
}
