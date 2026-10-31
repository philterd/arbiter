/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.model.AuditLog;
import ai.philterd.workspace.service.AuditTrailService;
import ai.philterd.workspace.service.DocumentLedgerService;
import ai.philterd.workspace.util.Csv;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes an {@link AuditTrailService.Trail} (#18) as CSV or JSON. Each ledger chain's verification comes first, as
 * a {@code PHILTER_LEDGER_CHAIN} row in CSV and under {@code chains} in JSON, then every event oldest first.
 */
@Component
public class AuditTrailWriter {

    static final String SOURCE_CHAIN = "PHILTER_LEDGER_CHAIN";
    static final String CSV_HEADER = "timestamp,source,documentId,actor,action,outcome,resourceType,resourceId,"
            + "details,philterDocumentId,piiType,startPosition,hash,previousHash,documentHash,policyName,"
            + "policyVersion,signed,signingKeyId,readError,chainVerified\n";

    private final ObjectMapper objectMapper;

    public AuditTrailWriter(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Sends the trail as a download named {@code <name>.csv} or {@code <name>.json}. */
    public void send(final AuditTrailService.Trail trail, final Map<String, Object> scope, final String format,
                     final String name, final HttpServletResponse response) throws IOException {
        final boolean csv = "csv".equals(format);
        response.setContentType(csv ? "text/csv" : "application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + name + (csv ? ".csv" : ".json") + "\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        try (Writer w = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
            if (csv) {
                csv(trail, w);
            } else {
                w.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json(trail, scope)));
            }
        }
    }

    /** {@code csv} or {@code json}, or null for anything else. */
    static String format(final String raw) {
        final String f = raw == null ? "csv" : raw.trim().toLowerCase();
        return "csv".equals(f) || "json".equals(f) ? f : null;
    }

    void csv(final AuditTrailService.Trail trail, final Writer w) throws IOException {
        w.write(CSV_HEADER);
        final Map<String, Boolean> verified = new LinkedHashMap<>();
        for (AuditTrailService.DocumentChain dc : trail.chains()) {
            final DocumentLedgerService.Chain c = dc.chain();
            verified.put(c.philterDocumentId(), verified(c));
            row(w, "", SOURCE_CHAIN, dc.documentId(), "", "CHAIN_VERIFICATION", outcome(c), "", "",
                    json(chainDetails(c)), c.philterDocumentId(), "", "", "", "", "", "", "", "", "", "",
                    String.valueOf(verified(c)));
        }
        for (AuditTrailService.Event e : trail.events()) {
            final String at = e.timestamp() == null ? "" : e.timestamp().toString();
            if (e.workspace() != null) {
                final AuditLog log = e.workspace();
                row(w, at, e.source(), nz(e.documentId()), nz(log.getUserEmail()), nz(log.getAction()),
                        nz(log.getOutcome()), nz(log.getResourceType()), nz(log.getResourceId()),
                        log.getDetails() == null ? "" : json(log.getDetails()),
                        "", "", "", "", "", "", "", "", "", "", "", "");
            } else {
                final DocumentLedgerService.Entry l = e.ledger();
                final Boolean chainVerified = verified.get(l.philterDocumentId());
                row(w, at, e.source(), nz(e.documentId()), "", action(l), "", "", "", "",
                        l.philterDocumentId(), nz(l.type()), String.valueOf(l.startPosition()), nz(l.hash()),
                        nz(l.previousHash()), nz(l.documentHash()), nz(l.policyName()),
                        String.valueOf(l.policyVersion()), String.valueOf(l.signed()), nz(l.signingKeyId()),
                        nz(l.readError()), chainVerified == null ? "" : String.valueOf(chainVerified));
            }
        }
        if (trail.eventsTruncated() || trail.documentsTruncated()) {
            row(w, "", "PHILTER_WORKSPACE", "", "", "TRAIL_TRUNCATED", "", "", "", json(truncation(trail)),
                    "", "", "", "", "", "", "", "", "", "", "", "");
        }
    }

    Map<String, Object> json(final AuditTrailService.Trail trail, final Map<String, Object> scope) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now().toString());
        out.put("scope", scope);
        out.putAll(truncation(trail));
        final List<Map<String, Object>> chains = new ArrayList<>();
        for (AuditTrailService.DocumentChain dc : trail.chains()) {
            final Map<String, Object> c = new LinkedHashMap<>();
            c.put("documentId", dc.documentId());
            c.put("philterDocumentId", dc.chain().philterDocumentId());
            c.putAll(chainDetails(dc.chain()));
            chains.add(c);
        }
        out.put("chains", chains);
        final List<Map<String, Object>> events = new ArrayList<>();
        for (AuditTrailService.Event e : trail.events()) {
            final Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("timestamp", e.timestamp() == null ? null : e.timestamp().toString());
            ev.put("source", e.source());
            ev.put("documentId", e.documentId());
            if (e.workspace() != null) {
                final AuditLog log = e.workspace();
                ev.put("actor", log.getUserEmail());
                ev.put("action", log.getAction());
                ev.put("outcome", log.getOutcome());
                ev.put("resourceType", log.getResourceType());
                ev.put("resourceId", log.getResourceId());
                ev.put("ipAddress", log.getIpAddress());
                ev.put("details", log.getDetails());
            } else {
                final DocumentLedgerService.Entry l = e.ledger();
                ev.put("action", action(l));
                ev.put("philterDocumentId", l.philterDocumentId());
                ev.put("piiType", l.type());
                ev.put("startPosition", l.startPosition());
                ev.put("hash", l.hash());
                ev.put("previousHash", l.previousHash());
                ev.put("documentHash", l.documentHash());
                ev.put("effectiveHash", l.effectiveHash());
                ev.put("policyName", l.policyName());
                ev.put("policyVersion", l.policyVersion());
                ev.put("policyContentHash", l.policyContentHash());
                ev.put("signed", l.signed());
                ev.put("signingKeyId", l.signingKeyId());
                ev.put("readError", l.readError());
            }
            events.add(ev);
        }
        out.put("events", events);
        return out;
    }

    private static Map<String, Object> truncation(final AuditTrailService.Trail trail) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventsTruncated", trail.eventsTruncated());
        out.put("documentsTruncated", trail.documentsTruncated());
        return out;
    }

    static Map<String, Object> chainDetails(final DocumentLedgerService.Chain c) {
        final Map<String, Object> d = new LinkedHashMap<>();
        d.put("state", c.state());
        d.put("verified", verified(c));
        d.put("hashChainValid", c.hashChainValid());
        d.put("signaturesValid", c.signaturesValid());
        d.put("signedEntries", c.signedEntries());
        d.put("unsignedEntries", c.unsignedEntries());
        d.put("validationError", c.validationError());
        d.put("entries", c.total());
        d.put("entriesTruncated", c.truncated());
        d.put("summary", c.summary());
        return d;
    }

    /** Philter opens each chain with an entry that records no redaction. */
    private static String action(final DocumentLedgerService.Entry l) {
        return "[genesis]".equals(l.previousHash()) ? "CHAIN_START" : "REDACTION";
    }

    private static boolean verified(final DocumentLedgerService.Chain c) {
        return DocumentLedgerService.STATE_OK.equals(c.state()) && Boolean.TRUE.equals(c.valid());
    }

    private static String outcome(final DocumentLedgerService.Chain c) {
        if (!DocumentLedgerService.STATE_OK.equals(c.state())) return c.state();
        return verified(c) ? "VERIFIED" : "NOT_VERIFIED";
    }

    private String json(final Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return "";
        }
    }

    private static String nz(final String s) {
        return s == null ? "" : s;
    }

    private static void row(final Writer w, final String... fields) throws IOException {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) w.write(',');
            w.write(Csv.escapeField(fields[i]));
        }
        w.write('\n');
    }
}
