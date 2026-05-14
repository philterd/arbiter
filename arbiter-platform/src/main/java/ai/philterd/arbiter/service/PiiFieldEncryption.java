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
import ai.philterd.arbiter.model.GeneralSettings;
import ai.philterd.arbiter.model.RedactionCertificate;
import ai.philterd.arbiter.model.Span;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertCallback;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveCallback;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;

/**
 * Field-level encryption for PII-bearing values stored in MongoDB. The classes in this
 * file plug into Spring Data Mongo's lifecycle callbacks so encryption and decryption
 * happen transparently around every {@code save} and {@code find} — call sites continue
 * to read and write plaintext on the in-memory entities.
 *
 * <h3>What gets encrypted</h3>
 * <ul>
 *     <li>{@link Document#getOriginalText() Document.originalText} — the source text of
 *         the document being redacted; carries the raw PII.</li>
 *     <li>{@link Document#getRedactedText() Document.redactedText} — the rendered output
 *         persisted at finalize time. Contains the surrounding context of redactions and
 *         is treated as sensitive.</li>
 *     <li>{@link Document#getFailureMessage() Document.failureMessage} — error messages
 *         can quote spans of the source document.</li>
 *     <li>{@link Span#getText() Span.text} — the literal PII string that was detected.</li>
 *     <li>{@link DocumentComment#getText() DocumentComment.text} — reviewer-supplied free
 *         text that may reference PII while documenting a decision.</li>
 * </ul>
 *
 * <h3>How it works</h3>
 * <p>Each of the three callback classes registered below implements three Spring Data
 * Mongo lifecycle callbacks:
 * <ul>
 *     <li><strong>BeforeConvertCallback</strong> — fires on save just before the entity is
 *         turned into BSON. The callback substitutes encrypted ciphertext into the entity
 *         so the wire format Mongo writes is the encrypted blob.</li>
 *     <li><strong>AfterSaveCallback</strong> — fires after the write completes. The
 *         callback restores plaintext on the in-memory entity so the caller's reference
 *         is still usable for further reads (it would otherwise hold the ciphertext that
 *         BeforeConvert wrote into it).</li>
 *     <li><strong>AfterConvertCallback</strong> — fires after a Mongo find completes and
 *         BSON has been turned back into an entity. The callback decrypts the stored
 *         fields so callers see plaintext as if encryption never happened.</li>
 * </ul>
 *
 * <h3>Backwards compatibility</h3>
 * <p>Pre-existing rows that were written before field encryption was switched on do not
 * carry the {@link SymmetricCipher#FIELD_PREFIX enc:v1:} marker.
 * {@link SymmetricCipher#decryptField(String)} returns those values unchanged, so they
 * continue to read normally. The first save of such a row promotes the field to its
 * encrypted form transparently.
 */
public final class PiiFieldEncryption {

    private PiiFieldEncryption() {}

    /** Lifecycle callbacks that wrap Document save/load with PII encryption. */
    @Component
    public static class DocumentCallbacks
            implements BeforeConvertCallback<Document>,
                       AfterSaveCallback<Document>,
                       AfterConvertCallback<Document> {

        private final SymmetricCipher cipher;

        public DocumentCallbacks(final SymmetricCipher cipher) {
            this.cipher = cipher;
        }

        @Override
        public Document onBeforeConvert(final Document entity, final String collection) {
            entity.setOriginalText(cipher.encryptField(entity.getOriginalText()));
            entity.setRedactedText(cipher.encryptField(entity.getRedactedText()));
            entity.setFailureMessage(cipher.encryptField(entity.getFailureMessage()));
            return entity;
        }

        @Override
        public Document onAfterSave(final Document entity, final org.bson.Document document, final String collection) {
            // The save mutated the entity to ciphertext via BeforeConvert; restore the
            // plaintext view so a caller that re-reads the same reference sees what they
            // originally set.
            entity.setOriginalText(cipher.decryptField(entity.getOriginalText()));
            entity.setRedactedText(cipher.decryptField(entity.getRedactedText()));
            entity.setFailureMessage(cipher.decryptField(entity.getFailureMessage()));
            return entity;
        }

        @Override
        public Document onAfterConvert(final Document entity, final org.bson.Document document, final String collection) {
            entity.setOriginalText(cipher.decryptField(entity.getOriginalText()));
            entity.setRedactedText(cipher.decryptField(entity.getRedactedText()));
            entity.setFailureMessage(cipher.decryptField(entity.getFailureMessage()));
            return entity;
        }
    }

    /** Lifecycle callbacks that wrap Span save/load with PII encryption. */
    @Component
    public static class SpanCallbacks
            implements BeforeConvertCallback<Span>,
                       AfterSaveCallback<Span>,
                       AfterConvertCallback<Span> {

        private final SymmetricCipher cipher;

        public SpanCallbacks(final SymmetricCipher cipher) {
            this.cipher = cipher;
        }

        @Override
        public Span onBeforeConvert(final Span entity, final String collection) {
            entity.setText(cipher.encryptField(entity.getText()));
            return entity;
        }

        @Override
        public Span onAfterSave(final Span entity, final org.bson.Document document, final String collection) {
            entity.setText(cipher.decryptField(entity.getText()));
            return entity;
        }

        @Override
        public Span onAfterConvert(final Span entity, final org.bson.Document document, final String collection) {
            entity.setText(cipher.decryptField(entity.getText()));
            return entity;
        }
    }

    /**
     * Lifecycle callbacks that encrypt the OpenSearch basic-auth password on the
     * {@link GeneralSettings} singleton row. Not strictly PII, but the same secret-at-rest
     * machinery applies — the password is treated like a credential and goes through the
     * same {@code enc:v1:} marker-prefix scheme.
     */
    @Component
    public static class GeneralSettingsCallbacks
            implements BeforeConvertCallback<GeneralSettings>,
                       AfterSaveCallback<GeneralSettings>,
                       AfterConvertCallback<GeneralSettings> {

        private final SymmetricCipher cipher;

        public GeneralSettingsCallbacks(final SymmetricCipher cipher) {
            this.cipher = cipher;
        }

        @Override
        public GeneralSettings onBeforeConvert(final GeneralSettings entity, final String collection) {
            entity.setOpensearchPassword(cipher.encryptField(entity.getOpensearchPassword()));
            return entity;
        }

        @Override
        public GeneralSettings onAfterSave(final GeneralSettings entity, final org.bson.Document document, final String collection) {
            entity.setOpensearchPassword(cipher.decryptField(entity.getOpensearchPassword()));
            return entity;
        }

        @Override
        public GeneralSettings onAfterConvert(final GeneralSettings entity, final org.bson.Document document, final String collection) {
            entity.setOpensearchPassword(cipher.decryptField(entity.getOpensearchPassword()));
            return entity;
        }
    }

    /** Lifecycle callbacks that wrap DocumentComment save/load with PII encryption. */
    @Component
    public static class DocumentCommentCallbacks
            implements BeforeConvertCallback<DocumentComment>,
                       AfterSaveCallback<DocumentComment>,
                       AfterConvertCallback<DocumentComment> {

        private final SymmetricCipher cipher;

        public DocumentCommentCallbacks(final SymmetricCipher cipher) {
            this.cipher = cipher;
        }

        @Override
        public DocumentComment onBeforeConvert(final DocumentComment entity, final String collection) {
            entity.setText(cipher.encryptField(entity.getText()));
            return entity;
        }

        @Override
        public DocumentComment onAfterSave(final DocumentComment entity, final org.bson.Document document, final String collection) {
            entity.setText(cipher.decryptField(entity.getText()));
            return entity;
        }

        @Override
        public DocumentComment onAfterConvert(final DocumentComment entity, final org.bson.Document document, final String collection) {
            entity.setText(cipher.decryptField(entity.getText()));
            return entity;
        }
    }

    /**
     * Lifecycle callbacks that wrap {@link RedactionCertificate} save/load with PII
     * encryption. The certificate denormalises two PII-bearing fields out of the
     * Document / Span tables — the document filename (often patient names, MRN-style
     * identifiers, etc.) and the literal span text on each overturn entry. Without
     * this callback those fields land in the {@code redaction_certificates} collection
     * as plaintext while their source tables are encrypted, defeating the at-rest
     * envelope for the denormalised copy. R2-F6 fix.
     */
    @Component
    public static class RedactionCertificateCallbacks
            implements BeforeConvertCallback<RedactionCertificate>,
                       AfterSaveCallback<RedactionCertificate>,
                       AfterConvertCallback<RedactionCertificate> {

        private final SymmetricCipher cipher;

        public RedactionCertificateCallbacks(final SymmetricCipher cipher) {
            this.cipher = cipher;
        }

        @Override
        public RedactionCertificate onBeforeConvert(final RedactionCertificate entity, final String collection) {
            entity.setDocumentFilename(cipher.encryptField(entity.getDocumentFilename()));
            if (entity.getOverturns() != null) {
                for (RedactionCertificate.OverturnEntry o : entity.getOverturns()) {
                    o.setSpanText(cipher.encryptField(o.getSpanText()));
                }
            }
            return entity;
        }

        @Override
        public RedactionCertificate onAfterSave(final RedactionCertificate entity, final org.bson.Document document, final String collection) {
            entity.setDocumentFilename(cipher.decryptField(entity.getDocumentFilename()));
            if (entity.getOverturns() != null) {
                for (RedactionCertificate.OverturnEntry o : entity.getOverturns()) {
                    o.setSpanText(cipher.decryptField(o.getSpanText()));
                }
            }
            return entity;
        }

        @Override
        public RedactionCertificate onAfterConvert(final RedactionCertificate entity, final org.bson.Document document, final String collection) {
            entity.setDocumentFilename(cipher.decryptField(entity.getDocumentFilename()));
            if (entity.getOverturns() != null) {
                for (RedactionCertificate.OverturnEntry o : entity.getOverturns()) {
                    o.setSpanText(cipher.decryptField(o.getSpanText()));
                }
            }
            return entity;
        }
    }
}
