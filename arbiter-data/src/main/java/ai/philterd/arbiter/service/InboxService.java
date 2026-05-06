/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.InboxMessage;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.InboxMessageRepository;
import ai.philterd.arbiter.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class InboxService {

    private final InboxMessageRepository repository;
    private final UserRepository userRepository;

    public InboxService(final InboxMessageRepository repository, final UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    public InboxMessage send(final String userId, final String message) {
        return send(userId, message, false);
    }

    /**
     * Send a system-generated message that may contain HTML. Callers must control the
     * content fully — never pass user-supplied strings here.
     */
    public InboxMessage sendHtml(final String userId, final String message) {
        return send(userId, message, true);
    }

    private InboxMessage send(final String userId, final String message, final boolean html) {
        if (userId == null || userId.isBlank() || message == null) return null;
        final InboxMessage m = new InboxMessage();
        m.setId(UUID.randomUUID().toString());
        m.setUserId(userId);
        m.setMessage(message);
        m.setCreatedAt(LocalDateTime.now());
        m.setRead(false);
        m.setHtml(html);
        return repository.save(m);
    }

    public List<InboxMessage> listForEmail(final String email, final boolean showArchived) {
        final String userId = userIdForEmail(email);
        if (userId == null) return List.of();
        return showArchived
                ? repository.findByUserIdOrderByCreatedAtDesc(userId)
                : repository.findNonArchivedByUserId(userId);
    }

    public long unreadCountForEmail(final String email) {
        final String userId = userIdForEmail(email);
        if (userId == null) return 0L;
        return repository.countUnreadNonArchivedByUserId(userId);
    }

    /**
     * Mark a message read, but only if it belongs to the supplied user. Returns true on success,
     * false if the message doesn't exist or belongs to someone else.
     */
    public boolean markRead(final String messageId, final String email) {
        final String userId = userIdForEmail(email);
        if (userId == null || messageId == null) return false;
        final InboxMessage m = repository.findById(messageId).orElse(null);
        if (m == null || !userId.equals(m.getUserId())) return false;
        if (!m.isRead()) {
            m.setRead(true);
            repository.save(m);
        }
        return true;
    }

    /**
     * Archive a message, marking it read at the same time. Returns true on success,
     * false if the message doesn't exist or belongs to someone else.
     */
    public boolean archive(final String messageId, final String email) {
        final String userId = userIdForEmail(email);
        if (userId == null || messageId == null) return false;
        final InboxMessage m = repository.findById(messageId).orElse(null);
        if (m == null || !userId.equals(m.getUserId())) return false;
        if (!m.isArchived()) {
            m.setArchived(true);
            m.setArchivedAt(LocalDateTime.now());
            m.setRead(true);
            repository.save(m);
        }
        return true;
    }

    private String userIdForEmail(final String email) {
        if (email == null || email.isBlank()) return null;
        final User user = userRepository.findByEmail(email).orElse(null);
        return user == null ? null : user.getId();
    }
}
