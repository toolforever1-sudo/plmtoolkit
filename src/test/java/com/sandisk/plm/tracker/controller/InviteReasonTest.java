package com.sandisk.plm.tracker.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class InviteReasonTest {

    @Test
    void notFound() {
        assertEquals("NOT_FOUND", UserPermissionsController.inviteReason(false, false, false));
        assertEquals("NOT_FOUND", UserPermissionsController.inviteReason(false, true, true));
    }

    @Test
    void foundButNotInDl() {
        assertEquals("NOT_IN_DL", UserPermissionsController.inviteReason(true, false, false));
    }

    @Test
    void inDlButEmailFailed() {
        assertEquals("EMAIL_FAILED", UserPermissionsController.inviteReason(true, true, false));
    }

    @Test
    void sentReturnsNull() {
        assertNull(UserPermissionsController.inviteReason(true, true, true));
    }
}
