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
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListViewTest {

    private static ListView view(final String... params) {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        for (int i = 0; i < params.length; i += 2) {
            request.addParameter(params[i], params[i + 1]);
        }
        return ListView.of(request, "/things", Set.of("name", "created"), "name", List.of("status"));
    }

    @Test
    void defaultsAreTheFirstPageOf25SortedByTheDefaultField() {
        final ListView list = view();
        assertEquals(0, list.offset());
        assertEquals(25, list.limit());
        assertEquals("name", list.sort());
        assertTrue(list.ascending());
        assertNull(list.search());
        assertFalse(list.filtered());
    }

    @Test
    void valuesPhilterWouldRefuseAreReplacedWithDefaults() {
        final ListView list = view("offset", "-5", "limit", "1000", "sort", "password", "order", "sideways");
        assertEquals(0, list.offset());
        assertEquals(100, list.limit(), "Philter's cap");
        assertEquals("name", list.sort(), "only the listed sort fields");
        assertTrue(list.ascending());
        assertEquals(25, view("limit", "zero").limit());
        assertEquals(25, view("limit", "0").limit());
    }

    @Test
    void blankSearchAndFiltersAreIgnoredAndOthersAreTrimmed() {
        assertNull(view("q", "  ", "status", "").search());
        final ListView list = view("q", " jane ", "status", " open ", "unlisted", "x");
        assertEquals("jane", list.search());
        assertEquals("open", list.filter("status"));
        assertNull(list.filter("unlisted"), "only the named filters are read");
        assertTrue(list.filtered());
    }

    @Test
    void optionsCarryThePageSortAndSearchToPhilter() {
        final ListOptions options = view("offset", "50", "limit", "10", "sort", "created", "order", "desc",
                "q", "jane").options();
        assertEquals(50, options.getOffset());
        assertEquals(10, options.getLimit());
        assertEquals("created", options.getSort());
        assertEquals(ListOptions.DESCENDING, options.getOrder());
        assertEquals("jane", options.getSearch());
    }

    @Test
    void pagingLinksKeepTheSearchFiltersOwnerAndSort() {
        final ListView list = view("q", "a+b & c", "status", "open", "sort", "created", "offset", "25")
                .withOwner(new OwnerFilter(true, "jdoe", false)).withTotal(80);
        assertEquals("/things?q=a%2Bb+%26+c&status=open&owner=jdoe&sort=created&order=asc&offset=50",
                list.nextUrl());
        assertEquals("/things?q=a%2Bb+%26+c&status=open&owner=jdoe&sort=created&order=asc", list.previousUrl());
        assertEquals("/things", list.clearUrl());
    }

    @Test
    void positionsAndNeighboursFollowTheTotal() {
        final ListView middle = view("offset", "25").withTotal(60);
        assertEquals(26, middle.first());
        assertEquals(50, middle.last());
        assertTrue(middle.hasPrevious());
        assertTrue(middle.hasNext());
        final ListView end = view("offset", "50").withTotal(60);
        assertEquals(60, end.last());
        assertFalse(end.hasNext());
        final ListView empty = view().withTotal(0);
        assertEquals(0, empty.first());
        assertFalse(empty.hasNext());
        assertTrue(view("offset", "75").withTotal(60).pastEnd());
        assertFalse(empty.pastEnd());
    }

    @Test
    void sortingByTheSameFieldReversesIt() {
        final ListView list = view("offset", "50").withTotal(100);
        assertEquals("/things?sort=name&order=desc", list.sortUrl("name"), "from the first page, reversed");
        assertEquals("/things?sort=created&order=asc", list.sortUrl("created"));
        assertEquals("/things", view("sort", "name", "order", "desc").sortUrl("name"), "back to the default");
    }

    @Test
    void aNonDefaultPageSizeIsKept() {
        final ListView list = view("limit", "50").withTotal(200);
        assertTrue(list.customLimit());
        assertEquals("/things?limit=50&offset=50", list.nextUrl());
    }

    @Test
    void theOwnerFilterIsOnlyForAdministrators() {
        final TestingAuthenticationToken admin = new TestingAuthenticationToken("boss", null, "ROLE_ADMIN");
        final TestingAuthenticationToken user = new TestingAuthenticationToken("jdoe", null, "ROLE_USER");
        final TestingAuthenticationToken auditor = new TestingAuthenticationToken("ann", null, "ROLE_AUDITOR");

        assertEquals(new OwnerFilter(true, "jdoe", false), OwnerFilter.from(admin, " jdoe ", null));
        assertEquals(new OwnerFilter(true, null, true), OwnerFilter.from(admin, "jdoe", "true"), "all wins");
        assertTrue(OwnerFilter.from(admin, "", null).mine());
        assertEquals(OwnerFilter.none(), OwnerFilter.from(user, "boss", "true"), "ignored for others");
        assertEquals(OwnerFilter.none(), OwnerFilter.from(auditor, "boss", null));
        assertEquals(OwnerFilter.none(), OwnerFilter.from(null, "boss", null));
        assertFalse(OwnerFilter.none().available());
    }
}
