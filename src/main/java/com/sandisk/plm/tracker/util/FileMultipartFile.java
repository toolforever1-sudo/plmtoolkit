package com.sandisk.plm.tracker.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;

/**
 * Minimal {@link MultipartFile} adapter backed by a {@link File} on disk.
 * Used by {@link com.sandisk.plm.tracker.service.UploadQuarantineService} to
 * file orphaned uploads back into the feedback queue (which expects a
 * MultipartFile attachment).
 */
public class FileMultipartFile implements MultipartFile {

    private final File file;
    private final String name;
    private final String originalFilename;
    private final String contentType;

    public FileMultipartFile(File file, String originalFilename, String contentType) {
        this.file = file;
        this.name = "file";
        this.originalFilename = originalFilename;
        this.contentType = contentType;
    }

    @Override public String getName() { return name; }
    @Override public String getOriginalFilename() { return originalFilename; }
    @Override public String getContentType() { return contentType; }
    @Override public boolean isEmpty() { return !file.exists() || file.length() == 0; }
    @Override public long getSize() { return file.length(); }

    @Override
    public byte[] getBytes() throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new FileInputStream(file);
    }

    @Override
    public void transferTo(File dest) throws IOException, IllegalStateException {
        Files.copy(file.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
