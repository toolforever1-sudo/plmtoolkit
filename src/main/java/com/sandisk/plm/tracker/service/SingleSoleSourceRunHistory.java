package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandisk.plm.tracker.model.SingleSoleSourceRunResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only JSON store for Single/Sole Source run history.
 * Capped at 60 entries (rolls off oldest).
 */
@Service
public class SingleSoleSourceRunHistory {

    private static final int MAX_KEEP = 60;
    private final ObjectMapper json = new ObjectMapper();
    private final ReentrantLock lock = new ReentrantLock();

    @Value("${app.singlesole.runs.file}")
    private String runsFile;

    public void append(SingleSoleSourceRunResult result) throws IOException {
        lock.lock();
        try {
            List<SingleSoleSourceRunResult> all = readAll();
            all.add(0, result);
            while (all.size() > MAX_KEEP) all.remove(all.size() - 1);
            File f = new File(runsFile);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            json.writerWithDefaultPrettyPrinter().writeValue(f, all);
        } finally {
            lock.unlock();
        }
    }

    public List<SingleSoleSourceRunResult> readAll() {
        File f = new File(runsFile);
        if (!f.exists() || f.length() == 0) return new ArrayList<>();
        try {
            return new ArrayList<>(Arrays.asList(
                    json.readValue(f, SingleSoleSourceRunResult[].class)));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public SingleSoleSourceRunResult latest() {
        List<SingleSoleSourceRunResult> all = readAll();
        return all.isEmpty() ? null : all.get(0);
    }
}
