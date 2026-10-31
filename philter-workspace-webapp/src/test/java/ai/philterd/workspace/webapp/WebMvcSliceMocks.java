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

import ai.philterd.workspace.repository.AuditLogRepository;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentCommentRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.GeneralSettingsRepository;
import ai.philterd.workspace.repository.GroupRepository;
import ai.philterd.workspace.repository.InboxMessageRepository;
import ai.philterd.workspace.repository.LlmJudgeDefaultsRepository;
import ai.philterd.workspace.repository.NotificationSettingsRepository;
import ai.philterd.workspace.repository.OllamaInstanceRepository;
import ai.philterd.workspace.repository.LocalDirectoryDataSourceRepository;
import ai.philterd.workspace.repository.LocalDirectoryDestinationRepository;
import ai.philterd.workspace.repository.OpenSearchDataSourceRepository;
import ai.philterd.workspace.repository.PendingUploadRepository;
import ai.philterd.workspace.repository.RelationalDbDataSourceRepository;
import ai.philterd.workspace.repository.S3DataSourceRepository;
import ai.philterd.workspace.repository.S3DestinationRepository;
import ai.philterd.workspace.repository.RedactionCertificateRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.repository.UserSettingsRepository;
import ai.philterd.workspace.repository.WeightSetRepository;
import ai.philterd.workspace.service.DestinationTester;
import ai.philterd.workspace.service.RedactionService;
import ai.philterd.workspace.webapp.controllers.AdminComplianceProfileController;
import ai.philterd.workspace.webapp.controllers.AdminGeneralController;
import ai.philterd.workspace.webapp.controllers.AdminGroupController;
import ai.philterd.workspace.webapp.controllers.AdminNotificationsController;
import ai.philterd.workspace.webapp.controllers.AdminOllamaController;
import ai.philterd.workspace.webapp.controllers.AdminPhilterController;
import ai.philterd.workspace.webapp.controllers.AdminToolsController;
import ai.philterd.workspace.webapp.controllers.AdminWeightSetController;
import ai.philterd.workspace.webapp.controllers.AuditLogAdminController;
import ai.philterd.workspace.webapp.controllers.BatchController;
import ai.philterd.workspace.webapp.controllers.PolicyController;
import ai.philterd.workspace.webapp.controllers.RedactionController;
import ai.philterd.workspace.webapp.controllers.ReportingController;
import ai.philterd.workspace.webapp.security.SecurityConfig;
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
 * The beans a Philter Workspace {@code @WebMvcTest} slice needs mocked: the slice pulls in more of the application
 * than its controller list, and these have no database or remote service behind them in a test.
 */
abstract class WebMvcSliceMocks {

    @MockBean protected ai.philterd.workspace.webapp.security.PhilterApiKeys philterApiKeys;
    @MockBean protected ai.philterd.workspace.webapp.security.PhilterSignInService philterSignInService;
    @MockBean protected ai.philterd.workspace.webapp.security.PhilterSignInFlow philterSignInFlow;
    @MockBean protected ai.philterd.workspace.webapp.security.BootstrapKeyWarning bootstrapKeyWarning;

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
    @MockBean protected ai.philterd.workspace.repository.BackgroundJobRepository backgroundJobRepository;
    @MockBean protected ai.philterd.workspace.repository.DataImportLogEntryRepository dataImportLogEntryRepository;
    @MockBean protected ai.philterd.workspace.repository.ElasticsearchDataSourceRepository elasticsearchDataSourceRepository;
    @MockBean protected S3DataSourceRepository s3DataSourceRepository;
    @MockBean protected RelationalDbDataSourceRepository relationalDbDataSourceRepository;
    @MockBean protected LocalDirectoryDataSourceRepository localDirectoryDataSourceRepository;
    @MockBean protected LocalDirectoryDestinationRepository localDirectoryDestinationRepository;
    @MockBean protected S3DestinationRepository s3DestinationRepository;
    @MockBean protected DestinationTester destinationTester;
    @MockBean protected ai.philterd.workspace.service.PhilterSettings philterSettings;
    @MockBean protected DocumentCommentRepository documentCommentRepository;
    @MockBean protected WeightSetRepository weightSetRepository;
    @MockBean protected LlmJudgeDefaultsRepository llmJudgeDefaultsRepository;
    @MockBean protected UserSettingsRepository userSettingsRepository;
    @MockBean protected InboxMessageRepository inboxMessageRepository;
    @MockBean protected PendingUploadRepository pendingUploadRepository;
    @MockBean protected ai.philterd.workspace.repository.PhilterWebhookDeliveryRepository philterWebhookDeliveryRepository;
    @MockBean protected ai.philterd.workspace.repository.DetectionRuleRepository detectionRuleRepository;
    @MockBean protected ai.philterd.workspace.service.DetectionRuleService detectionRuleService;
    @MockBean protected ai.philterd.workspace.service.CaseMetadataService caseMetadataService;
    @MockBean protected ai.philterd.workspace.service.DocumentDeliveryService documentDeliveryService;
    @MockBean protected ai.philterd.workspace.service.DocumentChangeGuard documentChangeGuard;
    @MockBean protected ai.philterd.workspace.webapp.security.PhilterWebhookSettings philterWebhookSettings;
    @MockBean protected ai.philterd.workspace.webapp.security.PhilterWebhookReceiver philterWebhookReceiver;
    @MockBean protected RedactionCertificateRepository redactionCertificateRepository;
    @MockBean protected ai.philterd.workspace.repository.FinalizationPolicyRepository finalizationPolicyRepository;
    @MockBean protected ai.philterd.workspace.repository.ComplianceProfileRepository complianceProfileRepository;
    @MockBean protected MongoOperations mongoOperations;
}
