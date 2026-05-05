/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.security;

final class WelcomeMessage {

    private static final String DOCS = "https://philterd.github.io/arbiter/";
    private static final String LINK_CLASS = "class=\"text-blue-600 hover:underline\"";

    private WelcomeMessage() {
    }

    static String html(final boolean admin) {
        final StringBuilder sb = new StringBuilder();
        sb.append("<p class=\"font-semibold mb-2\">Welcome to Arbiter!</p>");
        sb.append("<p class=\"mb-2\">Arbiter is a human-in-the-loop reviewer for redacted documents. ");
        sb.append("Documents flow through three steps:</p>");
        sb.append("<ol class=\"list-decimal ml-6 mb-2 space-y-1\">");
        sb.append("<li><strong>Upload</strong> — submit a document into a batch from the ");
        sb.append("<a ").append(LINK_CLASS).append(" href=\"/upload\">Upload page</a> ");
        sb.append("or via the <a ").append(LINK_CLASS).append(" href=\"")
                .append(DOCS).append("reference/api/\">ingest API</a>.</li>");
        sb.append("<li><strong>Review</strong> — open the ");
        sb.append("<a ").append(LINK_CLASS).append(" href=\"/queue\">Document Queue</a> ");
        sb.append("to inspect proposed redactions, approve or reject spans, and add manual ones.</li>");
        sb.append("<li><strong>Export</strong> — once approved, redacted output is available from ");
        sb.append("the document view.</li>");
        sb.append("</ol>");
        sb.append("<p class=\"mb-2\">Helpful links:</p>");
        sb.append("<ul class=\"list-disc ml-6 space-y-1\">");
        sb.append("<li><a ").append(LINK_CLASS).append(" href=\"")
                .append(DOCS).append("getting-started/\">Getting started</a></li>");
        sb.append("<li><a ").append(LINK_CLASS).append(" href=\"")
                .append(DOCS).append("workflow/\">Workflow overview</a></li>");
        sb.append("<li><a ").append(LINK_CLASS).append(" href=\"")
                .append(DOCS).append("user-guide/uploading/\">Uploading documents</a></li>");
        sb.append("<li><a ").append(LINK_CLASS).append(" href=\"")
                .append(DOCS).append("user-guide/reviewing/\">Reviewing redactions</a></li>");
        sb.append("<li><a ").append(LINK_CLASS).append(" href=\"")
                .append(DOCS).append("user-guide/queue/\">Using the document queue</a></li>");
        if (admin) {
            sb.append("<li><a ").append(LINK_CLASS).append(" href=\"")
                    .append(DOCS).append("admin/users-and-groups/\">Admin: users and groups</a></li>");
            sb.append("<li><a ").append(LINK_CLASS).append(" href=\"")
                    .append(DOCS).append("admin/batches/\">Admin: batches</a></li>");
            sb.append("<li><a ").append(LINK_CLASS).append(" href=\"")
                    .append(DOCS).append("admin/rules/\">Admin: approval rule sets</a></li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }
}
