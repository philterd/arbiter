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

import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.DocumentComment;
import ai.philterd.arbiter.model.RedactionCertificate;
import ai.philterd.arbiter.model.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Spring Data Mongo lifecycle callbacks that wrap PII-bearing fields with
 * transparent at-rest encryption. The tests drive the callbacks directly — Spring Data
 * Mongo is not in the picture — so what's exercised is the contract between
 * {@link PiiFieldEncryption} and {@link SymmetricCipher}.
 */
class PiiFieldEncryptionTest {

    private SymmetricCipher cipher;
    private PiiFieldEncryption.DocumentCallbacks documentCallbacks;
    private PiiFieldEncryption.SpanCallbacks spanCallbacks;
    private PiiFieldEncryption.DocumentCommentCallbacks commentCallbacks;
    private PiiFieldEncryption.RedactionCertificateCallbacks certificateCallbacks;

    @BeforeEach
    void setUp() {
        final byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        cipher = new SymmetricCipher(Base64.getEncoder().encodeToString(key));
        documentCallbacks = new PiiFieldEncryption.DocumentCallbacks(cipher);
        spanCallbacks = new PiiFieldEncryption.SpanCallbacks(cipher);
        commentCallbacks = new PiiFieldEncryption.DocumentCommentCallbacks(cipher);
        certificateCallbacks = new PiiFieldEncryption.RedactionCertificateCallbacks(cipher);
    }

    // ---------- Document ----------

    @Test
    void documentBeforeConvertEncryptsAllPiiFields() {
        final Document d = new Document();
        d.setOriginalText("Patient: alice@example.com");
        d.setRedactedText("Patient: <<EMAIL_ADDRESS>>");
        d.setFailureMessage("LLM call failed: alice@example.com");

        documentCallbacks.onBeforeConvert(d, "documents");

        assertEncrypted(d.getOriginalText(), "alice@example.com");
        assertEncrypted(d.getRedactedText(), "EMAIL_ADDRESS");
        assertEncrypted(d.getFailureMessage(), "alice@example.com");
    }

    @Test
    void documentAfterSaveRestoresPlaintextOnTheCallerReference() {
        // The save flow encrypts the entity in-place via BeforeConvert. AfterSave must
        // run automatically and restore plaintext so the caller's reference still
        // surfaces what they originally set — otherwise every controller that does
        // {@code save(d); d.getOriginalText();} would see ciphertext.
        final Document d = new Document();
        d.setOriginalText("alice@example.com");
        d.setRedactedText("redacted output");
        d.setFailureMessage(null);

        documentCallbacks.onBeforeConvert(d, "documents");
        // Sanity: after BeforeConvert the entity is encrypted (this is what gets persisted).
        assertEncrypted(d.getOriginalText(), "alice@example.com");

        // AfterSave restores plaintext on the same reference.
        final Document returned = documentCallbacks.onAfterSave(d, new org.bson.Document(), "documents");
        assertSame(d, returned, "The lifecycle contract is to mutate the entity in place, not replace it.");
        assertEquals("alice@example.com", d.getOriginalText());
        assertEquals("redacted output", d.getRedactedText());
        assertNull(d.getFailureMessage());
    }

    @Test
    void documentAfterConvertDecryptsValuesLoadedFromMongo() {
        // Simulate Spring Data having read a Mongo row into an entity. The originalText
        // value is the encrypted form on disk; AfterConvert restores plaintext.
        final Document d = new Document();
        d.setOriginalText(cipher.encryptField("alice@example.com"));
        d.setRedactedText(cipher.encryptField("Patient: <<EMAIL_ADDRESS>>"));
        d.setFailureMessage(null);

        documentCallbacks.onAfterConvert(d, new org.bson.Document(), "documents");

        assertEquals("alice@example.com", d.getOriginalText());
        assertEquals("Patient: <<EMAIL_ADDRESS>>", d.getRedactedText());
        assertNull(d.getFailureMessage());
    }

    @Test
    void documentAfterConvertReturnsLegacyPlaintextRowsUnchanged() {
        // A row written before encryption was switched on does NOT carry the marker
        // prefix. AfterConvert must return its values unchanged so the rollout is
        // backwards compatible.
        final Document d = new Document();
        d.setOriginalText("legacy plaintext source");
        d.setRedactedText("legacy plaintext redacted");
        d.setFailureMessage(null);

        documentCallbacks.onAfterConvert(d, new org.bson.Document(), "documents");

        assertEquals("legacy plaintext source", d.getOriginalText());
        assertEquals("legacy plaintext redacted", d.getRedactedText());
    }

    @Test
    void documentNullPiiFieldsAreLeftAlone() {
        // A Document with no body text yet (e.g. a placeholder for a queued ingest) must
        // round-trip null fields without crashing or assigning a non-null value.
        final Document d = new Document();
        d.setOriginalText(null);
        d.setRedactedText(null);
        d.setFailureMessage(null);

        documentCallbacks.onBeforeConvert(d, "documents");
        documentCallbacks.onAfterSave(d, new org.bson.Document(), "documents");
        documentCallbacks.onAfterConvert(d, new org.bson.Document(), "documents");

        assertNull(d.getOriginalText());
        assertNull(d.getRedactedText());
        assertNull(d.getFailureMessage());
    }

    @Test
    void documentEmptyStringFieldsAreNotEncrypted() {
        // Empty strings carry no PII and are useful as a "field set, but blank" signal.
        // The encrypt path passes them through untouched so the database is easier to
        // reason about (and so AfterConvert sees a stable empty rather than the
        // ciphertext of "").
        final Document d = new Document();
        d.setOriginalText("");
        d.setRedactedText("");
        d.setFailureMessage("");

        documentCallbacks.onBeforeConvert(d, "documents");
        assertEquals("", d.getOriginalText());
        assertEquals("", d.getRedactedText());
        assertEquals("", d.getFailureMessage());
    }

    @Test
    void documentSaveRoundTripPreservesNonPiiFields() {
        // The callbacks must touch ONLY the PII-bearing fields. Other state on the
        // entity (id, batchId, status, hash, source attribution, priority, lock fields)
        // must be preserved exactly.
        final Document d = new Document();
        d.setId("doc-1");
        d.setBatchId("b-1");
        d.setStatus("REVIEW_REQUIRED");
        d.setOriginalText("alice@example.com");
        d.setContentSha512("0123abc");
        d.setSourceSystem("OPENSEARCH");
        d.setPriority(3);

        documentCallbacks.onBeforeConvert(d, "documents");
        // Only originalText is mutated — non-PII fields are unchanged.
        assertEquals("doc-1", d.getId());
        assertEquals("b-1", d.getBatchId());
        assertEquals("REVIEW_REQUIRED", d.getStatus());
        assertEquals("0123abc", d.getContentSha512());
        assertEquals("OPENSEARCH", d.getSourceSystem());
        assertEquals(3, d.getPriority());
    }

    // ---------- Span ----------

    @Test
    void spanBeforeConvertEncryptsTextAndAfterSaveRestoresIt() {
        final Span s = new Span();
        s.setText("alice@example.com");

        spanCallbacks.onBeforeConvert(s, "spans");
        assertEncrypted(s.getText(), "alice@example.com");

        spanCallbacks.onAfterSave(s, new org.bson.Document(), "spans");
        assertEquals("alice@example.com", s.getText());
    }

    @Test
    void spanAfterConvertDecryptsLoadedText() {
        final Span s = new Span();
        s.setText(cipher.encryptField("alice@example.com"));

        spanCallbacks.onAfterConvert(s, new org.bson.Document(), "spans");
        assertEquals("alice@example.com", s.getText());
    }

    @Test
    void spanAfterConvertPassesLegacyPlaintextThrough() {
        final Span s = new Span();
        s.setText("legacy plaintext span");

        spanCallbacks.onAfterConvert(s, new org.bson.Document(), "spans");
        assertEquals("legacy plaintext span", s.getText());
    }

    @Test
    void spanWithNullTextRoundTripsCleanly() {
        // Manual spans created before any text is associated, or auto-created spans where
        // the text is unset, must not crash any of the lifecycle hooks.
        final Span s = new Span();
        s.setText(null);
        spanCallbacks.onBeforeConvert(s, "spans");
        spanCallbacks.onAfterSave(s, new org.bson.Document(), "spans");
        spanCallbacks.onAfterConvert(s, new org.bson.Document(), "spans");
        assertNull(s.getText());
    }

    @Test
    void spanCallbacksDoNotTouchOtherFields() {
        // Span has many fields (status, location, exemptionCode, ...). Only the
        // PII-bearing text field is inside the encryption boundary.
        final Span s = new Span();
        s.setId("span-1");
        s.setDocumentId("doc-1");
        s.setType("email-address");
        s.setText("alice@example.com");
        s.setConfidence(0.95);
        s.setStatus("APPROVED");
        s.setStatusChangedBy("alice@x.com");

        spanCallbacks.onBeforeConvert(s, "spans");

        assertEquals("span-1", s.getId());
        assertEquals("doc-1", s.getDocumentId());
        assertEquals("email-address", s.getType());
        assertEquals(0.95, s.getConfidence());
        assertEquals("APPROVED", s.getStatus());
        assertEquals("alice@x.com", s.getStatusChangedBy());
    }

    // ---------- DocumentComment ----------

    @Test
    void documentCommentBeforeConvertEncryptsTextAndAfterSaveRestoresIt() {
        final DocumentComment c = new DocumentComment();
        c.setText("Looked at SSN 123-45-6789 — it's a placeholder.");

        commentCallbacks.onBeforeConvert(c, "document_comments");
        assertEncrypted(c.getText(), "123-45-6789");

        commentCallbacks.onAfterSave(c, new org.bson.Document(), "document_comments");
        assertEquals("Looked at SSN 123-45-6789 — it's a placeholder.", c.getText());
    }

    @Test
    void documentCommentAfterConvertDecryptsLoadedText() {
        final DocumentComment c = new DocumentComment();
        c.setText(cipher.encryptField("This patient is sensitive."));

        commentCallbacks.onAfterConvert(c, new org.bson.Document(), "document_comments");
        assertEquals("This patient is sensitive.", c.getText());
    }

    @Test
    void documentCommentDoesNotMutateActorMetadata() {
        // Comments carry the actor's email and id alongside the text. Those are not PII
        // for our purposes (an admin investigation needs to see them in plaintext to
        // attribute actions). The encryption boundary is just the {@code text} field.
        final DocumentComment c = new DocumentComment();
        c.setId("c-1");
        c.setDocumentId("doc-1");
        c.setUserEmail("alice@x.com");
        c.setUserId("u-1");
        c.setText("Reviewer note");

        commentCallbacks.onBeforeConvert(c, "document_comments");
        assertEquals("c-1", c.getId());
        assertEquals("doc-1", c.getDocumentId());
        assertEquals("alice@x.com", c.getUserEmail());
        assertEquals("u-1", c.getUserId());
    }

    // ---------- multi-step interaction ----------

    @Test
    void documentRoundTripAcrossSaveThenLoadProducesIdenticalPlaintext() {
        // Simulate the full lifecycle: caller sets plaintext, save serializes (BeforeConvert
        // + AfterSave), then a separate read deserializes (AfterConvert). The end-state
        // entity must equal the start-state entity in PII content.
        final Document originalView = new Document();
        originalView.setOriginalText("Patient alice@example.com — DOB 1985-04-12");
        originalView.setRedactedText("Patient <<EMAIL>> — DOB <<DATE_OF_BIRTH>>");
        originalView.setFailureMessage(null);

        // Save side: BeforeConvert encrypts, simulate persistence to BSON, AfterSave
        // restores plaintext on the entity.
        documentCallbacks.onBeforeConvert(originalView, "documents");
        // Capture the on-disk representation — what would actually be in the database.
        final String storedOriginal = originalView.getOriginalText();
        final String storedRedacted = originalView.getRedactedText();
        documentCallbacks.onAfterSave(originalView, new org.bson.Document(), "documents");

        assertEquals("Patient alice@example.com — DOB 1985-04-12", originalView.getOriginalText(),
                "After save, the in-memory entity must surface plaintext to the caller.");

        // The stored form must NOT contain the plaintext anywhere — which is the whole
        // point of doing this.
        assertFalse(storedOriginal.contains("alice@example.com"),
                "On-disk originalText must never contain plaintext PII.");
        assertFalse(storedOriginal.contains("1985-04-12"));
        assertFalse(storedRedacted.contains("EMAIL"));

        // Load side: simulate Spring Data turning a fresh BSON document into a new entity
        // bearing the on-disk ciphertext. AfterConvert decrypts.
        final Document loadedView = new Document();
        loadedView.setOriginalText(storedOriginal);
        loadedView.setRedactedText(storedRedacted);
        documentCallbacks.onAfterConvert(loadedView, new org.bson.Document(), "documents");

        assertEquals(originalView.getOriginalText(), loadedView.getOriginalText());
        assertEquals(originalView.getRedactedText(), loadedView.getRedactedText());
    }

    // ---------- RedactionCertificate (R2-F6) ----------

    @Test
    void certificateBeforeConvertEncryptsDocumentFilenameAndEveryOverturnSpanText() {
        // Without this callback the redaction_certificates collection would store
        // these fields as plaintext while the source documents/spans collections
        // hold them encrypted — defeating the at-rest envelope for the
        // denormalised copy.
        final RedactionCertificate cert = new RedactionCertificate();
        cert.setDocumentFilename("mrn-12345678-jane-doe-discharge.pdf");
        final RedactionCertificate.OverturnEntry o1 = new RedactionCertificate.OverturnEntry();
        o1.setSpanText("555-12-3456");
        final RedactionCertificate.OverturnEntry o2 = new RedactionCertificate.OverturnEntry();
        o2.setSpanText("alice@example.com");
        cert.setOverturns(java.util.List.of(o1, o2));

        certificateCallbacks.onBeforeConvert(cert, "redaction_certificates");

        assertEncrypted(cert.getDocumentFilename(), "jane-doe");
        assertEncrypted(cert.getOverturns().get(0).getSpanText(), "555-12-3456");
        assertEncrypted(cert.getOverturns().get(1).getSpanText(), "alice@example.com");
    }

    @Test
    void certificateAfterSaveRestoresPlaintextOnCallerReference() {
        // Same in-place mutation contract as the Document/Span callbacks: a caller
        // that does {certificate.save(); certificate.getDocumentFilename();} should
        // see plaintext, not the ciphertext that BeforeConvert wrote.
        final RedactionCertificate cert = new RedactionCertificate();
        cert.setDocumentFilename("mrn-12345678.pdf");
        final RedactionCertificate.OverturnEntry o = new RedactionCertificate.OverturnEntry();
        o.setSpanText("555-12-3456");
        cert.setOverturns(java.util.List.of(o));

        certificateCallbacks.onBeforeConvert(cert, "redaction_certificates");
        assertEncrypted(cert.getDocumentFilename(), "mrn-12345678");
        assertEncrypted(cert.getOverturns().get(0).getSpanText(), "555-12-3456");

        final RedactionCertificate returned = certificateCallbacks.onAfterSave(
                cert, new org.bson.Document(), "redaction_certificates");
        assertSame(cert, returned);
        assertEquals("mrn-12345678.pdf", cert.getDocumentFilename());
        assertEquals("555-12-3456", cert.getOverturns().get(0).getSpanText());
    }

    @Test
    void certificateAfterConvertDecryptsValuesLoadedFromMongo() {
        // Round-trip: encrypt with BeforeConvert, then run AfterConvert (as if Mongo
        // loaded the stored ciphertext) and assert we get the plaintext back.
        final RedactionCertificate cert = new RedactionCertificate();
        cert.setDocumentFilename("mrn-12345678.pdf");
        final RedactionCertificate.OverturnEntry o = new RedactionCertificate.OverturnEntry();
        o.setSpanText("555-12-3456");
        cert.setOverturns(java.util.List.of(o));

        certificateCallbacks.onBeforeConvert(cert, "redaction_certificates");

        // Simulate Mongo loading the ciphertext into a fresh entity instance.
        final RedactionCertificate loaded = new RedactionCertificate();
        loaded.setDocumentFilename(cert.getDocumentFilename());
        final RedactionCertificate.OverturnEntry loadedOverturn = new RedactionCertificate.OverturnEntry();
        loadedOverturn.setSpanText(cert.getOverturns().get(0).getSpanText());
        loaded.setOverturns(java.util.List.of(loadedOverturn));

        certificateCallbacks.onAfterConvert(loaded, new org.bson.Document(), "redaction_certificates");

        assertEquals("mrn-12345678.pdf", loaded.getDocumentFilename());
        assertEquals("555-12-3456", loaded.getOverturns().get(0).getSpanText());
    }

    @Test
    void certificateCallbackHandlesNullAndEmptyOverturnsList() {
        // Defensive: the callback must not NPE when the certificate has no
        // overturns (no spans were overturned for that document) or when the
        // overturns list is null (legacy data).
        final RedactionCertificate empty = new RedactionCertificate();
        empty.setDocumentFilename(null);
        empty.setOverturns(null);
        certificateCallbacks.onBeforeConvert(empty, "redaction_certificates");
        certificateCallbacks.onAfterSave(empty, new org.bson.Document(), "redaction_certificates");
        certificateCallbacks.onAfterConvert(empty, new org.bson.Document(), "redaction_certificates");

        final RedactionCertificate noOverturns = new RedactionCertificate();
        noOverturns.setDocumentFilename("clean.pdf");
        noOverturns.setOverturns(java.util.List.of());
        certificateCallbacks.onBeforeConvert(noOverturns, "redaction_certificates");
        assertEncrypted(noOverturns.getDocumentFilename(), "clean.pdf");
    }

    // ---------- helpers ----------

    private static void assertEncrypted(final String storedValue, final String shouldNotContain) {
        assertTrue(storedValue != null && storedValue.startsWith(SymmetricCipher.FIELD_PREFIX),
                "Expected ciphertext-with-prefix; got: " + storedValue);
        assertFalse(storedValue.contains(shouldNotContain),
                "Ciphertext must not leak the plaintext substring '" + shouldNotContain + "'");
    }
}
