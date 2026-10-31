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

import ai.philterd.workspace.webapp.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shared fragments under templates/fragments, rendered through the real security configuration
 * (and so the real CSP) by FragmentsTestController.
 */
@WebMvcTest(controllers = FragmentsTestController.class)
@Import(SecurityConfig.class)
class PhilterFragmentsWebTest extends WebMvcSliceMocks {

    private static final String PATH = FragmentsTestController.PATH;

    @Autowired private MockMvc mockMvc;

    private MvcResult render(final String query, final String... roles) throws Exception {
        return mockMvc.perform(get(PATH + query).with(signedIn("someone", roles)))
                .andExpect(status().isOk()).andReturn();
    }

    private String page(final String query, final String... roles) throws Exception {
        return render(query, roles).getResponse().getContentAsString();
    }

    @Test
    void thePagedTableShowsOnePageAndLinksKeepTheSearchFilterAndSort() throws Exception {
        final String html = page("?q=thing&status=open&sort=name&order=desc&offset=25", "USER");

        assertTrue(html.contains("26 to 50 of 60"), html);
        assertTrue(html.contains(">thing-35<") && html.contains(">thing-11<"), "rows 26 to 50, descending");
        assertFalse(html.contains(">thing-36<") || html.contains(">thing-10<"));
        assertTrue(html.contains("href=\"/test/fragments?q=thing&amp;status=open&amp;sort=name&amp;order=desc&amp;offset=50\" rel=\"next\""), html);
        assertTrue(html.contains("href=\"/test/fragments?q=thing&amp;status=open&amp;sort=name&amp;order=desc\" rel=\"prev\""), html);
        assertTrue(html.contains("aria-sort=\"descending\""));
        assertTrue(html.contains("href=\"/test/fragments?q=thing&amp;status=open\""), "the header link sorts ascending again");
    }

    @Test
    void theFilterBarKeepsItsValuesAndTheSort() throws Exception {
        final String html = page("?q=thing-0&status=open&sort=name&order=desc", "USER");

        assertTrue(html.contains("name=\"q\" maxlength=\"200\" value=\"thing-0\""), html);
        assertTrue(html.contains("<option value=\"open\" selected=\"selected\">"), html);
        assertTrue(html.contains("type=\"hidden\" name=\"order\" value=\"desc\""), html);
        assertTrue(html.contains("1 to 9 of 9"), "thing-01 to thing-09");
        assertTrue(html.contains(">Clear</a>"));
    }

    @Test
    void emptyFilteredAndPastTheEndPagesSaySo() throws Exception {
        assertTrue(page("?q=nothing-matches", "USER").contains("Nothing matches the search and filters."));
        final String past = page("?offset=100", "USER");
        assertTrue(past.contains("This page is past the end of the list."), past);
        assertTrue(past.contains("href=\"/test/fragments\""), "a link to the first page");
    }

    @Test
    void onlyAdministratorsGetTheOwnerFilterAndOthersCannotUseIt() throws Exception {
        final String admin = page("?owner=jdoe", "ADMIN");
        assertTrue(admin.contains("name=\"owner\""), admin);
        assertTrue(admin.contains("<p id=\"owner-state\">jdoe</p>"));
        assertTrue(admin.contains("&amp;owner=jdoe") || admin.contains("?owner=jdoe"), "links keep the owner");
        assertTrue(page("?all=true", "ADMIN").contains("<p id=\"owner-state\">everyone</p>"));

        for (final String role : new String[]{"USER", "AUDITOR"}) {
            final String other = page("?owner=boss&all=true", role);
            assertFalse(other.contains("name=\"owner\""), role + " sees no owner control");
            assertTrue(other.contains("<p id=\"owner-state\">mine</p>"), role + "'s owner parameter is ignored");
            assertFalse(other.contains("owner=boss"), "and not carried on links");
        }
    }

    @Test
    void formErrorsNeverShowPhiltersText() throws Exception {
        final String html = page("?fail=1", "USER");
        assertTrue(html.contains("role=\"alert\""));
        assertTrue(html.contains("Philter did not accept the highlighted value."), html);
        assertTrue(html.contains("<p id=\"name-error\""));
        assertFalse(html.contains(FragmentsTestController.SUBMITTED), "Philter's message repeats submitted text");
        assertFalse(page("", "USER").contains("role=\"alert\""), "no errors, no alert");
    }

    @Test
    void confirmWorksWithAndWithoutJavaScript() throws Exception {
        final String html = page("", "USER");
        assertTrue(Pattern.compile("<a href=\"/test/fragments/thing-01/confirm\"[^>]*data-confirm-dialog=\"delete-thing\"")
                .matcher(html).find(), "a link to the confirm page that opens the dialog: " + html);
        final String dialog = html.substring(html.indexOf("<dialog id=\"delete-thing\""), html.indexOf("</dialog>"));
        assertTrue(dialog.contains("Delete thing-01?") && dialog.contains("<li>thing-01</li>"));
        assertTrue(dialog.contains("action=\"/test/fragments/thing-01/delete\" method=\"post\""), dialog);
        assertTrue(dialog.contains("name=\"_csrf\""), "the confirming form carries the CSRF token");
        assertTrue(dialog.contains("name=\"version\" value=\"7\""));
        assertTrue(dialog.contains("<form method=\"dialog\">"), "Cancel closes the dialog");
    }

    @Test
    void theJsonEditorShowsTheServersCheck() throws Exception {
        final String html = page("?json=%5B1", "USER");
        assertTrue(html.contains("data-json-editor"));
        assertTrue(html.contains("aria-invalid=\"true\""), html);
        assertTrue(html.contains("This is not valid JSON"), html);
        assertTrue(page("", "USER").contains("aria-invalid=\"false\""));
    }

    @Test
    void theScriptCarriesTheRequestsNonceAndNothingUsesInlineHandlers() throws Exception {
        final MvcResult result = render("?fail=1", "ADMIN");
        final String csp = result.getResponse().getHeader("Content-Security-Policy");
        final Matcher nonce = Pattern.compile("'nonce-([^']+)'").matcher(csp);
        assertTrue(nonce.find(), csp);
        final String html = result.getResponse().getContentAsString();
        assertTrue(html.contains("<script nonce=\"" + nonce.group(1) + "\">"), "the philter-ui script is allowed");
        assertFalse(Pattern.compile("\\son[a-z]+=", Pattern.CASE_INSENSITIVE).matcher(html).find(),
                "the CSP refuses inline event handlers");
    }

    @Test
    void everySharedFragmentFollowsTheCspRules() throws Exception {
        final Resource[] fragments = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:templates/fragments/*.html");
        assertTrue(fragments.length >= 6);
        for (final Resource fragment : fragments) {
            final String source = new String(fragment.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(Pattern.compile("\\son[a-z]+=", Pattern.CASE_INSENSITIVE).matcher(source).find(),
                    fragment.getFilename() + " has an inline event handler");
            final Matcher script = Pattern.compile("<script\\b[^>]*>").matcher(source);
            while (script.find()) {
                assertTrue(script.group().contains("th:nonce=\"${cspNonce}\""),
                        fragment.getFilename() + ": " + script.group());
            }
        }
    }
}
