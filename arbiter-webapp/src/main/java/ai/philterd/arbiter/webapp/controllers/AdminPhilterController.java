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

import ai.philterd.arbiter.model.PhilterDefaults;
import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.DataSourceHostAllowList;
import ai.philterd.arbiter.service.PhilterDefaultsService;
import ai.philterd.arbiter.service.PhilterInstanceClients;
import ai.philterd.arbiter.philter.PhilterClients;
import ai.philterd.philter.model.StatusResponse;
import ai.philterd.philter.model.exceptions.PhilterError;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import ai.philterd.arbiter.service.SymmetricCipher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/admin/philter")
public class AdminPhilterController {

    private final PhilterInstanceRepository repository;
    private final AuditLogService auditLogService;
    private final PhilterDefaultsService defaultsService;
    private final SymmetricCipher symmetricCipher;
    private final DataSourceHostAllowList hostAllowList;
    private final PhilterInstanceClients philterInstanceClients;

    public AdminPhilterController(final PhilterInstanceRepository repository,
                                  final AuditLogService auditLogService,
                                  final PhilterDefaultsService defaultsService,
                                  final SymmetricCipher symmetricCipher,
                                  final DataSourceHostAllowList hostAllowList,
                                  final PhilterInstanceClients philterInstanceClients) {
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.defaultsService = defaultsService;
        this.symmetricCipher = symmetricCipher;
        this.hostAllowList = hostAllowList;
        this.philterInstanceClients = philterInstanceClients;
    }

    @GetMapping
    public String list(final Model model) {
        final List<PhilterInstance> instances = repository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        model.addAttribute("instances", instances);
        model.addAttribute("defaults", defaultsService.load());
        return "admin-philter";
    }

    @PostMapping("/defaults")
    public String setDefaults(@RequestParam(value = "defaultInstanceId", required = false) final String defaultInstanceId,
                              final RedirectAttributes redirectAttributes) {
        final PhilterDefaults defaults = defaultsService.load();
        defaults.setDefaultInstanceId(normalize(defaultInstanceId));

        if (defaults.getDefaultInstanceId() != null
                && !repository.existsById(defaults.getDefaultInstanceId())) {
            redirectAttributes.addFlashAttribute("error",
                    "Selected Philter instance no longer exists.");
            return "redirect:/admin/philter";
        }

        defaultsService.save(defaults);
        auditLogService.log("PHILTER_DEFAULTS_CHANGE", "Settings",
                PhilterDefaults.SINGLETON_ID,
                Map.of("defaultInstanceId", str(defaults.getDefaultInstanceId())));
        redirectAttributes.addFlashAttribute("success", "Philter defaults saved.");
        return "redirect:/admin/philter";
    }

    private static String normalize(final String s) {
        if (s == null) return null;
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String str(final String s) {
        return s == null ? "" : s;
    }

    @PostMapping
    public String create(@RequestParam("name") final String name,
                         @RequestParam("endpoint") final String endpoint,
                         @RequestParam("port") final int port,
                         @RequestParam(value = "apiKey", required = false) final String apiKey,
                         final RedirectAttributes redirectAttributes) {
        final String trimmedName = name == null ? "" : name.trim();
        final String trimmedEndpoint = endpoint == null ? "" : endpoint.trim();
        final String trimmedKey = apiKey == null ? "" : apiKey.trim();

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/philter";
        }
        if (trimmedEndpoint.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Endpoint is required.");
            return "redirect:/admin/philter";
        }
        if (port < 1 || port > 65535) {
            redirectAttributes.addFlashAttribute("error", "Port must be between 1 and 65535.");
            return "redirect:/admin/philter";
        }
        if (trimmedKey.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "API key is required.");
            return "redirect:/admin/philter";
        }
        if (repository.findByName(trimmedName).isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A Philter instance named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/philter";
        }

        final String instanceUrl = buildInstanceUrl(trimmedEndpoint, port);
        if (!hostAllowList.isAllowed(instanceUrl)) {
            redirectAttributes.addFlashAttribute("error",
                    "Endpoint host is not permitted. Private network addresses (loopback, "
                            + "RFC-1918, link-local) are blocked by default. To allow an internal "
                            + "host, add it to arbiter.data-sources.allowed-hosts.");
            return "redirect:/admin/philter";
        }

        final PhilterInstance instance = new PhilterInstance();
        instance.setCreatedAt(LocalDateTime.now());
        instance.setId(UUID.randomUUID().toString());
        instance.setName(trimmedName);
        instance.setEndpoint(trimmedEndpoint);
        instance.setPort(port);
        instance.setEncryptedApiKey(symmetricCipher.encrypt(trimmedKey));

        try {
            repository.save(instance);
        } catch (DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "A Philter instance named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/philter";
        }

        auditLogService.log("PHILTER_INSTANCE_CREATE", "PhilterInstance", instance.getId(),
                Map.of("name", trimmedName, "endpoint", trimmedEndpoint, "port", port,
                        "apiKeyConfigured", true));
        redirectAttributes.addFlashAttribute("success",
                "Philter instance \"" + trimmedName + "\" added.");
        return "redirect:/admin/philter";
    }

    @PostMapping("/{id}/api-key")
    public String setApiKey(@PathVariable final String id,
                            @RequestParam(value = "apiKey", required = false) final String apiKey,
                            final RedirectAttributes redirectAttributes) {
        final PhilterInstance instance = repository.findById(id).orElse(null);
        if (instance == null) {
            redirectAttributes.addFlashAttribute("error", "Philter instance not found.");
            return "redirect:/admin/philter";
        }
        final String trimmed = apiKey == null ? "" : apiKey.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "API key is required.");
            return "redirect:/admin/philter";
        }
        final boolean previouslyConfigured = instance.getEncryptedApiKey() != null
                && !instance.getEncryptedApiKey().isBlank();
        instance.setEncryptedApiKey(symmetricCipher.encrypt(trimmed));
        repository.save(instance);
        auditLogService.log("PHILTER_INSTANCE_API_KEY_CHANGE", "PhilterInstance", id,
                Map.of(
                        "name", instance.getName() == null ? "" : instance.getName(),
                        "previouslyConfigured", previouslyConfigured,
                        "currentlyConfigured", true));
        redirectAttributes.addFlashAttribute("success",
                "API key updated for \"" + instance.getName() + "\".");
        return "redirect:/admin/philter";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final PhilterInstance instance = repository.findById(id).orElse(null);
        if (instance == null) {
            redirectAttributes.addFlashAttribute("error", "Philter instance not found.");
            return "redirect:/admin/philter";
        }

        final PhilterDefaults defaults = defaultsService.load();
        if (id.equals(defaults.getDefaultInstanceId())) {
            defaults.setDefaultInstanceId(null);
            defaultsService.save(defaults);
        }

        repository.deleteById(id);
        auditLogService.log("PHILTER_INSTANCE_DELETE", "PhilterInstance", id,
                Map.of("name", instance.getName() == null ? "" : instance.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Philter instance \"" + instance.getName() + "\" removed.");
        return "redirect:/admin/philter";
    }

    @PostMapping("/{id}/test")
    public String test(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final PhilterInstance instance = repository.findById(id).orElse(null);
        if (instance == null) {
            redirectAttributes.addFlashAttribute("error", "Philter instance not found.");
            return "redirect:/admin/philter";
        }

        final String url = PhilterInstanceClients.baseUrl(instance);
        if (!hostAllowList.isAllowed(url)) {
            redirectAttributes.addFlashAttribute("error",
                    "Endpoint host is not permitted. Update or remove this instance.");
            return "redirect:/admin/philter";
        }
        boolean ok = false;
        int status = 0;
        String detail;
        try {
            final ai.philterd.philter.PhilterClient client = philterInstanceClients.client(instance);
            final StatusResponse health = client.health();
            detail = "Philter " + (health == null || health.getApplicationVersion() == null
                    ? "(unknown version)" : health.getApplicationVersion());
            if (philterInstanceClients.hasApiKey(instance)) {
                // health() is unauthenticated; getLimits() accepts any valid key, whatever its scopes.
                client.getLimits();
                detail += ", API key accepted";
            } else {
                detail += ", no API key set";
            }
            ok = true;
        } catch (UnauthorizedException e) {
            status = e.getStatusCode();
            detail = "API key rejected (" + PhilterClients.describe(e) + ")";
        } catch (Exception e) {
            if (e instanceof PhilterError error) {
                status = error.getStatusCode();
                detail = PhilterClients.describe(e);
            } else {
                detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }

        auditLogService.log("PHILTER_INSTANCE_TEST", "PhilterInstance", id,
                Map.of("name", instance.getName() == null ? "" : instance.getName(),
                        "url", url,
                        "ok", ok,
                        "status", status,
                        "detail", detail));

        if (ok) {
            redirectAttributes.addFlashAttribute("success",
                    "Philter instance \"" + instance.getName() + "\" responded ("
                            + detail + ") at " + url + ".");
        } else {
            redirectAttributes.addFlashAttribute("error",
                    "Philter instance \"" + instance.getName() + "\" failed the test at "
                            + url + ": " + detail);
        }
        return "redirect:/admin/philter";
    }

    private static String buildInstanceUrl(final String endpoint, final int port) {
        String host = endpoint == null ? "localhost" : endpoint;
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        return host + ":" + port;
    }
}
