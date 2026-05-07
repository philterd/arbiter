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
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.LocalDirectoryDataSource;
import ai.philterd.arbiter.model.OpenSearchDataSource;
import ai.philterd.arbiter.model.RelationalDbDataSource;
import ai.philterd.arbiter.model.S3DataSource;
import ai.philterd.arbiter.repository.LocalDirectoryDataSourceRepository;
import ai.philterd.arbiter.repository.OpenSearchDataSourceRepository;
import ai.philterd.arbiter.repository.RelationalDbDataSourceRepository;
import ai.philterd.arbiter.repository.S3DataSourceRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.SymmetricCipher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/admin/data-sources")
public class AdminDataSourceController {

    private final OpenSearchDataSourceRepository repository;
    private final S3DataSourceRepository s3Repository;
    private final RelationalDbDataSourceRepository rdbRepository;
    private final LocalDirectoryDataSourceRepository localRepository;
    private final AuditLogService auditLogService;
    private final SymmetricCipher cipher;

    public AdminDataSourceController(final OpenSearchDataSourceRepository repository,
                                     final S3DataSourceRepository s3Repository,
                                     final RelationalDbDataSourceRepository rdbRepository,
                                     final LocalDirectoryDataSourceRepository localRepository,
                                     final AuditLogService auditLogService,
                                     final SymmetricCipher cipher) {
        this.repository = repository;
        this.s3Repository = s3Repository;
        this.rdbRepository = rdbRepository;
        this.localRepository = localRepository;
        this.auditLogService = auditLogService;
        this.cipher = cipher;
    }

    @GetMapping
    public String list(final Model model) {
        final List<OpenSearchDataSource> sources = repository
                .findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        model.addAttribute("dataSources", sources);

        final List<S3DataSource> s3Sources = s3Repository
                .findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        model.addAttribute("s3DataSources", s3Sources);

        final List<RelationalDbDataSource> rdbSources = rdbRepository
                .findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        model.addAttribute("rdbDataSources", rdbSources);

        final List<LocalDirectoryDataSource> localSources = localRepository
                .findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        model.addAttribute("localDataSources", localSources);

        return "admin-data-sources";
    }

    @PostMapping
    public String create(@RequestParam("name") final String name,
                         @RequestParam("endpoint") final String endpoint,
                         @RequestParam("query") final String query,
                         @RequestParam(value = "username", required = false) final String username,
                         @RequestParam(value = "password", required = false) final String password,
                         final RedirectAttributes redirectAttributes) {
        final String trimmedName = name == null ? "" : name.trim();
        final String trimmedEndpoint = endpoint == null ? "" : endpoint.trim();
        final String trimmedQuery = query == null ? "" : query.trim();
        final String trimmedUsername = username == null ? "" : username.trim();
        // Password is intentionally not trimmed — leading/trailing whitespace can be valid.
        final String rawPassword = password == null ? "" : password;

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedEndpoint.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Endpoint is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedQuery.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Query is required.");
            return "redirect:/admin/data-sources";
        }
        if (repository.findByName(trimmedName).isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A data source named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        final OpenSearchDataSource source = new OpenSearchDataSource();
        source.setId(UUID.randomUUID().toString());
        source.setName(trimmedName);
        source.setEndpoint(trimmedEndpoint);
        source.setQuery(trimmedQuery);
        source.setUsername(trimmedUsername.isEmpty() ? null : trimmedUsername);
        source.setEncryptedPassword(rawPassword.isEmpty() ? null : cipher.encrypt(rawPassword));
        source.setCreatedAt(LocalDateTime.now());

        try {
            repository.save(source);
        } catch (DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "A data source named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        auditLogService.log("OPENSEARCH_DATASOURCE_CREATE", "OpenSearchDataSource", source.getId(),
                Map.of("name", trimmedName,
                        "endpoint", trimmedEndpoint,
                        "query", trimmedQuery,
                        "username", source.getUsername() == null ? "" : source.getUsername(),
                        "passwordSet", source.getEncryptedPassword() != null));
        redirectAttributes.addFlashAttribute("success",
                "Data source \"" + trimmedName + "\" added.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final OpenSearchDataSource source = repository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "Data source not found.");
            return "redirect:/admin/data-sources";
        }
        repository.deleteById(id);
        auditLogService.log("OPENSEARCH_DATASOURCE_DELETE", "OpenSearchDataSource", id,
                Map.of("name", source.getName() == null ? "" : source.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Data source \"" + source.getName() + "\" removed.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/s3")
    public String createS3(@RequestParam("name") final String name,
                           @RequestParam("bucketName") final String bucketName,
                           @RequestParam("bucketKey") final String bucketKey,
                           @RequestParam("filenameGlob") final String filenameGlob,
                           @RequestParam(value = "accessKey", required = false) final String accessKey,
                           @RequestParam(value = "secretKey", required = false) final String secretKey,
                           final RedirectAttributes redirectAttributes) {
        final String trimmedName = name == null ? "" : name.trim();
        final String trimmedBucket = bucketName == null ? "" : bucketName.trim();
        final String trimmedKey = bucketKey == null ? "" : bucketKey.trim();
        final String trimmedGlob = filenameGlob == null ? "" : filenameGlob.trim();
        // Credentials are not trimmed — AWS keys can theoretically contain whitespace, and a user
        // who typed one is asking us to store exactly what they typed.
        final String rawAccessKey = accessKey == null ? "" : accessKey;
        final String rawSecretKey = secretKey == null ? "" : secretKey;

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedBucket.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Bucket name is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedKey.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Bucket key is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedGlob.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Filename glob is required.");
            return "redirect:/admin/data-sources";
        }
        // An access key without a secret key (or vice versa) is almost certainly a mistake.
        if (rawAccessKey.isEmpty() != rawSecretKey.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    "Provide both Access key and Secret key, or leave both blank.");
            return "redirect:/admin/data-sources";
        }
        if (s3Repository.findByName(trimmedName).isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "An S3 data source named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        final S3DataSource source = new S3DataSource();
        source.setId(UUID.randomUUID().toString());
        source.setName(trimmedName);
        source.setBucketName(trimmedBucket);
        source.setBucketKey(trimmedKey);
        source.setFilenameGlob(trimmedGlob);
        source.setEncryptedAccessKey(rawAccessKey.isEmpty() ? null : cipher.encrypt(rawAccessKey));
        source.setEncryptedSecretKey(rawSecretKey.isEmpty() ? null : cipher.encrypt(rawSecretKey));
        source.setCreatedAt(LocalDateTime.now());

        try {
            s3Repository.save(source);
        } catch (DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "An S3 data source named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        auditLogService.log("S3_DATASOURCE_CREATE", "S3DataSource", source.getId(),
                Map.of("name", trimmedName,
                        "bucketName", trimmedBucket,
                        "bucketKey", trimmedKey,
                        "filenameGlob", trimmedGlob,
                        "credentialsSet", source.getEncryptedAccessKey() != null));
        redirectAttributes.addFlashAttribute("success",
                "S3 data source \"" + trimmedName + "\" added.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/s3/{id}/delete")
    public String deleteS3(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final S3DataSource source = s3Repository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "S3 data source not found.");
            return "redirect:/admin/data-sources";
        }
        s3Repository.deleteById(id);
        auditLogService.log("S3_DATASOURCE_DELETE", "S3DataSource", id,
                Map.of("name", source.getName() == null ? "" : source.getName()));
        redirectAttributes.addFlashAttribute("success",
                "S3 data source \"" + source.getName() + "\" removed.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/rdb")
    public String createRdb(@RequestParam("name") final String name,
                            @RequestParam("jdbcUrl") final String jdbcUrl,
                            @RequestParam("sqlQuery") final String sqlQuery,
                            @RequestParam(value = "username", required = false) final String username,
                            @RequestParam(value = "password", required = false) final String password,
                            final RedirectAttributes redirectAttributes) {
        final String trimmedName = name == null ? "" : name.trim();
        final String trimmedUrl = jdbcUrl == null ? "" : jdbcUrl.trim();
        final String trimmedSql = sqlQuery == null ? "" : sqlQuery.trim();
        // Credentials are not trimmed — leading/trailing whitespace can be valid in passwords.
        final String rawUsername = username == null ? "" : username;
        final String rawPassword = password == null ? "" : password;

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedUrl.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "JDBC URL is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedSql.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "SQL query is required.");
            return "redirect:/admin/data-sources";
        }
        // A username without a password (or vice versa) is almost always a typo.
        if (rawUsername.isEmpty() != rawPassword.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    "Provide both Username and Password, or leave both blank.");
            return "redirect:/admin/data-sources";
        }
        if (rdbRepository.findByName(trimmedName).isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A relational database data source named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        final RelationalDbDataSource source = new RelationalDbDataSource();
        source.setId(UUID.randomUUID().toString());
        source.setName(trimmedName);
        source.setJdbcUrl(trimmedUrl);
        source.setSqlQuery(trimmedSql);
        source.setEncryptedUsername(rawUsername.isEmpty() ? null : cipher.encrypt(rawUsername));
        source.setEncryptedPassword(rawPassword.isEmpty() ? null : cipher.encrypt(rawPassword));
        source.setCreatedAt(LocalDateTime.now());

        try {
            rdbRepository.save(source);
        } catch (DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "A relational database data source named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        auditLogService.log("RDB_DATASOURCE_CREATE", "RelationalDbDataSource", source.getId(),
                Map.of("name", trimmedName,
                        "jdbcUrl", trimmedUrl,
                        "sqlQuery", trimmedSql,
                        "credentialsSet", source.getEncryptedUsername() != null));
        redirectAttributes.addFlashAttribute("success",
                "Relational database data source \"" + trimmedName + "\" added.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/rdb/{id}/delete")
    public String deleteRdb(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final RelationalDbDataSource source = rdbRepository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "Relational database data source not found.");
            return "redirect:/admin/data-sources";
        }
        rdbRepository.deleteById(id);
        auditLogService.log("RDB_DATASOURCE_DELETE", "RelationalDbDataSource", id,
                Map.of("name", source.getName() == null ? "" : source.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Relational database data source \"" + source.getName() + "\" removed.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/local")
    public String createLocal(@RequestParam("name") final String name,
                              @RequestParam("directoryPath") final String directoryPath,
                              @RequestParam("filenameGlob") final String filenameGlob,
                              final RedirectAttributes redirectAttributes) {
        final String trimmedName = name == null ? "" : name.trim();
        final String trimmedPath = directoryPath == null ? "" : directoryPath.trim();
        final String trimmedGlob = filenameGlob == null ? "" : filenameGlob.trim();

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedPath.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Directory path is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedGlob.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Filename glob is required.");
            return "redirect:/admin/data-sources";
        }
        if (localRepository.findByName(trimmedName).isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A local directory data source named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        final LocalDirectoryDataSource source = new LocalDirectoryDataSource();
        source.setId(UUID.randomUUID().toString());
        source.setName(trimmedName);
        source.setDirectoryPath(trimmedPath);
        source.setFilenameGlob(trimmedGlob);
        source.setCreatedAt(LocalDateTime.now());

        try {
            localRepository.save(source);
        } catch (DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "A local directory data source named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        auditLogService.log("LOCAL_DATASOURCE_CREATE", "LocalDirectoryDataSource", source.getId(),
                Map.of("name", trimmedName,
                        "directoryPath", trimmedPath,
                        "filenameGlob", trimmedGlob));
        redirectAttributes.addFlashAttribute("success",
                "Local directory data source \"" + trimmedName + "\" added.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/local/{id}/delete")
    public String deleteLocal(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final LocalDirectoryDataSource source = localRepository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "Local directory data source not found.");
            return "redirect:/admin/data-sources";
        }
        localRepository.deleteById(id);
        auditLogService.log("LOCAL_DATASOURCE_DELETE", "LocalDirectoryDataSource", id,
                Map.of("name", source.getName() == null ? "" : source.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Local directory data source \"" + source.getName() + "\" removed.");
        return "redirect:/admin/data-sources";
    }
}
