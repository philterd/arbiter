/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.store.sqlite;

import ai.philterd.workspace.model.OpenSearchDataSource;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.Group;
import ai.philterd.workspace.model.InboxMessage;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.model.User;
import ai.philterd.workspace.repository.OpenSearchDataSourceRepository;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.GroupRepository;
import ai.philterd.workspace.repository.InboxMessageRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteRepositoriesTest {

    @TempDir
    Path dir;

    private SqliteTestStore db;

    @BeforeEach
    void open() throws Exception {
        db = new SqliteTestStore(dir);
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    @Test
    void everyRepositoryAndDerivedQueryIsSupported() throws Exception {
        final List<Class<?>> repos = SqliteTestStore.allRepositoryInterfaces();
        assertEquals(27, repos.size());
        for (Class<?> repo : repos) {
            assertNotNull(db.repository(repo), repo.getSimpleName());
        }
    }

    @Test
    void saveAssignsIdAndReplacesWholeDocument() {
        final OpenSearchDataSourceRepository sources = db.repository(OpenSearchDataSourceRepository.class);
        final OpenSearchDataSource source = new OpenSearchDataSource();
        source.setName("claims");
        source.setQuery("{}");
        sources.save(source);
        assertTrue(source.getId().matches("[0-9a-f]{24}"));

        source.setQuery("{\"a\":1}");
        sources.save(source);
        assertEquals(1, sources.count());
        assertEquals("{\"a\":1}", sources.findById(source.getId()).orElseThrow().getQuery());
    }

    @Test
    void versionedSaveRefusesAStaleCopy() {
        final UserRepository users = db.repository(UserRepository.class);
        final User u = new User();
        u.setEmail("a@example.com");
        users.save(u);
        assertEquals(0L, u.getVersion());

        final User stale = users.findById(u.getId()).orElseThrow();
        u.setEmail("b@example.com");
        users.save(u);
        assertEquals(1L, u.getVersion());

        stale.setEmail("c@example.com");
        assertThrows(OptimisticLockingFailureException.class, () -> users.save(stale));
        assertEquals(0L, stale.getVersion());
        assertEquals("b@example.com", users.findById(u.getId()).orElseThrow().getEmail());
    }

    @Test
    void uniqueIndexRefusesDuplicates() {
        final BatchRepository batches = db.repository(BatchRepository.class);
        final Batch a = new Batch();
        a.setName("same");
        batches.save(a);
        final Batch b = new Batch();
        b.setName("same");
        assertThrows(DuplicateKeyException.class, () -> batches.save(b));
    }

    @Test
    void negationAlsoMatchesMissingFieldsLikeMongo() {
        final InboxMessageRepository inbox = db.repository(InboxMessageRepository.class);
        final InboxMessage unread = message("u1", false, false);
        final InboxMessage archived = message("u1", false, true);
        inbox.saveAll(List.of(unread, archived, message("u2", false, false)));
        // A message saved before "archived" existed has no such field.
        db.jdbc.update("UPDATE inbox_messages SET doc = json_remove(doc, '$.archived') WHERE id = ?", unread.getId());

        assertEquals(List.of(unread.getId()),
                inbox.findNonArchivedByUserId("u1").stream().map(InboxMessage::getId).toList());
        assertEquals(1, inbox.countUnreadNonArchivedByUserId("u1"));
    }

    @Test
    void pagedQueryFiltersSortsAndCounts() {
        final DocumentRepository docs = db.repository(DocumentRepository.class);
        for (int i = 0; i < 5; i++) {
            docs.save(document("b1", i % 2 == 0 ? "PENDING" : "FINALIZED", "Report-" + i + ".txt",
                    LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(i)));
        }
        final Document noStatus = document("b1", null, "report-x.TXT", LocalDateTime.of(2026, 2, 1, 0, 0));
        docs.save(noStatus);
        docs.save(document("b2", "PENDING", "report-9.txt", LocalDateTime.of(2026, 1, 1, 0, 0)));

        final Page<Document> page = docs.findByBatchIdAndStatusNotInAndFilenameContainingIgnoreCase("b1",
                List.of("FINALIZED"), "REPORT", PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));

        // PENDING report-0, -2, -4 plus the document with no status, as MongoDB's $nin matches it.
        assertEquals(4, page.getTotalElements());
        assertEquals(List.of(noStatus.getId(), page.getContent().get(1).getId()),
                page.getContent().stream().map(Document::getId).toList());
        assertEquals("Report-4.txt", page.getContent().get(1).getFilename());
    }

    @Test
    void timestampComparisonsFollowTimeOrder() {
        final DocumentRepository docs = db.repository(DocumentRepository.class);
        final LocalDateTime t = LocalDateTime.of(2026, 3, 1, 12, 0, 0);
        docs.save(document("b", "PENDING", "a", t));
        docs.save(document("b", "PENDING", "b", t.plusNanos(500_000_000)));
        docs.save(document("b", "PENDING", "c", t.plusSeconds(1)));

        assertEquals(2, docs.countByCreatedAtAfter(t));
        assertEquals(1, docs.countByCreatedAtAfter(t.plusNanos(600_000_000)));
    }

    @Test
    void collectionFieldsMatchAnyElement() {
        final GroupRepository groups = db.repository(GroupRepository.class);
        final Group g = new Group();
        g.setName("g");
        g.setUserIds(Set.of("u1", "u2"));
        groups.save(g);
        assertEquals(1, groups.findByUserIdsContaining("u2").size());
        assertEquals(0, groups.findByUserIdsContaining("u3").size());

        final UserRepository users = db.repository(UserRepository.class);
        final User admin = new User();
        admin.setEmail("admin@example.com");
        admin.setRoles(Set.of("ADMIN", "USER"));
        users.save(admin);
        assertEquals(1, users.countByRolesContaining("ADMIN"));
        assertEquals(0, users.countByRolesContaining("AUDITOR"));
    }

    @Test
    void ignoreCaseFoldsNonAsciiLetters() {
        final OpenSearchDataSourceRepository sources = db.repository(OpenSearchDataSourceRepository.class);
        final OpenSearchDataSource source = new OpenSearchDataSource();
        source.setName("Müller");
        sources.save(source);
        assertTrue(sources.findFirstByNameIgnoreCase("MÜLLER").isPresent());
        assertFalse(sources.findByName("MÜLLER").isPresent());
    }

    @Test
    void localCredentialsAreRemovedFromStoredUserRowsOnce() {
        final UserRepository users = db.repository(UserRepository.class);
        final ai.philterd.workspace.repository.GeneralSettingsRepository settings =
                db.repository(ai.philterd.workspace.repository.GeneralSettingsRepository.class);
        final User u = new User();
        u.setEmail("reviewer@example.com");
        u.setRoles(java.util.Set.of("USER"));
        users.save(u);
        // A row as local sign-in left it, with credentials the model no longer has.
        final String old = db.rawDoc("users", u.getId()).replaceFirst("\\{",
                "{\"passwordHash\":\"{bcrypt}hash-marker\",\"totpSecret\":\"totp-marker\",\"apiKey\":\"key-marker\",");
        db.jdbc.update("UPDATE \"users\" SET doc = ? WHERE id = ?", old, u.getId());
        assertTrue(db.rawDoc("users", u.getId()).contains("hash-marker"));

        new ai.philterd.workspace.service.LocalCredentialsCleanup(users, settings).run(null);

        final String raw = db.rawDoc("users", u.getId());
        assertFalse(raw.contains("hash-marker") || raw.contains("totp-marker") || raw.contains("key-marker"), raw);
        assertEquals("reviewer@example.com", users.findById(u.getId()).orElseThrow().getEmail());
        assertTrue(settings.findById(ai.philterd.workspace.model.GeneralSettings.SINGLETON_ID).orElseThrow()
                .isLocalCredentialsRemoved());

        // Runs once: a later start leaves rows alone.
        final Long version = users.findById(u.getId()).orElseThrow().getVersion();
        new ai.philterd.workspace.service.LocalCredentialsCleanup(users, settings).run(null);
        assertEquals(version, users.findById(u.getId()).orElseThrow().getVersion());
    }

    @Test
    void piiIsEncryptedOnDiskAndPlainInMemory() {
        final DocumentRepository docs = db.repository(DocumentRepository.class);
        final Document d = document("b", "PENDING", "f.txt", LocalDateTime.now());
        d.setOriginalText("SSN 123-45-6789");
        docs.save(d);

        assertEquals("SSN 123-45-6789", d.getOriginalText());
        final String raw = db.rawDoc("documents", d.getId());
        assertFalse(raw.contains("123-45-6789"));
        assertTrue(raw.contains("enc:v1:"));
        assertEquals("SSN 123-45-6789", docs.findById(d.getId()).orElseThrow().getOriginalText());

        final SpanRepository spans = db.repository(SpanRepository.class);
        final Span s = new Span();
        s.setDocumentId(d.getId());
        s.setText("123-45-6789");
        spans.save(s);
        assertFalse(db.rawDoc("spans", s.getId()).contains("123-45-6789"));
        assertEquals("123-45-6789", spans.findByDocumentId(d.getId()).get(0).getText());
    }

    private static InboxMessage message(final String userId, final boolean read, final boolean archived) {
        final InboxMessage m = new InboxMessage();
        m.setUserId(userId);
        m.setRead(read);
        m.setArchived(archived);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    private static Document document(final String batchId, final String status, final String filename,
                                     final LocalDateTime createdAt) {
        final Document d = new Document();
        d.setBatchId(batchId);
        d.setStatus(status);
        d.setFilename(filename);
        d.setCreatedAt(createdAt);
        return d;
    }
}
