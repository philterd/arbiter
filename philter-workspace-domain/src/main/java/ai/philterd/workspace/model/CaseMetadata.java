/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A court filing's case metadata (#16), supplied with the document. The case type can choose the policy Philter
 * redacts the document with. {@link #caseNumber} and {@link #filer} can identify people and are encrypted at rest;
 * {@link #caseType}, {@link #feeStatus}, and {@link #ifp} are not.
 */
public class CaseMetadata {

    public static final int MAX_CASE_NUMBER = 100;
    public static final int MAX_CASE_TYPE = 64;
    public static final int MAX_FILER = 200;
    /** A case type is a short name such as {@code civil} or {@code criminal}. */
    private static final Pattern CASE_TYPE = Pattern.compile("[a-z0-9][a-z0-9 _-]*");
    public static final Set<String> FEE_STATUSES = Set.of("paid", "unpaid", "waived");

    private String caseNumber;
    private String caseType;
    private String filer;
    private String feeStatus;
    /** Whether the filer proceeds in forma pauperis. Null when not given. */
    private Boolean ifp;

    public CaseMetadata() {
    }

    public CaseMetadata(final String caseNumber, final String caseType, final String filer, final String feeStatus,
                        final Boolean ifp) {
        this.caseNumber = caseNumber;
        this.caseType = caseType;
        this.filer = filer;
        this.feeStatus = feeStatus;
        this.ifp = ifp;
    }

    /** Thrown with every problem found, as messages for the person who supplied the metadata. */
    public static class InvalidException extends IllegalArgumentException {
        private final List<String> problems;

        public InvalidException(final List<String> problems) {
            super(String.join(" ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    /** A case type as stored and matched: trimmed and lower-cased. */
    public static String normalizeCaseType(final String caseType) {
        return caseType == null ? null : caseType.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The metadata trimmed, with the case type and fee status lower-cased and blanks made null, or null when nothing
     * is given.
     *
     * @throws InvalidException if a value is too long or not one of those allowed
     */
    public static CaseMetadata of(final String caseNumber, final String caseType, final String filer,
                                  final String feeStatus, final Boolean ifp) {
        final CaseMetadata m = new CaseMetadata(blankToNull(caseNumber), blankToNull(normalizeCaseType(caseType)),
                blankToNull(filer), blankToNull(feeStatus == null ? null : feeStatus.trim().toLowerCase(Locale.ROOT)),
                ifp);
        final List<String> problems = new ArrayList<>();
        if (m.caseNumber != null && m.caseNumber.length() > MAX_CASE_NUMBER) {
            problems.add("The case number may be up to " + MAX_CASE_NUMBER + " characters.");
        }
        if (m.caseType != null && (m.caseType.length() > MAX_CASE_TYPE || !CASE_TYPE.matcher(m.caseType).matches())) {
            problems.add("The case type must be up to " + MAX_CASE_TYPE + " letters, digits, spaces, hyphens, or "
                    + "underscores, starting with a letter or digit.");
        }
        if (m.filer != null && m.filer.length() > MAX_FILER) {
            problems.add("The filer may be up to " + MAX_FILER + " characters.");
        }
        if (m.feeStatus != null && !FEE_STATUSES.contains(m.feeStatus)) {
            problems.add("The fee status must be paid, unpaid, or waived.");
        }
        if (!problems.isEmpty()) {
            throw new InvalidException(problems);
        }
        return m.hasNoValues() ? null : m;
    }

    /** Whether no value is set. */
    public boolean hasNoValues() {
        return caseNumber == null && caseType == null && filer == null && feeStatus == null && ifp == null;
    }

    private static String blankToNull(final String s) {
        if (s == null) return null;
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public String getCaseNumber() { return caseNumber; }
    public void setCaseNumber(final String caseNumber) { this.caseNumber = caseNumber; }

    public String getCaseType() { return caseType; }
    public void setCaseType(final String caseType) { this.caseType = caseType; }

    public String getFiler() { return filer; }
    public void setFiler(final String filer) { this.filer = filer; }

    public String getFeeStatus() { return feeStatus; }
    public void setFeeStatus(final String feeStatus) { this.feeStatus = feeStatus; }

    public Boolean getIfp() { return ifp; }
    public void setIfp(final Boolean ifp) { this.ifp = ifp; }
}
