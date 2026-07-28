package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.HelpDocsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpSession;
import java.util.*;

@RestController
@RequestMapping("/api/help-docs")
public class HelpDocsController {

    @Autowired
    private HelpDocsService helpDocsService;

    @Autowired
    private ActivityLogger activityLogger;

    @GetMapping("/list")
    public Map<String, Object> listDocs() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docs", helpDocsService.listDocs());
        result.put("docCount", helpDocsService.getDocCount());
        result.put("chunkCount", helpDocsService.getChunkCount());
        return result;
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String q,
                                             @RequestParam(defaultValue = "10") int max) {
        return helpDocsService.search(q, max);
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");

        try {
            String filename = file.getOriginalFilename();
            HelpDocsService.DocMeta meta = helpDocsService.uploadAndIndex(filename, file.getBytes(), displayName);
            result.put("ok", true);
            result.put("doc", meta);
            activityLogger.log(username, displayName, "HELP_DOC_UPLOAD",
                filename + " (" + meta.chunkCount + " chunks)");
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @GetMapping("/{id}/download")
    public void download(@PathVariable String id, javax.servlet.http.HttpServletResponse response) throws Exception {
        java.io.File dir = new java.io.File("./data/help-docs");
        java.io.File[] files = dir.listFiles((d, name) -> name.startsWith(id + "-"));
        if (files == null || files.length == 0) { response.sendError(404, "File not found"); return; }
        java.io.File file = files[0];
        String filename = file.getName().substring(id.length() + 1); // strip id prefix
        String contentType = filename.endsWith(".pdf") ? "application/pdf"
            : filename.endsWith(".pptx") ? "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            : filename.endsWith(".xlsx") ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            : "application/octet-stream";
        response.setContentType(contentType);
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        java.nio.file.Files.copy(file.toPath(), response.getOutputStream());
    }

    @DeleteMapping("/{id}")
    public Map<String, String> deleteDoc(@PathVariable String id, HttpSession session) {
        Boolean isAdmin = (Boolean) session.getAttribute("isPlmAdmin");
        if (isAdmin == null || !isAdmin) return Map.of("error", "Admin only");
        helpDocsService.deleteDoc(id);
        return Map.of("ok", "Deleted");
    }
}
