package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.LdapAuthService.DirectoryUser;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class UserImportServiceTest {

    private DirectoryUser du(String user, String name, String email) {
        return new DirectoryUser(user, name, email);
    }

    private UserImportService svc(LdapAuthService ldap, UserPermissionsService perms) {
        return new UserImportService(ldap, perms);
    }

    @Test
    void confidentEmailMatchIsMatched() {
        LdapAuthService ldap = mock(LdapAuthService.class);
        UserPermissionsService perms = mock(UserPermissionsService.class);
        when(ldap.searchDirectory(eq("philip.tam@sandisk.com"), anyInt()))
            .thenReturn(Collections.singletonList(du("philip.tam", "Philip Tam", "Philip.Tam@sandisk.com")));
        when(ldap.listAccessGroupCandidates()).thenReturn(Collections.emptyList());
        when(perms.allRecords()).thenReturn(Collections.emptyMap());

        UserImportService.PreviewRow row = svc(ldap, perms)
            .resolveRow("Philip Tam", "philip.tam@sandisk.com");
        assertEquals("matched", row.status);
        assertNotNull(row.match);
        assertEquals("philip.tam", row.match.sAMAccountName);
    }

    @Test
    void emailExactMatchWinsEvenWhenNameDiffers() {
        LdapAuthService ldap = mock(LdapAuthService.class);
        UserPermissionsService perms = mock(UserPermissionsService.class);
        // Mahek's email doesn't share the name; email search returns the right person.
        when(ldap.searchDirectory(eq("mahek.naresh.oberai@sandisk.com"), anyInt()))
            .thenReturn(Collections.singletonList(du("mahek.oberai", "Mahek Amaria", "Mahek.Naresh.Oberai@sandisk.com")));
        when(ldap.listAccessGroupCandidates()).thenReturn(Collections.emptyList());
        when(perms.allRecords()).thenReturn(Collections.emptyMap());

        UserImportService.PreviewRow row = svc(ldap, perms)
            .resolveRow("Mahek Amaria", "mahek.naresh.oberai@sandisk.com");
        assertEquals("matched", row.status);
        assertEquals("mahek.oberai", row.match.sAMAccountName);
    }

    @Test
    void multipleHitsAreAmbiguous() {
        LdapAuthService ldap = mock(LdapAuthService.class);
        UserPermissionsService perms = mock(UserPermissionsService.class);
        when(ldap.searchDirectory(eq("Eva Lu"), anyInt()))
            .thenReturn(Arrays.asList(du("eva.lu", "Eva Lu", "eva.lu@sandisk.com"),
                                      du("eva.lu2", "Eva Lu", "eva.lu2@sandisk.com")));
        when(ldap.listAccessGroupCandidates()).thenReturn(Collections.emptyList());
        when(perms.allRecords()).thenReturn(Collections.emptyMap());

        UserImportService.PreviewRow row = svc(ldap, perms).resolveRow("Eva Lu", "");
        assertEquals("ambiguous", row.status);
        assertEquals(2, row.candidates.size());
        assertNull(row.match);
    }

    @Test
    void noHitsIsNomatch() {
        LdapAuthService ldap = mock(LdapAuthService.class);
        UserPermissionsService perms = mock(UserPermissionsService.class);
        when(ldap.searchDirectory(anyString(), anyInt())).thenReturn(Collections.emptyList());
        when(ldap.listAccessGroupCandidates()).thenReturn(Collections.emptyList());
        when(perms.allRecords()).thenReturn(Collections.emptyMap());

        UserImportService.PreviewRow row = svc(ldap, perms).resolveRow("Ghost User", "ghost@sandisk.com");
        assertEquals("nomatch", row.status);
    }

    @Test
    void alreadyInDlIsFlaggedAsAccess() {
        LdapAuthService ldap = mock(LdapAuthService.class);
        UserPermissionsService perms = mock(UserPermissionsService.class);
        when(ldap.searchDirectory(eq("vaibhav.singh@sandisk.com"), anyInt()))
            .thenReturn(Collections.singletonList(du("vaibhav.singh", "Vaibhav Singh", "vaibhav.singh@sandisk.com")));
        when(ldap.listAccessGroupCandidates())
            .thenReturn(Collections.singletonList(du("vaibhav.singh", "Vaibhav Singh", "vaibhav.singh@sandisk.com")));
        when(perms.allRecords()).thenReturn(Collections.emptyMap());

        UserImportService.PreviewRow row = svc(ldap, perms)
            .resolveRow("Vaibhav Singh", "vaibhav.singh@sandisk.com");
        assertEquals("already-access", row.status);
        assertNotNull(row.match);
    }

    @Test
    void blankRowIsFlaggedBlank() {
        LdapAuthService ldap = mock(LdapAuthService.class);
        UserPermissionsService perms = mock(UserPermissionsService.class);

        UserImportService.PreviewRow row = svc(ldap, perms).resolveRow("", "");
        assertEquals("blank", row.status);
        verify(ldap, never()).searchDirectory(anyString(), anyInt());
    }

    @Test
    void nullEmailInAdResultIsSafe() {
        LdapAuthService ldap = mock(LdapAuthService.class);
        UserPermissionsService perms = mock(UserPermissionsService.class);
        // AD result has a null email field — should not throw NPE and should still match as single hit.
        when(ldap.searchDirectory(eq("ghost@sandisk.com"), anyInt()))
            .thenReturn(Collections.singletonList(du("ghost", "Ghost", null)));
        when(ldap.listAccessGroupCandidates()).thenReturn(Collections.emptyList());
        when(perms.allRecords()).thenReturn(Collections.emptyMap());

        UserImportService.PreviewRow row = svc(ldap, perms).resolveRow("Ghost", "ghost@sandisk.com");
        assertEquals("matched", row.status);
        assertNotNull(row.match);
        assertEquals("ghost", row.match.sAMAccountName);
    }
}
