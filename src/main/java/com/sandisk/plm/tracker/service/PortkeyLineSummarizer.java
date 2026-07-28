package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.logging.Logger;

/** Default {@link LineSummarizer} using the Portkey LLM gateway. Fail-soft:
 *  returns null when Portkey is disabled/unconfigured or the call errors. */
@Component
public class PortkeyLineSummarizer implements LineSummarizer {

    private static final Logger LOG = Logger.getLogger(PortkeyLineSummarizer.class.getName());

    @Autowired private PortkeyClient portkeyClient;
    @Value("${portkey.enabled:false}")       private boolean portkeyEnabled;
    @Value("${portkey.api-key:}")            private String portkeyApiKey;
    @Value("${portkey.provider:@anthropic-eastus2}") private String portkeyProvider;
    @Value("${portkey.model:claude-sonnet-4-6}")     private String portkeyModel;

    @Override
    public String summarize(String ecnNumber, String description) {
        if (description == null || description.trim().isEmpty()) return null;
        if (!portkeyEnabled || portkeyApiKey == null || portkeyApiKey.isEmpty()) return null;
        try {
            String system = "You condense Agile PLM ECN descriptions into a single, plain, "
                    + "under-18-word summary line. No preamble, no trailing period, no markdown.";
            String user = "Condense this ECN description to one short line:\n\n" + description;
            String model = portkeyProvider + "/" + portkeyModel;
            String out = portkeyClient.chat(model, system, user, 80);
            if (out == null) return null;
            out = out.trim().replaceAll("\\s+", " ");
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            LOG.warning("[RESTART-ECN] summarize failed for " + ecnNumber + ": " + e.getMessage());
            return null;
        }
    }
}
