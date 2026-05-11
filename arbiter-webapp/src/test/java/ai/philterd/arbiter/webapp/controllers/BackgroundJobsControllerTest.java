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

import ai.philterd.arbiter.model.BackgroundJob;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.repository.BackgroundJobRepository;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.service.UserGroupsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackgroundJobsControllerTest {

    private BackgroundJobRepository jobRepository;
    private BatchRepository batchRepository;
    private UserGroupsService userGroupsService;
    private BackgroundJobsController controller;

    @BeforeEach
    void setUp() {
        jobRepository = mock(BackgroundJobRepository.class);
        batchRepository = mock(BatchRepository.class);
        userGroupsService = mock(UserGroupsService.class);
        controller = new BackgroundJobsController(jobRepository, batchRepository, userGroupsService,
                mock(ai.philterd.arbiter.service.BatchAccessService.class),
                mock(ai.philterd.arbiter.service.DataImportLogService.class));
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken("admin@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication user(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static BackgroundJob job(final String id, final String type, final String batchId) {
        final BackgroundJob j = new BackgroundJob();
        j.setId(id);
        j.setType(type);
        j.setBatchId(batchId);
        j.setStatus(BackgroundJob.STATUS_RUNNING);
        j.setCreatedAt(Instant.now());
        return j;
    }

    private static Batch batch(final String id, final String groupId) {
        final Batch b = new Batch();
        b.setId(id);
        b.setGroupId(groupId);
        return b;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sectionsFromModel(final Model model) {
        return (List<Map<String, Object>>) model.asMap().get("sections");
    }

    private static Map<String, Object> sectionByCategory(final List<Map<String, Object>> sections,
                                                         final String category) {
        return sections.stream()
                .filter(s -> category.equals(s.get("category")))
                .findFirst()
                .orElseThrow();
    }

    // ---------- visibility ----------

    @Test
    void adminSeesEveryJob() {
        when(jobRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                job("j1", BackgroundJob.TYPE_OPENSEARCH_INGEST, "b1"),
                job("j2", BackgroundJob.TYPE_ELASTICSEARCH_INGEST, "b2")));

        final Model model = new ConcurrentModel();
        final String view = controller.list(admin(), model);

        assertEquals("jobs", view);
        final List<BackgroundJob> jobs = (List<BackgroundJob>) sectionByCategory(
                sectionsFromModel(model), BackgroundJob.CATEGORY_DATA_IMPORT).get("jobs");
        assertEquals(2, jobs.size());
    }

    @Test
    void nonAdminSeesOnlyJobsInTheirGroups() {
        when(jobRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                job("mine", BackgroundJob.TYPE_OPENSEARCH_INGEST, "b-mine"),
                job("theirs", BackgroundJob.TYPE_OPENSEARCH_INGEST, "b-theirs"),
                job("orphan", BackgroundJob.TYPE_OPENSEARCH_INGEST, null)));
        when(batchRepository.findAllById(any())).thenReturn(List.of(
                batch("b-mine", "g-mine"),
                batch("b-theirs", "g-theirs")));
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g-mine"));

        final Model model = new ConcurrentModel();
        controller.list(user("alice@x.com"), model);

        final List<BackgroundJob> jobs = (List<BackgroundJob>) sectionByCategory(
                sectionsFromModel(model), BackgroundJob.CATEGORY_DATA_IMPORT).get("jobs");
        assertEquals(1, jobs.size());
        assertEquals("mine", jobs.get(0).getId());
    }

    @Test
    void nonAdminWithNoGroupsSeesNothing() {
        when(jobRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                job("j1", BackgroundJob.TYPE_OPENSEARCH_INGEST, "b1")));
        when(batchRepository.findAllById(any())).thenReturn(List.of(batch("b1", "g1")));
        when(userGroupsService.groupIdsForEmail(anyString())).thenReturn(Set.of());

        final Model model = new ConcurrentModel();
        controller.list(user("bob@x.com"), model);

        final List<BackgroundJob> jobs = (List<BackgroundJob>) sectionByCategory(
                sectionsFromModel(model), BackgroundJob.CATEGORY_DATA_IMPORT).get("jobs");
        assertTrue(jobs.isEmpty());
    }

    @Test
    void anonymousSeesNothingButPageStillRenders() {
        when(jobRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                job("j1", BackgroundJob.TYPE_OPENSEARCH_INGEST, "b1")));
        when(batchRepository.findAllById(any())).thenReturn(List.of(batch("b1", "g1")));
        when(userGroupsService.groupIdsForEmail(any())).thenReturn(Set.of());

        final Model model = new ConcurrentModel();
        final String view = controller.list(null, model);

        assertEquals("jobs", view);
        final List<BackgroundJob> jobs = (List<BackgroundJob>) sectionByCategory(
                sectionsFromModel(model), BackgroundJob.CATEGORY_DATA_IMPORT).get("jobs");
        assertTrue(jobs.isEmpty());
    }

    // ---------- grouping by category ----------

    @Test
    void dataImportSectionAlwaysRenderedEvenWhenEmpty() {
        when(jobRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        final Model model = new ConcurrentModel();
        controller.list(admin(), model);

        final List<Map<String, Object>> sections = sectionsFromModel(model);
        // The first (and only) section should be Data Import — present even with no jobs.
        assertEquals(BackgroundJob.CATEGORY_DATA_IMPORT, sections.get(0).get("category"));
        assertEquals("Data Import Jobs", sections.get(0).get("label"));
        assertTrue(((List<?>) sections.get(0).get("jobs")).isEmpty());
    }

    @Test
    void unknownTypesGetTheirOwnOtherSection() {
        // A job with an unknown type maps to CATEGORY_OTHER and shows up in its own section.
        final BackgroundJob mystery = job("m1", "FUTURE_TYPE", null);
        when(jobRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                job("j1", BackgroundJob.TYPE_OPENSEARCH_INGEST, "b1"),
                mystery));

        final Model model = new ConcurrentModel();
        controller.list(admin(), model);

        final List<Map<String, Object>> sections = sectionsFromModel(model);
        // Data Import comes first; OTHER comes last.
        assertEquals(BackgroundJob.CATEGORY_DATA_IMPORT, sections.get(0).get("category"));
        assertEquals(BackgroundJob.CATEGORY_OTHER, sections.get(sections.size() - 1).get("category"));
        assertEquals("Other Jobs", sections.get(sections.size() - 1).get("label"));

        final List<BackgroundJob> dataImport = (List<BackgroundJob>) sections.get(0).get("jobs");
        final List<BackgroundJob> other = (List<BackgroundJob>)
                sections.get(sections.size() - 1).get("jobs");
        assertEquals(1, dataImport.size());
        assertEquals("j1", dataImport.get(0).getId());
        assertEquals(1, other.size());
        assertEquals("m1", other.get(0).getId());
    }

    @Test
    void onlyOtherSectionMissingWhenAllJobsAreKnown() {
        when(jobRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                job("j1", BackgroundJob.TYPE_OPENSEARCH_INGEST, "b1"),
                job("j2", BackgroundJob.TYPE_ELASTICSEARCH_INGEST, "b2")));

        final Model model = new ConcurrentModel();
        controller.list(admin(), model);

        final List<Map<String, Object>> sections = sectionsFromModel(model);
        final boolean hasOther = sections.stream()
                .anyMatch(s -> BackgroundJob.CATEGORY_OTHER.equals(s.get("category")));
        assertFalse(hasOther, "OTHER section should not appear when no jobs fall under it");
    }
}
