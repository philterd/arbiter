/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp;

import ai.philterd.arbiter.repository.AuditLogRepository;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentCommentRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.GeneralSettingsRepository;
import ai.philterd.arbiter.repository.GroupRepository;
import ai.philterd.arbiter.repository.InboxMessageRepository;
import ai.philterd.arbiter.repository.LlmJudgeDefaultsRepository;
import ai.philterd.arbiter.repository.NotificationSettingsRepository;
import ai.philterd.arbiter.repository.OllamaInstanceRepository;
import ai.philterd.arbiter.repository.LocalDirectoryDataSourceRepository;
import ai.philterd.arbiter.repository.LocalDirectoryDestinationRepository;
import ai.philterd.arbiter.repository.OpenSearchDataSourceRepository;
import ai.philterd.arbiter.repository.PendingUploadRepository;
import ai.philterd.arbiter.repository.RelationalDbDataSourceRepository;
import ai.philterd.arbiter.repository.S3DataSourceRepository;
import ai.philterd.arbiter.repository.S3DestinationRepository;
import ai.philterd.arbiter.repository.SqsDestinationRepository;
import ai.philterd.arbiter.repository.RedactionCertificateRepository;
import ai.philterd.arbiter.repository.PhilterDefaultsRepository;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.repository.PolicyRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.repository.UserSettingsRepository;
import ai.philterd.arbiter.repository.WeightSetRepository;
import ai.philterd.arbiter.service.DestinationTester;
import ai.philterd.arbiter.service.RedactionService;
import ai.philterd.arbiter.webapp.controllers.AdminComplianceProfileController;
import ai.philterd.arbiter.webapp.controllers.AdminController;
import ai.philterd.arbiter.webapp.controllers.AdminGeneralController;
import ai.philterd.arbiter.webapp.controllers.AdminGroupController;
import ai.philterd.arbiter.webapp.controllers.AdminNotificationsController;
import ai.philterd.arbiter.webapp.controllers.AdminOllamaController;
import ai.philterd.arbiter.webapp.controllers.AdminPhilterController;
import ai.philterd.arbiter.webapp.controllers.AdminWeightSetController;
import ai.philterd.arbiter.webapp.controllers.AuditLogAdminController;
import ai.philterd.arbiter.webapp.controllers.BatchController;
import ai.philterd.arbiter.webapp.controllers.PolicyController;
import ai.philterd.arbiter.webapp.controllers.RedactionController;
import ai.philterd.arbiter.webapp.controllers.ReportingController;
import ai.philterd.arbiter.webapp.security.MongoUserDetailsService;
import ai.philterd.arbiter.webapp.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test focused on permissions and authorization. Loads several controllers behind the
 * real {@link SecurityConfig} so the URL-pattern rules — admin gating on {@code /admin/**},
 * {@code /policies/**}, {@code /api/v1/policies/**} and {@code /reporting}, plus the
 * authenticated-by-default rule for everything else — are exercised end-to-end.
 */
@WebMvcTest(controllers = {
        AdminComplianceProfileController.class,
        AdminController.class,
        AdminGeneralController.class,
        AdminGroupController.class,
        AdminNotificationsController.class,
        AdminOllamaController.class,
        AdminPhilterController.class,
        AdminWeightSetController.class,
        AuditLogAdminController.class,
        BatchController.class,
        PolicyController.class,
        ReportingController.class,
        RedactionController.class,
})
@Import({SecurityConfig.class, MongoUserDetailsService.class,
        ai.philterd.arbiter.service.BatchAccessService.class,
        ai.philterd.arbiter.service.DocumentAccessService.class})
public class AuthorizationIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ai.philterd.arbiter.service.ApiKeyHashingService apiKeyHashingService;

    // Repositories — Spring Data interfaces, all mockable.
    @MockBean private RedactionService redactionService;
    @MockBean private SpanRepository spanRepository;
    @MockBean private DocumentRepository documentRepository;
    @MockBean private BatchRepository batchRepository;
    @MockBean private UserRepository userRepository;
    @MockBean private GroupRepository groupRepository;
    @MockBean private AuditLogRepository auditLogRepository;
    @MockBean private NotificationSettingsRepository notificationSettingsRepository;
    @MockBean private GeneralSettingsRepository generalSettingsRepository;
    @MockBean private OllamaInstanceRepository ollamaInstanceRepository;
    @MockBean private OpenSearchDataSourceRepository openSearchDataSourceRepository;
    @MockBean private ai.philterd.arbiter.repository.BackgroundJobRepository backgroundJobRepository;
    @MockBean private ai.philterd.arbiter.repository.ElasticsearchDataSourceRepository elasticsearchDataSourceRepository;
    @MockBean private S3DataSourceRepository s3DataSourceRepository;
    @MockBean private RelationalDbDataSourceRepository relationalDbDataSourceRepository;
    @MockBean private LocalDirectoryDataSourceRepository localDirectoryDataSourceRepository;
    @MockBean private LocalDirectoryDestinationRepository localDirectoryDestinationRepository;
    @MockBean private S3DestinationRepository s3DestinationRepository;
    @MockBean private SqsDestinationRepository sqsDestinationRepository;
    @MockBean private DestinationTester destinationTester;
    @MockBean private PhilterInstanceRepository philterInstanceRepository;
    @MockBean private PhilterDefaultsRepository philterDefaultsRepository;
    @MockBean private PolicyRepository policyRepository;
    @MockBean private DocumentCommentRepository documentCommentRepository;
    @MockBean private WeightSetRepository weightSetRepository;
    @MockBean private LlmJudgeDefaultsRepository llmJudgeDefaultsRepository;
    @MockBean private UserSettingsRepository userSettingsRepository;
    @MockBean private InboxMessageRepository inboxMessageRepository;
    @MockBean private PendingUploadRepository pendingUploadRepository;
    @MockBean private RedactionCertificateRepository redactionCertificateRepository;
    @MockBean private ai.philterd.arbiter.repository.FinalizationPolicyRepository finalizationPolicyRepository;
    @MockBean private ai.philterd.arbiter.repository.ComplianceProfileRepository complianceProfileRepository;
    @MockBean private ai.philterd.arbiter.repository.InvitationRepository invitationRepository;
    @MockBean private MongoOperations mongoOperations;

    // ---------------------------------------------------------------------
    // Anonymous: form-based UI redirects to /login; /api/** returns 401.
    // ---------------------------------------------------------------------

    @Test
    void anonymousIsBlockedFromUiPaths() throws Exception {
        // Anonymous must not be served any authenticated page — either by redirect
        // to /login (form-login entry point) or by HTTP 401, depending on which entry
        // point Spring Security activates.
        for (String path : new String[]{"/", "/batches", "/upload",
                "/admin/general", "/policies", "/reporting"}) {
            mockMvc.perform(get(path)).andExpect(notOk());
        }
    }

    private static org.springframework.test.web.servlet.ResultMatcher notOk() {
        return result -> {
            final int s = result.getResponse().getStatus();
            org.junit.jupiter.api.Assertions.assertTrue(
                    s == 401 || (s >= 300 && s < 400),
                    "anonymous request should be 401 or redirect, was " + s);
        };
    }

    @Test
    void anonymousGetsUnauthorizedFromApi() throws Exception {
        // /api/** returns HTTP 401 (no redirect) — see exceptionHandling rule in SecurityConfig.
        mockMvc.perform(get("/api/v1/policies")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/policies/content?instanceId=embedded&name=Default"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------
    // Non-admin (USER role): admin endpoints are forbidden, regular endpoints work.
    // ---------------------------------------------------------------------

    @Test
    void userRoleForbiddenFromAdminPaths() throws Exception {
        mockMvc.perform(get("/admin/general").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/users").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/groups").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/notifications").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/audit").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/philter").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/llm-judge").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/weights").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void userRoleForbiddenFromPoliciesAndReporting() throws Exception {
        mockMvc.perform(get("/policies").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/policies/some-id/edit").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
        // /api/** now rejects cookie-based auth: the session is dropped before the
        // role check, so the response is 401 (not 403). See cookieAuthRejectedOnApiPaths.
        mockMvc.perform(get("/api/v1/policies").with(user("u").roles("USER")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/policies/content?instanceId=embedded&name=Default")
                .with(user("u").roles("USER"))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/reporting").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/batches").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void userRoleAllowedOnRegularPaths() throws Exception {
        mockMvc.perform(get("/").with(user("u").roles("USER"))).andExpect(status().isOk());
        mockMvc.perform(get("/upload").with(user("u").roles("USER"))).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------
    // Admin role: previously-forbidden URLs are reachable.
    // ---------------------------------------------------------------------

    @Test
    void adminRoleReachesAdminPaths() throws Exception {
        mockMvc.perform(get("/admin/general").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/users").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/policies").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk());
        // /api/v1/policies is now Bearer-only — see cookieAuthRejectedOnApiPaths /
        // bearerTokenAuthAllowedOnApiPaths below for the new shape of these checks.
        mockMvc.perform(get("/reporting").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------
    // /api/** is Bearer-only: cookie/session authentication is dropped at
    // the security filter chain, so the only way to reach the API is by
    // sending a valid {@code Authorization: Bearer <api-key>} header.
    // ---------------------------------------------------------------------

    @Test
    void cookieAuthRejectedOnApiPaths_admin() throws Exception {
        // Even an authenticated admin gets 401 on /api/** without a Bearer token —
        // the session cookie is no longer accepted on the API surface.
        mockMvc.perform(get("/api/v1/policies").with(user("a").roles("ADMIN")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/policies/content?instanceId=embedded&name=Default")
                        .with(user("a").roles("ADMIN")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cookieAuthRejectedOnApiPaths_user() throws Exception {
        mockMvc.perform(get("/api/v1/policies").with(user("u").roles("USER")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cookieAuthStillWorksOnNonApiPaths() throws Exception {
        // Regression: cookie/session auth still works fine on the UI side.
        mockMvc.perform(get("/policies").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/").with(user("u").roles("USER")))
                .andExpect(status().isOk());
    }

    @Test
    void bearerTokenAuthAllowedOnApiPathsWhenRolePermits() throws Exception {
        final ai.philterd.arbiter.model.User user = new ai.philterd.arbiter.model.User();
        user.setEmail("admin@example.com");
        user.setRoles(java.util.Set.of("ADMIN"));
        org.mockito.Mockito.when(apiKeyHashingService.hash(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("hashed-key");
        org.mockito.Mockito.when(userRepository.findByApiKey("hashed-key"))
                .thenReturn(java.util.Optional.of(user));

        mockMvc.perform(get("/api/v1/policies")
                        .header("Authorization", "Bearer some-api-key"))
                .andExpect(status().isOk());
    }

    @Test
    void bearerTokenAuthEnforcesRoleOnApiPaths() throws Exception {
        // A USER-role bearer token still gets 403 on the admin-gated /api/v1/policies —
        // bearer auth doesn't bypass authorization, just authentication.
        final ai.philterd.arbiter.model.User user = new ai.philterd.arbiter.model.User();
        user.setEmail("user@example.com");
        user.setRoles(java.util.Set.of("USER"));
        org.mockito.Mockito.when(apiKeyHashingService.hash(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("hashed-key");
        org.mockito.Mockito.when(userRepository.findByApiKey("hashed-key"))
                .thenReturn(java.util.Optional.of(user));

        mockMvc.perform(get("/api/v1/policies")
                        .header("Authorization", "Bearer some-api-key"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidBearerTokenStillReturnsUnauthorized() throws Exception {
        org.mockito.Mockito.when(userRepository.findByApiKey(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/policies")
                        .header("Authorization", "Bearer not-a-real-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cookieAndInvalidBearerTogetherStillRejected() throws Exception {
        // Even with a real session cookie, a present-but-invalid Bearer header doesn't
        // upgrade the request — the session cookie is still dropped on /api/**.
        org.mockito.Mockito.when(userRepository.findByApiKey(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/policies").with(user("a").roles("ADMIN"))
                        .header("Authorization", "Bearer not-a-real-key"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------
    // CSRF: non-API POSTs require a CSRF token; /api/** is exempt.
    // ---------------------------------------------------------------------

    @Test
    void postWithoutCsrfRejectedOnUiPaths() throws Exception {
        mockMvc.perform(post("/policies").with(user("a").roles("ADMIN"))
                        .param("name", "p").param("content", "{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/general/url").with(user("a").roles("ADMIN"))
                        .param("arbiterUrl", "http://example.com"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postWithCsrfAcceptedOnUiPaths() throws Exception {
        // Admin POSTs work when CSRF token is present.
        mockMvc.perform(post("/admin/general/url").with(user("a").roles("ADMIN")).with(csrf())
                        .param("arbiterUrl", "http://example.com"))
                .andExpect(status().is3xxRedirection());
    }

    // ---------------------------------------------------------------------
    // /batches is URL-gated to ADMIN: every method is rejected at the security
    // filter chain for non-admin roles, ahead of any controller-level check.
    // ---------------------------------------------------------------------

    @Test
    void userRoleCannotModifyBatches() throws Exception {
        mockMvc.perform(post("/batches/abc/group").with(user("u").roles("USER")).with(csrf())
                        .param("groupId", "g1"))
                .andExpect(status().isForbidden());
    }
}
