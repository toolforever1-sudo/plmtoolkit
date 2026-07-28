package com.sandisk.plm.tracker.util;

import com.sandisk.plm.tracker.service.PortkeyClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class UserColumnMapperTest {

    private UserColumnMapper mapperWith(PortkeyClient client) {
        UserColumnMapper m = new UserColumnMapper();
        ReflectionTestUtils.setField(m, "portkeyClient", client);
        ReflectionTestUtils.setField(m, "aiModel", "test-model");
        ReflectionTestUtils.setField(m, "aiEnabled", true);
        return m;
    }

    @Test
    void heuristicMapsObviousHeaders() {
        UserColumnMapper.Mapping map = mapperWith(null)
            .map(Arrays.asList("Name", "Email"), Collections.emptyList());
        assertEquals(0, map.nameColumn);
        assertEquals(1, map.emailColumn);
        assertTrue(map.confident);
        assertEquals("heuristic", map.method);
    }

    @Test
    void heuristicHandlesReversedAndAltLabels() {
        UserColumnMapper.Mapping map = mapperWith(null)
            .map(Arrays.asList("E-mail Address", "Full Name"), Collections.emptyList());
        assertEquals(1, map.nameColumn);
        assertEquals(0, map.emailColumn);
        assertTrue(map.confident);
    }

    @Test
    void aiFallbackUsedWhenHeadersAreOpaque() throws Exception {
        PortkeyClient client = mock(PortkeyClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.chat(anyString(), anyString(), anyString(), anyInt()))
            .thenReturn("{\"nameColumn\":0,\"emailColumn\":1,\"confident\":true,\"reasoning\":\"col0 looks like names\"}");
        UserColumnMapper.Mapping map = mapperWith(client).map(
            Arrays.asList("Col1", "Col2"),
            Arrays.asList(Arrays.asList("Philip Tam", "Philip.Tam@sandisk.com")));
        assertEquals(0, map.nameColumn);
        assertEquals(1, map.emailColumn);
        assertTrue(map.confident);
        assertEquals("ai", map.method);
    }

    @Test
    void notConfidentWhenNoNameColumnAndAiDisabled() {
        UserColumnMapper.Mapping map = mapperWith(null)
            .map(Arrays.asList("Region", "Cost Center"), Collections.emptyList());
        assertFalse(map.confident);
        assertNotNull(map.question);
    }

    @Test
    void emailMissingIsAllowedButFlagged() {
        UserColumnMapper.Mapping map = mapperWith(null)
            .map(Arrays.asList("Full Name"), Collections.emptyList());
        assertEquals(0, map.nameColumn);
        assertEquals(-1, map.emailColumn);
        // name found but no email -> still usable, confident on name
        assertTrue(map.confident);
    }

    @Test
    void aiNotConfidentIsHonored() throws Exception {
        PortkeyClient client = mock(PortkeyClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.chat(anyString(), anyString(), anyString(), anyInt()))
            .thenReturn("{\"nameColumn\":0,\"emailColumn\":1,\"confident\":false,\"reasoning\":\"unsure\"}");
        UserColumnMapper.Mapping map = mapperWith(client).map(
            Arrays.asList("Col1", "Col2"),
            Arrays.asList(Arrays.asList("Some Value", "other@sandisk.com")));
        assertEquals(0, map.nameColumn);
        assertEquals("ai", map.method);
        assertFalse(map.confident);
    }

    @Test
    void aiOutOfRangeIndexIgnored() throws Exception {
        PortkeyClient client = mock(PortkeyClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.chat(anyString(), anyString(), anyString(), anyInt()))
            .thenReturn("{\"nameColumn\":99,\"emailColumn\":1,\"confident\":true}");
        UserColumnMapper.Mapping map = mapperWith(client).map(
            Arrays.asList("Col1", "Col2"),
            Arrays.asList(Arrays.asList("Some Value", "other@sandisk.com")));
        // nameColumn=99 is out of range for 2-column list → byAi returns null → falls back to heuristic
        assertFalse(map.confident);
        assertNotNull(map.question);
    }
}
