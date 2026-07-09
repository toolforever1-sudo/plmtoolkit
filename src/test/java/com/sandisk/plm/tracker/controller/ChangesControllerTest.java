package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.ChangeRecord;
import com.sandisk.plm.tracker.service.ChangeQueryService;
import com.sandisk.plm.tracker.service.ExcelExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.util.Arrays;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChangesController.class)
public class ChangesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChangeQueryService changeQueryService;

    @MockBean
    private ExcelExportService excelExportService;

    @Test
    void getChanges_returnsJson() throws Exception {
        ChangeQueryService.SearchResult result = new ChangeQueryService.SearchResult(
                Arrays.asList(
                        new ChangeRecord("01-100", "Description", "Old", "New",
                                new Timestamp(1712860800000L), "Zhu, Peter", "B")
                ),
                1, 1, 150, false, false, null
        );

        when(changeQueryService.search(
                eq("Description"), isNull(), isNull(), eq(7),
                isNull(), isNull(), eq(true)))
                .thenReturn(result);

        mockMvc.perform(get("/api/changes")
                        .param("field", "Description")
                        .param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].itemNumber", is("01-100")))
                .andExpect(jsonPath("$.totalCount", is(1)))
                .andExpect(jsonPath("$.uniqueItems", is(1)))
                .andExpect(jsonPath("$.queryTimeMs", is(150)));
    }

    @Test
    void getChanges_defaultParams() throws Exception {
        ChangeQueryService.SearchResult result = new ChangeQueryService.SearchResult(
                Arrays.asList(), 0, 0, 50, false, false, null);

        when(changeQueryService.search(
                isNull(), isNull(), isNull(), eq(7),
                isNull(), isNull(), eq(true)))
                .thenReturn(result);

        mockMvc.perform(get("/api/changes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(0)))
                .andExpect(jsonPath("$.totalCount", is(0)));
    }

    @Test
    void exportChanges_returnsXlsx() throws Exception {
        ChangeQueryService.SearchResult result = new ChangeQueryService.SearchResult(
                Arrays.asList(
                        new ChangeRecord("01-100", "Description", "Old", "New",
                                new Timestamp(1712860800000L), "Zhu, Peter", "B")
                ),
                1, 1, 150, false, false, null
        );

        when(changeQueryService.search(
                anyString(), isNull(), isNull(), anyInt(),
                isNull(), isNull(), anyBoolean()))
                .thenReturn(result);

        mockMvc.perform(get("/api/changes/export")
                        .param("field", "Description")
                        .param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition",
                        containsString("attachment; filename=")));
    }
}
