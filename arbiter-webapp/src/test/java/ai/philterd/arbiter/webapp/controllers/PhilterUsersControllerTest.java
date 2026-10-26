/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.Group;
import ai.philterd.arbiter.model.Roles;
import ai.philterd.arbiter.repository.GroupRepository;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.webapp.controllers.PhilterUsersController.Row;
import ai.philterd.arbiter.webapp.security.PhilterSessionAuthentication;
import ai.philterd.arbiter.webapp.security.PhilterUserProvisioner;
import ai.philterd.arbiter.webapp.security.PhilterUsersService;
import ai.philterd.philter.model.User;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.io.IOException;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhilterUsersControllerTest {

    private PhilterUsersService usersService;
    private PhilterUserProvisioner provisioner;
    private UserRepository userRepository;
    private GroupRepository groupRepository;
    private SessionRegistry sessionRegistry;
    private PhilterUsersController controller;
    private SessionInformation reviewerSession;
    private final PhilterSessionAuthentication admin = new PhilterSessionAuthentication("boss", "enc-admin",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    @BeforeEach
    void setUp() throws IOException {
        usersService = mock(PhilterUsersService.class);
        provisioner = mock(PhilterUserProvisioner.class);
        userRepository = mock(UserRepository.class);
        groupRepository = mock(GroupRepository.class);
        sessionRegistry = mock(SessionRegistry.class);
        reviewerSession = new SessionInformation("jdoe", "s1", new Date());
        when(sessionRegistry.getAllSessions("jdoe", false)).thenReturn(List.of(reviewerSession));
        controller = new PhilterUsersController(usersService, provisioner, userRepository, groupRepository,
                mock(AuditLogService.class), sessionRegistry);
        when(usersService.getUser("enc-admin", "jdoe")).thenReturn(philter("jdoe", "user", true));
        when(usersService.getUser("enc-admin", "ci")).thenReturn(philter("ci", "user", false));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static User philter(final String username, final String role, final boolean passwordSet) {
        final User user = new User();
        user.setUsername(username);
        user.setRole(role);
        user.setActive(true);
        user.setPasswordSet(passwordSet);
        return user;
    }

    private static ai.philterd.arbiter.model.User record(final String id, final String role) {
        final ai.philterd.arbiter.model.User user = new ai.philterd.arbiter.model.User();
        user.setId(id);
        user.setEmail("jdoe");
        user.setRoles(Set.of(role));
        return user;
    }

    private static Group group(final String id, final String... members) {
        final Group group = new Group();
        group.setId(id);
        group.setName("Group " + id);
        group.setUserIds(new HashSet<>(Set.of(members)));
        group.setLeaderUserIds(new HashSet<>(Set.of(members)));
        return group;
    }

    private static String error(final RedirectAttributesModelMap flash) {
        final Object e = flash.getFlashAttributes().get("error");
        return e == null ? null : e.toString();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsPhilterUsersWithTheirArbiterRoleAndGroups() throws Exception {
        when(usersService.listUsers("enc-admin")).thenReturn(List.of(
                philter("boss", "admin", true), philter("ci", "user", false), philter("jdoe", "user", true)));
        when(userRepository.findByEmail("jdoe")).thenReturn(Optional.of(record("r1", Roles.AUDITOR)));
        when(groupRepository.findAll()).thenReturn(List.of(group("g1", "r1"), group("g2", "other")));
        final ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("admin-philter-users", controller.users(admin, new MockHttpServletRequest(), model));

        final List<Row> rows = (List<Row>) model.get("rows");
        assertTrue(rows.get(0).self());
        assertTrue(rows.get(1).apiOnly());
        assertNull(rows.get(1).arbiterRole(), "no record yet");
        assertEquals(Roles.AUDITOR, rows.get(2).arbiterRole());
        assertEquals(Set.of("g1"), rows.get(2).groupIds());
        verify(userRepository, never()).save(any());
    }

    @Test
    void unreachablePhilterShowsAnErrorAndAnEmptyList() throws Exception {
        when(usersService.listUsers("enc-admin")).thenThrow(new IOException("connection refused"));
        final ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("admin-philter-users", controller.users(admin, new MockHttpServletRequest(), model));
        assertNotNull(model.get("error"));
    }

    @Test
    void rejectedAdministratorKeyEndsTheSession() throws Exception {
        when(usersService.listUsers("enc-admin")).thenThrow(new UnauthorizedException("expired"));
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        assertEquals("redirect:/login?expired", controller.users(admin, request, new ExtendedModelMap()));
        assertTrue(session.isInvalid());
    }

    @Test
    void addingAUserCreatesTheirArbiterRecord() throws Exception {
        when(provisioner.setArbiterRole("jdoe", "user", false)).thenReturn(record("r1", Roles.USER));

        controller.create(" jdoe ", "", "first-password-1234", false, admin, new MockHttpServletRequest(),
                new RedirectAttributesModelMap());

        verify(usersService).createUser("enc-admin", "jdoe", "", "user", "first-password-1234");
        verify(provisioner).setArbiterRole("jdoe", "user", false);
    }

    @Test
    void existingUsernameIsReportedWithoutPhiltersText() throws Exception {
        doThrow(new ClientException("Conflict", 409, "user jdoe exists")).when(usersService)
                .createUser(any(), any(), any(), any(), any());
        final RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        controller.create("jdoe", null, "first-password-1234", false, admin, new MockHttpServletRequest(), flash);

        assertTrue(error(flash).contains("already exists"));
        verify(provisioner, never()).setArbiterRole(anyString(), anyString(), eq(false));
    }

    @Test
    void roleChangeUpdatesTheRecordAndEndsTheirSessions() throws Exception {
        controller.setRole("jdoe", "admin", admin, new MockHttpServletRequest(), new RedirectAttributesModelMap());

        verify(usersService).setRole("enc-admin", "jdoe", "admin");
        verify(provisioner).applyPhilterRole("jdoe", "admin");
        assertTrue(reviewerSession.isExpired());
    }

    @Test
    void lastAdministratorConflictIsExplained() throws Exception {
        doThrow(new ClientException("Conflict", 409, "last admin")).when(usersService).setRole(any(), any(), any());
        final RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        controller.setRole("jdoe", "user", admin, new MockHttpServletRequest(), flash);

        assertTrue(error(flash).contains("at least one active administrator"));
        assertFalse(reviewerSession.isExpired());
    }

    @Test
    void ownAccountIsNotChangedHere() throws Exception {
        final RedirectAttributesModelMap role = new RedirectAttributesModelMap();
        final RedirectAttributesModelMap deactivate = new RedirectAttributesModelMap();
        final RedirectAttributesModelMap password = new RedirectAttributesModelMap();
        final RedirectAttributesModelMap signOut = new RedirectAttributesModelMap();
        controller.setRole("boss", "user", admin, new MockHttpServletRequest(), role);
        controller.deactivate("boss", admin, new MockHttpServletRequest(), deactivate);
        controller.resetPassword("boss", "new-password-12345", admin, new MockHttpServletRequest(), password);
        controller.signOut("boss", admin, new MockHttpServletRequest(), signOut);

        for (final RedirectAttributesModelMap flash : List.of(role, deactivate, password, signOut)) {
            assertTrue(error(flash).contains("own account"));
        }
        verify(usersService, never()).setRole(any(), any(), any());
        verify(usersService, never()).deactivate(any(), any());
        verify(usersService, never()).resetPassword(any(), any(), any());
        verify(usersService, never()).revokeSessions(any(), any());
    }

    @Test
    void meIsNeverATarget() throws Exception {
        for (final String me : List.of("me", "ME")) {
            final RedirectAttributesModelMap flash = new RedirectAttributesModelMap();
            controller.signOut(me, admin, new MockHttpServletRequest(), flash);
            controller.setArbiterAccess(me, false, List.of("g1"), admin, new MockHttpServletRequest(), flash);
            assertEquals("There is no such user in Philter.", error(flash));
        }
        verify(usersService, never()).revokeSessions(any(), any());
        verify(provisioner, never()).setArbiterRole(any(), any(), eq(false));
    }

    @Test
    void userPhilterReturnsMustBeTheOneAskedFor() throws Exception {
        when(usersService.getUser("enc-admin", "alias")).thenReturn(philter("boss", "admin", true));
        final RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        controller.signOut("alias", admin, new MockHttpServletRequest(), flash);

        assertEquals("There is no such user in Philter.", error(flash));
        verify(usersService, never()).revokeSessions(any(), any());
    }

    @Test
    void rejectedAdministratorKeyDuringTheCheckEndsTheSession() throws Exception {
        when(usersService.getUser("enc-admin", "jdoe")).thenThrow(new UnauthorizedException("revoked"));
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        assertEquals("redirect:/login?expired",
                controller.setRole("jdoe", "admin", admin, request, new RedirectAttributesModelMap()));
        assertTrue(session.isInvalid());
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsWithoutAPhilterUserAreListedOnlyWhenPhiltersListLoaded() throws Exception {
        final ai.philterd.arbiter.model.User leftover = record("old", Roles.USER);
        leftover.setEmail("old@example.com");
        when(userRepository.findAll()).thenReturn(List.of(record("r1", Roles.USER), leftover));
        when(usersService.listUsers("enc-admin")).thenReturn(List.of(philter("jdoe", "user", true)));
        final ExtendedModelMap model = new ExtendedModelMap();
        controller.users(admin, new MockHttpServletRequest(), model);
        assertEquals(List.of(leftover), model.get("orphans"));

        when(usersService.listUsers("enc-admin")).thenThrow(new IOException("down"));
        final ExtendedModelMap down = new ExtendedModelMap();
        controller.users(admin, new MockHttpServletRequest(), down);
        assertTrue(((List<?>) down.get("orphans")).isEmpty());
    }

    @Test
    void recordWithoutAPhilterUserIsDeletedAndLeavesItsGroups() throws Exception {
        final ai.philterd.arbiter.model.User leftover = record("old", Roles.USER);
        leftover.setEmail("old@example.com");
        when(userRepository.findById("old")).thenReturn(Optional.of(leftover));
        when(usersService.getUser("enc-admin", "old@example.com")).thenThrow(new ClientException("Not Found", 404, "no"));
        final Group shared = group("g1", "old", "other");
        when(groupRepository.findAll()).thenReturn(List.of(shared));

        controller.deleteRecord("old", admin, new MockHttpServletRequest(), new RedirectAttributesModelMap());

        verify(userRepository).deleteById("old");
        assertFalse(shared.getUserIds().contains("old"));
        assertFalse(shared.getLeaderUserIds().contains("old"));
    }

    @Test
    void recordOfAPhilterUserOrAGroupsOnlyMemberIsNotDeleted() throws Exception {
        when(userRepository.findById("r1")).thenReturn(Optional.of(record("r1", Roles.USER)));
        final RedirectAttributesModelMap stillInPhilter = new RedirectAttributesModelMap();
        controller.deleteRecord("r1", admin, new MockHttpServletRequest(), stillInPhilter);
        assertTrue(error(stillInPhilter).contains("is a Philter user"));

        final ai.philterd.arbiter.model.User leftover = record("old", Roles.USER);
        leftover.setEmail("old@example.com");
        when(userRepository.findById("old")).thenReturn(Optional.of(leftover));
        when(usersService.getUser("enc-admin", "old@example.com")).thenThrow(new ClientException("Not Found", 404, "no"));
        when(groupRepository.findAll()).thenReturn(List.of(group("g1", "old")));
        final RedirectAttributesModelMap onlyMember = new RedirectAttributesModelMap();
        controller.deleteRecord("old", admin, new MockHttpServletRequest(), onlyMember);
        assertTrue(error(onlyMember).contains("only member"));

        doThrow(new IOException("down")).when(usersService).getUser("enc-admin", "old@example.com");
        controller.deleteRecord("old", admin, new MockHttpServletRequest(), new RedirectAttributesModelMap());
        verify(userRepository, never()).deleteById(any());
    }

    @Test
    void apiOnlyAccountsCanOnlyBeDeactivatedOrReactivated() throws Exception {
        final RedirectAttributesModelMap flash = new RedirectAttributesModelMap();
        controller.resetPassword("ci", "new-password-12345", admin, new MockHttpServletRequest(), flash);
        assertEquals(PhilterUsersController.API_ONLY, error(flash));
        controller.setRole("ci", "admin", admin, new MockHttpServletRequest(), new RedirectAttributesModelMap());
        controller.setArbiterAccess("ci", true, null, admin, new MockHttpServletRequest(), new RedirectAttributesModelMap());
        verify(usersService, never()).resetPassword(any(), any(), any());
        verify(usersService, never()).setRole(any(), any(), any());
        verify(provisioner, never()).setArbiterRole(any(), any(), eq(true));

        controller.deactivate("ci", admin, new MockHttpServletRequest(), new RedirectAttributesModelMap());
        verify(usersService).deactivate("enc-admin", "ci");
    }

    @Test
    void passwordResetEndsTheirSessions() throws Exception {
        controller.resetPassword("jdoe", "new-password-12345", admin, new MockHttpServletRequest(),
                new RedirectAttributesModelMap());

        verify(usersService).resetPassword("enc-admin", "jdoe", "new-password-12345");
        assertTrue(reviewerSession.isExpired());
    }

    @Test
    void arbiterAccessSetsAuditorAndGroups() {
        when(userRepository.findByEmail("jdoe")).thenReturn(Optional.of(record("r1", Roles.USER)));
        final Group joining = group("g1", "other");
        final Group leaving = group("g2", "r1", "other");
        when(groupRepository.findAll()).thenReturn(List.of(joining, leaving));
        when(provisioner.setArbiterRole("jdoe", "user", true)).thenReturn(record("r1", Roles.AUDITOR));

        controller.setArbiterAccess("jdoe", true, List.of("g1"), admin, new MockHttpServletRequest(),
                new RedirectAttributesModelMap());

        assertTrue(joining.getUserIds().contains("r1"));
        assertFalse(leaving.getUserIds().contains("r1"));
        assertFalse(leaving.getLeaderUserIds().contains("r1"), "no longer its team lead");
        assertTrue(reviewerSession.isExpired(), "the role changed from User to Auditor");
    }

    @Test
    void groupsOnlyMemberCannotBeRemoved() {
        when(userRepository.findByEmail("jdoe")).thenReturn(Optional.of(record("r1", Roles.USER)));
        when(groupRepository.findAll()).thenReturn(List.of(group("g1", "r1")));
        final RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        controller.setArbiterAccess("jdoe", false, List.of(), admin, new MockHttpServletRequest(), flash);

        assertTrue(error(flash).contains("only member"));
        verify(provisioner, never()).setArbiterRole(any(), any(), eq(false));
        verify(groupRepository, never()).save(any());
    }

    @Test
    void savingArbiterAccessCreatesTheRecordOfSomeoneNotSignedInYet() {
        when(userRepository.findByEmail("jdoe")).thenReturn(Optional.empty());
        when(groupRepository.findAll()).thenReturn(List.of());
        when(provisioner.setArbiterRole("jdoe", "user", false)).thenReturn(record("r1", Roles.USER));

        controller.setArbiterAccess("jdoe", false, null, admin, new MockHttpServletRequest(),
                new RedirectAttributesModelMap());

        verify(provisioner).setArbiterRole("jdoe", "user", false);
    }

    @Test
    void auditorIsToldThatOnlyAdministratorsSeeUsers() throws Exception {
        when(usersService.listUsers("enc-admin")).thenThrow(new ClientException("Forbidden", 403, "not an administrator"));
        final ExtendedModelMap model = new ExtendedModelMap();

        controller.users(admin, new MockHttpServletRequest(), model);

        assertEquals("Only Philter administrators can see the users.", model.get("error"));
    }
}
