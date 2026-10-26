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
import ai.philterd.arbiter.service.PhilterInstanceTls;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.io.IOException;
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
    private final PhilterInstanceTls instanceTls;

    /** Keystores are a few kilobytes; anything larger is not one. */
    private static final long MAX_STORE_BYTES = 256 * 1024;

    public AdminPhilterController(final PhilterInstanceRepository repository,
                                  final AuditLogService auditLogService,
                                  final PhilterDefaultsService defaultsService,
                                  final SymmetricCipher symmetricCipher,
                                  final DataSourceHostAllowList hostAllowList,
                                  final PhilterInstanceClients philterInstanceClients,
                                  final PhilterInstanceTls instanceTls) {
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.defaultsService = defaultsService;
        this.symmetricCipher = symmetricCipher;
        this.hostAllowList = hostAllowList;
        this.philterInstanceClients = philterInstanceClients;
        this.instanceTls = instanceTls;
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
        final KeyCheck check = checkKey(instance);
        if (check.refusal() != null) {
            auditKeyRefused(instance, check);
            redirectAttributes.addFlashAttribute("error", check.refusal());
            return "redirect:/admin/philter";
        }
        final Boolean adminKey = check.admin();
        instance.setApiKeyAdmin(adminKey);

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
                "Philter instance \"" + trimmedName + "\" added." + (adminKey == null ? NOT_CHECKED : ""));
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
        final String previousKey = instance.getEncryptedApiKey();
        instance.setEncryptedApiKey(symmetricCipher.encrypt(trimmed));
        final KeyCheck check = checkKey(instance);
        if (check.refusal() != null) {
            instance.setEncryptedApiKey(previousKey);
            auditKeyRefused(instance, check);
            redirectAttributes.addFlashAttribute("error", check.refusal());
            return "redirect:/admin/philter";
        }
        final Boolean adminKey = check.admin();
        instance.setApiKeyAdmin(adminKey);
        repository.save(instance);
        auditLogService.log("PHILTER_INSTANCE_API_KEY_CHANGE", "PhilterInstance", id,
                Map.of(
                        "name", instance.getName() == null ? "" : instance.getName(),
                        "previouslyConfigured", previouslyConfigured,
                        "currentlyConfigured", true));
        redirectAttributes.addFlashAttribute("success",
                "API key updated for \"" + instance.getName() + "\"." + (adminKey == null ? NOT_CHECKED : ""));
        return "redirect:/admin/philter";
    }

    @PostMapping("/{id}/tls")
    public String setTls(@PathVariable final String id,
                         @RequestParam(value = "keyStore", required = false) final MultipartFile keyStore,
                         @RequestParam(value = "keyStorePassword", required = false) final String keyStorePassword,
                         @RequestParam(value = "trustStore", required = false) final MultipartFile trustStore,
                         @RequestParam(value = "trustStorePassword", required = false) final String trustStorePassword,
                         final RedirectAttributes redirectAttributes) {
        final PhilterInstance instance = repository.findById(id).orElse(null);
        if (instance == null) {
            redirectAttributes.addFlashAttribute("error", "Philter instance not found.");
            return "redirect:/admin/philter";
        }
        final boolean hasKeyStore = keyStore != null && !keyStore.isEmpty();
        final boolean hasTrustStore = trustStore != null && !trustStore.isEmpty();
        if (!hasKeyStore && !hasTrustStore) {
            redirectAttributes.addFlashAttribute("error", "Choose a client certificate keystore, a trust store, or both.");
            return "redirect:/admin/philter";
        }
        final boolean hadKeyStore = PhilterInstanceTls.hasKeyStore(instance);
        final boolean hadTrustStore = PhilterInstanceTls.hasTrustStore(instance);
        try {
            if (hasKeyStore) {
                if (keyStorePassword == null || keyStorePassword.isEmpty()) {
                    throw new IllegalArgumentException("The client certificate keystore needs its password.");
                }
                instanceTls.setKeyStore(instance, read(keyStore, "client certificate keystore"), keyStorePassword);
            }
            if (hasTrustStore) {
                instanceTls.setTrustStore(instance, read(trustStore, "trust store"), trustStorePassword);
            }
        } catch (IllegalArgumentException e) {
            // The message names the store and the problem, never the password.
            redirectAttributes.addFlashAttribute("error", "TLS settings for \"" + instance.getName()
                    + "\" were not saved. " + e.getMessage());
            return "redirect:/admin/philter";
        }
        repository.save(instance);
        auditLogService.log("PHILTER_INSTANCE_TLS_CHANGE", "PhilterInstance", id,
                Map.of("name", str(instance.getName()),
                        "keyStore", hasKeyStore ? (hadKeyStore ? "replaced" : "added") : "unchanged",
                        "trustStore", hasTrustStore ? (hadTrustStore ? "replaced" : "added") : "unchanged"));
        redirectAttributes.addFlashAttribute("success",
                "TLS settings updated for \"" + instance.getName() + "\".");
        return "redirect:/admin/philter";
    }

    @PostMapping("/{id}/tls/remove")
    public String removeTls(@PathVariable final String id,
                            @RequestParam("store") final String store,
                            final RedirectAttributes redirectAttributes) {
        final PhilterInstance instance = repository.findById(id).orElse(null);
        if (instance == null) {
            redirectAttributes.addFlashAttribute("error", "Philter instance not found.");
            return "redirect:/admin/philter";
        }
        final String removed;
        if ("keyStore".equals(store) && PhilterInstanceTls.hasKeyStore(instance)) {
            instanceTls.clearKeyStore(instance);
            removed = "client certificate";
        } else if ("trustStore".equals(store) && PhilterInstanceTls.hasTrustStore(instance)) {
            instanceTls.clearTrustStore(instance);
            removed = "trust store";
        } else {
            redirectAttributes.addFlashAttribute("error", "That TLS setting is not set on this instance.");
            return "redirect:/admin/philter";
        }
        repository.save(instance);
        auditLogService.log("PHILTER_INSTANCE_TLS_CHANGE", "PhilterInstance", id,
                Map.of("name", str(instance.getName()),
                        "keyStore", "keyStore".equals(store) ? "removed" : "unchanged",
                        "trustStore", "trustStore".equals(store) ? "removed" : "unchanged"));
        redirectAttributes.addFlashAttribute("success", "Removed the " + removed + " from \""
                + instance.getName() + "\"; it now uses the global setting.");
        return "redirect:/admin/philter";
    }

    private static byte[] read(final MultipartFile file, final String what) {
        if (file.getSize() > MAX_STORE_BYTES) {
            throw new IllegalArgumentException("The " + what + " is larger than a keystore can be ("
                    + (MAX_STORE_BYTES / 1024) + " KB at most).");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("The " + what + " could not be read from the upload.", e);
        }
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
            boolean administratorsKey = false;
            if (philterInstanceClients.hasApiKey(instance)) {
                // health() is unauthenticated; getLimits() accepts any valid key, whatever its scopes,
                // and reports the role of the key's user.
                final ai.philterd.philter.model.Limits limits = client.getLimits();
                final String role = limits == null || limits.getCaller() == null ? null : limits.getCaller().getRole();
                final Boolean adminKey = role == null ? null : "admin".equalsIgnoreCase(role);
                recordKeyRole(instance, adminKey);
                if (Boolean.TRUE.equals(adminKey)) {
                    administratorsKey = true;
                    detail += ", but the API key belongs to a Philter administrator. Remove that user's "
                            + "administrator role in Philter and narrow the key, rather than switching to "
                            + "another user's key, which would lose access to its policies and contexts";
                } else if (adminKey == null) {
                    detail += ", API key accepted, but Philter did not report its role";
                } else {
                    detail += ", API key accepted";
                }
            } else {
                detail += ", no API key set";
            }
            ok = !administratorsKey;
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

    static final String ADMIN_KEY = "This API key belongs to a Philter administrator, and Arbiter uses the key "
            + "for background work such as ingest. If Arbiter's policies and contexts already belong to this "
            + "key's user, keep the user and remove its administrator role in Philter, then narrow the key to "
            + "the redact and policies:read scopes. Otherwise use a key for a Philter user who is not an "
            + "administrator. See the Philter section of Arbiter's workflow docs.";

    private static final String NOT_CHECKED = " Philter could not be reached to check that the key does not "
            + "belong to an administrator; use Test once it is.";

    /**
     * What Philter says about an instance's key before it is saved: whether it is an administrator's
     * ({@code admin} null when Philter could not be reached or did not say), and the error to refuse it
     * with, for an administrator's key or one Philter rejects.
     */
    private record KeyCheck(Boolean admin, String refusal, String reason) {
    }

    private KeyCheck checkKey(final PhilterInstance instance) {
        try {
            final Boolean admin = philterInstanceClients.keyBelongsToAdministrator(instance);
            return Boolean.TRUE.equals(admin)
                    ? new KeyCheck(true, ADMIN_KEY, "administrator") : new KeyCheck(admin, null, null);
        } catch (UnauthorizedException e) {
            return new KeyCheck(null, "Philter rejected this API key. Check the key and try again.", "rejected");
        } catch (Exception e) {
            return new KeyCheck(null, null, null);
        }
    }

    private void auditKeyRefused(final PhilterInstance instance, final KeyCheck check) {
        auditLogService.log("PHILTER_INSTANCE_API_KEY_REFUSED", "PhilterInstance", instance.getId(),
                Map.of("name", instance.getName() == null ? "" : instance.getName(), "reason", check.reason()));
    }

    /**
     * Saves what Philter reported about the key on a fresh read of the instance, and only if its key is
     * still the one checked, so a change made meanwhile (a new key, TLS stores) is not overwritten.
     */
    private void recordKeyRole(final PhilterInstance checked, final Boolean adminKey) {
        repository.findById(checked.getId()).ifPresent(fresh -> {
            if (java.util.Objects.equals(fresh.getEncryptedApiKey(), checked.getEncryptedApiKey())) {
                fresh.setApiKeyAdmin(adminKey);
                repository.save(fresh);
            }
        });
    }

    private static String buildInstanceUrl(final String endpoint, final int port) {
        String host = endpoint == null ? "localhost" : endpoint;
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        return host + ":" + port;
    }
}
