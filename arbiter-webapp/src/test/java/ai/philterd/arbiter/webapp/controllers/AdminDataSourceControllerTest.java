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

import ai.philterd.arbiter.model.LocalDirectoryDataSource;
import ai.philterd.arbiter.model.OpenSearchDataSource;
import ai.philterd.arbiter.model.RelationalDbDataSource;
import ai.philterd.arbiter.model.S3DataSource;
import ai.philterd.arbiter.repository.ElasticsearchDataSourceRepository;
import ai.philterd.arbiter.repository.LocalDirectoryDataSourceRepository;
import ai.philterd.arbiter.repository.OpenSearchDataSourceRepository;
import ai.philterd.arbiter.repository.RelationalDbDataSourceRepository;
import ai.philterd.arbiter.repository.S3DataSourceRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.SymmetricCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration-style tests for the four data-source flows on
 * {@link AdminDataSourceController}: OpenSearch, S3, Relational Database,
 * Local Directory. Positive happy paths + every key negative case.
 */
class AdminDataSourceControllerTest {

    private OpenSearchDataSourceRepository repository;
    private ElasticsearchDataSourceRepository esRepository;
    private S3DataSourceRepository s3Repository;
    private RelationalDbDataSourceRepository rdbRepository;
    private LocalDirectoryDataSourceRepository localRepository;
    private AuditLogService auditLogService;
    private SymmetricCipher cipher;
    private AdminDataSourceController controller;

    @BeforeEach
    void setUp() {
        repository = mock(OpenSearchDataSourceRepository.class);
        esRepository = mock(ElasticsearchDataSourceRepository.class);
        s3Repository = mock(S3DataSourceRepository.class);
        rdbRepository = mock(RelationalDbDataSourceRepository.class);
        localRepository = mock(LocalDirectoryDataSourceRepository.class);
        auditLogService = mock(AuditLogService.class);
        cipher = mock(SymmetricCipher.class);
        // Identity-ish encrypt so assertions can recover the supplied value.
        when(cipher.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        final ai.philterd.arbiter.service.DataSourceHostAllowList allowList =
                new ai.philterd.arbiter.service.DataSourceHostAllowList("");
        controller = new AdminDataSourceController(
                repository, esRepository, s3Repository, rdbRepository, localRepository,
                auditLogService, cipher, new ObjectMapper(),
                allowList,
                new ai.philterd.arbiter.service.JdbcUrlValidator(allowList));
    }

    private RedirectAttributes flash() { return new RedirectAttributesModelMap(); }
    private static String error(final RedirectAttributes ra) {
        final Object e = ra.getFlashAttributes().get("error"); return e == null ? null : e.toString();
    }
    private static String success(final RedirectAttributes ra) {
        final Object s = ra.getFlashAttributes().get("success"); return s == null ? null : s.toString();
    }

    // ====================================================================
    // OpenSearch
    // ====================================================================

    @Test
    void osCreateHappyPathPersistsAndAudits() {
        when(repository.findFirstByNameIgnoreCase("legal")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        final String view = controller.create("legal", "http://es:9200",
                "contracts/_search { \"query\": { \"match_all\": {} } }",
                "body", null, "alice", "secretpw", ra);

        assertEquals("redirect:/admin/data-sources", view);
        assertEquals("Data source \"legal\" added.", success(ra));

        final ArgumentCaptor<OpenSearchDataSource> saved =
                ArgumentCaptor.forClass(OpenSearchDataSource.class);
        verify(repository).save(saved.capture());
        assertEquals("legal", saved.getValue().getName());
        assertEquals("http://es:9200", saved.getValue().getEndpoint());
        assertEquals("body", saved.getValue().getTextField());
        assertEquals("alice", saved.getValue().getUsername());
        assertEquals("enc:secretpw", saved.getValue().getEncryptedPassword());
        verify(auditLogService).log(eq("OPENSEARCH_DATASOURCE_CREATE"), anyString(), anyString(), any());
    }

    @Test
    void osCreateRejectsBlankName() {
        final RedirectAttributes ra = flash();
        controller.create("   ", "http://es:9200", "q", "body", null, null, null, ra);
        assertEquals("Name is required.", error(ra));
        verify(repository, never()).save(any());
    }

    @Test
    void osCreateRejectsBlankEndpoint() {
        final RedirectAttributes ra = flash();
        controller.create("n", " ", "q", "body", null, null, null, ra);
        assertEquals("Endpoint is required.", error(ra));
    }

    @Test
    void osCreateRejectsBlankQuery() {
        final RedirectAttributes ra = flash();
        controller.create("n", "http://es", "", "body", null, null, null, ra);
        assertEquals("Query is required.", error(ra));
    }

    @Test
    void osCreateRejectsBlankTextField() {
        final RedirectAttributes ra = flash();
        controller.create("n", "http://es", "q", "  ", null, null, null, ra);
        assertEquals("Text field is required.", error(ra));
    }

    @Test
    void osCreateRejectsDuplicateNameCaseInsensitive() {
        final OpenSearchDataSource existing = new OpenSearchDataSource();
        existing.setName("Legal");
        when(repository.findFirstByNameIgnoreCase("legal")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.create("legal", "http://es", "q", "body", null, null, null, ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).contains("already exists"));
        verify(repository, never()).save(any());
    }

    @Test
    void osCreateHandlesDuplicateKeyExceptionFromSave() {
        // Race condition: precheck passes, but Mongo's unique index rejects the insert.
        when(repository.findFirstByNameIgnoreCase("legal")).thenReturn(Optional.empty());
        when(repository.save(any(OpenSearchDataSource.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key"));
        final RedirectAttributes ra = flash();

        controller.create("legal", "http://es", "q", "body", null, null, null, ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).contains("already exists"));
    }

    @Test
    void osDeleteHappyPath() {
        final OpenSearchDataSource src = new OpenSearchDataSource();
        src.setId("ds-1");
        src.setName("legal");
        when(repository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        controller.delete("ds-1", ra);

        verify(repository).deleteById("ds-1");
        verify(auditLogService).log(eq("OPENSEARCH_DATASOURCE_DELETE"), anyString(), eq("ds-1"), any());
        assertEquals("Data source \"legal\" removed.", success(ra));
    }

    @Test
    void osDeleteMissingIdReturnsErrorAndDoesNotDelete() {
        when(repository.findById("ghost")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.delete("ghost", ra);

        assertEquals("Data source not found.", error(ra));
        verify(repository, never()).deleteById(anyString());
    }

    // ====================================================================
    // S3
    // ====================================================================

    @Test
    void s3CreateHappyPathPersistsAndEncryptsCredentials() {
        when(s3Repository.findFirstByNameIgnoreCase("archive")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.createS3("archive", "my-bucket", "raw/", "*.txt",
                "AKIA…", "wJalrXUtnFEMI", ra);

        final ArgumentCaptor<S3DataSource> saved = ArgumentCaptor.forClass(S3DataSource.class);
        verify(s3Repository).save(saved.capture());
        assertEquals("archive", saved.getValue().getName());
        assertEquals("my-bucket", saved.getValue().getBucketName());
        assertEquals("raw/", saved.getValue().getBucketKey());
        assertEquals("*.txt", saved.getValue().getFilenameGlob());
        assertEquals("enc:AKIA…", saved.getValue().getEncryptedAccessKey());
        assertEquals("enc:wJalrXUtnFEMI", saved.getValue().getEncryptedSecretKey());
        assertEquals("S3 data source \"archive\" added.", success(ra));
    }

    @Test
    void s3CreateAmbientCredentialsLeavesEncryptedFieldsNull() {
        when(s3Repository.findFirstByNameIgnoreCase("amb")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.createS3("amb", "my-bucket", "k/", "*.pdf", "", "", ra);

        final ArgumentCaptor<S3DataSource> saved = ArgumentCaptor.forClass(S3DataSource.class);
        verify(s3Repository).save(saved.capture());
        assertNull(saved.getValue().getEncryptedAccessKey());
        assertNull(saved.getValue().getEncryptedSecretKey());
    }

    @Test
    void s3CreateRejectsBlankBucket() {
        final RedirectAttributes ra = flash();
        controller.createS3("n", "  ", "k/", "*.txt", null, null, ra);
        assertEquals("Bucket name is required.", error(ra));
    }

    @Test
    void s3CreateRejectsBlankBucketKey() {
        final RedirectAttributes ra = flash();
        controller.createS3("n", "b", "", "*.txt", null, null, ra);
        assertEquals("Bucket key is required.", error(ra));
    }

    @Test
    void s3CreateRejectsBlankFilenameGlob() {
        final RedirectAttributes ra = flash();
        controller.createS3("n", "b", "k/", " ", null, null, ra);
        assertEquals("Filename glob is required.", error(ra));
    }

    @Test
    void s3CreateRejectsAccessKeyWithoutSecretKey() {
        final RedirectAttributes ra = flash();
        controller.createS3("n", "b", "k/", "*.txt", "AKIA", "", ra);
        assertEquals("Provide both Access key and Secret key, or leave both blank.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3CreateRejectsSecretKeyWithoutAccessKey() {
        final RedirectAttributes ra = flash();
        controller.createS3("n", "b", "k/", "*.txt", "", "secret", ra);
        assertEquals("Provide both Access key and Secret key, or leave both blank.", error(ra));
    }

    @Test
    void s3DeleteMissingIdReturnsError() {
        when(s3Repository.findById("ghost")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();
        controller.deleteS3("ghost", ra);
        assertEquals("S3 data source not found.", error(ra));
        verify(s3Repository, never()).deleteById(anyString());
    }

    // ====================================================================
    // Relational Database
    // ====================================================================

    @Test
    void rdbCreateHappyPathPersistsAndEncryptsBothCredentials() {
        when(rdbRepository.findFirstByNameIgnoreCase("warehouse")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.createRdb("warehouse", "jdbc:postgresql://host/db",
                "SELECT body FROM documents", "alice", "pw", ra);

        final ArgumentCaptor<RelationalDbDataSource> saved =
                ArgumentCaptor.forClass(RelationalDbDataSource.class);
        verify(rdbRepository).save(saved.capture());
        assertEquals("jdbc:postgresql://host/db", saved.getValue().getJdbcUrl());
        assertEquals("SELECT body FROM documents", saved.getValue().getSqlQuery());
        assertEquals("enc:alice", saved.getValue().getEncryptedUsername());
        assertEquals("enc:pw", saved.getValue().getEncryptedPassword());
    }

    @Test
    void rdbCreateRejectsBlankJdbcUrl() {
        final RedirectAttributes ra = flash();
        controller.createRdb("n", "", "SELECT 1", null, null, ra);
        assertEquals("JDBC URL is required.", error(ra));
    }

    @Test
    void rdbCreateRejectsBlankSqlQuery() {
        final RedirectAttributes ra = flash();
        controller.createRdb("n", "jdbc:x", "  ", null, null, ra);
        assertEquals("SQL query is required.", error(ra));
    }

    @Test
    void rdbCreateRejectsDangerousSqlKeywords() {
        // The controller refuses queries that could mutate the source table.
        final RedirectAttributes ra = flash();
        controller.createRdb("evil", "jdbc:x",
                "DELETE FROM documents WHERE 1=1", null, null, ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("disallowed"),
                "expected dangerous-keyword rejection, got: " + error(ra));
        verify(rdbRepository, never()).save(any());
        // The blocked attempt must be audit-logged with the matched keywords.
        verify(auditLogService).log(eq("RDB_DANGEROUS_SQL_BLOCKED"), anyString(), any(), any());
    }

    @Test
    void rdbCreateRejectsUsernameWithoutPassword() {
        final RedirectAttributes ra = flash();
        controller.createRdb("n", "jdbc:x", "SELECT 1", "alice", "", ra);
        assertEquals("Provide both Username and Password, or leave both blank.", error(ra));
    }

    @Test
    void rdbDeleteHappyPath() {
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        src.setName("warehouse");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        controller.deleteRdb("ds-1", ra);

        verify(rdbRepository).deleteById("ds-1");
        assertEquals("Relational database data source \"warehouse\" removed.", success(ra));
    }

    @Test
    void rdbDeleteMissingIdReturnsError() {
        when(rdbRepository.findById("ghost")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();
        controller.deleteRdb("ghost", ra);
        assertEquals("Relational database data source not found.", error(ra));
    }

    // ====================================================================
    // Local Directory
    // ====================================================================

    @Test
    void localCreateHappyPathPersists() {
        when(localRepository.findFirstByNameIgnoreCase("incoming")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.createLocal("incoming", "/var/lib/arbiter/incoming", "*.txt", ra);

        final ArgumentCaptor<LocalDirectoryDataSource> saved =
                ArgumentCaptor.forClass(LocalDirectoryDataSource.class);
        verify(localRepository).save(saved.capture());
        assertEquals("incoming", saved.getValue().getName());
        assertEquals("/var/lib/arbiter/incoming", saved.getValue().getDirectoryPath());
        assertEquals("*.txt", saved.getValue().getFilenameGlob());
    }

    @Test
    void localCreateRejectsBlankPath() {
        final RedirectAttributes ra = flash();
        controller.createLocal("n", "  ", "*.txt", ra);
        assertEquals("Directory path is required.", error(ra));
    }

    @Test
    void localCreateRejectsBlankGlob() {
        final RedirectAttributes ra = flash();
        controller.createLocal("n", "/p", "  ", ra);
        assertEquals("Filename glob is required.", error(ra));
    }

    @Test
    void localCreateRejectsDuplicateName() {
        final LocalDirectoryDataSource existing = new LocalDirectoryDataSource();
        existing.setName("Incoming");
        when(localRepository.findFirstByNameIgnoreCase("incoming")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.createLocal("incoming", "/p", "*.txt", ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).contains("already exists"));
    }

    @Test
    void localDeleteMissingIdReturnsError() {
        when(localRepository.findById("ghost")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();
        controller.deleteLocal("ghost", ra);
        assertEquals("Local directory data source not found.", error(ra));
    }

    // ====================================================================
    // SSRF allow-list — admin "Test connection" endpoints
    // ====================================================================

    @Test
    void testOpenSearchRejectsHostNotOnAllowList() {
        // Build a controller wired with a restrictive allow-list. Hitting Test connection
        // against a non-listed host must short-circuit with the allow-list error before
        // any HTTP request is made.
        final ai.philterd.arbiter.service.DataSourceHostAllowList opensearchOnly =
                new ai.philterd.arbiter.service.DataSourceHostAllowList("opensearch.internal");
        final AdminDataSourceController restricted = new AdminDataSourceController(
                repository, esRepository, s3Repository, rdbRepository, localRepository,
                auditLogService, cipher, new ObjectMapper(),
                opensearchOnly,
                new ai.philterd.arbiter.service.JdbcUrlValidator(opensearchOnly));

        final java.util.Map<String, Object> result = restricted.testOpenSearch(
                "http://attacker.example.com:9200", "contracts/_search { }", null, null);

        assertEquals(Boolean.FALSE, result.get("ok"));
        assertNotNull(result.get("error"));
        assertTrue(result.get("error").toString().contains("allow-list"),
                "expected allow-list error, got: " + result.get("error"));
    }

    @Test
    void testElasticsearchRejectsHostNotOnAllowList() {
        final ai.philterd.arbiter.service.DataSourceHostAllowList elasticOnly =
                new ai.philterd.arbiter.service.DataSourceHostAllowList("elastic.internal");
        final AdminDataSourceController restricted = new AdminDataSourceController(
                repository, esRepository, s3Repository, rdbRepository, localRepository,
                auditLogService, cipher, new ObjectMapper(),
                elasticOnly,
                new ai.philterd.arbiter.service.JdbcUrlValidator(elasticOnly));

        final java.util.Map<String, Object> result = restricted.testElasticsearch(
                "http://attacker.example.com:9200", "orders/_search { }", null, null);

        assertEquals(Boolean.FALSE, result.get("ok"));
        assertTrue(result.get("error").toString().contains("allow-list"),
                "expected allow-list error, got: " + result.get("error"));
    }

    @Test
    void testOpenSearchAllowsHostOnAllowList() {
        // When the host matches, the allow-list lets the request through to the actual
        // HTTP call. The request will fail (no real server), but we should NOT see the
        // allow-list error message. That's the difference between rejection-by-policy
        // and rejection-by-network.
        final ai.philterd.arbiter.service.DataSourceHostAllowList loopbackOnly =
                new ai.philterd.arbiter.service.DataSourceHostAllowList("127.0.0.1");
        final AdminDataSourceController restricted = new AdminDataSourceController(
                repository, esRepository, s3Repository, rdbRepository, localRepository,
                auditLogService, cipher, new ObjectMapper(),
                loopbackOnly,
                new ai.philterd.arbiter.service.JdbcUrlValidator(loopbackOnly));

        // Use port 1 — guaranteed to refuse so the test doesn't hang on a real connect.
        final java.util.Map<String, Object> result = restricted.testOpenSearch(
                "http://127.0.0.1:1", "contracts/_search { }", null, null);

        assertEquals(Boolean.FALSE, result.get("ok"));
        // ok=false because the connection failed, but the *reason* is not the allow-list.
        final Object error = result.get("error");
        if (error != null) {
            assertTrue(!error.toString().contains("allow-list"),
                    "did not expect allow-list error, got: " + error);
        }
    }
}
