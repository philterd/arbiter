/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.arbiter.webapp;

import ai.philterd.arbiter.core.model.Redaction;
import ai.philterd.arbiter.core.model.RedactionResponse;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.AuditLogRepository;
import ai.philterd.arbiter.repository.DocumentCommentRepository;
import ai.philterd.arbiter.repository.GroupRepository;
import ai.philterd.arbiter.repository.LlmJudgeDefaultsRepository;
import ai.philterd.arbiter.repository.GeneralSettingsRepository;
import ai.philterd.arbiter.repository.InboxMessageRepository;
import ai.philterd.arbiter.repository.NotificationSettingsRepository;
import ai.philterd.arbiter.repository.OllamaInstanceRepository;
import ai.philterd.arbiter.repository.PendingUploadRepository;
import ai.philterd.arbiter.repository.RedactionCertificateRepository;
import ai.philterd.arbiter.repository.PhilterDefaultsRepository;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.repository.PolicyRepository;
import ai.philterd.arbiter.repository.UserSettingsRepository;
import ai.philterd.arbiter.repository.WeightSetRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.model.GeneralSettings;
import ai.philterd.arbiter.service.GeneralSettingsService;
import ai.philterd.arbiter.service.RedactionService;
import ai.philterd.arbiter.webapp.controllers.RedactionController;
import org.springframework.data.mongodb.core.MongoOperations;
import ai.philterd.arbiter.webapp.security.MongoUserDetailsService;
import ai.philterd.arbiter.webapp.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(RedactionController.class)
@Import({SecurityConfig.class, MongoUserDetailsService.class})
@WithMockUser
public class RedactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RedactionService redactionService;

    @MockBean
    private SpanRepository spanRepository;

    @MockBean
    private DocumentRepository documentRepository;

    @MockBean
    private BatchRepository batchRepository;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private GroupRepository groupRepository;

    @MockBean
    private AuditLogRepository auditLogRepository;

    @MockBean
    private NotificationSettingsRepository notificationSettingsRepository;

    @MockBean
    private GeneralSettingsRepository generalSettingsRepository;

    @MockBean
    private OllamaInstanceRepository ollamaInstanceRepository;

    @MockBean
    private PhilterInstanceRepository philterInstanceRepository;

    @MockBean
    private PhilterDefaultsRepository philterDefaultsRepository;

    @MockBean
    private PolicyRepository policyRepository;

    @MockBean
    private DocumentCommentRepository documentCommentRepository;

    @MockBean
    private WeightSetRepository weightSetRepository;

    @MockBean
    private LlmJudgeDefaultsRepository llmJudgeDefaultsRepository;

    @MockBean
    private UserSettingsRepository userSettingsRepository;

    @MockBean
    private InboxMessageRepository inboxMessageRepository;

    @MockBean
    private PendingUploadRepository pendingUploadRepository;

    @MockBean
    private RedactionCertificateRepository redactionCertificateRepository;

    @MockBean
    private ai.philterd.arbiter.repository.FinalizationPolicyRepository finalizationPolicyRepository;

    @MockBean
    private ai.philterd.arbiter.repository.ComplianceProfileRepository complianceProfileRepository;

    @MockBean
    private MongoOperations mongoOperations;

    @MockBean
    private GeneralSettingsService generalSettingsService;

    @Test
    public void testDashboard() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"));
    }

    @Test
    public void testUpload() throws Exception {
        mockMvc.perform(get("/upload"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    public void testRedactText() throws Exception {
        final String text = "George Washington lived in Mount Vernon.";

        final Batch batch = new Batch();
        batch.setId("batch-1");
        batch.setName("Test batch");

        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));

        final GeneralSettings settings = new GeneralSettings();
        settings.setMaxUploadFileSizeBytes(10L * 1024L * 1024L);
        when(generalSettingsService.load()).thenReturn(settings);

        final MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", text.getBytes());

        // The endpoint now enqueues the upload onto the redaction queue and redirects back to
        // /upload with a success flash. The actual redaction runs asynchronously in the worker.
        mockMvc.perform(multipart("/redact").file(file).param("batchId", "batch-1").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/upload"))
                .andExpect(flash().attributeExists("success"));
    }
}
