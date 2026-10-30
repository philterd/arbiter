/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.service;

import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.DocumentComment;
import ai.philterd.workspace.model.GeneralSettings;
import ai.philterd.workspace.model.PendingUpload;
import ai.philterd.workspace.model.RedactionCertificate;
import ai.philterd.workspace.model.Span;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertCallback;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveCallback;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;

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
 *     <li>{@link PendingUpload#getContent() PendingUpload.content}: the bytes of an upload
 *         waiting in the ingest queue, with {@link SymmetricCipher#encryptBytes}.</li>
 * </ul>
 *
 * <h3>How it works</h3>
 * <p>{@link #encrypt} and {@link #decrypt} hold the field list and are store-neutral.
 * Each store calls them around its writes and reads; for MongoDB, the callback classes
 * below do so from Spring Data's lifecycle callbacks:
 * <ul>
 *     <li><strong>BeforeConvertCallback</strong> encrypts just before the entity is turned
 *         into BSON, so Mongo writes ciphertext.</li>
 *     <li><strong>AfterSaveCallback</strong> restores plaintext on the caller's entity, which
 *         would otherwise hold the ciphertext written into it.</li>
 *     <li><strong>AfterConvertCallback</strong> decrypts every entity read back.</li>
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

    /**
     * Encrypt the PII fields of {@code entity} in place. Entities with no PII fields are left
     * alone. Every store calls this before writing an entity.
     */
    public static void encrypt(final SymmetricCipher cipher, final Object entity) {
        if (entity instanceof PendingUpload pu) {
            pu.setContent(cipher.encryptBytes(pu.getContent()));
            return;
        }
        apply(entity, cipher::encryptField);
    }

    /** Decrypt the PII fields of {@code entity} in place. Every store calls this after reading or writing an entity. */
    public static void decrypt(final SymmetricCipher cipher, final Object entity) {
        if (entity instanceof PendingUpload pu) {
            pu.setContent(cipher.decryptBytes(pu.getContent()));
            return;
        }
        apply(entity, cipher::decryptField);
    }

    private static void apply(final Object entity, final UnaryOperator<String> f) {
        if (entity instanceof Document d) {
            d.setOriginalText(f.apply(d.getOriginalText()));
            d.setRedactedText(f.apply(d.getRedactedText()));
            d.setFailureMessage(f.apply(d.getFailureMessage()));
        } else if (entity instanceof Span sp) {
            sp.setText(f.apply(sp.getText()));
        } else if (entity instanceof GeneralSettings g) {
            g.setOpensearchPassword(f.apply(g.getOpensearchPassword()));
        } else if (entity instanceof DocumentComment c) {
            c.setText(f.apply(c.getText()));
        } else if (entity instanceof RedactionCertificate rc) {
            rc.setDocumentFilename(f.apply(rc.getDocumentFilename()));
            if (rc.getOverturns() != null) {
                for (RedactionCertificate.OverturnEntry o : rc.getOverturns()) {
                    o.setSpanText(f.apply(o.getSpanText()));
                }
            }
        }
    }

    /**
     * MongoDB lifecycle callbacks around {@link #encrypt} and {@link #decrypt}: encrypt before
     * the entity is turned into BSON, then restore plaintext on the caller's entity after the
     * save, and decrypt every entity read back.
     */
    abstract static class MongoCallbacks<T>
            implements BeforeConvertCallback<T>, AfterSaveCallback<T>, AfterConvertCallback<T> {

        private final SymmetricCipher cipher;

        MongoCallbacks(final SymmetricCipher cipher) {
            this.cipher = cipher;
        }

        @Override
        public T onBeforeConvert(final T entity, final String collection) {
            encrypt(cipher, entity);
            return entity;
        }

        @Override
        public T onAfterSave(final T entity, final org.bson.Document document, final String collection) {
            decrypt(cipher, entity);
            return entity;
        }

        @Override
        public T onAfterConvert(final T entity, final org.bson.Document document, final String collection) {
            decrypt(cipher, entity);
            return entity;
        }
    }

    /** Lifecycle callbacks that wrap Document save/load with PII encryption. */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class DocumentCallbacks extends MongoCallbacks<Document> {

        public DocumentCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }

    /** Lifecycle callbacks that wrap PendingUpload save/load: the queued upload's bytes. */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class PendingUploadCallbacks extends MongoCallbacks<PendingUpload> {

        public PendingUploadCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }

    /** Lifecycle callbacks that wrap Span save/load with PII encryption. */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class SpanCallbacks extends MongoCallbacks<Span> {

        public SpanCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }

    /**
     * Lifecycle callbacks that encrypt the OpenSearch basic-auth password on the
     * {@link GeneralSettings} singleton row. Not strictly PII, but the same secret-at-rest
     * machinery applies — the password is treated like a credential and goes through the
     * same {@code enc:v1:} marker-prefix scheme.
     */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class GeneralSettingsCallbacks extends MongoCallbacks<GeneralSettings> {

        public GeneralSettingsCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }

    /** Lifecycle callbacks that wrap DocumentComment save/load with PII encryption. */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class DocumentCommentCallbacks extends MongoCallbacks<DocumentComment> {

        public DocumentCommentCallbacks(final SymmetricCipher cipher) {
            super(cipher);
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
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class RedactionCertificateCallbacks extends MongoCallbacks<RedactionCertificate> {

        public RedactionCertificateCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }
}
