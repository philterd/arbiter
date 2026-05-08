/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.controllers;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves the mkdocs-built documentation site at {@code /docs/**}. The Docker build
 * stage drops the rendered site into the webapp's classpath at
 * {@code static/docs/}, so it travels inside the jar.
 *
 * <p>This controller exists because mkdocs-material uses directory-style URLs
 * (e.g. {@code /docs/getting-started/}), and Spring's stock
 * {@link org.springframework.web.servlet.resource.PathResourceResolver} does not
 * map them to {@code index.html} — the empty trailing path is rejected by
 * {@code ResourceHttpRequestHandler} before any custom resolver runs. Handling
 * the path here keeps the rules explicit.
 */
@Controller
@RequestMapping("/docs")
public class DocsController {

    private static final String CLASSPATH_BASE = "static/docs/";

    @GetMapping({"", "/", "/**"})
    public ResponseEntity<Resource> serve(final HttpServletRequest request) {
        // Strip the leading "/docs" from the path, then normalize so that
        // an empty or trailing-slash path resolves to index.html.
        final String requestPath = request.getRequestURI();
        String relative = requestPath.length() > "/docs".length()
                ? requestPath.substring("/docs".length())
                : "";
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.isEmpty() || relative.endsWith("/")) {
            relative += "index.html";
        }

        final Resource resource = new ClassPathResource(CLASSPATH_BASE + relative);
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }

        final MediaType contentType = MediaTypeFactory.getMediaType(resource.getFilename())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(contentType).body(resource);
    }
}
