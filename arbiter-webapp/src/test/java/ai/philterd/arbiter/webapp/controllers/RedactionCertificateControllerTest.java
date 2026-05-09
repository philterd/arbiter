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

import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.RedactionCertificate;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.RedactionCertificateRepository;
import ai.philterd.arbiter.service.DocumentAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedactionCertificateControllerTest {

    private RedactionCertificateRepository certificateRepository;
    private DocumentRepository documentRepository;
    private DocumentAccessService documentAccessService;
    private RedactionCertificateController controller;

    @BeforeEach
    void setUp() {
        certificateRepository = mock(RedactionCertificateRepository.class);
        documentRepository = mock(DocumentRepository.class);
        documentAccessService = mock(DocumentAccessService.class);
        controller = new RedactionCertificateController(certificateRepository, documentRepository,
                documentAccessService);
    }

    private static Authentication user() {
        return new UsernamePasswordAuthenticationToken("alice@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static Document doc(final String id) {
        final Document d = new Document();
        d.setId(id);
        return d;
    }

    @Test
    void missingDocumentReturns404() {
        when(documentRepository.findById("ghost")).thenReturn(Optional.empty());
        final ResponseEntity<RedactionCertificate> response = controller.getCertificate("ghost", user());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void notFoundFromAccessServiceTranslatesTo404Body() {
        // requireDocumentAccess throws NOT_FOUND when caller is out-of-group; the
        // controller translates that to ResponseEntity.notFound().build() so probing
        // ids can't tell missing-document apart from no-access.
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc("d1")));
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found."))
                .when(documentAccessService).requireDocumentAccess(any(), any());

        final ResponseEntity<RedactionCertificate> response = controller.getCertificate("d1", user());

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void nonNotFoundStatusFromAccessServiceIsRethrown() {
        // Today nothing throws anything other than 404, but the catch must not silently
        // swallow a future 409 (or any other status). Rethrow so Spring renders it.
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc("d1")));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Document is soft-deleted."))
                .when(documentAccessService).requireDocumentAccess(any(), any());

        final ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.getCertificate("d1", user()));
        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
    }

    @Test
    void emptyCertificateListReturns404() {
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc("d1")));
        doNothing().when(documentAccessService).requireDocumentAccess(any(), any());
        when(certificateRepository.findByDocumentIdOrderByFinalizedAtDesc("d1"))
                .thenReturn(List.of());

        final ResponseEntity<RedactionCertificate> response = controller.getCertificate("d1", user());

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void mostRecentCertificateReturned() {
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc("d1")));
        doNothing().when(documentAccessService).requireDocumentAccess(any(), any());
        final RedactionCertificate latest = new RedactionCertificate();
        latest.setId("cert-2");
        // The repo method is sorted descending by finalizedAt — the controller takes get(0).
        when(certificateRepository.findByDocumentIdOrderByFinalizedAtDesc("d1"))
                .thenReturn(List.of(latest));

        final ResponseEntity<RedactionCertificate> response = controller.getCertificate("d1", user());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("cert-2", response.getBody().getId());
    }
}
