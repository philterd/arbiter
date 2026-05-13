/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract test: every {@code @PostMapping / @PatchMapping / @PutMapping} on a
 * {@code /api/v1/**} controller that takes a {@code @RequestBody} must declare
 * {@code consumes = MediaType.APPLICATION_JSON_VALUE}.
 *
 * <p>Why this matters as a unit test rather than a code-review rule: the JSON
 * content-type requirement is the load-bearing CSRF defence for the API namespace.
 * A cross-site simple form POST sends {@code application/x-www-form-urlencoded},
 * which the {@code consumes} predicate rejects with 415 before any handler side
 * effects fire. Spring's current default content negotiation also rejects such
 * requests, so missing the explicit declaration is not directly exploitable today
 * — but the asymmetry is dangerous: a framework upgrade, a {@code ContentNegotiation}
 * configurer tweak, or a global {@code @RequestMapping} default could silently flip
 * the defence off only on the endpoints that didn't declare it. Asserting the rule
 * mechanically keeps the contract symmetric across every endpoint and catches new
 * endpoints added in the future.
 */
class ApiV1JsonContentTypeContractTest {

    private static final String API_V1_PREFIX = "/api/v1";
    private static final String SCAN_BASE_PACKAGE = "ai.philterd.arbiter";

    @Test
    void everyApiV1MutatingEndpointWithRequestBodyDeclaresJsonContentType() throws Exception {
        final Set<Class<?>> controllers = findRestControllers();
        assertTrue(!controllers.isEmpty(), "expected to find @RestController classes via component scan");

        final List<String> violations = new ArrayList<>();
        int apiV1MethodsScanned = 0;

        for (Class<?> controller : controllers) {
            final RequestMapping classMapping = controller.getAnnotation(RequestMapping.class);
            if (classMapping == null) continue;
            final String[] classPaths = classMapping.value().length > 0
                    ? classMapping.value() : classMapping.path();
            final boolean isApiV1 = Arrays.stream(classPaths)
                    .anyMatch(p -> p != null && p.startsWith(API_V1_PREFIX));
            if (!isApiV1) continue;

            for (Method method : controller.getDeclaredMethods()) {
                final EndpointSpec spec = inspect(method);
                if (spec == null) continue;
                if (!takesRequestBody(method)) continue;
                apiV1MethodsScanned++;
                if (!spec.consumes.contains(MediaType.APPLICATION_JSON_VALUE)) {
                    violations.add(controller.getSimpleName() + "#" + method.getName()
                            + " (" + spec.httpAnnotation + ") — declared consumes="
                            + spec.consumes + ", expected to include "
                            + MediaType.APPLICATION_JSON_VALUE);
                }
            }
        }

        // Guard against the scan silently finding zero endpoints — the test would otherwise
        // become a permanent green no-op if the package layout or build classpath changes.
        assertTrue(apiV1MethodsScanned >= 5,
                "scan found only " + apiV1MethodsScanned + " /api/v1 endpoints with @RequestBody — "
                        + "expected at least 5; verify the component scan still locates controllers");

        if (!violations.isEmpty()) {
            fail("Found /api/v1 mutating endpoints with @RequestBody that don't declare "
                    + "consumes=application/json — see this test's class javadoc for the CSRF rationale.\n"
                    + "Violations:\n  - " + String.join("\n  - ", violations));
        }
    }

    private static Set<Class<?>> findRestControllers() {
        // useDefaultFilters=false → only @RestController types match, no @Component noise.
        final ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        return scanner.findCandidateComponents(SCAN_BASE_PACKAGE).stream()
                .map(bd -> {
                    try {
                        return Class.forName(bd.getBeanClassName());
                    } catch (ClassNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean takesRequestBody(final Method method) {
        for (Parameter p : method.getParameters()) {
            if (p.isAnnotationPresent(RequestBody.class)) return true;
        }
        return false;
    }

    private record EndpointSpec(String httpAnnotation, List<String> consumes) {}

    private static EndpointSpec inspect(final Method method) {
        final PostMapping post = method.getAnnotation(PostMapping.class);
        if (post != null) return new EndpointSpec("@PostMapping", Arrays.asList(post.consumes()));
        final PatchMapping patch = method.getAnnotation(PatchMapping.class);
        if (patch != null) return new EndpointSpec("@PatchMapping", Arrays.asList(patch.consumes()));
        final PutMapping put = method.getAnnotation(PutMapping.class);
        if (put != null) return new EndpointSpec("@PutMapping", Arrays.asList(put.consumes()));
        return null;
    }
}
