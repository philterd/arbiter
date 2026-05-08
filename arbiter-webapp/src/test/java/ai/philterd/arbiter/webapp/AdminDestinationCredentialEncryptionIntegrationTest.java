/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp;

import ai.philterd.arbiter.model.S3Destination;
import ai.philterd.arbiter.model.SqsDestination;
import ai.philterd.arbiter.repository.LocalDirectoryDestinationRepository;
import ai.philterd.arbiter.repository.S3DestinationRepository;
import ai.philterd.arbiter.repository.SqsDestinationRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.DestinationTester;
import ai.philterd.arbiter.service.SymmetricCipher;
import ai.philterd.arbiter.webapp.controllers.AdminDestinationController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the AdminDestinationController credential-encryption
 * contract. Drives the controller through Spring MVC's {@link MockMvc} (so
 * binding, parameter resolution, and the redirect view are all real) with the
 * <em>real</em> {@link SymmetricCipher} wired in (no mock cipher). Repositories
 * are mocked at the persistence boundary so an {@link ArgumentCaptor} can read
 * what would have been written to MongoDB.
 *
 * <p>The contract verified here: by the time a destination object reaches the
 * repository, its access and secret keys are AES-GCM ciphertext — never the
 * plaintext that arrived in the HTTP form. Catches the regression where a
 * future refactor accidentally bypasses the cipher (e.g. swaps
 * {@code cipher.encrypt(x)} for the raw {@code x}).</p>
 *
 * <p>Uses {@code MockMvcBuilders.standaloneSetup} rather than
 * {@code @WebMvcTest} so the test owns exactly the components that matter for
 * encryption — the encryption guarantee shouldn't depend on the rest of the
 * application context being constructible.</p>
 */
class AdminDestinationCredentialEncryptionIntegrationTest {

    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/123456789012/MyQueue";

    private MockMvc mockMvc;
    private SymmetricCipher cipher;
    private LocalDirectoryDestinationRepository localRepository;
    private S3DestinationRepository s3Repository;
    private SqsDestinationRepository sqsRepository;
    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        // Real cipher with a deterministic test key — produces real AES-GCM ciphertext.
        cipher = new SymmetricCipher("integration-test-fixed-secret");

        localRepository = mock(LocalDirectoryDestinationRepository.class);
        s3Repository = mock(S3DestinationRepository.class);
        sqsRepository = mock(SqsDestinationRepository.class);
        auditLogService = mock(AuditLogService.class);

        final AdminDestinationController controller = new AdminDestinationController(
                localRepository, s3Repository, sqsRepository,
                auditLogService, cipher, mock(DestinationTester.class));

        // Standalone setup wires this single controller through the real Spring MVC
        // dispatcher. Form binding, @RequestParam resolution, and view-name redirect
        // resolution are all real — exactly what runs in production.
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ====================================================================
    // S3 — create
    // ====================================================================

    @Test
    void s3CreateViaHttpPersistsCiphertextNotPlaintext() throws Exception {
        when(s3Repository.findFirstByNameIgnoreCase(any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/destinations/s3")
                        .param("name", "archive")
                        .param("bucketName", "my-bucket")
                        .param("bucketKey", "finalized/")
                        .param("accessKey", ACCESS_KEY)
                        .param("secretKey", SECRET_KEY))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/destinations"));

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        final S3Destination saved = captor.getValue();

        assertEncrypted(saved.getEncryptedAccessKey(), ACCESS_KEY);
        assertEncrypted(saved.getEncryptedSecretKey(), SECRET_KEY);
    }

    @Test
    void s3CreateWithBlankCredentialsPersistsNullEncryptedFields() throws Exception {
        when(s3Repository.findFirstByNameIgnoreCase(any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/destinations/s3")
                        .param("name", "ambient")
                        .param("bucketName", "bucket")
                        .param("bucketKey", "k/")
                        .param("accessKey", "")
                        .param("secretKey", ""))
                .andExpect(status().is3xxRedirection());

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        // Both fields are null when no credentials were supplied — there is nothing to
        // encrypt, and null is the right "use ambient AWS credentials" sentinel.
        assertNull(captor.getValue().getEncryptedAccessKey());
        assertNull(captor.getValue().getEncryptedSecretKey());
    }

    @Test
    void s3CreateTwiceProducesDistinctCiphertextForIdenticalCredentials() throws Exception {
        when(s3Repository.findFirstByNameIgnoreCase(any())).thenReturn(Optional.empty());

        for (final String name : new String[]{"first", "second"}) {
            mockMvc.perform(post("/admin/destinations/s3")
                            .param("name", name)
                            .param("bucketName", "bucket")
                            .param("bucketKey", "k/")
                            .param("accessKey", ACCESS_KEY)
                            .param("secretKey", SECRET_KEY))
                    .andExpect(status().is3xxRedirection());
        }

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository, times(2)).save(captor.capture());
        final S3Destination first = captor.getAllValues().get(0);
        final S3Destination second = captor.getAllValues().get(1);

        // Unique IV per call must produce distinct ciphertext.
        assertNotEquals(first.getEncryptedAccessKey(), second.getEncryptedAccessKey(),
                "two HTTP-driven encryptions of the same access key produced identical ciphertext");
        assertNotEquals(first.getEncryptedSecretKey(), second.getEncryptedSecretKey(),
                "two HTTP-driven encryptions of the same secret key produced identical ciphertext");
    }

    // ====================================================================
    // S3 — edit
    // ====================================================================

    @Test
    void s3EditReplaceCredentialsViaHttpPersistsCiphertextNotPlaintext() throws Exception {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        existing.setEncryptedAccessKey(cipher.encrypt("OLD-AK"));
        existing.setEncryptedSecretKey(cipher.encrypt("OLD-SK"));
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));

        mockMvc.perform(post("/admin/destinations/s3/s1/edit")
                        .param("bucketName", "bucket")
                        .param("bucketKey", "k/")
                        .param("accessKey", ACCESS_KEY)
                        .param("secretKey", SECRET_KEY))
                .andExpect(status().is3xxRedirection());

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        final S3Destination saved = captor.getValue();

        assertEncrypted(saved.getEncryptedAccessKey(), ACCESS_KEY);
        assertEncrypted(saved.getEncryptedSecretKey(), SECRET_KEY);
    }

    @Test
    void s3EditClearCredentialsViaHttpPersistsNullEncryptedFields() throws Exception {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        existing.setEncryptedAccessKey(cipher.encrypt("OLD-AK"));
        existing.setEncryptedSecretKey(cipher.encrypt("OLD-SK"));
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));

        mockMvc.perform(post("/admin/destinations/s3/s1/edit")
                        .param("bucketName", "bucket")
                        .param("bucketKey", "k/")
                        .param("clearCredentials", "true"))
                .andExpect(status().is3xxRedirection());

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        assertNull(captor.getValue().getEncryptedAccessKey());
        assertNull(captor.getValue().getEncryptedSecretKey());
    }

    // ====================================================================
    // SQS — create
    // ====================================================================

    @Test
    void sqsCreateViaHttpPersistsCiphertextNotPlaintext() throws Exception {
        when(sqsRepository.findFirstByNameIgnoreCase(any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/destinations/sqs")
                        .param("name", "redaction-events")
                        .param("queueUrl", QUEUE_URL)
                        .param("accessKey", ACCESS_KEY)
                        .param("secretKey", SECRET_KEY))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/destinations"));

        final ArgumentCaptor<SqsDestination> captor = ArgumentCaptor.forClass(SqsDestination.class);
        verify(sqsRepository).save(captor.capture());
        final SqsDestination saved = captor.getValue();

        assertEncrypted(saved.getEncryptedAccessKey(), ACCESS_KEY);
        assertEncrypted(saved.getEncryptedSecretKey(), SECRET_KEY);
    }

    @Test
    void sqsCreateWithBlankCredentialsPersistsNullEncryptedFields() throws Exception {
        when(sqsRepository.findFirstByNameIgnoreCase(any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/destinations/sqs")
                        .param("name", "ambient")
                        .param("queueUrl", QUEUE_URL)
                        .param("accessKey", "")
                        .param("secretKey", ""))
                .andExpect(status().is3xxRedirection());

        final ArgumentCaptor<SqsDestination> captor = ArgumentCaptor.forClass(SqsDestination.class);
        verify(sqsRepository).save(captor.capture());
        assertNull(captor.getValue().getEncryptedAccessKey());
        assertNull(captor.getValue().getEncryptedSecretKey());
    }

    @Test
    void sqsCreateTwiceProducesDistinctCiphertextForIdenticalCredentials() throws Exception {
        when(sqsRepository.findFirstByNameIgnoreCase(any())).thenReturn(Optional.empty());

        for (final String name : new String[]{"first", "second"}) {
            mockMvc.perform(post("/admin/destinations/sqs")
                            .param("name", name)
                            .param("queueUrl", QUEUE_URL)
                            .param("accessKey", ACCESS_KEY)
                            .param("secretKey", SECRET_KEY))
                    .andExpect(status().is3xxRedirection());
        }

        final ArgumentCaptor<SqsDestination> captor = ArgumentCaptor.forClass(SqsDestination.class);
        verify(sqsRepository, times(2)).save(captor.capture());
        final SqsDestination first = captor.getAllValues().get(0);
        final SqsDestination second = captor.getAllValues().get(1);

        assertNotEquals(first.getEncryptedAccessKey(), second.getEncryptedAccessKey());
        assertNotEquals(first.getEncryptedSecretKey(), second.getEncryptedSecretKey());
    }

    // ====================================================================
    // SQS — edit
    // ====================================================================

    @Test
    void sqsEditReplaceCredentialsViaHttpPersistsCiphertextNotPlaintext() throws Exception {
        final SqsDestination existing = new SqsDestination();
        existing.setId("q1"); existing.setName("redaction-events");
        existing.setQueueUrl(QUEUE_URL);
        existing.setEncryptedAccessKey(cipher.encrypt("OLD-AK"));
        existing.setEncryptedSecretKey(cipher.encrypt("OLD-SK"));
        when(sqsRepository.findById("q1")).thenReturn(Optional.of(existing));

        mockMvc.perform(post("/admin/destinations/sqs/q1/edit")
                        .param("queueUrl", QUEUE_URL)
                        .param("accessKey", ACCESS_KEY)
                        .param("secretKey", SECRET_KEY))
                .andExpect(status().is3xxRedirection());

        final ArgumentCaptor<SqsDestination> captor = ArgumentCaptor.forClass(SqsDestination.class);
        verify(sqsRepository).save(captor.capture());
        final SqsDestination saved = captor.getValue();

        assertEncrypted(saved.getEncryptedAccessKey(), ACCESS_KEY);
        assertEncrypted(saved.getEncryptedSecretKey(), SECRET_KEY);
    }

    @Test
    void sqsEditClearCredentialsViaHttpPersistsNullEncryptedFields() throws Exception {
        final SqsDestination existing = new SqsDestination();
        existing.setId("q1"); existing.setName("redaction-events");
        existing.setQueueUrl(QUEUE_URL);
        existing.setEncryptedAccessKey(cipher.encrypt("OLD-AK"));
        existing.setEncryptedSecretKey(cipher.encrypt("OLD-SK"));
        when(sqsRepository.findById("q1")).thenReturn(Optional.of(existing));

        mockMvc.perform(post("/admin/destinations/sqs/q1/edit")
                        .param("queueUrl", QUEUE_URL)
                        .param("clearCredentials", "true"))
                .andExpect(status().is3xxRedirection());

        final ArgumentCaptor<SqsDestination> captor = ArgumentCaptor.forClass(SqsDestination.class);
        verify(sqsRepository).save(captor.capture());
        assertNull(captor.getValue().getEncryptedAccessKey());
        assertNull(captor.getValue().getEncryptedSecretKey());
    }

    // ====================================================================
    // Helper — the encryption contract for a single credential field.
    // ====================================================================

    /**
     * Asserts the field is the AES-GCM ciphertext of {@code plaintext} under the
     * test cipher: present, distinct from the plaintext (verbatim, hex, or
     * base64), and decryptable back to the original.
     */
    private void assertEncrypted(final String storedField, final String plaintext) {
        assertNotNull(storedField, "credential field is null — encryption was bypassed entirely");
        assertNotEquals(plaintext, storedField, "credential was stored unencrypted");

        if (storedField.contains(plaintext)) {
            throw new AssertionError("ciphertext contains plaintext substring");
        }
        final String hex = bytesToHex(plaintext.getBytes(StandardCharsets.UTF_8));
        if (storedField.toLowerCase().contains(hex.toLowerCase())) {
            throw new AssertionError("ciphertext contains hex-encoded plaintext");
        }
        final String b64 = Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8))
                .replace("=", "");
        if (b64.length() >= 8 && storedField.contains(b64)) {
            throw new AssertionError("ciphertext contains base64-encoded plaintext");
        }

        // Round-trip — proves the stored value is ciphertext under our key, not random
        // bytes or a constant or ciphertext under some other key.
        assertEquals(plaintext, cipher.decrypt(storedField),
                "stored ciphertext does not round-trip back to the original plaintext");
    }

    private static String bytesToHex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
