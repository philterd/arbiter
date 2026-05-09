/*
 * Copyright 2026 Philterd, LLC.
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
import ai.philterd.arbiter.repository.UserSettingsRepository;
import ai.philterd.arbiter.repository.WeightSetRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.model.GeneralSettings;
import ai.philterd.arbiter.service.GeneralSettingsService;
import ai.philterd.arbiter.service.DestinationTester;
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
    private ai.philterd.arbiter.service.ApiKeyHashingService apiKeyHashingService;

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
    private OpenSearchDataSourceRepository openSearchDataSourceRepository;

    @MockBean
    private ai.philterd.arbiter.repository.BackgroundJobRepository backgroundJobRepository;

    @MockBean
    private ai.philterd.arbiter.repository.ElasticsearchDataSourceRepository elasticsearchDataSourceRepository;

    @MockBean
    private S3DataSourceRepository s3DataSourceRepository;

    @MockBean
    private RelationalDbDataSourceRepository relationalDbDataSourceRepository;

    @MockBean
    private LocalDirectoryDataSourceRepository localDirectoryDataSourceRepository;

    @MockBean
    private LocalDirectoryDestinationRepository localDirectoryDestinationRepository;

    @MockBean
    private S3DestinationRepository s3DestinationRepository;

    @MockBean
    private SqsDestinationRepository sqsDestinationRepository;

    @MockBean
    private DestinationTester destinationTester;

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
    private ai.philterd.arbiter.repository.InvitationRepository invitationRepository;

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

    @MockBean
    private ai.philterd.arbiter.service.UserGroupsService userGroupsService;

    @MockBean
    private ai.philterd.arbiter.service.OpenSearchIngestJobService openSearchIngestJobService;

    @MockBean
    private ai.philterd.arbiter.service.ElasticsearchIngestJobService elasticsearchIngestJobService;

    /**
     * A non-admin reviewer with no group membership submits an OpenSearch ingest pointed at a
     * batch they do not have access to. The controller must reject the request before the
     * job service is ever called — without this check, any authenticated user could ingest
     * documents into anyone's batch.
     */
    @Test
    public void ingestFromSourceRejectedWhenUserCannotAccessBatch() throws Exception {
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Foreign batch");
        batch.setGroupId("g-foreign");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        when(userGroupsService.groupIdsForEmail(any())).thenReturn(java.util.Set.of());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ingest-from-source")
                        .param("sourceType", "opensearch")
                        .param("batchId", "b1")
                        .param("dataSourceId", "src-1")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/upload"))
                .andExpect(flash().attributeExists("error"));

        org.mockito.Mockito.verify(openSearchIngestJobService, org.mockito.Mockito.never())
                .start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        org.mockito.Mockito.verify(elasticsearchIngestJobService, org.mockito.Mockito.never())
                .start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    /** Mirror for the Elasticsearch source type. */
    @Test
    public void ingestFromElasticsearchRejectedWhenUserCannotAccessBatch() throws Exception {
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Foreign batch");
        batch.setGroupId("g-foreign");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        when(userGroupsService.groupIdsForEmail(any())).thenReturn(java.util.Set.of());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ingest-from-source")
                        .param("sourceType", "elasticsearch")
                        .param("batchId", "b1")
                        .param("dataSourceId", "src-1")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/upload"))
                .andExpect(flash().attributeExists("error"));

        org.mockito.Mockito.verify(openSearchIngestJobService, org.mockito.Mockito.never())
                .start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        org.mockito.Mockito.verify(elasticsearchIngestJobService, org.mockito.Mockito.never())
                .start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    /** A reviewer in the batch's group is allowed through to the job service. */
    @Test
    public void ingestFromSourceAllowedForGroupMember() throws Exception {
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Mine");
        batch.setGroupId("g-mine");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        when(userGroupsService.groupIdsForEmail(any())).thenReturn(java.util.Set.of("g-mine"));

        final ai.philterd.arbiter.model.BackgroundJob job = new ai.philterd.arbiter.model.BackgroundJob();
        job.setStatus(ai.philterd.arbiter.model.BackgroundJob.STATUS_PENDING);
        when(openSearchIngestJobService.start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(job);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ingest-from-source")
                        .param("sourceType", "opensearch")
                        .param("batchId", "b1")
                        .param("dataSourceId", "src-1")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/jobs"))
                .andExpect(flash().attributeExists("success"));

        org.mockito.Mockito.verify(openSearchIngestJobService)
                .start(org.mockito.ArgumentMatchers.eq("src-1"),
                        org.mockito.ArgumentMatchers.eq("b1"),
                        org.mockito.ArgumentMatchers.anyInt(),
                        any());
    }

    /** An admin can start an ingest into any batch. */
    @Test
    @WithMockUser(roles = "ADMIN")
    public void ingestFromSourceAllowedForAdmin() throws Exception {
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Anyone's batch");
        batch.setGroupId("g-someone");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));

        final ai.philterd.arbiter.model.BackgroundJob job = new ai.philterd.arbiter.model.BackgroundJob();
        job.setStatus(ai.philterd.arbiter.model.BackgroundJob.STATUS_PENDING);
        when(openSearchIngestJobService.start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(job);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ingest-from-source")
                        .param("sourceType", "opensearch")
                        .param("batchId", "b1")
                        .param("dataSourceId", "src-1")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/jobs"));
    }

    /** A request for a batch that doesn't exist is rejected. */
    @Test
    public void ingestFromSourceRejectedWhenBatchMissing() throws Exception {
        when(batchRepository.findById("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ingest-from-source")
                        .param("sourceType", "opensearch")
                        .param("batchId", "ghost")
                        .param("dataSourceId", "src-1")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/upload"))
                .andExpect(flash().attributeExists("error"));

        org.mockito.Mockito.verify(openSearchIngestJobService, org.mockito.Mockito.never())
                .start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    /** A closed batch cannot accept new ingest jobs. */
    @Test
    @WithMockUser(roles = "ADMIN")
    public void ingestFromSourceRejectedWhenBatchClosed() throws Exception {
        final Batch closed = new Batch();
        closed.setId("b1");
        closed.setName("Closed");
        closed.setGroupId("g1");
        closed.setClosed(true);
        when(batchRepository.findById("b1")).thenReturn(Optional.of(closed));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ingest-from-source")
                        .param("sourceType", "opensearch")
                        .param("batchId", "b1")
                        .param("dataSourceId", "src-1")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/upload"))
                .andExpect(flash().attributeExists("error"));

        org.mockito.Mockito.verify(openSearchIngestJobService, org.mockito.Mockito.never())
                .start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
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

    // ---------------------------------------------------------------------
    // /download — content-type allow-list & filename sanitization.
    // ---------------------------------------------------------------------

    /**
     * The user-supplied {@code contentType} request parameter is restricted to a small
     * allow-list ({@code text/plain}, {@code application/pdf}). Anything else — including
     * the classic stored-XSS shapes like {@code text/html} — is rejected with HTTP 400
     * before the response body is even built.
     */
    @Test
    public void download_rejectsForbiddenContentType_html() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/download")
                        .param("redactedText", "<script>alert(1)</script>")
                        .param("fileName", "evil.html")
                        .param("contentType", "text/html")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void download_rejectsForbiddenContentType_javascript() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/download")
                        .param("redactedText", "alert(1)")
                        .param("fileName", "evil.js")
                        .param("contentType", "application/javascript")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void download_rejectsBlankContentType() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/download")
                        .param("redactedText", "data")
                        .param("fileName", "x.txt")
                        .param("contentType", "")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void download_acceptsPlainTextAndSanitizesFilename() throws Exception {
        // The fileName is built with CR/LF and double-quote injection attempts; the
        // safeFilename helper must scrub them before they reach Content-Disposition.
        final org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/download")
                                .param("redactedText", "Hello.")
                                .param("fileName", "report\r\nX-Injected: yes\";attack=\".txt")
                                .param("contentType", "text/plain")
                                .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        final String disposition = result.getResponse().getHeader("Content-Disposition");
        org.junit.jupiter.api.Assertions.assertNotNull(disposition,
                "Content-Disposition must be set on a successful download");
        org.junit.jupiter.api.Assertions.assertFalse(disposition.contains("\r"),
                "CR must not appear in the response header");
        org.junit.jupiter.api.Assertions.assertFalse(disposition.contains("\n"),
                "LF must not appear in the response header");
        // Quotes inside the filename portion must have been stripped — only the wrapping
        // quotes around the value should remain.
        final String prefix = "attachment; filename=\"";
        org.junit.jupiter.api.Assertions.assertTrue(disposition.startsWith(prefix),
                "Content-Disposition must keep the standard attachment wrapper");
        // After the prefix, the next quote should be the closing one (the value contains no
        // unescaped double quotes).
        final String tail = disposition.substring(prefix.length());
        final int firstQuote = tail.indexOf('"');
        org.junit.jupiter.api.Assertions.assertEquals(tail.length() - 1, firstQuote,
                "filename portion must not contain bare double quotes; got: " + disposition);
    }
}
