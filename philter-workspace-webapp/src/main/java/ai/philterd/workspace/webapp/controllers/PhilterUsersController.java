/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.model.Group;
import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.workspace.repository.GroupRepository;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.webapp.security.PhilterSessionAuthentication;
import ai.philterd.workspace.webapp.security.PhilterSessions;
import ai.philterd.workspace.webapp.security.PhilterUserProvisioner;
import ai.philterd.workspace.webapp.security.PhilterUsersService;
import ai.philterd.workspace.webapp.ui.Confirmation;
import ai.philterd.workspace.webapp.ui.ListView;
import ai.philterd.philter.model.GetUsersResponse;
import ai.philterd.philter.model.ListOptions;
import ai.philterd.philter.model.User;
import ai.philterd.philter.model.exceptions.PhilterError;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Users page when people sign in through Philter. Accounts, passwords, MFA, and the admin role are
 * Philter's, changed through its Users API with the signed-in administrator's session key. Philter Workspace's own
 * part is the User or Auditor role and group membership, kept on the person's Philter Workspace record.
 *
 * <p>A user without a password (automation and service accounts) is listed as API only and can only
 * be deactivated or reactivated here. An administrator cannot change their own role, password, MFA, or
 * active state here; Philter refuses most of those too.</p>
 */
@Controller
@RequestMapping("/admin/users")
public class PhilterUsersController {

    static final String PHILTER_ADMIN = "admin";
    static final String PHILTER_USER = "user";
    /** In a Users API path, {@code me} means the calling key's own user. */
    static final String RESERVED_ME = "me";

    private static final Logger log = LoggerFactory.getLogger(PhilterUsersController.class);
    private static final String PATH = "/admin/users";
    private static final String REDIRECT = "redirect:" + PATH;
    private static final String RECORDS_REDIRECT = "redirect:" + PATH + "/records";

    private final PhilterUsersService usersService;
    private final PhilterUserProvisioner provisioner;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final AuditLogService auditLogService;
    private final SessionRegistry sessionRegistry;

    public PhilterUsersController(final PhilterUsersService usersService,
                                  final PhilterUserProvisioner provisioner,
                                  final UserRepository userRepository,
                                  final GroupRepository groupRepository,
                                  final AuditLogService auditLogService,
                                  final SessionRegistry sessionRegistry) {
        this.usersService = usersService;
        this.provisioner = provisioner;
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.auditLogService = auditLogService;
        this.sessionRegistry = sessionRegistry;
    }

    /** Sort fields the Users table offers; Philter can sort users by these. */
    static final Set<String> SORTS = Set.of("username");
    static final List<String> FILTERS = List.of("role", "active");

    /**
     * One row of the page: the Philter user, their Philter Workspace role and groups if they have a record, and
     * the confirmations for its destructive actions, by action.
     */
    public record Row(User philter, String workspaceRole, Set<String> groupIds, boolean apiOnly, boolean self,
                      Map<String, Confirmation> confirm) {
    }

    /** The Users page: one page of Philter's users, searched, filtered, and sorted by Philter. */
    @GetMapping
    public String users(final Authentication authentication, final HttpServletRequest request, final Model model) {
        final PhilterSessionAuthentication admin = admin(authentication);
        if (admin == null) {
            return "redirect:/login";
        }
        ListView list = ListView.of(request, PATH, SORTS, "username", FILTERS);
        final ListOptions options = list.options();
        final String role = list.filter("role");
        if (PHILTER_ADMIN.equals(role) || PHILTER_USER.equals(role)) {
            options.role(role);
        }
        final String active = list.filter("active");
        if ("true".equals(active) || "false".equals(active)) {
            options.active(Boolean.valueOf(active));
        }
        List<User> philterUsers = List.of();
        try {
            final GetUsersResponse page = usersService.listPage(admin.getEncryptedSessionKey(), options);
            if (page != null && page.getUsers() != null) {
                philterUsers = page.getUsers();
                list = list.withTotal(page.getTotal());
            }
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return endSession(request);
            }
            model.addAttribute("error", listError(e));
        }
        final List<Group> groups = groupRepository.findAll();
        final List<Row> rows = new ArrayList<>();
        for (final User user : philterUsers) {
            final ai.philterd.workspace.model.User record = userRepository.findByEmail(user.getUsername()).orElse(null);
            final Set<String> groupIds = new HashSet<>();
            if (record != null) {
                for (final Group group : groups) {
                    if (group.getUserIds() != null && group.getUserIds().contains(record.getId())) {
                        groupIds.add(group.getId());
                    }
                }
            }
            final String workspaceRole = record == null || record.getRoles() == null || record.getRoles().isEmpty()
                    ? null : record.getRoles().iterator().next();
            rows.add(new Row(user, workspaceRole, groupIds, !user.isPasswordSet(),
                    user.getUsername().equals(admin.getName()), confirmations(user.getUsername())));
        }
        model.addAttribute("list", list);
        model.addAttribute("rows", rows);
        model.addAttribute("groups", groups);
        return "admin-philter-users";
    }

    /**
     * Philter Workspace records with no Philter user, such as local accounts from before the switch to Philter
     * sign-in. Finding them means reading every Philter user, so they have their own page rather than
     * slowing every page of the Users table. Nothing is listed when Philter's users cannot be read, or every
     * record would look like one.
     */
    @GetMapping("/records")
    public String records(final Authentication authentication, final HttpServletRequest request, final Model model) {
        final PhilterSessionAuthentication admin = admin(authentication);
        if (admin == null) {
            return "redirect:/login";
        }
        final List<ai.philterd.workspace.model.User> orphans = new ArrayList<>();
        try {
            final Set<String> usernames = new HashSet<>();
            usersService.listUsers(admin.getEncryptedSessionKey()).forEach(u -> usernames.add(u.getUsername()));
            for (final ai.philterd.workspace.model.User record : userRepository.findAll()) {
                if (record.getEmail() != null && !usernames.contains(record.getEmail())) {
                    orphans.add(record);
                }
            }
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return endSession(request);
            }
            model.addAttribute("error", listError(e));
        }
        final Map<String, Confirmation> deletions = new HashMap<>();
        orphans.forEach(o -> deletions.put(o.getId(), recordDeletion(o)));
        model.addAttribute("orphans", orphans);
        model.addAttribute("deletions", deletions);
        return "admin-philter-users";
    }

    /** The confirmation page for a destructive action on a user, for browsers without JavaScript. */
    @GetMapping("/{username}/confirm/{action}")
    public String confirm(@PathVariable final String username, @PathVariable final String action,
                          final Model model) {
        final Confirmation confirmation = confirmations(username).get(action);
        if (confirmation == null) {
            return REDIRECT;
        }
        model.addAttribute("confirmation", confirmation);
        return "confirm";
    }

    /** The confirmation page for deleting a record without a Philter user. */
    @GetMapping("/records/{recordId}/confirm-delete")
    public String confirmRecordDeletion(@PathVariable final String recordId, final Model model) {
        final ai.philterd.workspace.model.User record = userRepository.findById(recordId).orElse(null);
        if (record == null) {
            return RECORDS_REDIRECT;
        }
        model.addAttribute("confirmation", recordDeletion(record));
        return "confirm";
    }

    /** The destructive actions on one user, confirmed first, by the action's path segment. */
    static Map<String, Confirmation> confirmations(final String username) {
        final String base = PATH + "/" + UriUtils.encodePathSegment(username, StandardCharsets.UTF_8);
        return Map.of(
                "deactivate", new Confirmation("Deactivate " + username + "?",
                        "They will no longer be able to sign in or use their API keys, and their sessions end. "
                                + "You can reactivate them later.",
                        base + "/deactivate", "Deactivate", base + "/confirm/deactivate", PATH),
                "remove-mfa", new Confirmation("Remove two-factor authentication for " + username + "?",
                        "They will sign in with their password alone until they set it up again.",
                        base + "/remove-mfa", "Remove 2FA", base + "/confirm/remove-mfa", PATH),
                "sign-out", new Confirmation("Sign " + username + " out everywhere?",
                        "Every session they have in Philter and Philter Workspace ends.",
                        base + "/sign-out", "Sign out", base + "/confirm/sign-out", PATH));
    }

    private static Confirmation recordDeletion(final ai.philterd.workspace.model.User record) {
        final String base = PATH + "/records/" + UriUtils.encodePathSegment(record.getId(), StandardCharsets.UTF_8);
        return new Confirmation("Delete the record for " + record.getEmail() + "?",
                "The record is removed from its groups and as a team lead. Nobody can sign in as it.",
                base + "/delete", "Delete record", base + "/confirm-delete", PATH + "/records");
    }

    private static String listError(final Exception e) {
        if (e instanceof PhilterError error && error.getStatusCode() == 403) {
            // Auditors can open admin pages, but Philter lists users only for its administrators.
            return "Only Philter administrators can see the users.";
        }
        log.warn("Could not list Philter users: {}", PhilterClients.describe(e));
        return "Philter could not be reached, so users cannot be listed.";
    }

    @PostMapping
    public String create(@RequestParam("username") final String username,
                         @RequestParam(value = "email", required = false) final String email,
                         @RequestParam("password") final String password,
                         @RequestParam(value = "admin", defaultValue = "false") final boolean makeAdmin,
                         final Authentication authentication,
                         final HttpServletRequest request,
                         final RedirectAttributes flash) {
        final PhilterSessionAuthentication admin = admin(authentication);
        final String name = username == null ? "" : username.trim();
        if (admin == null || name.isEmpty()) {
            flash.addFlashAttribute("error", "A username is required.");
            return REDIRECT;
        }
        final String role = makeAdmin ? PHILTER_ADMIN : PHILTER_USER;
        try {
            usersService.createUser(admin.getEncryptedSessionKey(), name, email == null ? null : email.trim(),
                    role, password);
        } catch (Exception e) {
            return refused(e, request, flash, "A user with that username already exists in Philter, "
                    + "active or deactivated.");
        }
        // Philter's audit log records the new user (user_created); Philter Workspace's record follows it.
        provisioner.setWorkspaceRole(name, role, false);
        flash.addFlashAttribute("success", "User \"" + name + "\" added. They choose their own password at first sign-in.");
        return REDIRECT;
    }

    @PostMapping("/{username}/role")
    public String setRole(@PathVariable final String username,
                          @RequestParam("role") final String role,
                          final Authentication authentication,
                          final HttpServletRequest request,
                          final RedirectAttributes flash) {
        if (!PHILTER_ADMIN.equals(role) && !PHILTER_USER.equals(role)) {
            flash.addFlashAttribute("error", "Unknown role.");
            return REDIRECT;
        }
        final Target target = guard(authentication, username, true, false, request, flash);
        if (target.redirect() != null) {
            return target.redirect();
        }
        final PhilterSessionAuthentication admin = target.admin();
        try {
            usersService.setRole(admin.getEncryptedSessionKey(), username, role);
        } catch (Exception e) {
            return refused(e, request, flash, "Philter keeps at least one active administrator. "
                    + "Make another user an administrator first.");
        }
        provisioner.applyPhilterRole(username, role);
        expireSessions(username);
        flash.addFlashAttribute("success", "\"" + username + "\" is now " + (PHILTER_ADMIN.equals(role)
                ? "an administrator." : "a user."));
        return REDIRECT;
    }

    @PostMapping("/{username}/email")
    public String setEmail(@PathVariable final String username,
                           @RequestParam("email") final String email,
                           final Authentication authentication,
                           final HttpServletRequest request,
                           final RedirectAttributes flash) {
        final Target target = guard(authentication, username, false, false, request, flash);
        if (target.redirect() != null) {
            return target.redirect();
        }
        final PhilterSessionAuthentication admin = target.admin();
        try {
            usersService.setEmail(admin.getEncryptedSessionKey(), username, email == null ? "" : email.trim());
        } catch (Exception e) {
            return refused(e, request, flash, "The email address was not changed.");
        }
        flash.addFlashAttribute("success", "Email address of \"" + username + "\" updated.");
        return REDIRECT;
    }

    @PostMapping("/{username}/password")
    public String resetPassword(@PathVariable final String username,
                                @RequestParam("password") final String password,
                                final Authentication authentication,
                                final HttpServletRequest request,
                                final RedirectAttributes flash) {
        final Target target = guard(authentication, username, true, false, request, flash);
        if (target.redirect() != null) {
            return target.redirect();
        }
        final PhilterSessionAuthentication admin = target.admin();
        try {
            usersService.resetPassword(admin.getEncryptedSessionKey(), username, password);
        } catch (Exception e) {
            return refused(e, request, flash, "The password was not reset. Try again.");
        }
        expireSessions(username);
        flash.addFlashAttribute("success", "Password of \"" + username + "\" reset. They choose a new one at "
                + "next sign-in, and their current sessions have ended.");
        return REDIRECT;
    }

    @PostMapping("/{username}/deactivate")
    public String deactivate(@PathVariable final String username, final Authentication authentication,
                             final HttpServletRequest request, final RedirectAttributes flash) {
        final Target target = guard(authentication, username, true, true, request, flash);
        if (target.redirect() != null) {
            return target.redirect();
        }
        final PhilterSessionAuthentication admin = target.admin();
        try {
            usersService.deactivate(admin.getEncryptedSessionKey(), username);
        } catch (Exception e) {
            return refused(e, request, flash, "Philter did not deactivate \"" + username + "\": they are "
                    + "already deactivated or the last active administrator.");
        }
        expireSessions(username);
        flash.addFlashAttribute("success", "\"" + username + "\" deactivated.");
        return REDIRECT;
    }

    @PostMapping("/{username}/reactivate")
    public String reactivate(@PathVariable final String username, final Authentication authentication,
                             final HttpServletRequest request, final RedirectAttributes flash) {
        final Target target = guard(authentication, username, true, true, request, flash);
        if (target.redirect() != null) {
            return target.redirect();
        }
        final PhilterSessionAuthentication admin = target.admin();
        try {
            usersService.reactivate(admin.getEncryptedSessionKey(), username);
        } catch (Exception e) {
            return refused(e, request, flash, "\"" + username + "\" is already active.");
        }
        flash.addFlashAttribute("success", "\"" + username + "\" reactivated.");
        return REDIRECT;
    }

    @PostMapping("/{username}/unlock-sign-in")
    public String unlockSignIn(@PathVariable final String username, final Authentication authentication,
                               final HttpServletRequest request, final RedirectAttributes flash) {
        final Target target = guard(authentication, username, false, false, request, flash);
        if (target.redirect() != null) {
            return target.redirect();
        }
        final PhilterSessionAuthentication admin = target.admin();
        try {
            usersService.unlockSignIn(admin.getEncryptedSessionKey(), username);
        } catch (Exception e) {
            return refused(e, request, flash, "Sign-in for \"" + username + "\" is not locked.");
        }
        flash.addFlashAttribute("success", "Sign-in for \"" + username + "\" unlocked.");
        return REDIRECT;
    }

    @PostMapping("/{username}/unlock-mfa")
    public String unlockMfa(@PathVariable final String username, final Authentication authentication,
                            final HttpServletRequest request, final RedirectAttributes flash) {
        final Target target = guard(authentication, username, false, false, request, flash);
        if (target.redirect() != null) {
            return target.redirect();
        }
        final PhilterSessionAuthentication admin = target.admin();
        try {
            usersService.unlockMfa(admin.getEncryptedSessionKey(), username);
        } catch (Exception e) {
            return refused(e, request, flash, "Two-factor authentication for \"" + username + "\" is not locked.");
        }
        flash.addFlashAttribute("success", "Two-factor authentication for \"" + username + "\" unlocked.");
        return REDIRECT;
    }

    @PostMapping("/{username}/remove-mfa")
    public String removeMfa(@PathVariable final String username, final Authentication authentication,
                            final HttpServletRequest request, final RedirectAttributes flash) {
        final Target target = guard(authentication, username, true, false, request, flash);
        if (target.redirect() != null) {
            return target.redirect();
        }
        final PhilterSessionAuthentication admin = target.admin();
        try {
            usersService.removeMfa(admin.getEncryptedSessionKey(), username);
        } catch (Exception e) {
            return refused(e, request, flash, "\"" + username + "\" has no two-factor authentication to remove.");
        }
        flash.addFlashAttribute("success", "Two-factor authentication removed from \"" + username
                + "\". They set it up again at next sign-in if it is required.");
        return REDIRECT;
    }

    @PostMapping("/{username}/sign-out")
    public String signOut(@PathVariable final String username, final Authentication authentication,
                          final HttpServletRequest request, final RedirectAttributes flash) {
        final Target target = guard(authentication, username, true, false, request, flash);
        if (target.redirect() != null) {
            return target.redirect();
        }
        final PhilterSessionAuthentication admin = target.admin();
        final int revoked;
        try {
            revoked = usersService.revokeSessions(admin.getEncryptedSessionKey(), username);
        } catch (Exception e) {
            return refused(e, request, flash, "\"" + username + "\" was not signed out.");
        }
        expireSessions(username);
        flash.addFlashAttribute("success", "\"" + username + "\" signed out everywhere.");
        return REDIRECT;
    }

    /**
     * Philter Workspace's part: User or Auditor, and groups. Creates the person's Philter Workspace record if they have none.
     * A Philter administrator is always a Philter Workspace administrator, so only their groups change.
     */
    @PostMapping("/{username}/workspace")
    public String setWorkspaceAccess(@PathVariable final String username,
                                   @RequestParam(value = "auditor", defaultValue = "false") final boolean auditor,
                                   @RequestParam(value = "groupIds", required = false) final List<String> groupIds,
                                   final Authentication authentication,
                                   final HttpServletRequest request,
                                   final RedirectAttributes flash) {
        final Target target = guard(authentication, username, false, false, request, flash);
        if (target.redirect() != null) {
            return target.redirect();
        }
        final User philter = target.user();
        final Set<String> wanted = groupIds == null ? Set.of() : new HashSet<>(groupIds);
        final ai.philterd.workspace.model.User existing = userRepository.findByEmail(username).orElse(null);
        final List<Group> emptied = new ArrayList<>();
        if (existing != null) {
            for (final Group group : groupRepository.findAll()) {
                if (!wanted.contains(group.getId()) && group.getUserIds() != null
                        && group.getUserIds().equals(Set.of(existing.getId()))) {
                    emptied.add(group);
                }
            }
        }
        if (!emptied.isEmpty()) {
            flash.addFlashAttribute("error", "\"" + username + "\" is the only member of \"" + emptied.get(0).getName()
                    + "\", and a group needs at least one member. Add someone else to it first.");
            return REDIRECT;
        }
        final Set<String> previousRoles = existing == null || existing.getRoles() == null
                ? Set.of() : Set.copyOf(existing.getRoles());
        final ai.philterd.workspace.model.User record = provisioner.setWorkspaceRole(username, philter.getRole(), auditor);
        for (final Group group : groupRepository.findAll()) {
            final Set<String> members = group.getUserIds() == null ? new HashSet<>() : new HashSet<>(group.getUserIds());
            final boolean member = members.contains(record.getId());
            if (wanted.contains(group.getId()) && !member) {
                members.add(record.getId());
                group.setUserIds(members);
                groupRepository.save(group);
            } else if (!wanted.contains(group.getId()) && member) {
                members.remove(record.getId());
                group.setUserIds(members);
                final Set<String> leaders = new HashSet<>(group.getLeaderUserIds());
                leaders.remove(record.getId());
                group.setLeaderUserIds(leaders);
                groupRepository.save(group);
            }
        }
        if (!previousRoles.equals(record.getRoles())) {
            expireSessions(username);
        }
        audit("USER_UPDATE", record.getId(), username, Map.of("role", record.getRoles().iterator().next(),
                "groupCount", wanted.size()));
        flash.addFlashAttribute("success", "Philter Workspace access of \"" + username + "\" updated.");
        return REDIRECT;
    }

    /**
     * Deletes a Philter Workspace record that has no Philter user, such as a local account from before the switch
     * to Philter sign-in, after Philter confirms there is no such user. Removes it from its groups, and is
     * refused if that would leave a group with no members.
     */
    @PostMapping("/records/{recordId}/delete")
    public String deleteRecord(@PathVariable final String recordId,
                               final Authentication authentication,
                               final HttpServletRequest request,
                               final RedirectAttributes flash) {
        final PhilterSessionAuthentication admin = admin(authentication);
        final ai.philterd.workspace.model.User record = userRepository.findById(recordId).orElse(null);
        if (admin == null || record == null || record.getEmail() == null) {
            flash.addFlashAttribute("error", "There is no such Philter Workspace record.");
            return RECORDS_REDIRECT;
        }
        if (record.getEmail().equals(admin.getName())) {
            flash.addFlashAttribute("error", "You cannot delete your own record.");
            return RECORDS_REDIRECT;
        }
        try {
            usersService.getUser(admin.getEncryptedSessionKey(), record.getEmail());
            flash.addFlashAttribute("error", "\"" + record.getEmail() + "\" is a Philter user. Deactivate them instead.");
            return RECORDS_REDIRECT;
        } catch (Exception e) {
            if (!(e instanceof PhilterError error && error.getStatusCode() == 404)) {
                return refused(e, request, flash, "The record was not deleted.");
            }
        }
        final List<Group> groups = groupRepository.findAll();
        for (final Group group : groups) {
            if (group.getUserIds() != null && group.getUserIds().equals(Set.of(record.getId()))) {
                flash.addFlashAttribute("error", "\"" + record.getEmail() + "\" is the only member of \""
                        + group.getName() + "\". Add someone else to it first.");
                return RECORDS_REDIRECT;
            }
        }
        for (final Group group : groups) {
            if (group.getUserIds() != null && group.getUserIds().contains(record.getId())) {
                final Set<String> members = new HashSet<>(group.getUserIds());
                members.remove(record.getId());
                group.setUserIds(members);
                final Set<String> leaders = new HashSet<>(group.getLeaderUserIds());
                leaders.remove(record.getId());
                group.setLeaderUserIds(leaders);
                groupRepository.save(group);
            }
        }
        userRepository.deleteById(record.getId());
        expireSessions(record.getEmail());
        audit("USER_DELETE", record.getId(), record.getEmail(), Map.of());
        flash.addFlashAttribute("success", "Philter Workspace record \"" + record.getEmail() + "\" deleted.");
        return RECORDS_REDIRECT;
    }

    static final String API_ONLY = "This is an API-only account, with no password. In Philter Workspace it can only be "
            + "deactivated or reactivated; manage it with Philter's Users API.";

    private static PhilterSessionAuthentication admin(final Authentication authentication) {
        return authentication instanceof PhilterSessionAuthentication philter ? philter : null;
    }

    /** The administrator and the Philter user an action is on, or where to send the browser instead. */
    private record Target(PhilterSessionAuthentication admin, User user, String redirect) {
        static Target refuse(final String redirect) {
            return new Target(null, null, redirect);
        }
    }

    /**
     * Checks that the signed-in administrator may act on {@code username}: not on themselves when
     * {@code notSelf}, and on an API-only account only when {@code allowApiOnly}. The user Philter returns
     * must have exactly that username, since {@code me} in a Users API path means the caller.
     */
    private Target guard(final Authentication authentication, final String username, final boolean notSelf,
                         final boolean allowApiOnly, final HttpServletRequest request,
                         final RedirectAttributes flash) {
        final PhilterSessionAuthentication admin = admin(authentication);
        if (admin == null) {
            return Target.refuse(REDIRECT);
        }
        if (username == null || username.isBlank() || RESERVED_ME.equalsIgnoreCase(username)) {
            flash.addFlashAttribute("error", "There is no such user in Philter.");
            return Target.refuse(REDIRECT);
        }
        if (notSelf && username.equals(admin.getName())) {
            flash.addFlashAttribute("error", "You cannot change this on your own account here. "
                    + "Use Settings for your password, or ask another administrator.");
            return Target.refuse(REDIRECT);
        }
        final User user;
        try {
            user = usersService.getUser(admin.getEncryptedSessionKey(), username);
        } catch (Exception e) {
            return Target.refuse(refused(e, request, flash, "\"" + username + "\" was not changed."));
        }
        if (user == null || !username.equals(user.getUsername())) {
            flash.addFlashAttribute("error", "There is no such user in Philter.");
            return Target.refuse(REDIRECT);
        }
        if (!allowApiOnly && !user.isPasswordSet()) {
            flash.addFlashAttribute("error", API_ONLY);
            return Target.refuse(REDIRECT);
        }
        return new Target(admin, user, null);
    }

    /** Shows why Philter refused, in Philter Workspace's words; Philter's own text is not shown or logged. */
    private String refused(final Exception e, final HttpServletRequest request, final RedirectAttributes flash,
                           final String conflict) {
        if (e instanceof UnauthorizedException) {
            return endSession(request);
        }
        flash.addFlashAttribute("error", message(e, conflict));
        return REDIRECT;
    }

    private static String message(final Exception e, final String conflict) {
        if (e instanceof PhilterError error) {
            if ("admin_change_in_progress".equals(error.getReason())) {
                return "Another change to administrators is in progress. Try again in a moment.";
            }
            switch (error.getStatusCode()) {
                case 400:
                    return "Philter refused the values. A username cannot contain / \\ ; % or control "
                            + "characters, an email address must be valid, and a password must be at least 16 "
                            + "characters and at most 72 bytes.";
                case 403:
                    return "Philter refused: your Philter account is not an administrator.";
                case 404:
                    return "There is no such user in Philter.";
                case 409:
                    return conflict;
                default:
                    break;
            }
        }
        log.warn("A Philter Users API call failed: {}", PhilterClients.describe(e));
        return "Philter could not complete the change. Try again, or check that Philter is reachable.";
    }

    /** Philter rejected the administrator's own key: end their session, as the session check would. */
    private static String endSession(final HttpServletRequest request) {
        return PhilterSessions.end(request);
    }

    private void expireSessions(final String username) {
        sessionRegistry.getAllSessions(username, false).forEach(SessionInformation::expireNow);
    }

    private void audit(final String action, final String recordId, final String username,
                       final Map<String, Object> details) {
        final Map<String, Object> all = new HashMap<>(details);
        all.put("username", username);
        all.put("method", "philter");
        auditLogService.log(action, "User", recordId, all);
    }
}
