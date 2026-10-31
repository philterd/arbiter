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

import ai.philterd.philter.PhilterClient;
import ai.philterd.philter.model.LedgerChain;
import ai.philterd.philter.model.LedgerEntry;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import ai.philterd.workspace.model.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A document's chains in Philter's ledger, read with Philter Workspace's key (#18). */
class DocumentLedgerServiceTest {

    private final PhilterClient client = mock(PhilterClient.class);
    private DocumentLedgerService service;

    @BeforeEach
    void setUp() {
        final PhilterSettings settings = mock(PhilterSettings.class);
        when(settings.client()).thenReturn(client);
        service = new DocumentLedgerService(settings);
    }

    private static LedgerEntry entry(final String id, final int i) {
        final LedgerEntry e = new LedgerEntry();
        e.setDocumentId(id);
        e.setFilename("Jane Roe motion.txt");
        e.setType("ssn");
        e.setToken("123-45-6789");
        e.setReplacement("XXX-XX-6789");
        e.setStartPosition(i * 10L);
        e.setHash("h" + i);
        e.setPreviousHash(i == 0 ? null : "h" + (i - 1));
        e.setTimestamp("2026-10-31T12:00:0" + (i % 10) + ".000Z");
        e.setPolicyName("court");
        e.setPolicyVersion(3);
        e.setSignature(i % 2 == 0 ? "sig" : null);
        e.setSigningKeyId(i % 2 == 0 ? "k1" : null);
        return e;
    }

    /** A chain of {@code total} entries served a page at a time, as Philter does. */
    private void chain(final String id, final int total, final boolean valid) throws Exception {
        when(client.getLedgerChain(eq(id), isNull(), anyInt(), anyInt())).thenAnswer(inv -> {
            final int offset = inv.getArgument(2);
            final int limit = inv.getArgument(3);
            final LedgerChain page = new LedgerChain();
            page.setDocumentId(id);
            page.setValid(valid);
            page.setHashChainValid(true);
            page.setSignaturesValid(valid);
            page.setSignedEntries(valid ? total : total / 2);
            page.setUnsignedEntries(valid ? 0 : total - total / 2);
            page.setTotal((long) total);
            final List<LedgerEntry> entries = new ArrayList<>();
            for (int i = offset; i < Math.min(total, offset + limit); i++) entries.add(entry(id, i));
            page.setEntries(entries);
            return page;
        });
    }

    private static Document document(final String current, final String... previous) {
        final Document d = new Document();
        d.setId("d1");
        d.setPhilterDocumentId(current);
        d.setPreviousPhilterDocumentIds(new ArrayList<>(Arrays.asList(previous)));
        return d;
    }

    @Test
    void everyRedactionsChainIsReadAPageAtATimeEarliestFirst() throws Exception {
        chain("p-old", 3, true);
        chain("p-new", 250, false);

        final DocumentLedgerService.Ledger ledger = service.forDocument(document("p-new", "p-old"));

        assertEquals(List.of("p-old", "p-new"), ledger.chains().stream()
                .map(DocumentLedgerService.Chain::philterDocumentId).toList());
        assertEquals(253, ledger.entries().size(), "all of a chain longer than one page");
        final DocumentLedgerService.Chain old = ledger.chains().get(0);
        assertEquals("OK", old.state());
        assertTrue(old.valid());
        assertEquals("Verified: the hash chain and every signature check.", old.summary());
        final DocumentLedgerService.Chain current = ledger.chains().get(1);
        assertFalse(current.valid());
        assertEquals(250, current.total());
        assertFalse(current.truncated());
        assertEquals("Not verified: not every entry has a valid signature.", current.summary());

        final DocumentLedgerService.Entry first = ledger.entries().get(0);
        assertEquals("p-old", first.philterDocumentId());
        assertEquals(Instant.parse("2026-10-31T12:00:00Z"), first.timestamp());
        assertEquals("ssn", first.type());
        assertEquals("court", first.policyName());
        assertTrue(first.signed());
        assertFalse(ledger.entries().get(1).signed());
    }

    @Test
    void anEntryCarriesNoOriginalValueReplacementOrFilename() throws Exception {
        chain("p1", 1, true);
        final DocumentLedgerService.Entry e = service.forDocument(document("p1")).entries().get(0);
        final String text = e.toString();
        assertFalse(text.contains("123-45-6789") || text.contains("6789") || text.contains("Jane Roe"), text);
        assertTrue(Arrays.stream(DocumentLedgerService.Entry.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .noneMatch(n -> n.equals("token") || n.equals("replacement") || n.equals("filename")));
    }

    @Test
    void aLongChainIsReadUpToTheCapAndSaysSo() throws Exception {
        chain("p1", DocumentLedgerService.MAX_ENTRIES_PER_CHAIN + 150, true);
        final DocumentLedgerService.Ledger ledger = service.forDocument(document("p1"));
        assertEquals(DocumentLedgerService.MAX_ENTRIES_PER_CHAIN, ledger.entries().size());
        assertTrue(ledger.chains().get(0).truncated());
    }

    @Test
    void withoutATotalEveryPageIsReadUntilAShortOne() throws Exception {
        when(client.getLedgerChain(eq("p1"), isNull(), anyInt(), anyInt())).thenAnswer(inv -> {
            final int offset = inv.getArgument(2);
            final LedgerChain page = new LedgerChain();
            page.setValid(true);
            final List<LedgerEntry> entries = new ArrayList<>();
            for (int i = offset; i < Math.min(230, offset + (int) inv.getArgument(3)); i++) entries.add(entry("p1", i));
            page.setEntries(entries);
            return page;
        });
        final DocumentLedgerService.Ledger ledger = service.forDocument(document("p1"));
        assertEquals(230, ledger.entries().size());
        assertFalse(ledger.chains().get(0).truncated());
    }

    @Test
    void whyAChainCouldNotBeReadIsReported() throws Exception {
        when(client.getLedgerChain(eq("p0"), isNull(), anyInt(), anyInt()))
                .thenThrow(new ClientException("Not Found", 404, null));
        when(client.getLedgerChain(eq("p1"), isNull(), anyInt(), anyInt()))
                .thenThrow(new ClientException("Forbidden", 403, null, "insufficient_scope"));
        when(client.getLedgerChain(eq("p2"), isNull(), anyInt(), anyInt()))
                .thenThrow(new UnauthorizedException("no"));
        when(client.getLedgerChain(eq("p3"), isNull(), anyInt(), anyInt()))
                .thenThrow(new java.io.IOException("connection refused"));
        final DocumentLedgerService.Ledger ledger = service.forDocument(document("p3", "p0", "p1", "p2"));
        assertEquals(List.of("NO_CHAIN", "KEY_LACKS_SCOPE", "KEY_REJECTED", "UNREACHABLE"),
                ledger.chains().stream().map(DocumentLedgerService.Chain::state).toList());
        assertTrue(ledger.chains().get(1).summary().contains("ledger:read"));
        assertTrue(ledger.entries().isEmpty());
        assertNull(ledger.chains().get(0).valid());
    }

    @Test
    void aChainPhilterCouldNotValidateIsNotVerified() throws Exception {
        final LedgerChain unreadable = new LedgerChain();
        unreadable.setValid(false);
        unreadable.setValidationError("An entry could not be read or checked.");
        unreadable.setEntries(List.of());
        when(client.getLedgerChain(eq("p1"), isNull(), anyInt(), anyInt())).thenReturn(unreadable);
        final DocumentLedgerService.Chain chain = service.forDocument(document("p1")).chains().get(0);
        assertEquals("OK", chain.state());
        assertEquals("Not verified: Philter could not check the chain.", chain.summary());
    }

    @Test
    void aDocumentPhilterNeverRedactedHasNoChains() throws Exception {
        final DocumentLedgerService.Ledger ledger = service.forDocument(document(null));
        assertTrue(ledger.chains().isEmpty());
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).getLedgerChain(any(), any(), any(), any());
    }

    @Test
    void timestampsWithAnOffsetOrNoneAtAllAreHandled() {
        assertEquals(Instant.parse("2026-10-31T16:00:00Z"), DocumentLedgerService.parse("2026-10-31T12:00:00-04:00"));
        assertNull(DocumentLedgerService.parse("yesterday"));
        assertNull(DocumentLedgerService.parse(null));
    }
}
