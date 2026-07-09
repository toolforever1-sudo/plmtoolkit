package com.sandisk.plm.tracker.model;

import java.util.ArrayList;
import java.util.List;

public class FeedbackQueueFile {
    public int nextId = 1;
    public List<FeedbackItem> items = new ArrayList<>();

    public FeedbackQueueFile() {}
}
