/*
 * Copyright 2026 Philterd
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

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.DocumentComment;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentCommentRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/v1")
public class CommentController {

    private static final int MAX_COMMENT_LENGTH = 4000;

    private final DocumentRepository documentRepository;
    private final BatchRepository batchRepository;
    private final DocumentCommentRepository commentRepository;
    private final UserRepository userRepository;
    private final UserGroupsService userGroupsService;
    private final AuditLogService auditLogService;

    public CommentController(final DocumentRepository documentRepository,
                             final BatchRepository batchRepository,
                             final DocumentCommentRepository commentRepository,
                             final UserRepository userRepository,
                             final UserGroupsService userGroupsService,
                             final AuditLogService auditLogService) {
        this.documentRepository = documentRepository;
        this.batchRepository = batchRepository;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.userGroupsService = userGroupsService;
        this.auditLogService = auditLogService;
    }

    public record CommentRequest(String text) {}

    @GetMapping("/documents/{id}/comments")
    public List<Map<String, Object>> list(@PathVariable final String id, final Authentication authentication) {
        final Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + id));
        requireDocumentAccess(authentication, document);

        return commentRepository.findByDocumentIdOrderByTimestampDesc(id).stream()
                .map(CommentController::toJson)
                .toList();
    }

    @PostMapping("/documents/{id}/comments")
    public Map<String, Object> add(@PathVariable final String id,
                                   @RequestBody final CommentRequest body,
                                   final Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Authentication is required.");
        }
        if (body == null || body.text() == null || body.text().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Comment text is required.");
        }
        final String trimmed = body.text().trim();
        if (trimmed.length() > MAX_COMMENT_LENGTH) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Comment is too long (max " + MAX_COMMENT_LENGTH + " characters).");
        }

        final Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + id));
        requireDocumentAccess(authentication, document);

        final String userEmail = authentication.getName();
        final String userId = userRepository.findByEmail(userEmail).map(User::getId).orElse(null);

        final DocumentComment comment = new DocumentComment();
        comment.setId(UUID.randomUUID().toString());
        comment.setDocumentId(id);
        comment.setUserEmail(userEmail);
        comment.setUserId(userId);
        comment.setText(trimmed);
        comment.setTimestamp(Instant.now());
        commentRepository.save(comment);

        auditLogService.log("DOCUMENT_COMMENT_ADD", "Document", id,
                Map.of("commentId", comment.getId(),
                        "length", trimmed.length()));

        return toJson(comment);
    }

    // ----- helpers -----

    private static Map<String, Object> toJson(final DocumentComment c) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("userEmail", c.getUserEmail() == null ? "" : c.getUserEmail());
        m.put("text", c.getText() == null ? "" : c.getText());
        m.put("timestamp", c.getTimestamp() == null ? null : c.getTimestamp().toString());
        return m;
    }

    private void requireDocumentAccess(final Authentication auth, final Document document) {
        if (isAdmin(auth)) return;
        final Batch batch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        if (batch == null || batch.getGroupId() == null) {
            throw new ResponseStatusException(NOT_FOUND, "Document not found: " + document.getId());
        }
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                auth == null ? null : auth.getName());
        if (!myGroupIds.contains(batch.getGroupId())) {
            throw new ResponseStatusException(NOT_FOUND, "Document not found: " + document.getId());
        }
    }

    private static boolean isAdmin(final Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }
}
