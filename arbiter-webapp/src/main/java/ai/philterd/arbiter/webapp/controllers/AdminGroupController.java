/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.Group;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.GroupRepository;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.AuditLogService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Controller
@RequestMapping("/admin/groups")
public class AdminGroupController {

    private final GroupRepository groupRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public AdminGroupController(final GroupRepository groupRepository,
                                final UserRepository userRepository,
                                final AuditLogService auditLogService) {
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public String list(final Model model) {
        final List<User> allUsers = userRepository.findAll(PageRequest.of(0, 500, Sort.by("email"))).getContent();

        final Map<String, String> emailsById = new LinkedHashMap<>();
        for (User u : allUsers) {
            emailsById.put(u.getId(), u.getEmail());
        }

        final List<Group> groups = groupRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();

        final List<Map<String, Object>> rows = new ArrayList<>();
        for (Group g : groups) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", g.getId());
            row.put("name", g.getName());
            row.put("userIds", g.getUserIds() == null ? Set.of() : g.getUserIds());
            row.put("leaderUserIds",
                    g.getLeaderUserIds() == null ? Set.of() : g.getLeaderUserIds());
            final List<String> memberNames = new ArrayList<>();
            if (g.getUserIds() != null) {
                for (String uid : g.getUserIds()) {
                    final String email = emailsById.get(uid);
                    if (email != null) memberNames.add(email);
                }
            }
            memberNames.sort(String::compareToIgnoreCase);
            row.put("memberNames", memberNames);
            // Leader emails surfaced separately so the listing can show "Led by …" beside
            // the membership list.
            final List<String> leaderNames = new ArrayList<>();
            if (g.getLeaderUserIds() != null) {
                for (String uid : g.getLeaderUserIds()) {
                    final String email = emailsById.get(uid);
                    if (email != null) leaderNames.add(email);
                }
            }
            leaderNames.sort(String::compareToIgnoreCase);
            row.put("leaderNames", leaderNames);
            rows.add(row);
        }

        model.addAttribute("groups", rows);
        model.addAttribute("allUsers", allUsers);
        return "admin-groups";
    }

    @PostMapping
    public String create(@RequestParam("name") final String name,
                         @RequestParam(value = "userIds", required = false) final List<String> userIds,
                         @RequestParam(value = "leaderIds", required = false) final List<String> leaderIds,
                         final RedirectAttributes redirectAttributes) {
        final String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Group name is required.");
            return "redirect:/admin/groups";
        }
        if (groupRepository.findByName(trimmed).isPresent()) {
            redirectAttributes.addFlashAttribute("error", "Group \"" + trimmed + "\" already exists.");
            return "redirect:/admin/groups";
        }
        final Set<String> validUserIds = filterExistingUserIds(userIds);
        if (validUserIds.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "A group must contain at least one user.");
            return "redirect:/admin/groups";
        }
        final Set<String> validLeaderIds = filterLeadersToMembers(leaderIds, validUserIds);

        final Group group = new Group();
        group.setCreatedAt(LocalDateTime.now());
        group.setId(UUID.randomUUID().toString());
        group.setName(trimmed);
        group.setUserIds(validUserIds);
        group.setLeaderUserIds(validLeaderIds);
        groupRepository.save(group);
        auditLogService.log("GROUP_CREATE", "Group", group.getId(),
                Map.of("name", trimmed,
                        "memberCount", validUserIds.size(),
                        "leaderCount", validLeaderIds.size()));
        redirectAttributes.addFlashAttribute("success", "Group \"" + trimmed + "\" created.");
        return "redirect:/admin/groups";
    }

    @PostMapping("/{groupId}/edit")
    public String edit(@PathVariable final String groupId,
                       @RequestParam("name") final String name,
                       @RequestParam(value = "userIds", required = false) final List<String> userIds,
                       @RequestParam(value = "leaderIds", required = false) final List<String> leaderIds,
                       final RedirectAttributes redirectAttributes) {
        final Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) {
            redirectAttributes.addFlashAttribute("error", "Group not found.");
            return "redirect:/admin/groups";
        }
        final String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Group name is required.");
            return "redirect:/admin/groups";
        }
        final boolean nameTaken = groupRepository.findByName(trimmed)
                .filter(other -> !other.getId().equals(groupId))
                .isPresent();
        if (nameTaken) {
            redirectAttributes.addFlashAttribute("error", "Group name \"" + trimmed + "\" is already used.");
            return "redirect:/admin/groups";
        }
        final Set<String> validUserIds = filterExistingUserIds(userIds);
        if (validUserIds.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "A group must contain at least one user.");
            return "redirect:/admin/groups";
        }
        final Set<String> validLeaderIds = filterLeadersToMembers(leaderIds, validUserIds);

        final String previousName = group.getName();
        final int previousCount = group.getUserIds() == null ? 0 : group.getUserIds().size();
        final int previousLeaderCount = group.getLeaderUserIds() == null
                ? 0 : group.getLeaderUserIds().size();
        group.setName(trimmed);
        group.setUserIds(validUserIds);
        group.setLeaderUserIds(validLeaderIds);
        groupRepository.save(group);
        auditLogService.log("GROUP_UPDATE", "Group", group.getId(),
                Map.of("previousName", previousName == null ? "" : previousName,
                        "name", trimmed,
                        "previousMemberCount", previousCount,
                        "memberCount", validUserIds.size(),
                        "previousLeaderCount", previousLeaderCount,
                        "leaderCount", validLeaderIds.size()));
        redirectAttributes.addFlashAttribute("success", "Group \"" + trimmed + "\" updated.");
        return "redirect:/admin/groups";
    }

    /**
     * Enforces the invariant that team leads must also be members. Filters the requested
     * leader-id list down to entries that are both (a) in {@code memberIds} and (b)
     * actually existing users. Submitting a leader id that isn't a member is silently
     * dropped — the form sources the checkboxes from the same membership pool, so this
     * only fires for tampered POSTs.
     */
    private Set<String> filterLeadersToMembers(final List<String> requested,
                                               final Set<String> memberIds) {
        final Set<String> result = new HashSet<>();
        if (requested == null) return result;
        for (String id : requested) {
            if (id != null && !id.isBlank() && memberIds.contains(id)
                    && userRepository.existsById(id)) {
                result.add(id);
            }
        }
        return result;
    }

    @PostMapping("/{groupId}/delete")
    public String delete(@PathVariable final String groupId, final RedirectAttributes redirectAttributes) {
        final Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) {
            redirectAttributes.addFlashAttribute("error", "Group not found.");
            return "redirect:/admin/groups";
        }
        groupRepository.deleteById(groupId);
        auditLogService.log("GROUP_DELETE", "Group", groupId,
                Map.of("name", group.getName() == null ? "" : group.getName()));
        redirectAttributes.addFlashAttribute("success", "Group \"" + group.getName() + "\" deleted.");
        return "redirect:/admin/groups";
    }

    private Set<String> filterExistingUserIds(final List<String> requested) {
        final Set<String> result = new HashSet<>();
        if (requested == null) return result;
        for (String id : requested) {
            if (id != null && !id.isBlank() && userRepository.existsById(id)) {
                result.add(id);
            }
        }
        return result;
    }
}
