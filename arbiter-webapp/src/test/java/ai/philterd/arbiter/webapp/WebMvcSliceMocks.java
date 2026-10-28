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
import ai.philterd.arbiter.repository.RedactionCertificateRepository;
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
import ai.philterd.arbiter.webapp.controllers.AdminToolsController;
import ai.philterd.arbiter.webapp.controllers.AdminWeightSetController;
import ai.philterd.arbiter.webapp.controllers.AuditLogAdminController;
import ai.philterd.arbiter.webapp.controllers.BatchController;
import ai.philterd.arbiter.webapp.controllers.PolicyController;
import ai.philterd.arbiter.webapp.controllers.RedactionController;
import ai.philterd.arbiter.webapp.controllers.ReportingController;
import ai.philterd.arbiter.webapp.security.MongoUserDetailsService;
import ai.philterd.arbiter.webapp.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;


import static org.mockito.Mockito.when;

/**
 * The beans an Arbiter {@code @WebMvcTest} slice needs mocked: the slice pulls in more of the application
 * than its controller list, and these have no database or remote service behind them in a test.
 */
abstract class WebMvcSliceMocks {

    @MockBean protected ai.philterd.arbiter.service.ApiKeyHashingService apiKeyHashingService;

    /** The slice does not auto-configure Actuator, so the aggregate HealthController reads is mocked. */
    @MockBean protected HealthEndpoint healthEndpoint;

    @BeforeEach
    void healthIsUp() {
        when(healthEndpoint.health()).thenReturn(Health.up().build());
    }

    // Repositories — Spring Data interfaces, all mockable.
    @MockBean protected RedactionService redactionService;
    @MockBean protected SpanRepository spanRepository;
    @MockBean protected DocumentRepository documentRepository;
    @MockBean protected BatchRepository batchRepository;
    @MockBean protected UserRepository userRepository;
    @MockBean protected GroupRepository groupRepository;
    @MockBean protected AuditLogRepository auditLogRepository;
    @MockBean protected NotificationSettingsRepository notificationSettingsRepository;
    @MockBean protected GeneralSettingsRepository generalSettingsRepository;
    @MockBean protected OllamaInstanceRepository ollamaInstanceRepository;
    @MockBean protected OpenSearchDataSourceRepository openSearchDataSourceRepository;
    @MockBean protected ai.philterd.arbiter.repository.BackgroundJobRepository backgroundJobRepository;
    @MockBean protected ai.philterd.arbiter.repository.DataImportLogEntryRepository dataImportLogEntryRepository;
    @MockBean protected ai.philterd.arbiter.repository.ElasticsearchDataSourceRepository elasticsearchDataSourceRepository;
    @MockBean protected S3DataSourceRepository s3DataSourceRepository;
    @MockBean protected RelationalDbDataSourceRepository relationalDbDataSourceRepository;
    @MockBean protected LocalDirectoryDataSourceRepository localDirectoryDataSourceRepository;
    @MockBean protected LocalDirectoryDestinationRepository localDirectoryDestinationRepository;
    @MockBean protected S3DestinationRepository s3DestinationRepository;
    @MockBean protected DestinationTester destinationTester;
    @MockBean protected ai.philterd.arbiter.service.PhilterSettings philterSettings;
    @MockBean protected PolicyRepository policyRepository;
    @MockBean protected DocumentCommentRepository documentCommentRepository;
    @MockBean protected WeightSetRepository weightSetRepository;
    @MockBean protected LlmJudgeDefaultsRepository llmJudgeDefaultsRepository;
    @MockBean protected UserSettingsRepository userSettingsRepository;
    @MockBean protected InboxMessageRepository inboxMessageRepository;
    @MockBean protected PendingUploadRepository pendingUploadRepository;
    @MockBean protected RedactionCertificateRepository redactionCertificateRepository;
    @MockBean protected ai.philterd.arbiter.repository.FinalizationPolicyRepository finalizationPolicyRepository;
    @MockBean protected ai.philterd.arbiter.repository.ComplianceProfileRepository complianceProfileRepository;
    @MockBean protected ai.philterd.arbiter.repository.InvitationRepository invitationRepository;
    @MockBean protected MongoOperations mongoOperations;
}
