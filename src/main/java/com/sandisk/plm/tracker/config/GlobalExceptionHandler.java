package com.sandisk.plm.tracker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler that turns common web errors into clean JSON
 * responses so the frontend doesn't just see "Internal Server Error".
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    // Bound directly from application.properties so the displayed limit is always
    // the same as what's actually enforced — no risk of drift.
    @Value("${spring.servlet.multipart.max-file-size:100MB}")
    private String configuredMaxFileSize;

    /**
     * Triggered when an upload exceeds {@code spring.servlet.multipart.max-file-size}
     * or {@code spring.servlet.multipart.max-request-size}. Tomcat rejects the
     * request before the controller runs, so without this handler the browser
     * only sees a 500 with no useful message.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex,
                                                                    HttpServletRequest request) {
        // Prefer the property value (always present); fall back to whatever Spring
        // exposed on the exception if the @Value resolution somehow failed.
        String limitText = configuredMaxFileSize != null && !configuredMaxFileSize.isEmpty()
                ? configuredMaxFileSize
                : (ex.getMaxUploadSize() > 0
                    ? (ex.getMaxUploadSize() / (1024 * 1024)) + " MB"
                    : "the configured limit");

        // Try to surface the size of the file the user actually attempted, taken
        // from the HTTP Content-Length header (whole multipart request bytes).
        String actualText = "";
        long contentLength = request.getContentLengthLong();
        if (contentLength > 0) {
            actualText = " Your upload was " + formatBytes(contentLength) + ".";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("status", "error");
        body.put("message", "File too large — maximum upload size is " + limitText + "."
                + actualText
                + " Please reduce the file size, or ask a PLM admin to raise the limit.");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    private String formatBytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1) return String.format("%.1f MB", mb);
        double kb = bytes / 1024.0;
        return String.format("%.0f KB", kb);
    }
}
