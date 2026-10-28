/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp;

import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.workspace.webapp.ui.Confirmation;
import ai.philterd.workspace.webapp.ui.FormErrors;
import ai.philterd.workspace.webapp.ui.JsonInput;
import ai.philterd.workspace.webapp.ui.ListView;
import ai.philterd.workspace.webapp.ui.OwnerFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Renders test-fragments.html, standing in for Philter: 60 things, searched by name, filtered by status
 * (only "open" matches anything), and sorted by name, a page at a time.
 */
@Controller
class FragmentsTestController {

    static final String PATH = "/test/fragments";
    /** What Philter's error message repeats back in the "fail" case: submitted text. */
    static final String SUBMITTED = "Jane Q. Example";

    private static final List<String> THINGS = IntStream.rangeClosed(1, 60)
            .mapToObj(i -> String.format("thing-%02d", i)).toList();

    @GetMapping(PATH)
    String page(final Authentication authentication, final HttpServletRequest request, final Model model) {
        final ListView query = ListView.of(request, PATH, Set.of("name"), "name", List.of("status"))
                .withOwner(OwnerFilter.from(authentication, request.getParameter("owner"),
                        request.getParameter("all")));
        final List<String> matching = THINGS.stream()
                .filter(t -> query.search() == null || t.contains(query.search()))
                .filter(t -> query.filter("status") == null || "open".equals(query.filter("status")))
                .sorted(query.ascending() ? Comparator.naturalOrder() : Comparator.reverseOrder())
                .toList();
        final ListView list = query.withTotal(matching.size());
        model.addAttribute("list", list);
        model.addAttribute("things", matching.subList(Math.min(list.offset(), matching.size()),
                (int) Math.min(list.last(), matching.size())));
        if (request.getParameter("fail") != null) {
            model.addAttribute("errors", FormErrors.from(new ClientException("HTTP 400: " + SUBMITTED, 400,
                    SUBMITTED, "invalid_request", "req-1", "name")));
        }
        final String json = request.getParameter("json");
        model.addAttribute("policyJson", json == null ? "{}" : json);
        model.addAttribute("policyError", json == null ? null : JsonInput.objectError(json));
        model.addAttribute("confirmation", new Confirmation("Delete thing-01?", "It cannot be undone.",
                List.of("thing-01"), PATH + "/thing-01/delete", "Delete thing", PATH + "/thing-01/confirm",
                PATH, java.util.Map.of("version", "7")));
        return "test-fragments";
    }
}
