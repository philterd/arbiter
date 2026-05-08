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

import ai.philterd.arbiter.model.ElasticsearchDataSource;
import ai.philterd.arbiter.model.LocalDirectoryDataSource;
import ai.philterd.arbiter.model.OpenSearchDataSource;
import ai.philterd.arbiter.model.RelationalDbDataSource;
import ai.philterd.arbiter.model.S3DataSource;
import ai.philterd.arbiter.repository.ElasticsearchDataSourceRepository;
import ai.philterd.arbiter.repository.LocalDirectoryDataSourceRepository;
import ai.philterd.arbiter.repository.OpenSearchDataSourceRepository;
import ai.philterd.arbiter.repository.RelationalDbDataSourceRepository;
import ai.philterd.arbiter.repository.S3DataSourceRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.SymmetricCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/admin/data-sources")
public class AdminDataSourceController {

    private final OpenSearchDataSourceRepository repository;
    private final ElasticsearchDataSourceRepository esRepository;
    private final S3DataSourceRepository s3Repository;
    private final RelationalDbDataSourceRepository rdbRepository;
    private final LocalDirectoryDataSourceRepository localRepository;
    private final AuditLogService auditLogService;
    private final SymmetricCipher cipher;
    private final ObjectMapper objectMapper;
    private final ai.philterd.arbiter.service.DataSourceHostAllowList hostAllowList;
    private final ai.philterd.arbiter.service.JdbcUrlValidator jdbcUrlValidator;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public AdminDataSourceController(final OpenSearchDataSourceRepository repository,
                                     final ElasticsearchDataSourceRepository esRepository,
                                     final S3DataSourceRepository s3Repository,
                                     final RelationalDbDataSourceRepository rdbRepository,
                                     final LocalDirectoryDataSourceRepository localRepository,
                                     final AuditLogService auditLogService,
                                     final SymmetricCipher cipher,
                                     final ObjectMapper objectMapper,
                                     final ai.philterd.arbiter.service.DataSourceHostAllowList hostAllowList,
                                     final ai.philterd.arbiter.service.JdbcUrlValidator jdbcUrlValidator) {
        this.repository = repository;
        this.esRepository = esRepository;
        this.s3Repository = s3Repository;
        this.rdbRepository = rdbRepository;
        this.localRepository = localRepository;
        this.auditLogService = auditLogService;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.hostAllowList = hostAllowList;
        this.jdbcUrlValidator = jdbcUrlValidator;
    }

    @GetMapping
    public String list(final Model model) {
        final List<OpenSearchDataSource> sources = repository
                .findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        model.addAttribute("dataSources", sources);

        final List<ElasticsearchDataSource> esSources = esRepository
                .findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        model.addAttribute("esDataSources", esSources);

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
                         @RequestParam("textField") final String textField,
                         @RequestParam(value = "filenameField", required = false) final String filenameField,
                         @RequestParam(value = "username", required = false) final String username,
                         @RequestParam(value = "password", required = false) final String password,
                         final RedirectAttributes redirectAttributes) {
        final String trimmedName = name == null ? "" : name.trim();
        final String trimmedEndpoint = endpoint == null ? "" : endpoint.trim();
        final String trimmedQuery = query == null ? "" : query.trim();
        final String trimmedTextField = textField == null ? "" : textField.trim();
        final String trimmedFilenameField = filenameField == null ? "" : filenameField.trim();
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
        if (trimmedTextField.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Text field is required.");
            return "redirect:/admin/data-sources";
        }
        final java.util.Optional<OpenSearchDataSource> existing =
                repository.findFirstByNameIgnoreCase(trimmedName);
        if (existing.isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A data source named \"" + existing.get().getName() + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        final OpenSearchDataSource source = new OpenSearchDataSource();
        source.setId(UUID.randomUUID().toString());
        source.setName(trimmedName);
        source.setEndpoint(trimmedEndpoint);
        source.setQuery(trimmedQuery);
        source.setTextField(trimmedTextField);
        source.setFilenameField(trimmedFilenameField.isEmpty() ? null : trimmedFilenameField);
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
                        "textField", trimmedTextField,
                        "filenameField", source.getFilenameField() == null ? "" : source.getFilenameField(),
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

    @PostMapping("/{id}/edit")
    public String editOpenSearch(@PathVariable final String id,
                                 @RequestParam("endpoint") final String endpoint,
                                 @RequestParam("query") final String query,
                                 @RequestParam("textField") final String textField,
                                 @RequestParam(value = "filenameField", required = false) final String filenameField,
                                 @RequestParam(value = "username", required = false) final String username,
                                 @RequestParam(value = "password", required = false) final String password,
                                 @RequestParam(value = "clearPassword", defaultValue = "false") final boolean clearPassword,
                                 final RedirectAttributes redirectAttributes) {
        final OpenSearchDataSource source = repository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "Data source not found.");
            return "redirect:/admin/data-sources";
        }
        final String trimmedEndpoint = endpoint == null ? "" : endpoint.trim();
        final String trimmedQuery = query == null ? "" : query.trim();
        final String trimmedTextField = textField == null ? "" : textField.trim();
        final String trimmedFilenameField = filenameField == null ? "" : filenameField.trim();
        final String trimmedUsername = username == null ? "" : username.trim();
        // Password is intentionally not trimmed.
        final String rawPassword = password == null ? "" : password;

        if (trimmedEndpoint.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Endpoint is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedQuery.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Query is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedTextField.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Text field is required.");
            return "redirect:/admin/data-sources";
        }

        // Password handling:
        //  - clearPassword=true wipes the stored password.
        //  - non-empty rawPassword replaces it.
        //  - blank rawPassword (and clearPassword=false) leaves the existing password alone so
        //    admins can edit other fields without re-typing credentials.
        final boolean passwordChanged;
        if (clearPassword) {
            source.setEncryptedPassword(null);
            passwordChanged = true;
        } else if (!rawPassword.isEmpty()) {
            source.setEncryptedPassword(cipher.encrypt(rawPassword));
            passwordChanged = true;
        } else {
            passwordChanged = false;
        }

        source.setEndpoint(trimmedEndpoint);
        source.setQuery(trimmedQuery);
        source.setTextField(trimmedTextField);
        source.setFilenameField(trimmedFilenameField.isEmpty() ? null : trimmedFilenameField);
        source.setUsername(trimmedUsername.isEmpty() ? null : trimmedUsername);
        repository.save(source);

        auditLogService.log("OPENSEARCH_DATASOURCE_UPDATE", "OpenSearchDataSource", source.getId(),
                Map.of("name", source.getName() == null ? "" : source.getName(),
                        "endpoint", trimmedEndpoint,
                        "query", trimmedQuery,
                        "textField", trimmedTextField,
                        "filenameField", source.getFilenameField() == null ? "" : source.getFilenameField(),
                        "username", source.getUsername() == null ? "" : source.getUsername(),
                        "passwordChanged", passwordChanged,
                        "passwordSet", source.getEncryptedPassword() != null));
        redirectAttributes.addFlashAttribute("success",
                "Data source \"" + source.getName() + "\" updated.");
        return "redirect:/admin/data-sources";
    }

    /**
     * Connect to the supplied OpenSearch endpoint, run the query, and return up to the first 10
     * hits. Used by the "Test" button on the Add OpenSearch data source form so the admin can
     * verify connectivity and the query shape before saving the data source.
     *
     * <p>Query format matches what we already accept on creation: {@code <path> <json>}, where
     * {@code <path>} is the index/_search path (e.g. {@code contracts/_search}) and {@code <json>}
     * is the request body.
     */
    @PostMapping("/test")
    @ResponseBody
    public Map<String, Object> testOpenSearch(@RequestParam("endpoint") final String endpoint,
                                              @RequestParam("query") final String query,
                                              @RequestParam(value = "username", required = false) final String username,
                                              @RequestParam(value = "password", required = false) final String password) {
        return runOpenSearchTest(endpoint, query, username, password);
    }

    /**
     * Same connectivity / preview test as {@link #testOpenSearch}, but for an already-saved
     * data source identified by id. The stored password is decrypted server-side so the UI
     * never has to handle the cleartext.
     */
    @PostMapping("/{id}/test")
    @ResponseBody
    public Map<String, Object> testSavedOpenSearch(@PathVariable final String id) {
        final OpenSearchDataSource source = repository.findById(id).orElse(null);
        if (source == null) {
            return Map.of("ok", false, "error", "Data source not found.");
        }
        final String password = source.getEncryptedPassword() == null
                || source.getEncryptedPassword().isEmpty()
                ? null
                : cipher.decrypt(source.getEncryptedPassword());
        return runOpenSearchTest(source.getEndpoint(), source.getQuery(),
                source.getUsername(), password);
    }

    private Map<String, Object> runOpenSearchTest(final String endpoint, final String query,
                                                  final String username, final String password) {
        final Map<String, Object> result = new LinkedHashMap<>();
        final String trimmedEndpoint = endpoint == null ? "" : endpoint.trim();
        final String trimmedQuery = query == null ? "" : query.trim();
        if (trimmedEndpoint.isEmpty()) {
            result.put("ok", false);
            result.put("error", "Endpoint is required.");
            return result;
        }
        if (trimmedQuery.isEmpty()) {
            result.put("ok", false);
            result.put("error", "Query is required.");
            return result;
        }
        // Optional defense-in-depth: when arbiter.data-sources.allowed-hosts is configured,
        // the endpoint host must be on the allow-list. Disabled by default; opt-in for
        // deployments where Arbiter has access to internal services that admins should not
        // be able to reach.
        if (!hostAllowList.isAllowed(trimmedEndpoint)) {
            result.put("ok", false);
            result.put("error", "Endpoint host is not on the data-source allow-list "
                    + "(arbiter.data-sources.allowed-hosts).");
            return result;
        }

        final int split = firstWhitespace(trimmedQuery);
        final String path = split < 0 ? trimmedQuery : trimmedQuery.substring(0, split);
        final String body = split < 0 ? "{}" : trimmedQuery.substring(split + 1).trim();

        final String url = trimmedEndpoint.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
        final HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (username != null && !username.isBlank()) {
            final String credentials = username + ":" + (password == null ? "" : password);
            final String basic = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            reqBuilder.header("Authorization", "Basic " + basic);
        }

        try {
            final HttpResponse<String> resp = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                result.put("ok", false);
                result.put("error", "OpenSearch returned HTTP " + resp.statusCode()
                        + (resp.body() == null || resp.body().isEmpty()
                            ? "." : ": " + truncate(resp.body(), 500)));
                return result;
            }
            final JsonNode root = objectMapper.readTree(resp.body());
            final JsonNode hitsArr = root.path("hits").path("hits");
            final List<Map<String, Object>> hits = new ArrayList<>();
            if (hitsArr.isArray()) {
                for (int i = 0; i < hitsArr.size() && i < 10; i++) {
                    final JsonNode hit = hitsArr.get(i);
                    final Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("_index", hit.path("_index").asText(""));
                    entry.put("_id", hit.path("_id").asText(""));
                    final JsonNode source = hit.path("_source");
                    entry.put("_source", source.isMissingNode() ? new LinkedHashMap<>()
                            : objectMapper.convertValue(source, Map.class));
                    hits.add(entry);
                }
            }
            final long total = root.path("hits").path("total").path("value").asLong(
                    root.path("hits").path("total").asLong(hits.size()));
            result.put("ok", true);
            result.put("total", total);
            result.put("hits", hits);
            return result;
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", "Could not reach OpenSearch: " + e.getMessage());
            return result;
        }
    }

    private static int firstWhitespace(final String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static String truncate(final String s, final int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static final java.util.regex.Pattern DANGEROUS_SQL_PATTERN =
            java.util.regex.Pattern.compile("\\b(DELETE|TRUNCATE|DROP)\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Return the set of mutation keywords (DELETE, TRUNCATE, DROP) found as whole words in the
     * supplied SQL, in upper case and de-duplicated. Whole-word matching avoids false positives
     * on column names like {@code dropoff_count} or {@code deleted_at}. Used to refuse
     * read-write SQL on relational data sources at create time.
     */
    private static List<String> dangerousSqlKeywords(final String sql) {
        if (sql == null || sql.isEmpty()) return List.of();
        final java.util.LinkedHashSet<String> matches = new java.util.LinkedHashSet<>();
        final java.util.regex.Matcher m = DANGEROUS_SQL_PATTERN.matcher(sql);
        while (m.find()) {
            matches.add(m.group(1).toUpperCase(java.util.Locale.ROOT));
        }
        return new ArrayList<>(matches);
    }

    // Elasticsearch ----------------------------------------------------------
    //
    // Behaves identically to OpenSearch: same query/_search/hits/_source dialect, same
    // create/edit/delete/test affordances. Stored in its own collection so a name can be
    // reused across types.

    @PostMapping("/es")
    public String createEs(@RequestParam("name") final String name,
                           @RequestParam("endpoint") final String endpoint,
                           @RequestParam("query") final String query,
                           @RequestParam("textField") final String textField,
                           @RequestParam(value = "filenameField", required = false) final String filenameField,
                           @RequestParam(value = "username", required = false) final String username,
                           @RequestParam(value = "password", required = false) final String password,
                           final RedirectAttributes redirectAttributes) {
        final String trimmedName = name == null ? "" : name.trim();
        final String trimmedEndpoint = endpoint == null ? "" : endpoint.trim();
        final String trimmedQuery = query == null ? "" : query.trim();
        final String trimmedTextField = textField == null ? "" : textField.trim();
        final String trimmedFilenameField = filenameField == null ? "" : filenameField.trim();
        final String trimmedUsername = username == null ? "" : username.trim();
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
        if (trimmedTextField.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Text field is required.");
            return "redirect:/admin/data-sources";
        }
        final java.util.Optional<ElasticsearchDataSource> existingEs =
                esRepository.findFirstByNameIgnoreCase(trimmedName);
        if (existingEs.isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "An Elasticsearch data source named \"" + existingEs.get().getName() + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        final ElasticsearchDataSource source = new ElasticsearchDataSource();
        source.setId(UUID.randomUUID().toString());
        source.setName(trimmedName);
        source.setEndpoint(trimmedEndpoint);
        source.setQuery(trimmedQuery);
        source.setTextField(trimmedTextField);
        source.setFilenameField(trimmedFilenameField.isEmpty() ? null : trimmedFilenameField);
        source.setUsername(trimmedUsername.isEmpty() ? null : trimmedUsername);
        source.setEncryptedPassword(rawPassword.isEmpty() ? null : cipher.encrypt(rawPassword));
        source.setCreatedAt(LocalDateTime.now());

        try {
            esRepository.save(source);
        } catch (DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "An Elasticsearch data source named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        auditLogService.log("ELASTICSEARCH_DATASOURCE_CREATE", "ElasticsearchDataSource", source.getId(),
                Map.of("name", trimmedName,
                        "endpoint", trimmedEndpoint,
                        "query", trimmedQuery,
                        "textField", trimmedTextField,
                        "filenameField", source.getFilenameField() == null ? "" : source.getFilenameField(),
                        "username", source.getUsername() == null ? "" : source.getUsername(),
                        "passwordSet", source.getEncryptedPassword() != null));
        redirectAttributes.addFlashAttribute("success",
                "Elasticsearch data source \"" + trimmedName + "\" added.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/es/{id}/delete")
    public String deleteEs(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final ElasticsearchDataSource source = esRepository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "Data source not found.");
            return "redirect:/admin/data-sources";
        }
        esRepository.deleteById(id);
        auditLogService.log("ELASTICSEARCH_DATASOURCE_DELETE", "ElasticsearchDataSource", id,
                Map.of("name", source.getName() == null ? "" : source.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Elasticsearch data source \"" + source.getName() + "\" removed.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/es/{id}/edit")
    public String editEs(@PathVariable final String id,
                         @RequestParam("endpoint") final String endpoint,
                         @RequestParam("query") final String query,
                         @RequestParam("textField") final String textField,
                         @RequestParam(value = "filenameField", required = false) final String filenameField,
                         @RequestParam(value = "username", required = false) final String username,
                         @RequestParam(value = "password", required = false) final String password,
                         @RequestParam(value = "clearPassword", defaultValue = "false") final boolean clearPassword,
                         final RedirectAttributes redirectAttributes) {
        final ElasticsearchDataSource source = esRepository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "Data source not found.");
            return "redirect:/admin/data-sources";
        }
        final String trimmedEndpoint = endpoint == null ? "" : endpoint.trim();
        final String trimmedQuery = query == null ? "" : query.trim();
        final String trimmedTextField = textField == null ? "" : textField.trim();
        final String trimmedFilenameField = filenameField == null ? "" : filenameField.trim();
        final String trimmedUsername = username == null ? "" : username.trim();
        final String rawPassword = password == null ? "" : password;

        if (trimmedEndpoint.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Endpoint is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedQuery.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Query is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedTextField.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Text field is required.");
            return "redirect:/admin/data-sources";
        }

        final boolean passwordChanged;
        if (clearPassword) {
            source.setEncryptedPassword(null);
            passwordChanged = true;
        } else if (!rawPassword.isEmpty()) {
            source.setEncryptedPassword(cipher.encrypt(rawPassword));
            passwordChanged = true;
        } else {
            passwordChanged = false;
        }

        source.setEndpoint(trimmedEndpoint);
        source.setQuery(trimmedQuery);
        source.setTextField(trimmedTextField);
        source.setFilenameField(trimmedFilenameField.isEmpty() ? null : trimmedFilenameField);
        source.setUsername(trimmedUsername.isEmpty() ? null : trimmedUsername);
        esRepository.save(source);

        auditLogService.log("ELASTICSEARCH_DATASOURCE_UPDATE", "ElasticsearchDataSource", source.getId(),
                Map.of("name", source.getName() == null ? "" : source.getName(),
                        "endpoint", trimmedEndpoint,
                        "query", trimmedQuery,
                        "textField", trimmedTextField,
                        "filenameField", source.getFilenameField() == null ? "" : source.getFilenameField(),
                        "username", source.getUsername() == null ? "" : source.getUsername(),
                        "passwordChanged", passwordChanged,
                        "passwordSet", source.getEncryptedPassword() != null));
        redirectAttributes.addFlashAttribute("success",
                "Elasticsearch data source \"" + source.getName() + "\" updated.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/es/test")
    @ResponseBody
    public Map<String, Object> testElasticsearch(@RequestParam("endpoint") final String endpoint,
                                                 @RequestParam("query") final String query,
                                                 @RequestParam(value = "username", required = false) final String username,
                                                 @RequestParam(value = "password", required = false) final String password) {
        return runOpenSearchTest(endpoint, query, username, password);
    }

    @PostMapping("/es/{id}/test")
    @ResponseBody
    public Map<String, Object> testSavedElasticsearch(@PathVariable final String id) {
        final ElasticsearchDataSource source = esRepository.findById(id).orElse(null);
        if (source == null) {
            return Map.of("ok", false, "error", "Data source not found.");
        }
        final String password = source.getEncryptedPassword() == null
                || source.getEncryptedPassword().isEmpty()
                ? null
                : cipher.decrypt(source.getEncryptedPassword());
        return runOpenSearchTest(source.getEndpoint(), source.getQuery(),
                source.getUsername(), password);
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
        final java.util.Optional<S3DataSource> existingS3 =
                s3Repository.findFirstByNameIgnoreCase(trimmedName);
        if (existingS3.isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "An S3 data source named \"" + existingS3.get().getName() + "\" already exists.");
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
        // SSRF / RCE defense: refuse JDBC URLs whose driver can run code at connect
        // time (H2 INIT, Derby restoreFrom), or that smuggle a known-dangerous param
        // (PostgreSQL socketFactory, MySQL autoDeserialize, etc.). When the deployer
        // has configured arbiter.data-sources.allowed-hosts, the host portion is also
        // gated against that list. See JdbcUrlValidator for the full rule set.
        final ai.philterd.arbiter.service.JdbcUrlValidator.Result urlCheck =
                jdbcUrlValidator.validate(trimmedUrl);
        if (!urlCheck.ok()) {
            auditLogService.log("RDB_DANGEROUS_JDBC_URL_BLOCKED", "RelationalDbDataSource", null,
                    Map.of("name", trimmedName,
                            "jdbcUrl", trimmedUrl,
                            "reason", urlCheck.error() == null ? "" : urlCheck.error()));
            redirectAttributes.addFlashAttribute("error", urlCheck.error());
            return "redirect:/admin/data-sources";
        }
        if (trimmedSql.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "SQL query is required.");
            return "redirect:/admin/data-sources";
        }
        final List<String> dangerous = dangerousSqlKeywords(trimmedSql);
        if (!dangerous.isEmpty()) {
            // Refuse to save a data source whose SQL could mutate or remove data on the
            // remote system. Audit the attempt with the matched keywords so admins can
            // investigate intent without re-storing the offending SQL twice.
            auditLogService.log("RDB_DANGEROUS_SQL_BLOCKED", "RelationalDbDataSource", null,
                    Map.of("name", trimmedName,
                            "jdbcUrl", trimmedUrl,
                            "keywords", dangerous,
                            "sqlQuery", trimmedSql));
            redirectAttributes.addFlashAttribute("error",
                    "SQL query contains disallowed keyword(s) " + String.join(", ", dangerous)
                            + ". Data sources must use read-only queries.");
            return "redirect:/admin/data-sources";
        }
        // A username without a password (or vice versa) is almost always a typo.
        if (rawUsername.isEmpty() != rawPassword.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    "Provide both Username and Password, or leave both blank.");
            return "redirect:/admin/data-sources";
        }
        final java.util.Optional<RelationalDbDataSource> existingRdb =
                rdbRepository.findFirstByNameIgnoreCase(trimmedName);
        if (existingRdb.isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A relational database data source named \"" + existingRdb.get().getName() + "\" already exists.");
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
        final java.util.Optional<LocalDirectoryDataSource> existingLocal =
                localRepository.findFirstByNameIgnoreCase(trimmedName);
        if (existingLocal.isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A local directory data source named \"" + existingLocal.get().getName() + "\" already exists.");
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
