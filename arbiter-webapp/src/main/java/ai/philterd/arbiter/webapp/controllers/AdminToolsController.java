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
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.BackgroundJob;
import ai.philterd.arbiter.repository.BackgroundJobRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.DataImportLogService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin "Tools" tab — destructive maintenance actions that don't belong on the
 * data-source/destination/notifications pages. Right now this hosts a single tool
 * for wiping the per-job data-import history and log entries.
 */
@Controller
@RequestMapping("/admin/tools")
@PreAuthorize("hasRole('ADMIN')")
public class AdminToolsController {

    private static final List<String> DATA_IMPORT_TYPES = List.of(
            BackgroundJob.TYPE_OPENSEARCH_INGEST,
            BackgroundJob.TYPE_ELASTICSEARCH_INGEST,
            BackgroundJob.TYPE_LOCAL_DIRECTORY_INGEST,
            BackgroundJob.TYPE_S3_INGEST);

    /**
     * Terminal statuses for the cleanup flow. PENDING and RUNNING are intentionally
     * excluded so an in-flight or queued import is never deleted out from under the
     * worker (or before the user can see its results).
     */
    private static final List<String> TERMINAL_STATUSES = List.of(
            BackgroundJob.STATUS_COMPLETED,
            BackgroundJob.STATUS_FAILED);

    private final BackgroundJobRepository jobRepository;
    private final DataImportLogService importLogService;
    private final AuditLogService auditLogService;

    public AdminToolsController(final BackgroundJobRepository jobRepository,
                                final DataImportLogService importLogService,
                                final AuditLogService auditLogService) {
        this.jobRepository = jobRepository;
        this.importLogService = importLogService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public String page(final Model model) {
        return "admin-tools";
    }

    @PostMapping("/cleanup-data-imports")
    public String cleanupDataImports(final RedirectAttributes redirectAttributes) {
        // Two-step delete keeps in-flight jobs intact: collect the ids of jobs that
        // are *terminal* (COMPLETED or FAILED), wipe their log entries, then drop
        // the job rows themselves. PENDING and RUNNING jobs are skipped entirely so
        // a worker can't be left mid-paging with no job row to update.
        final List<BackgroundJob> terminalJobs =
                jobRepository.findByTypeInAndStatusIn(DATA_IMPORT_TYPES, TERMINAL_STATUSES);
        final Set<String> terminalIds = terminalJobs.stream()
                .map(BackgroundJob::getId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        final long entriesDeleted = importLogService.deleteByJobIds(terminalIds);
        final long jobsDeleted =
                jobRepository.deleteByTypeInAndStatusIn(DATA_IMPORT_TYPES, TERMINAL_STATUSES);
        auditLogService.log("ADMIN_TOOLS_CLEANUP_DATA_IMPORTS", "Tools", "data-imports",
                Map.of("jobsDeleted", jobsDeleted,
                        "logEntriesDeleted", entriesDeleted,
                        "statusesRemoved", TERMINAL_STATUSES));
        redirectAttributes.addFlashAttribute("success",
                "Cleaned up " + jobsDeleted + " completed data import job(s) and "
                        + entriesDeleted + " log entry/entries. "
                        + "Pending and running jobs were left in place.");
        return "redirect:/admin/tools";
    }
}
