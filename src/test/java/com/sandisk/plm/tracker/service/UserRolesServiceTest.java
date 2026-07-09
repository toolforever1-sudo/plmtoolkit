package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Engineering-insider role: grant/revoke is restricted to PLM IT
 * ({@code app.roles.engineering-insider.granters}) and must be STRICTER than
 * the general permissions-admin allowlist — a permissions admin who is not in
 * the granters list gets a SecurityException, enforced server-side.
 */
public class UserRolesServiceTest {

    private UserPermissionsService svc;

    private static Set<String> groups(String... cns) {
        return new LinkedHashSet<>(java.util.Arrays.asList(cns));
    }

    @BeforeEach
    public void setUp() throws Exception {
        svc = new UserPermissionsService();
        svc.setEngInsiderGrantersCsvForTest("8252,plmadmin,pdl-plm-it-core");
        // Grant/revoke write the activity log; wire a logger pointed at a temp file.
        ActivityLogger al = new ActivityLogger();
        setField(al, "logFilePath",
            java.io.File.createTempFile("roles-test-activity", ".jsonl").getAbsolutePath());
        setField(svc, "activityLogger", al);
        // Point the roles store at a temp file so tests never touch ./data.
        java.io.File tmp = java.io.File.createTempFile("roles-test", ".json");
        tmp.delete();
        svc.setRolesFilePathForTest(new java.io.File(tmp.getParentFile(), "roles-sub/" + tmp.getName()).getPath());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    public void plmItByUsernameCanGrantAndRevoke() {
        svc.grantRole("8252", Collections.emptySet(), "Jindal, Vikas",
            "19877", UserPermissionsService.ROLE_ENGINEERING_INSIDER);
        assertTrue(svc.hasRole("19877", UserPermissionsService.ROLE_ENGINEERING_INSIDER));
        assertTrue(svc.hasRole("19877".toUpperCase(), UserPermissionsService.ROLE_ENGINEERING_INSIDER));

        svc.revokeRole("8252", Collections.emptySet(), "Jindal, Vikas",
            "19877", UserPermissionsService.ROLE_ENGINEERING_INSIDER);
        assertFalse(svc.hasRole("19877", UserPermissionsService.ROLE_ENGINEERING_INSIDER));
    }

    @Test
    public void plmItByAdGroupCanGrant() {
        svc.grantRole("31204", groups("pdl-plm-it-core"), "Ashe, Kathy",
            "27455", UserPermissionsService.ROLE_ENGINEERING_INSIDER);
        assertTrue(svc.hasRole("27455", UserPermissionsService.ROLE_ENGINEERING_INSIDER));
    }

    @Test
    public void permissionsAdminWhoIsNotPlmItGets403() {
        // "vsingh" is a permissions admin in prod config but NOT in the granters CSV.
        SecurityException ex = assertThrows(SecurityException.class, () ->
            svc.grantRole("vsingh", groups("pdl-plm-admin"), "Singh, Vikas",
                "19877", UserPermissionsService.ROLE_ENGINEERING_INSIDER));
        assertTrue(ex.getMessage().contains("PLM IT"));
        assertFalse(svc.hasRole("19877", UserPermissionsService.ROLE_ENGINEERING_INSIDER));

        assertThrows(SecurityException.class, () ->
            svc.revokeRole("vsingh", Collections.emptySet(), "Singh, Vikas",
                "19877", UserPermissionsService.ROLE_ENGINEERING_INSIDER));
    }

    @Test
    public void grantIsIdempotentAndAuditStamped() {
        svc.grantRole("plmadmin", null, "Emergency Admin",
            "19877", UserPermissionsService.ROLE_ENGINEERING_INSIDER);
        svc.grantRole("plmadmin", null, "Emergency Admin",
            "19877", UserPermissionsService.ROLE_ENGINEERING_INSIDER);
        assertEquals(1, svc.roleHolders(UserPermissionsService.ROLE_ENGINEERING_INSIDER).size());

        java.util.Map<String, Object> audit =
            svc.roleLastChange(UserPermissionsService.ROLE_ENGINEERING_INSIDER, "19877");
        assertNotNull(audit);
        assertEquals("grant", audit.get("action"));
        assertEquals("plmadmin", audit.get("by"));
    }

    @Test
    public void nobodyIsPlmItWhenCsvEmpty() {
        svc.setEngInsiderGrantersCsvForTest("");
        assertFalse(svc.isPlmIt("8252", groups("pdl-plm-admin")));
    }

    @Test
    public void hasRoleFalseForUnknownUserOrRole() {
        assertFalse(svc.hasRole("nobody", UserPermissionsService.ROLE_ENGINEERING_INSIDER));
        assertFalse(svc.hasRole(null, UserPermissionsService.ROLE_ENGINEERING_INSIDER));
        assertFalse(svc.hasRole("8252", null));
    }
}
