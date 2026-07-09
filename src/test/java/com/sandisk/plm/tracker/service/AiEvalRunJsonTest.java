package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandisk.plm.tracker.model.AiEvalRun;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class AiEvalRunJsonTest {

    @Test
    public void roundTripPreservesAllFields() throws Exception {
        AiEvalRun original = new AiEvalRun();
        original.runId = "11111111-2222-3333-4444-555555555555";
        original.createdAt = "2026-05-03T07:42:11Z";
        original.createdBy = "plmadmin";
        original.status = "DONE";
        original.parentRunId = "00000000-0000-0000-0000-000000000000";

        original.config.persona.role = "CIO";
        original.config.persona.team = "PLM IT";
        original.config.persona.experience = "None";
        original.config.persona.goal = "trying to find what changed on ECN-12345";
        original.config.testerModel = "@anthropic-eastus2/claude-sonnet-4-6";
        original.config.evaluatorModel = "@openai-eastus2/gpt-4o";
        original.config.questionCount = 10;

        AiEvalRun.Result r = new AiEvalRun.Result();
        r.qIndex = 1;
        r.question = "How do I see all changes by Peter Zhu last week?";
        r.answer = "Use the Activity tab and filter by user...";
        r.grade = "B";
        r.reason = "Mentioned Activity tab but not the date-range picker.";
        r.answerLatencyMs = 2340;
        r.gradeLatencyMs = 1820;
        original.results.add(r);

        original.summary.avgGradeNumeric = 3.0;
        original.summary.avgGradeLetter = "B";
        original.summary.failureCount = 0;
        original.summary.totalLatencyMs = 4160;

        original.errors.addAll(Arrays.asList());

        ObjectMapper om = new ObjectMapper();
        String json = om.writeValueAsString(original);
        AiEvalRun back = om.readValue(json, AiEvalRun.class);

        assertEquals(original.runId, back.runId);
        assertEquals(original.createdAt, back.createdAt);
        assertEquals(original.createdBy, back.createdBy);
        assertEquals(original.status, back.status);
        assertEquals(original.parentRunId, back.parentRunId);
        assertEquals(original.config.persona.role, back.config.persona.role);
        assertEquals(original.config.persona.team, back.config.persona.team);
        assertEquals(original.config.persona.experience, back.config.persona.experience);
        assertEquals(original.config.persona.goal, back.config.persona.goal);
        assertEquals(original.config.testerModel, back.config.testerModel);
        assertEquals(original.config.evaluatorModel, back.config.evaluatorModel);
        assertEquals(original.config.questionCount, back.config.questionCount);
        assertEquals(1, back.results.size());
        assertEquals("B", back.results.get(0).grade);
        assertEquals(2340L, back.results.get(0).answerLatencyMs);
        assertEquals(3.0, back.summary.avgGradeNumeric, 0.0001);
        assertEquals("B", back.summary.avgGradeLetter);
        assertNotNull(back.errors);
    }
}
