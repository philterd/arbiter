/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.ui;

import ai.philterd.philter.model.ListOptions;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One list page of items kept in Philter: its offset and limit, sort, search, filters, and owner, read
 * from the request, and once the page is loaded, Philter's total. The {@code fragments/list} fragments
 * render from it, and every link it builds keeps the search, filters, owner, and sort, so they survive
 * paging. Values Philter would refuse (a negative offset, a limit over 100, an unknown sort field) are
 * replaced with defaults rather than passed on.
 */
public final class ListView {

    public static final int DEFAULT_LIMIT = 25;
    /** Philter caps a page at 100 items. */
    public static final int MAX_LIMIT = 100;
    private static final int MAX_PARAM_LENGTH = 200;

    private final String path;
    private final int offset;
    private final int limit;
    private final String sort;
    private final boolean ascending;
    private final String defaultSort;
    private final String search;
    private final Map<String, String> filters;
    private final OwnerFilter owner;
    private final long total;

    private ListView(final String path, final int offset, final int limit, final String sort,
                     final boolean ascending, final String defaultSort, final String search,
                     final Map<String, String> filters, final OwnerFilter owner, final long total) {
        this.path = path;
        this.offset = offset;
        this.limit = limit;
        this.sort = sort;
        this.ascending = ascending;
        this.defaultSort = defaultSort;
        this.search = search;
        this.filters = filters;
        this.owner = owner;
        this.total = total;
    }

    /**
     * Reads the list state from {@code request}: {@code offset}, {@code limit}, {@code sort} (one of
     * {@code sortFields}, else {@code defaultSort}), {@code order} ({@code asc} or {@code desc}), {@code q},
     * and each of {@code filterNames}. The owner is {@link OwnerFilter#none()} until {@link #withOwner}.
     */
    public static ListView of(final HttpServletRequest request, final String path, final Set<String> sortFields,
                              final String defaultSort, final List<String> filterNames) {
        final int offset = Math.max(0, intParam(request, "offset", 0));
        final int requestedLimit = intParam(request, "limit", DEFAULT_LIMIT);
        final int limit = requestedLimit < 1 ? DEFAULT_LIMIT : Math.min(requestedLimit, MAX_LIMIT);
        final String requestedSort = text(request.getParameter("sort"));
        final String sort = requestedSort != null && sortFields.contains(requestedSort) ? requestedSort : defaultSort;
        final boolean ascending = !"desc".equalsIgnoreCase(text(request.getParameter("order")));
        final Map<String, String> filters = new LinkedHashMap<>();
        for (final String name : filterNames) {
            final String value = text(request.getParameter(name));
            if (value != null) {
                filters.put(name, value);
            }
        }
        return new ListView(path, offset, limit, sort, ascending, defaultSort, text(request.getParameter("q")),
                Collections.unmodifiableMap(filters), OwnerFilter.none(), 0);
    }

    /** The same view with the owner filter that applies to this person. */
    public ListView withOwner(final OwnerFilter ownerFilter) {
        return new ListView(path, offset, limit, sort, ascending, defaultSort, search, filters, ownerFilter, total);
    }

    /** The same view once Philter has said how many items match. */
    public ListView withTotal(final long matching) {
        return new ListView(path, offset, limit, sort, ascending, defaultSort, search, filters, owner,
                Math.max(0, matching));
    }

    /** The offset, limit, sort, order, and search, for Philter's list call. Filters are the caller's to add. */
    public ListOptions options() {
        final ListOptions options = new ListOptions().offset(offset).limit(limit).search(search);
        if (sort != null) {
            options.sort(sort);
            if (ascending) {
                options.ascending();
            } else {
                options.descending();
            }
        }
        return options;
    }

    public String path() {
        return path;
    }

    public int offset() {
        return offset;
    }

    public int limit() {
        return limit;
    }

    /** Whether the page size differs from {@link #DEFAULT_LIMIT}, so forms must carry it. */
    public boolean customLimit() {
        return limit != DEFAULT_LIMIT;
    }

    public String sort() {
        return sort;
    }

    public boolean ascending() {
        return ascending;
    }

    public String search() {
        return search;
    }

    /** A filter's value, or null when it is not set. */
    public String filter(final String name) {
        return filters.get(name);
    }

    public Map<String, String> filters() {
        return filters;
    }

    public OwnerFilter owner() {
        return owner;
    }

    public long total() {
        return total;
    }

    /** Whether a search, filter, or owner other than the person's own narrows the list. */
    public boolean filtered() {
        return search != null || !filters.isEmpty() || !owner.mine();
    }

    /** The 1-based position of the first item shown, or 0 when the page is empty. */
    public long first() {
        return offset < total ? offset + 1L : 0;
    }

    /** The 1-based position of the last item shown. */
    public long last() {
        return Math.min(offset + (long) limit, total);
    }

    public boolean hasPrevious() {
        return offset > 0;
    }

    public boolean hasNext() {
        return offset + (long) limit < total;
    }

    /** True when the offset is past the end, for example after the last item on a page was deleted. */
    public boolean pastEnd() {
        return total > 0 && offset >= total;
    }

    public String previousUrl() {
        return url(Math.max(0, offset - limit), sort, ascending);
    }

    public String nextUrl() {
        return url(offset + limit, sort, ascending);
    }

    public String firstUrl() {
        return url(0, sort, ascending);
    }

    public boolean sortedBy(final String field) {
        return field.equals(sort);
    }

    /** Sorts by {@code field}, reversing the order when the list is already sorted by it, from the first page. */
    public String sortUrl(final String field) {
        return url(0, field, !sortedBy(field) || !ascending);
    }

    /** The path with everything but the offset, sort, and order: the action of the filter bar's form. */
    public String clearUrl() {
        return path;
    }

    private String url(final int at, final String sortField, final boolean asc) {
        final Map<String, String> params = new LinkedHashMap<>();
        if (search != null) {
            params.put("q", search);
        }
        params.putAll(filters);
        params.putAll(owner.params());
        if (sortField != null && !(sortField.equals(defaultSort) && asc)) {
            params.put("sort", sortField);
            params.put("order", asc ? "asc" : "desc");
        }
        if (limit != DEFAULT_LIMIT) {
            params.put("limit", Integer.toString(limit));
        }
        if (at > 0) {
            params.put("offset", Integer.toString(at));
        }
        final StringBuilder url = new StringBuilder(path);
        char separator = '?';
        for (final Map.Entry<String, String> param : params.entrySet()) {
            url.append(separator).append(encode(param.getKey())).append('=').append(encode(param.getValue()));
            separator = '&';
        }
        return url.toString();
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String text(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_PARAM_LENGTH ? trimmed.substring(0, MAX_PARAM_LENGTH) : trimmed;
    }

    private static int intParam(final HttpServletRequest request, final String name, final int fallback) {
        final String value = text(request.getParameter(name));
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
