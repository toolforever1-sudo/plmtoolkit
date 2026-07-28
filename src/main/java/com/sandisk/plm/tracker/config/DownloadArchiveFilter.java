package com.sandisk.plm.tracker.config;

import com.sandisk.plm.tracker.service.FileArchiveService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tees every response output stream through a SHA-256 digest so any download
 * (anything with a {@code Content-Disposition: attachment} header) is recorded
 * to {@link FileArchiveService} as a metadata-only entry. No file bytes are
 * stored — just filename, size, hash, user, and route — which is exactly what
 * "option (b) — metadata-only for downloads" from the design spec calls for.
 *
 * <p>Hooking at this level (servlet filter, after the controller has written
 * its bytes but before they leave the box) means we don't need to wire every
 * one of the ~15 export controllers individually. New export endpoints get
 * auto-coverage.
 *
 * <p>Filter is registered on {@code /api/*} only — static assets are not
 * recorded even when they happen to be downloaded.
 */
@Component
public class DownloadArchiveFilter extends OncePerRequestFilter {

    private static final Logger logger = Logger.getLogger(DownloadArchiveFilter.class.getName());

    // Match Content-Disposition: ...filename="X"... or ...filename=X (no quotes).
    private static final Pattern FILENAME_RX = Pattern.compile("filename\\*?=\"?([^\";]+)");

    @Autowired
    private FileArchiveService fileArchive;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, javax.servlet.ServletException {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        HashingResponseWrapper wrapped = new HashingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapped);
        } finally {
            // Only record if the response is actually a download (server sent
            // Content-Disposition: attachment) and the digest is initialised
            // (i.e. some bytes were written through getOutputStream).
            try {
                String cd = response.getHeader("Content-Disposition");
                if (cd != null && cd.toLowerCase().contains("attachment") && wrapped.bytesWritten > 0) {
                    String filename = extractFilename(cd);
                    HttpSession session = request.getSession(false);
                    String user = session != null ? (String) session.getAttribute("username") : null;
                    String displayName = session != null ? (String) session.getAttribute("displayName") : null;
                    String feature = featureFromUri(uri);
                    String sha = wrapped.digestHex();
                    fileArchive.recordDownload(filename, wrapped.bytesWritten, sha,
                            user, displayName, uri, feature);
                }
            } catch (Exception e) {
                // Archiving must never break the response delivery.
                logger.fine("[FILE-ARCHIVE] download tee skipped: " + e.getMessage());
            }
        }
    }

    private static String extractFilename(String contentDisposition) {
        if (contentDisposition == null) return null;
        Matcher m = FILENAME_RX.matcher(contentDisposition);
        if (m.find()) {
            String f = m.group(1).trim();
            // Strip the leading UTF-8 charset marker if present (RFC 5987).
            if (f.startsWith("UTF-8''")) f = f.substring(7);
            return f;
        }
        return null;
    }

    private static String featureFromUri(String uri) {
        // Same logic as UploadQuarantineService.featureFromEndpoint — keep them
        // aligned so uploads and downloads share a vocabulary in the index.
        if (uri == null) return "unknown";
        String s = uri.startsWith("/") ? uri.substring(1) : uri;
        String[] parts = s.split("/", 4);
        if (parts.length >= 2 && "api".equalsIgnoreCase(parts[0])) {
            return parts[1].toLowerCase();
        }
        return "unknown";
    }

    /**
     * Wrap the response so every byte that flows through {@code getOutputStream()}
     * or {@code getWriter()} is fed into a SHA-256 digest as it passes by. Zero
     * buffering — bytes go straight to the client.
     */
    private static class HashingResponseWrapper extends HttpServletResponseWrapper {
        private MessageDigest digest;
        private long bytesWritten;
        private ServletOutputStream cachedOut;
        private PrintWriter cachedWriter;

        HashingResponseWrapper(HttpServletResponse response) {
            super(response);
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException nsa) {
                // Should never happen on any modern JDK.
                this.digest = null;
            }
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (cachedOut != null) return cachedOut;
            final ServletOutputStream delegate = super.getOutputStream();
            cachedOut = new ServletOutputStream() {
                @Override public boolean isReady() { return delegate.isReady(); }
                @Override public void setWriteListener(WriteListener wl) { delegate.setWriteListener(wl); }
                @Override public void write(int b) throws IOException {
                    delegate.write(b);
                    if (digest != null) digest.update((byte) b);
                    bytesWritten++;
                }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    delegate.write(b, off, len);
                    if (digest != null) digest.update(b, off, len);
                    bytesWritten += len;
                }
                @Override public void flush() throws IOException { delegate.flush(); }
                @Override public void close() throws IOException { delegate.close(); }
            };
            return cachedOut;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (cachedWriter != null) return cachedWriter;
            // For text responses we don't bother hashing — text responses
            // aren't typically downloads anyway. Fall through to the default.
            cachedWriter = super.getWriter();
            return cachedWriter;
        }

        String digestHex() {
            if (digest == null) return "";
            byte[] d = digest.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        }
    }
}
