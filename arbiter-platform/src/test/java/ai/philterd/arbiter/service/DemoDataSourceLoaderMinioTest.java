/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.S3DataSource;
import ai.philterd.arbiter.repository.ElasticsearchDataSourceRepository;
import ai.philterd.arbiter.repository.LocalDirectoryDataSourceRepository;
import ai.philterd.arbiter.repository.LocalDirectoryDestinationRepository;
import ai.philterd.arbiter.repository.OpenSearchDataSourceRepository;
import ai.philterd.arbiter.repository.S3DataSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the demo MinIO {@link S3DataSource} that {@link DemoDataSourceLoader} registers
 * has exactly the field values needed to match the {@code minio-init} seed layout in
 * {@code docker-compose.yaml}. A drift between either side leaves the demo S3 ingest
 * matching zero files — the original symptom that prompted these tests.
 */
class DemoDataSourceLoaderMinioTest {

    private OpenSearchDataSourceRepository openSearchRepository;
    private ElasticsearchDataSourceRepository elasticsearchRepository;
    private LocalDirectoryDataSourceRepository localDirectoryRepository;
    private LocalDirectoryDestinationRepository localDestinationRepository;
    private S3DataSourceRepository s3Repository;
    private SymmetricCipher cipher;
    private DemoDataSourceLoader loader;

    @BeforeEach
    void setUp() {
        openSearchRepository = mock(OpenSearchDataSourceRepository.class);
        elasticsearchRepository = mock(ElasticsearchDataSourceRepository.class);
        localDirectoryRepository = mock(LocalDirectoryDataSourceRepository.class);
        localDestinationRepository = mock(LocalDirectoryDestinationRepository.class);
        s3Repository = mock(S3DataSourceRepository.class);
        cipher = mock(SymmetricCipher.class);
        when(cipher.encrypt(anyString())).thenAnswer(inv -> "enc(" + inv.getArgument(0) + ")");

        // No existing rows — the loader will create them.
        when(openSearchRepository.findFirstByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(elasticsearchRepository.findFirstByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(localDirectoryRepository.findFirstByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(localDestinationRepository.findFirstByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(s3Repository.findFirstByNameIgnoreCase(anyString())).thenReturn(Optional.empty());

        loader = new DemoDataSourceLoader(
                openSearchRepository, elasticsearchRepository, localDirectoryRepository,
                localDestinationRepository, s3Repository, cipher,
                // Use blank endpoints for OpenSearch / Elasticsearch so the loader's
                // backend-seed step short-circuits without trying to make HTTP calls.
                "", "",
                "/app/local-files", "/app/output",
                "http://minio:9000", "arbiter-demo", "minioadmin", "minioadmin");
    }

    @Test
    void registersDemoMinioSourceWithBucketKeyMatchingTheSeedLayout() {
        // Drive the loader's ApplicationRunner entry point — this is what Spring Boot
        // calls on startup. The loader walks each ensure*() method; we care about
        // ensureMinioDataSource here.
        loader.run(new DefaultApplicationArguments());

        final ArgumentCaptor<S3DataSource> saved = ArgumentCaptor.forClass(S3DataSource.class);
        verify(s3Repository).save(saved.capture());
        final S3DataSource ds = saved.getValue();

        assertEquals("Demo MinIO (S3-compatible)", ds.getName());
        assertEquals("http://minio:9000", ds.getEndpoint());
        assertEquals("arbiter-demo", ds.getBucketName(),
                "Bucket name must match what minio-init creates with `mc mb`.");
        assertEquals("arbiter-demo/", ds.getBucketKey(),
                "Bucket-key prefix must match where minio-init copies the sample files. "
                        + "If you change the prefix here you must also change the destination "
                        + "argument to `mc cp` in docker-compose.yaml — otherwise the demo S3 "
                        + "ingest finds zero files.");
        assertEquals("*.txt", ds.getFilenameGlob(),
                "Glob must match the file extensions of the sample-files contents.");
        // Credentials are encrypted at rest — verify the cipher was consulted, but don't
        // bind to a specific ciphertext shape.
        assertNotNull(ds.getEncryptedAccessKey());
        assertNotNull(ds.getEncryptedSecretKey());
    }

    @Test
    void doesNotOverwriteExistingDemoMinioRow() {
        // When a row already exists, the loader must skip — otherwise an operator's
        // hand-edit of the bucketKey or credentials would be silently restored to defaults
        // on every restart.
        when(s3Repository.findFirstByNameIgnoreCase("Demo MinIO (S3-compatible)"))
                .thenReturn(Optional.of(new S3DataSource()));

        loader.run(new DefaultApplicationArguments());

        verify(s3Repository, never()).save(any(S3DataSource.class));
    }
}
