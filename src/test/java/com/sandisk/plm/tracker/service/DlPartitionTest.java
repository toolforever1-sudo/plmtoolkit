package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.UserPermissionsService.DlPartition;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class DlPartitionTest {

    private Map<String, String> u(String sam) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("sAMAccountName", sam);
        m.put("displayName", sam + " Name");
        m.put("email", sam + "@sandisk.com");
        return m;
    }

    @Test
    void splitsInDlFromNeedsDlCaseInsensitive() {
        List<Map<String, String>> users = Arrays.asList(u("alice"), u("BOB"), u("carol"));
        Set<String> dl = new HashSet<>(Arrays.asList("bob"));   // stored lowercase
        DlPartition p = UserPermissionsService.partitionByDl(users, dl);
        assertEquals(1, p.alreadyInDl.size());
        assertEquals("BOB", p.alreadyInDl.get(0).get("sAMAccountName"));
        assertEquals(2, p.needsDl.size());
    }

    @Test
    void emptyDlSetMeansEveryoneNeedsDl() {
        List<Map<String, String>> users = Arrays.asList(u("alice"), u("bob"));
        DlPartition p = UserPermissionsService.partitionByDl(users, Collections.emptySet());
        assertEquals(2, p.needsDl.size());
        assertTrue(p.alreadyInDl.isEmpty());
    }

    @Test
    void nullDlSetMeansEveryoneNeedsDl() {
        List<Map<String, String>> users = Collections.singletonList(u("alice"));
        DlPartition p = UserPermissionsService.partitionByDl(users, null);
        assertEquals(1, p.needsDl.size());
    }
}
