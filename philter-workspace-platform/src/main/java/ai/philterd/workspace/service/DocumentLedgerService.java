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

import ai.philterd.philter.model.LedgerChain;
import ai.philterd.philter.model.LedgerEntry;
import ai.philterd.philter.model.exceptions.PhilterError;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.philter.PhilterClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * A document's entries in Philter's redaction ledger (#18), read with Philter Workspace's own API key, which owns
 * the chains of the documents it redacted, so no cross-user access is needed. The key needs the {@code ledger:read}
 * scope.
 *
 * <p>Each entry keeps only what identifies and proves it: the PII type, position, hashes, timestamp, policy, and
 * signing key. The original value is never read (that needs {@code ledger:export}), and the replacement and the
 * filename are dropped, since a replacement can be reversible (encryption) or guessable (a hash of an SSN), and a
 * filename can name a person.
 */
@Service
public class DocumentLedgerService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentLedgerService.class);

    /** Philter's largest page of a chain. */
    static final int PAGE = 100;
    /** The most entries read from one chain; a chain has one entry per redacted span. */
    static final int MAX_ENTRIES_PER_CHAIN = 5000;

    public static final String STATE_OK = "OK";
    public static final String STATE_NO_CHAIN = "NO_CHAIN";
    public static final String STATE_KEY_LACKS_SCOPE = "KEY_LACKS_SCOPE";
    public static final String STATE_KEY_REJECTED = "KEY_REJECTED";
    public static final String STATE_UNREACHABLE = "UNREACHABLE";

    private final PhilterSettings philter;

    public DocumentLedgerService(final PhilterSettings philter) {
        this.philter = philter;
    }

    /**
     * One redaction's chain: how it verified, or why it could not be read. {@code valid} is Philter's verdict for the
     * whole chain, which holds only when the hash chain and every signature verify.
     */
    public record Chain(String philterDocumentId, String state, Boolean valid, Boolean hashChainValid,
                        Boolean signaturesValid, Integer signedEntries, Integer unsignedEntries,
                        String validationError, long total, boolean truncated) {

        /** In words, for the page and the exports. */
        public String summary() {
            return switch (state) {
                case STATE_OK -> {
                    if (Boolean.TRUE.equals(valid)) {
                        yield "Verified: the hash chain and every signature check.";
                    }
                    if (validationError != null) {
                        yield "Not verified: Philter could not check the chain.";
                    }
                    final List<String> failed = new ArrayList<>();
                    if (Boolean.FALSE.equals(hashChainValid)) failed.add("the hash chain does not check");
                    if (Boolean.FALSE.equals(signaturesValid)) failed.add("not every entry has a valid signature");
                    yield "Not verified: " + (failed.isEmpty() ? "Philter reports the chain invalid"
                            : String.join(", and ", failed)) + ".";
                }
                case STATE_NO_CHAIN -> "No ledger chain: the batch's context does not keep Philter's ledger, or the "
                        + "chain was deleted.";
                case STATE_KEY_LACKS_SCOPE -> "Not read: PHILTER_WORKSPACE_PHILTER_API_KEY needs the ledger:read "
                        + "scope.";
                case STATE_KEY_REJECTED -> "Not read: Philter rejected PHILTER_WORKSPACE_PHILTER_API_KEY.";
                default -> "Not read: Philter could not be reached.";
            };
        }
    }

    /** One ledger entry, without its original value, replacement, or filename. */
    public record Entry(String philterDocumentId, Instant timestamp, String type, long startPosition, String hash,
                        String previousHash, String documentHash, String effectiveHash, String policyName,
                        int policyVersion, String policyContentHash, String signingKeyId, boolean signed,
                        String readError) {
    }

    /** Every chain of a document, the current redaction's last, and their entries in chain order. */
    public record Ledger(List<Chain> chains, List<Entry> entries) {
    }

    /**
     * The chains of every redaction of the document: earlier ones first, then the current one. A document Philter
     * never redacted has none.
     */
    public Ledger forDocument(final Document document) {
        final List<String> ids = new ArrayList<>();
        if (document.getPreviousPhilterDocumentIds() != null) {
            document.getPreviousPhilterDocumentIds().stream().filter(id -> id != null && !id.isBlank())
                    .forEach(ids::add);
        }
        if (document.getPhilterDocumentId() != null && !document.getPhilterDocumentId().isBlank()) {
            ids.add(document.getPhilterDocumentId());
        }
        final List<Chain> chains = new ArrayList<>();
        final List<Entry> entries = new ArrayList<>();
        for (String id : ids) {
            chains.add(read(id, entries));
        }
        return new Ledger(chains, entries);
    }

    private Chain read(final String philterDocumentId, final List<Entry> into) {
        LedgerChain first = null;
        final List<Entry> read = new ArrayList<>();
        try {
            int offset = 0;
            while (true) {
                final LedgerChain page = philter.client().getLedgerChain(philterDocumentId, null, offset, PAGE);
                if (first == null) first = page;
                final List<LedgerEntry> got = page.getEntries() == null ? List.of() : page.getEntries();
                for (LedgerEntry e : got) {
                    read.add(entry(philterDocumentId, e));
                }
                offset += got.size();
                // Without a total, a short page is the last one.
                final boolean last = page.getTotal() == null ? got.size() < PAGE : offset >= page.getTotal();
                if (got.isEmpty() || last || offset >= MAX_ENTRIES_PER_CHAIN) break;
            }
        } catch (Exception e) {
            final String state = stateFor(e);
            if (!STATE_NO_CHAIN.equals(state)) {
                LOG.warn("Could not read Philter's ledger chain for {}: {}", philterDocumentId,
                        PhilterClients.describe(e));
            }
            return new Chain(philterDocumentId, state, null, null, null, null, null, null, 0, false);
        }
        into.addAll(read);
        final long total = first.getTotal() == null ? read.size() : first.getTotal();
        // Stopped at the cap, whether or not Philter said how many there are.
        final boolean truncated = read.size() < total
                || (first.getTotal() == null && read.size() >= MAX_ENTRIES_PER_CHAIN);
        return new Chain(philterDocumentId, STATE_OK, first.isValid(), first.getHashChainValid(),
                first.getSignaturesValid(), first.getSignedEntries(), first.getUnsignedEntries(),
                first.getValidationError(), total, truncated);
    }

    private static String stateFor(final Exception e) {
        if (e instanceof PhilterError error) {
            return switch (error.getStatusCode()) {
                case 404 -> STATE_NO_CHAIN;
                case 403 -> STATE_KEY_LACKS_SCOPE;
                case 401 -> STATE_KEY_REJECTED;
                default -> STATE_UNREACHABLE;
            };
        }
        return STATE_UNREACHABLE;
    }

    private static Entry entry(final String philterDocumentId, final LedgerEntry e) {
        return new Entry(philterDocumentId, parse(e.getTimestamp()), e.getType(), e.getStartPosition(), e.getHash(),
                e.getPreviousHash(), e.getDocumentHash(), e.getEffectiveHash(), e.getPolicyName(),
                e.getPolicyVersion(), e.getPolicyContentHash(), e.getSigningKeyId(),
                e.getSignature() != null && !e.getSignature().isBlank(), e.getReadError());
    }

    static Instant parse(final String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return null;
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(timestamp).toInstant();
            } catch (DateTimeParseException again) {
                return null;
            }
        }
    }
}
