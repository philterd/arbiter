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

import ai.philterd.arbiter.model.LocalDirectoryDestination;
import ai.philterd.arbiter.model.S3Destination;
import ai.philterd.arbiter.model.SqsDestination;
import ai.philterd.arbiter.repository.LocalDirectoryDestinationRepository;
import ai.philterd.arbiter.repository.S3DestinationRepository;
import ai.philterd.arbiter.repository.SqsDestinationRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.DestinationTester;
import ai.philterd.arbiter.service.SymmetricCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AdminDestinationController}: list view, local-directory
 * create/delete, and S3 create/delete. Covers happy paths and every key
 * negative case (validation, paired credentials, duplicate-name races).
 */
class AdminDestinationControllerTest {

    private LocalDirectoryDestinationRepository localRepository;
    private S3DestinationRepository s3Repository;
    private SqsDestinationRepository sqsRepository;
    private AuditLogService auditLogService;
    private SymmetricCipher cipher;
    private DestinationTester destinationTester;
    private AdminDestinationController controller;

    @BeforeEach
    void setUp() {
        localRepository = mock(LocalDirectoryDestinationRepository.class);
        s3Repository = mock(S3DestinationRepository.class);
        sqsRepository = mock(SqsDestinationRepository.class);
        auditLogService = mock(AuditLogService.class);
        cipher = mock(SymmetricCipher.class);
        destinationTester = mock(DestinationTester.class);
        when(cipher.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));

        // Default: no destinations.
        when(localRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(s3Repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(sqsRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller = new AdminDestinationController(
                localRepository, s3Repository, sqsRepository, auditLogService, cipher, destinationTester);
    }

    private static RedirectAttributes flash() { return new RedirectAttributesModelMap(); }
    private static String error(final RedirectAttributes ra) {
        final Object e = ra.getFlashAttributes().get("error"); return e == null ? null : e.toString();
    }
    private static String success(final RedirectAttributes ra) {
        final Object s = ra.getFlashAttributes().get("success"); return s == null ? null : s.toString();
    }

    // ====================================================================
    // List
    // ====================================================================

    @Test
    void listExposesAllCollectionsToTheModel() {
        final LocalDirectoryDestination l = new LocalDirectoryDestination();
        l.setId("l1"); l.setName("local-1");
        final S3Destination s = new S3Destination();
        s.setId("s1"); s.setName("s3-1");
        final SqsDestination q = new SqsDestination();
        q.setId("q1"); q.setName("sqs-1");
        when(localRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(l)));
        when(s3Repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(s)));
        when(sqsRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(q)));

        final Model model = new ConcurrentModel();
        final String view = controller.list(model);

        assertEquals("admin-destinations", view);
        @SuppressWarnings("unchecked")
        final List<LocalDirectoryDestination> locals =
                (List<LocalDirectoryDestination>) model.getAttribute("localDestinations");
        @SuppressWarnings("unchecked")
        final List<S3Destination> s3s =
                (List<S3Destination>) model.getAttribute("s3Destinations");
        @SuppressWarnings("unchecked")
        final List<SqsDestination> sqss =
                (List<SqsDestination>) model.getAttribute("sqsDestinations");
        assertEquals(1, locals.size());
        assertSame(l, locals.get(0));
        assertEquals(1, s3s.size());
        assertSame(s, s3s.get(0));
        assertEquals(1, sqss.size());
        assertSame(q, sqss.get(0));
    }

    // ====================================================================
    // Local Directory — create
    // ====================================================================

    @Test
    void localCreateHappyPathPersistsAndAudits() {
        when(localRepository.findFirstByNameIgnoreCase("archive")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        final String view = controller.createLocal("archive", "/var/lib/arbiter/redacted", ra);

        assertEquals("redirect:/admin/destinations", view);
        assertEquals("Local directory destination \"archive\" added.", success(ra));
        assertNull(error(ra));

        final ArgumentCaptor<LocalDirectoryDestination> captor =
                ArgumentCaptor.forClass(LocalDirectoryDestination.class);
        verify(localRepository).save(captor.capture());
        final LocalDirectoryDestination saved = captor.getValue();
        assertNotNull(saved.getId());
        assertEquals("archive", saved.getName());
        assertEquals("/var/lib/arbiter/redacted", saved.getDirectoryPath());
        assertNotNull(saved.getCreatedAt());

        verify(auditLogService).log(eq("LOCAL_DESTINATION_CREATE"),
                eq("LocalDirectoryDestination"), eq(saved.getId()),
                eq(Map.of("name", "archive", "directoryPath", "/var/lib/arbiter/redacted")));
    }

    @Test
    void localCreateTrimsNameAndPath() {
        when(localRepository.findFirstByNameIgnoreCase("archive")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.createLocal("  archive  ", "  /tmp/out  ", ra);

        final ArgumentCaptor<LocalDirectoryDestination> captor =
                ArgumentCaptor.forClass(LocalDirectoryDestination.class);
        verify(localRepository).save(captor.capture());
        assertEquals("archive", captor.getValue().getName());
        assertEquals("/tmp/out", captor.getValue().getDirectoryPath());
    }

    @Test
    void localCreateRejectsBlankName() {
        final RedirectAttributes ra = flash();

        controller.createLocal("   ", "/tmp/out", ra);

        assertEquals("Name is required.", error(ra));
        verify(localRepository, never()).save(any());
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    @Test
    void localCreateRejectsBlankPath() {
        final RedirectAttributes ra = flash();

        controller.createLocal("archive", "  ", ra);

        assertEquals("Directory path is required.", error(ra));
        verify(localRepository, never()).save(any());
    }

    @Test
    void localCreateRejectsDuplicateNameCaseInsensitive() {
        final LocalDirectoryDestination existing = new LocalDirectoryDestination();
        existing.setId("ex"); existing.setName("Archive");
        when(localRepository.findFirstByNameIgnoreCase("archive")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.createLocal("archive", "/tmp/out", ra);

        assertEquals("A local directory destination named \"archive\" already exists.", error(ra));
        verify(localRepository, never()).save(any());
    }

    @Test
    void localCreateRecoversFromDuplicateKeyRace() {
        when(localRepository.findFirstByNameIgnoreCase("archive")).thenReturn(Optional.empty());
        when(localRepository.save(any(LocalDirectoryDestination.class)))
                .thenThrow(new DuplicateKeyException("dup"));
        final RedirectAttributes ra = flash();

        final String view = controller.createLocal("archive", "/tmp/out", ra);

        assertEquals("redirect:/admin/destinations", view);
        assertEquals("A local directory destination named \"archive\" already exists.", error(ra));
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    // ====================================================================
    // Local Directory — edit
    // ====================================================================

    @Test
    void localEditUpdatesPathAndAuditsButLeavesNameUntouched() {
        final LocalDirectoryDestination existing = new LocalDirectoryDestination();
        existing.setId("l1"); existing.setName("archive"); existing.setDirectoryPath("/old/path");
        when(localRepository.findById("l1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        final String view = controller.editLocal("l1", "/new/path", ra);

        assertEquals("redirect:/admin/destinations", view);
        assertEquals("Local directory destination \"archive\" updated.", success(ra));

        final ArgumentCaptor<LocalDirectoryDestination> captor =
                ArgumentCaptor.forClass(LocalDirectoryDestination.class);
        verify(localRepository).save(captor.capture());
        assertEquals("archive", captor.getValue().getName(), "name must be immutable");
        assertEquals("/new/path", captor.getValue().getDirectoryPath());

        verify(auditLogService).log(eq("LOCAL_DESTINATION_UPDATE"),
                eq("LocalDirectoryDestination"), eq("l1"),
                eq(Map.of("name", "archive", "directoryPath", "/new/path")));
    }

    @Test
    void localEditTrimsPath() {
        final LocalDirectoryDestination existing = new LocalDirectoryDestination();
        existing.setId("l1"); existing.setName("archive"); existing.setDirectoryPath("/old");
        when(localRepository.findById("l1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editLocal("l1", "  /trimmed  ", ra);

        final ArgumentCaptor<LocalDirectoryDestination> captor =
                ArgumentCaptor.forClass(LocalDirectoryDestination.class);
        verify(localRepository).save(captor.capture());
        assertEquals("/trimmed", captor.getValue().getDirectoryPath());
    }

    @Test
    void localEditRejectsBlankPath() {
        final LocalDirectoryDestination existing = new LocalDirectoryDestination();
        existing.setId("l1"); existing.setName("archive"); existing.setDirectoryPath("/old");
        when(localRepository.findById("l1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editLocal("l1", "   ", ra);

        assertEquals("Directory path is required.", error(ra));
        verify(localRepository, never()).save(any());
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    @Test
    void localEditOfMissingIdSetsError() {
        when(localRepository.findById("nope")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.editLocal("nope", "/some/path", ra);

        assertEquals("Local directory destination not found.", error(ra));
        verify(localRepository, never()).save(any());
    }

    // ====================================================================
    // Local Directory — delete
    // ====================================================================

    @Test
    void localDeleteRemovesAndAudits() {
        final LocalDirectoryDestination existing = new LocalDirectoryDestination();
        existing.setId("l1"); existing.setName("archive");
        when(localRepository.findById("l1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        final String view = controller.deleteLocal("l1", ra);

        assertEquals("redirect:/admin/destinations", view);
        assertEquals("Local directory destination \"archive\" removed.", success(ra));
        verify(localRepository).deleteById("l1");
        verify(auditLogService).log(eq("LOCAL_DESTINATION_DELETE"),
                eq("LocalDirectoryDestination"), eq("l1"),
                eq(Map.of("name", "archive")));
    }

    @Test
    void localDeleteOfMissingIdSetsErrorAndDoesNotDelete() {
        when(localRepository.findById("nope")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.deleteLocal("nope", ra);

        assertEquals("Local directory destination not found.", error(ra));
        verify(localRepository, never()).deleteById(anyString());
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    // ====================================================================
    // S3 — create
    // ====================================================================

    @Test
    void s3CreateWithCredentialsEncryptsAndAudits() {
        when(s3Repository.findFirstByNameIgnoreCase("s3-archive")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        final String view = controller.createS3("s3-archive", "my-bucket", "finalized/",
                "AKIAEXAMPLE", "supersecret", ra);

        assertEquals("redirect:/admin/destinations", view);
        assertEquals("S3 destination \"s3-archive\" added.", success(ra));
        assertNull(error(ra));

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        final S3Destination saved = captor.getValue();
        assertEquals("s3-archive", saved.getName());
        assertEquals("my-bucket", saved.getBucketName());
        assertEquals("finalized/", saved.getBucketKey());
        assertEquals("enc:AKIAEXAMPLE", saved.getEncryptedAccessKey());
        assertEquals("enc:supersecret", saved.getEncryptedSecretKey());
        assertNotNull(saved.getCreatedAt());

        verify(auditLogService).log(eq("S3_DESTINATION_CREATE"),
                eq("S3Destination"), eq(saved.getId()),
                eq(Map.of("name", "s3-archive",
                        "bucketName", "my-bucket",
                        "bucketKey", "finalized/",
                        "credentialsSet", true)));
    }

    @Test
    void s3CreateWithoutCredentialsLeavesEncryptedFieldsNull() {
        when(s3Repository.findFirstByNameIgnoreCase("ambient")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.createS3("ambient", "bucket", "k/", "", "", ra);

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        assertNull(captor.getValue().getEncryptedAccessKey());
        assertNull(captor.getValue().getEncryptedSecretKey());

        verify(auditLogService).log(eq("S3_DESTINATION_CREATE"),
                eq("S3Destination"), anyString(),
                eq(Map.of("name", "ambient",
                        "bucketName", "bucket",
                        "bucketKey", "k/",
                        "credentialsSet", false)));
    }

    @Test
    void s3CreateRejectsBlankName() {
        final RedirectAttributes ra = flash();

        controller.createS3("  ", "bucket", "k/", null, null, ra);

        assertEquals("Name is required.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3CreateRejectsBlankBucket() {
        final RedirectAttributes ra = flash();

        controller.createS3("name", "  ", "k/", null, null, ra);

        assertEquals("Bucket name is required.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3CreateRejectsBlankBucketKey() {
        final RedirectAttributes ra = flash();

        controller.createS3("name", "bucket", "  ", null, null, ra);

        assertEquals("Bucket key is required.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3CreateRejectsAccessKeyWithoutSecret() {
        final RedirectAttributes ra = flash();

        controller.createS3("name", "bucket", "k/", "AKIA", "", ra);

        assertEquals("Provide both Access key and Secret key, or leave both blank.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3CreateRejectsSecretWithoutAccessKey() {
        final RedirectAttributes ra = flash();

        controller.createS3("name", "bucket", "k/", "", "secret", ra);

        assertEquals("Provide both Access key and Secret key, or leave both blank.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3CreateRejectsDuplicateNameCaseInsensitive() {
        final S3Destination existing = new S3Destination();
        existing.setId("ex"); existing.setName("S3-Archive");
        when(s3Repository.findFirstByNameIgnoreCase("s3-archive")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.createS3("s3-archive", "bucket", "k/", "AKIA", "secret", ra);

        assertEquals("An S3 destination named \"s3-archive\" already exists.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3CreateRecoversFromDuplicateKeyRace() {
        when(s3Repository.findFirstByNameIgnoreCase("s3-archive")).thenReturn(Optional.empty());
        when(s3Repository.save(any(S3Destination.class)))
                .thenThrow(new DuplicateKeyException("dup"));
        final RedirectAttributes ra = flash();

        controller.createS3("s3-archive", "bucket", "k/", null, null, ra);

        assertEquals("An S3 destination named \"s3-archive\" already exists.", error(ra));
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    // ====================================================================
    // S3 — edit
    // ====================================================================

    @Test
    void s3EditWithBlankCredentialsKeepsExistingCredentials() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        existing.setBucketName("old-bucket"); existing.setBucketKey("old/");
        existing.setEncryptedAccessKey("enc:OLDAK");
        existing.setEncryptedSecretKey("enc:OLDSK");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("s1", "new-bucket", "new/", "", "", null, ra);

        assertEquals("S3 destination \"s3-archive\" updated.", success(ra));
        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        assertEquals("s3-archive", captor.getValue().getName(), "name must be immutable");
        assertEquals("new-bucket", captor.getValue().getBucketName());
        assertEquals("new/", captor.getValue().getBucketKey());
        assertEquals("enc:OLDAK", captor.getValue().getEncryptedAccessKey(), "credentials must be preserved");
        assertEquals("enc:OLDSK", captor.getValue().getEncryptedSecretKey(), "credentials must be preserved");

        verify(auditLogService).log(eq("S3_DESTINATION_UPDATE"),
                eq("S3Destination"), eq("s1"),
                eq(Map.of("name", "s3-archive",
                        "bucketName", "new-bucket",
                        "bucketKey", "new/",
                        "credentialsChanged", false,
                        "credentialsCleared", false)));
    }

    @Test
    void s3EditWithNewCredentialsEncryptsAndAuditsChange() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        existing.setEncryptedAccessKey("enc:OLDAK");
        existing.setEncryptedSecretKey("enc:OLDSK");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("s1", "bucket", "k/", "NEWAK", "NEWSK", null, ra);

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        assertEquals("enc:NEWAK", captor.getValue().getEncryptedAccessKey());
        assertEquals("enc:NEWSK", captor.getValue().getEncryptedSecretKey());

        verify(auditLogService).log(eq("S3_DESTINATION_UPDATE"),
                eq("S3Destination"), eq("s1"),
                eq(Map.of("name", "s3-archive",
                        "bucketName", "bucket",
                        "bucketKey", "k/",
                        "credentialsChanged", true,
                        "credentialsCleared", false)));
    }

    @Test
    void s3EditWithClearCredentialsNullsThemOut() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        existing.setEncryptedAccessKey("enc:OLDAK");
        existing.setEncryptedSecretKey("enc:OLDSK");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("s1", "bucket", "k/", "", "", "true", ra);

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        assertNull(captor.getValue().getEncryptedAccessKey());
        assertNull(captor.getValue().getEncryptedSecretKey());

        verify(auditLogService).log(eq("S3_DESTINATION_UPDATE"),
                eq("S3Destination"), eq("s1"),
                eq(Map.of("name", "s3-archive",
                        "bucketName", "bucket",
                        "bucketKey", "k/",
                        "credentialsChanged", true,
                        "credentialsCleared", true)));
    }

    @Test
    void s3EditClearWinsOverProvidedCredentials() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        existing.setEncryptedAccessKey("enc:OLDAK");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        // Even with credentials supplied, clear=true should win and null them out.
        controller.editS3("s1", "bucket", "k/", "AKIA", "secret", "true", ra);

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        assertNull(captor.getValue().getEncryptedAccessKey());
        assertNull(captor.getValue().getEncryptedSecretKey());
    }

    @Test
    void s3EditRejectsHalfFilledCredentialsWithoutClear() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("s1", "bucket", "k/", "AKIA", "", null, ra);

        assertEquals("Provide both Access key and Secret key, leave both blank to keep existing, or check Clear credentials.",
                error(ra));
        verify(s3Repository, never()).save(any());
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    @Test
    void s3EditRejectsBlankBucket() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("s1", "  ", "k/", "", "", null, ra);

        assertEquals("Bucket name is required.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3EditRejectsBlankBucketKey() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("s1", "bucket", "  ", "", "", null, ra);

        assertEquals("Bucket key is required.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3EditOfMissingIdSetsError() {
        when(s3Repository.findById("nope")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.editS3("nope", "bucket", "k/", "", "", null, ra);

        assertEquals("S3 destination not found.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    // ====================================================================
    // S3 — delete
    // ====================================================================

    @Test
    void s3DeleteRemovesAndAudits() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        final String view = controller.deleteS3("s1", ra);

        assertEquals("redirect:/admin/destinations", view);
        assertEquals("S3 destination \"s3-archive\" removed.", success(ra));
        verify(s3Repository).deleteById("s1");
        verify(auditLogService).log(eq("S3_DESTINATION_DELETE"),
                eq("S3Destination"), eq("s1"),
                eq(Map.of("name", "s3-archive")));
    }

    @Test
    void s3DeleteOfMissingIdSetsErrorAndDoesNotDelete() {
        when(s3Repository.findById("nope")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.deleteS3("nope", ra);

        assertEquals("S3 destination not found.", error(ra));
        verify(s3Repository, never()).deleteById(anyString());
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    // ====================================================================
    // SQS — create
    // ====================================================================

    private static final String QUEUE_URL =
            "https://sqs.us-east-1.amazonaws.com/123456789012/redaction-events";

    @Test
    void sqsCreateWithCredentialsEncryptsAndAudits() {
        when(sqsRepository.findFirstByNameIgnoreCase("redaction-events")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        final String view = controller.createSqs("redaction-events", QUEUE_URL,
                "AKIAEXAMPLE", "supersecret", ra);

        assertEquals("redirect:/admin/destinations", view);
        assertEquals("SQS destination \"redaction-events\" added.", success(ra));
        assertNull(error(ra));

        final ArgumentCaptor<SqsDestination> captor = ArgumentCaptor.forClass(SqsDestination.class);
        verify(sqsRepository).save(captor.capture());
        final SqsDestination saved = captor.getValue();
        assertEquals("redaction-events", saved.getName());
        assertEquals(QUEUE_URL, saved.getQueueUrl());
        assertEquals("enc:AKIAEXAMPLE", saved.getEncryptedAccessKey());
        assertEquals("enc:supersecret", saved.getEncryptedSecretKey());
        assertNotNull(saved.getCreatedAt());

        verify(auditLogService).log(eq("SQS_DESTINATION_CREATE"),
                eq("SqsDestination"), eq(saved.getId()),
                eq(Map.of("name", "redaction-events",
                        "queueUrl", QUEUE_URL,
                        "credentialsSet", true)));
    }

    @Test
    void sqsCreateWithoutCredentialsLeavesEncryptedFieldsNull() {
        when(sqsRepository.findFirstByNameIgnoreCase("ambient-queue")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.createSqs("ambient-queue", QUEUE_URL, "", "", ra);

        final ArgumentCaptor<SqsDestination> captor = ArgumentCaptor.forClass(SqsDestination.class);
        verify(sqsRepository).save(captor.capture());
        assertNull(captor.getValue().getEncryptedAccessKey());
        assertNull(captor.getValue().getEncryptedSecretKey());

        verify(auditLogService).log(eq("SQS_DESTINATION_CREATE"),
                eq("SqsDestination"), anyString(),
                eq(Map.of("name", "ambient-queue",
                        "queueUrl", QUEUE_URL,
                        "credentialsSet", false)));
    }

    @Test
    void sqsCreateRejectsBlankName() {
        final RedirectAttributes ra = flash();

        controller.createSqs("  ", QUEUE_URL, null, null, ra);

        assertEquals("Name is required.", error(ra));
        verify(sqsRepository, never()).save(any());
    }

    @Test
    void sqsCreateRejectsBlankQueueUrl() {
        final RedirectAttributes ra = flash();

        controller.createSqs("name", "  ", null, null, ra);

        assertEquals("Queue URL is required.", error(ra));
        verify(sqsRepository, never()).save(any());
    }

    @Test
    void sqsCreateRejectsAccessKeyWithoutSecret() {
        final RedirectAttributes ra = flash();

        controller.createSqs("name", QUEUE_URL, "AKIA", "", ra);

        assertEquals("Provide both Access key and Secret key, or leave both blank.", error(ra));
        verify(sqsRepository, never()).save(any());
    }

    @Test
    void sqsCreateRejectsSecretWithoutAccessKey() {
        final RedirectAttributes ra = flash();

        controller.createSqs("name", QUEUE_URL, "", "secret", ra);

        assertEquals("Provide both Access key and Secret key, or leave both blank.", error(ra));
        verify(sqsRepository, never()).save(any());
    }

    @Test
    void sqsCreateRejectsDuplicateNameCaseInsensitive() {
        final SqsDestination existing = new SqsDestination();
        existing.setId("ex"); existing.setName("Redaction-Events");
        when(sqsRepository.findFirstByNameIgnoreCase("redaction-events")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.createSqs("redaction-events", QUEUE_URL, "AKIA", "secret", ra);

        assertEquals("An SQS destination named \"redaction-events\" already exists.", error(ra));
        verify(sqsRepository, never()).save(any());
    }

    @Test
    void sqsCreateRecoversFromDuplicateKeyRace() {
        when(sqsRepository.findFirstByNameIgnoreCase("redaction-events")).thenReturn(Optional.empty());
        when(sqsRepository.save(any(SqsDestination.class)))
                .thenThrow(new DuplicateKeyException("dup"));
        final RedirectAttributes ra = flash();

        controller.createSqs("redaction-events", QUEUE_URL, null, null, ra);

        assertEquals("An SQS destination named \"redaction-events\" already exists.", error(ra));
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    // ====================================================================
    // SQS — edit
    // ====================================================================

    @Test
    void sqsEditWithBlankCredentialsKeepsExistingCredentials() {
        final SqsDestination existing = new SqsDestination();
        existing.setId("q1"); existing.setName("redaction-events");
        existing.setQueueUrl("https://sqs.us-east-1.amazonaws.com/000/old");
        existing.setEncryptedAccessKey("enc:OLDAK");
        existing.setEncryptedSecretKey("enc:OLDSK");
        when(sqsRepository.findById("q1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editSqs("q1", QUEUE_URL, "", "", null, ra);

        assertEquals("SQS destination \"redaction-events\" updated.", success(ra));
        final ArgumentCaptor<SqsDestination> captor = ArgumentCaptor.forClass(SqsDestination.class);
        verify(sqsRepository).save(captor.capture());
        assertEquals("redaction-events", captor.getValue().getName(), "name must be immutable");
        assertEquals(QUEUE_URL, captor.getValue().getQueueUrl());
        assertEquals("enc:OLDAK", captor.getValue().getEncryptedAccessKey(), "credentials must be preserved");
        assertEquals("enc:OLDSK", captor.getValue().getEncryptedSecretKey(), "credentials must be preserved");

        verify(auditLogService).log(eq("SQS_DESTINATION_UPDATE"),
                eq("SqsDestination"), eq("q1"),
                eq(Map.of("name", "redaction-events",
                        "queueUrl", QUEUE_URL,
                        "credentialsChanged", false,
                        "credentialsCleared", false)));
    }

    @Test
    void sqsEditWithNewCredentialsEncryptsAndAuditsChange() {
        final SqsDestination existing = new SqsDestination();
        existing.setId("q1"); existing.setName("redaction-events");
        existing.setQueueUrl(QUEUE_URL);
        existing.setEncryptedAccessKey("enc:OLDAK");
        existing.setEncryptedSecretKey("enc:OLDSK");
        when(sqsRepository.findById("q1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editSqs("q1", QUEUE_URL, "NEWAK", "NEWSK", null, ra);

        final ArgumentCaptor<SqsDestination> captor = ArgumentCaptor.forClass(SqsDestination.class);
        verify(sqsRepository).save(captor.capture());
        assertEquals("enc:NEWAK", captor.getValue().getEncryptedAccessKey());
        assertEquals("enc:NEWSK", captor.getValue().getEncryptedSecretKey());

        verify(auditLogService).log(eq("SQS_DESTINATION_UPDATE"),
                eq("SqsDestination"), eq("q1"),
                eq(Map.of("name", "redaction-events",
                        "queueUrl", QUEUE_URL,
                        "credentialsChanged", true,
                        "credentialsCleared", false)));
    }

    @Test
    void sqsEditWithClearCredentialsNullsThemOut() {
        final SqsDestination existing = new SqsDestination();
        existing.setId("q1"); existing.setName("redaction-events");
        existing.setQueueUrl(QUEUE_URL);
        existing.setEncryptedAccessKey("enc:OLDAK");
        existing.setEncryptedSecretKey("enc:OLDSK");
        when(sqsRepository.findById("q1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editSqs("q1", QUEUE_URL, "", "", "true", ra);

        final ArgumentCaptor<SqsDestination> captor = ArgumentCaptor.forClass(SqsDestination.class);
        verify(sqsRepository).save(captor.capture());
        assertNull(captor.getValue().getEncryptedAccessKey());
        assertNull(captor.getValue().getEncryptedSecretKey());

        verify(auditLogService).log(eq("SQS_DESTINATION_UPDATE"),
                eq("SqsDestination"), eq("q1"),
                eq(Map.of("name", "redaction-events",
                        "queueUrl", QUEUE_URL,
                        "credentialsChanged", true,
                        "credentialsCleared", true)));
    }

    @Test
    void sqsEditClearWinsOverProvidedCredentials() {
        final SqsDestination existing = new SqsDestination();
        existing.setId("q1"); existing.setName("redaction-events");
        existing.setQueueUrl(QUEUE_URL);
        existing.setEncryptedAccessKey("enc:OLDAK");
        when(sqsRepository.findById("q1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editSqs("q1", QUEUE_URL, "AKIA", "secret", "true", ra);

        final ArgumentCaptor<SqsDestination> captor = ArgumentCaptor.forClass(SqsDestination.class);
        verify(sqsRepository).save(captor.capture());
        assertNull(captor.getValue().getEncryptedAccessKey());
        assertNull(captor.getValue().getEncryptedSecretKey());
    }

    @Test
    void sqsEditRejectsHalfFilledCredentialsWithoutClear() {
        final SqsDestination existing = new SqsDestination();
        existing.setId("q1"); existing.setName("redaction-events");
        existing.setQueueUrl(QUEUE_URL);
        when(sqsRepository.findById("q1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editSqs("q1", QUEUE_URL, "AKIA", "", null, ra);

        assertEquals("Provide both Access key and Secret key, leave both blank to keep existing, or check Clear credentials.",
                error(ra));
        verify(sqsRepository, never()).save(any());
    }

    @Test
    void sqsEditRejectsBlankQueueUrl() {
        final SqsDestination existing = new SqsDestination();
        existing.setId("q1"); existing.setName("redaction-events");
        existing.setQueueUrl(QUEUE_URL);
        when(sqsRepository.findById("q1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editSqs("q1", "  ", "", "", null, ra);

        assertEquals("Queue URL is required.", error(ra));
        verify(sqsRepository, never()).save(any());
    }

    @Test
    void sqsEditOfMissingIdSetsError() {
        when(sqsRepository.findById("nope")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.editSqs("nope", QUEUE_URL, "", "", null, ra);

        assertEquals("SQS destination not found.", error(ra));
        verify(sqsRepository, never()).save(any());
    }

    // ====================================================================
    // SQS — delete
    // ====================================================================

    @Test
    void sqsDeleteRemovesAndAudits() {
        final SqsDestination existing = new SqsDestination();
        existing.setId("q1"); existing.setName("redaction-events");
        when(sqsRepository.findById("q1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        final String view = controller.deleteSqs("q1", ra);

        assertEquals("redirect:/admin/destinations", view);
        assertEquals("SQS destination \"redaction-events\" removed.", success(ra));
        verify(sqsRepository).deleteById("q1");
        verify(auditLogService).log(eq("SQS_DESTINATION_DELETE"),
                eq("SqsDestination"), eq("q1"),
                eq(Map.of("name", "redaction-events")));
    }

    @Test
    void sqsDeleteOfMissingIdSetsErrorAndDoesNotDelete() {
        when(sqsRepository.findById("nope")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.deleteSqs("nope", ra);

        assertEquals("SQS destination not found.", error(ra));
        verify(sqsRepository, never()).deleteById(anyString());
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    // ====================================================================
    // Test endpoints — JSON delegations to DestinationTester
    // ====================================================================

    @Test
    void localTestEndpointReturnsSuccessJson() {
        when(destinationTester.testLocalDirectory("/tmp/out"))
                .thenReturn(DestinationTester.TestResult.success("Test file written to /tmp/out/arbiter-test-1.txt."));

        final org.springframework.http.ResponseEntity<java.util.Map<String, Object>> resp =
                controller.testLocal("/tmp/out");

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(Boolean.TRUE, resp.getBody().get("ok"));
        assertEquals("Test file written to /tmp/out/arbiter-test-1.txt.", resp.getBody().get("message"));
        assertNull(resp.getBody().get("error"));
    }

    @Test
    void localTestEndpointReturnsFailureJson() {
        when(destinationTester.testLocalDirectory("/no/such/dir"))
                .thenReturn(DestinationTester.TestResult.failure("Directory does not exist: /no/such/dir"));

        final org.springframework.http.ResponseEntity<java.util.Map<String, Object>> resp =
                controller.testLocal("/no/such/dir");

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(Boolean.FALSE, resp.getBody().get("ok"));
        assertEquals("Directory does not exist: /no/such/dir", resp.getBody().get("error"));
        assertNull(resp.getBody().get("message"));
    }

    @Test
    void s3TestEndpointPassesAllParamsAndReturnsSuccess() {
        when(destinationTester.testS3("bucket", "k/", "AKIA", "secret"))
                .thenReturn(DestinationTester.TestResult.success("Test object written to s3://bucket/k/arbiter-test-1.txt."));

        final org.springframework.http.ResponseEntity<java.util.Map<String, Object>> resp =
                controller.testS3("bucket", "k/", "AKIA", "secret");

        assertEquals(Boolean.TRUE, resp.getBody().get("ok"));
        assertEquals("Test object written to s3://bucket/k/arbiter-test-1.txt.", resp.getBody().get("message"));
        verify(destinationTester).testS3("bucket", "k/", "AKIA", "secret");
    }

    @Test
    void s3TestEndpointReturnsFailureJson() {
        when(destinationTester.testS3("bucket", "k/", null, null))
                .thenReturn(DestinationTester.TestResult.failure("Could not write to S3: AccessDenied"));

        final org.springframework.http.ResponseEntity<java.util.Map<String, Object>> resp =
                controller.testS3("bucket", "k/", null, null);

        assertEquals(Boolean.FALSE, resp.getBody().get("ok"));
        assertEquals("Could not write to S3: AccessDenied", resp.getBody().get("error"));
    }

    @Test
    void sqsTestEndpointPassesAllParamsAndReturnsSuccess() {
        final String url = "https://sqs.us-east-1.amazonaws.com/123/q";
        when(destinationTester.testSqs(url, "AKIA", "secret"))
                .thenReturn(DestinationTester.TestResult.success("Test message sent to " + url + "."));

        final org.springframework.http.ResponseEntity<java.util.Map<String, Object>> resp =
                controller.testSqs(url, "AKIA", "secret");

        assertEquals(Boolean.TRUE, resp.getBody().get("ok"));
        assertEquals("Test message sent to " + url + ".", resp.getBody().get("message"));
        verify(destinationTester).testSqs(url, "AKIA", "secret");
    }

    @Test
    void sqsTestEndpointReturnsFailureJson() {
        when(destinationTester.testSqs("bad-url", null, null))
                .thenReturn(DestinationTester.TestResult.failure("Could not parse AWS region from the queue URL."));

        final org.springframework.http.ResponseEntity<java.util.Map<String, Object>> resp =
                controller.testSqs("bad-url", null, null);

        assertEquals(Boolean.FALSE, resp.getBody().get("ok"));
        assertEquals("Could not parse AWS region from the queue URL.", resp.getBody().get("error"));
    }
}
