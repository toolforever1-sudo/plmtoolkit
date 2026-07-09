package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Builds the Restart ECN Proposal (date header + one AI-condensed bullet per
 *  related ECN) and Problem Statement ("Deploying ECNs:" + numbers). */
@Service
public class RestartEcnProposalBuilder {

    private static final DateTimeFormatter MDY = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final int FALLBACK_MAX = 120;

    @Autowired LineSummarizer summarizer;
    @Value("${restartEcn.proposalHeader:Deployment {date}:}") String proposalHeader;
    @Value("${restartEcn.problemStatementHeader:Deploying ECNs:}") String problemHeader;

    public static final class Built {
        public final String proposal;
        public final String problemStatement;
        Built(String p, String ps) { this.proposal = p; this.problemStatement = ps; }
    }

    /** @param descriptions ordered map of ECN number -> raw description. */
    public Built build(LocalDate deployDate, Map<String, String> descriptions) {
        if (descriptions == null) descriptions = java.util.Collections.emptyMap();
        String date = deployDate.format(MDY);
        StringBuilder proposal = new StringBuilder(proposalHeader.replace("{date}", date));
        StringBuilder nums = new StringBuilder();
        for (Map.Entry<String, String> e : descriptions.entrySet()) {
            String ecn = e.getKey();
            String line = summarizer.summarize(ecn, e.getValue());
            if (line == null || line.trim().isEmpty()) line = truncate(e.getValue());
            proposal.append("\n• ").append(ecn).append(": ").append(line);
            if (nums.length() > 0) nums.append(", ");
            nums.append(ecn);
        }
        String problem = problemHeader + "\n" + nums;
        return new Built(proposal.toString(), problem);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        s = s.trim().replaceAll("\\s+", " ");
        return s.length() <= FALLBACK_MAX ? s : s.substring(0, FALLBACK_MAX).trim() + "…";
    }
}
