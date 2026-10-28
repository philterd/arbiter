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

import ai.philterd.arbiter.philter.PhilterClients;
import ai.philterd.arbiter.service.SymmetricCipher;
import ai.philterd.arbiter.webapp.security.PhilterSessionAuthentication;
import ai.philterd.philter.PhilterClient;
import ai.philterd.philter.model.CreateUserRequest;
import ai.philterd.philter.model.MfaEnrollment;
import ai.philterd.philter.model.SignInResponse;
import ai.philterd.philter.model.UpdateAdminSettingsRequest;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-in through Philter, end to end: the real Arbiter (SQLite, in-memory sessions) against a
 * {@code philterd/philter:4.0.0} container with password sign-in enabled. Needs Docker; runs in
 * {@code mvn verify}.
 *
 * <p>Philter runs with a one-minute idle timeout for session keys and Arbiter checks the key on every
 * request, so expiry can be seen in about a minute. Each test signs in from its own address, which
 * Philter believes since every address is a trusted proxy here, so Philter's per-address rate limit is
 * not shared between tests.</p>
 */
// Set here rather than in @DynamicPropertySource, which is applied after Arbiter's store post-processor
// reads arbiter.store. Spring Session is left out, so sessions are in memory and no Valkey is needed.
@SpringBootTest(classes = ArbiterApplication.class, properties = {
        "arbiter.sign-in.mode=philter",
        "arbiter.sign-in.check-interval-seconds=0",
        "arbiter.store=sqlite",
        "arbiter.demo-data.enabled=false",
        "arbiter.data-sources.allowed-hosts=localhost,127.0.0.1",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.session.SessionAutoConfiguration"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PhilterSignInIT {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BOOTSTRAP_KEY = "sk_" + HexFormat.of().formatHex(bytes(16));
    private static final String ADMIN_PASSWORD = "admin-" + HexFormat.of().formatHex(bytes(12));
    private static final AtomicInteger ADDRESSES = new AtomicInteger(1);

    static final GenericContainer<?> PHILTER = new GenericContainer<>("philterd/philter:4.0.0")
            .withExposedPorts(8080)
            .withEnv("PHILTER_ENCRYPTION_KEY", Base64.getEncoder().encodeToString(bytes(32)))
            .withEnv("PHILTER_BOOTSTRAP_API_KEY", BOOTSTRAP_KEY)
            .withEnv("PASSWORD_SIGN_IN_ENABLED", "true")
            .withEnv("SSL_ENABLED", "false")
            .withEnv("SESSION_KEY_IDLE_TIMEOUT_MINUTES", "1")
            .withEnv("TRUSTED_PROXIES", "0.0.0.0/0,::/0")
            .waitingFor(Wait.forHttp("/api/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    static {
        PHILTER.start();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private SymmetricCipher cipher;
    @Autowired private ai.philterd.arbiter.repository.UserRepository userRepository;
    @Autowired private ai.philterd.arbiter.service.ApiKeyHashingService apiKeyHashingService;
    @Autowired private ai.philterd.arbiter.service.RedactionService redactionService;

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) throws Exception {
        registry.add("arbiter.philter.url", PhilterSignInIT::philterUrl);
        // An administrator's key, so Admin → Philter's check has something to flag.
        registry.add("arbiter.philter.api-key", () -> BOOTSTRAP_KEY);
        final String database = Files.createTempDirectory("arbiter-it").resolve("arbiter.db").toString();
        registry.add("arbiter.sqlite.path", () -> database);
    }

    @BeforeAll
    static void setUpPhilter() throws Exception {
        bootstrap().setPassword("admin", ADMIN_PASSWORD);
    }

    private static byte[] bytes(final int n) {
        final byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String philterUrl() {
        return "http://" + PHILTER.getHost() + ":" + PHILTER.getMappedPort(8080);
    }

    private static PhilterClient bootstrap() {
        return PhilterClients.build(philterUrl(), BOOTSTRAP_KEY, (javax.net.ssl.SSLContext) null);
    }

    private static String password() {
        return "pw-" + HexFormat.of().formatHex(bytes(12));
    }

    /** A user created in Philter with a password they must change, as an administrator creates one. */
    private static String createUser(final String prefix, final String password) throws Exception {
        final String username = prefix + "-" + HexFormat.of().formatHex(bytes(3));
        final CreateUserRequest request = new CreateUserRequest();
        request.setUsername(username);
        request.setRole("user");
        request.setPassword(password);
        bootstrap().createUser(request);
        return username;
    }

    /** A user who has already chosen their own password. */
    private static String createSignedUpUser(final String prefix, final String password) throws Exception {
        final String first = password();
        final String username = createUser(prefix, first);
        final SignInResponse limited = bootstrap().signIn(username, first);
        PhilterClients.build(philterUrl(), limited.getApiKey(), (javax.net.ssl.SSLContext) null)
                .changePassword(first, password);
        return username;
    }

    /** Each test signs in from its own address, so Philter's per-address rate limit is its own. */
    private static RequestPostProcessor from(final String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static String newAddress() {
        return "10.20." + (ADDRESSES.get() / 250) + "." + (ADDRESSES.getAndIncrement() % 250 + 1);
    }

    private MvcResult signIn(final MockHttpSession session, final String address, final String username,
                             final String password) throws Exception {
        return mockMvc.perform(post("/login").session(session).with(csrf()).with(from(address))
                .param("username", username).param("password", password)).andReturn();
    }

    private String encryptedKey(final MockHttpSession session) {
        final SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertNotNull(context, "signed in");
        return ((PhilterSessionAuthentication) context.getAuthentication()).getEncryptedSessionKey();
    }

    private static String code(final String secret, final long step) throws Exception {
        return new DefaultCodeGenerator().generate(secret, step);
    }

    private static long currentStep() {
        return Instant.now().getEpochSecond() / 30;
    }

    /** Waits for the next 30-second step, since Philter accepts each code once. */
    private static long nextStep(final long used) throws InterruptedException {
        while (currentStep() <= used) {
            Thread.sleep(1000);
        }
        return currentStep();
    }

    @Test
    void signInKeepsTheKeyOnTheServerAndSignOutRevokesIt(final CapturedOutput output) throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        final MvcResult result = signIn(session, address, "admin", ADMIN_PASSWORD);
        assertEquals("/", result.getResponse().getRedirectedUrl());
        final String sessionKey = cipher.decrypt(encryptedKey(session));
        assertFalse(result.getResponse().getContentAsString().contains(sessionKey));
        assertTrue(result.getResponse().getCookies().length == 0
                || java.util.Arrays.stream(result.getResponse().getCookies()).noneMatch(c -> c.getValue().contains(sessionKey)));
        mockMvc.perform(get("/admin/users").session(session).with(from(address))).andExpect(status().isOk());

        mockMvc.perform(post("/logout").session(session).with(csrf()).with(from(address)))
                .andExpect(redirectedUrl("/login?logout"));

        assertThrows(UnauthorizedException.class,
                () -> PhilterClients.build(philterUrl(), sessionKey, (javax.net.ssl.SSLContext) null).getCurrentUser());
        assertFalse(output.getAll().contains(ADMIN_PASSWORD), "the password is never logged");
        assertFalse(output.getAll().contains(sessionKey), "the session key is never logged");
    }

    @Test
    void wrongPasswordAndUnknownUserAreTheSameAndLockAlike(final CapturedOutput output) throws Exception {
        final String password = password();
        final String user = createSignedUpUser("lock", password);
        final String address = newAddress();
        assertEquals("/login?error", signIn(new MockHttpSession(), address, user, "wrong-" + password)
                .getResponse().getRedirectedUrl());
        assertEquals("/login?error", signIn(new MockHttpSession(), address, "nobody-" + user, password)
                .getResponse().getRedirectedUrl());

        for (int i = 0; i < 4; i++) {
            signIn(new MockHttpSession(), address, user, "wrong-" + password);
            signIn(new MockHttpSession(), address, "nobody-" + user, password);
        }
        assertEquals("/login?locked", signIn(new MockHttpSession(), address, user, password)
                .getResponse().getRedirectedUrl(), "locked even with the right password");
        assertEquals("/login?locked", signIn(new MockHttpSession(), address, "nobody-" + user, password)
                .getResponse().getRedirectedUrl());
        assertFalse(output.getAll().contains("wrong-" + password), "passwords are never logged");
    }

    @Test
    void forcedPasswordChangeComesBeforeAnyOtherPage() throws Exception {
        final String first = password();
        final String user = createUser("change", first);
        final String address = newAddress();
        final MockHttpSession session = new MockHttpSession();

        assertEquals("/sign-in/password", signIn(session, address, user, first).getResponse().getRedirectedUrl());
        mockMvc.perform(get("/settings").session(session).with(from(address)).accept("text/html"))
                .andExpect(redirectedUrl("http://localhost/login"));

        final String chosen = password();
        mockMvc.perform(post("/sign-in/password").session(session).with(csrf()).with(from(address))
                        .param("currentPassword", first).param("newPassword", chosen).param("confirmPassword", chosen))
                .andExpect(redirectedUrl("/login?passwordChanged"));

        assertEquals("/", signIn(new MockHttpSession(), address, user, chosen).getResponse().getRedirectedUrl());
    }

    @Test
    void mfaEnrollmentAndSignInWithACode() throws Exception {
        final String password = password();
        final String user = createSignedUpUser("mfa", password);
        final String address = newAddress();
        final UpdateAdminSettingsRequest required = new UpdateAdminSettingsRequest();
        required.setMfaAvailable(true);
        required.setMfaRequired(true);
        bootstrap().updateAdminSettings(required);
        try {
            final MockHttpSession session = new MockHttpSession();
            assertEquals("/sign-in/mfa-enrollment",
                    signIn(session, address, user, password).getResponse().getRedirectedUrl());
            final String page = mockMvc.perform(post("/sign-in/mfa-enrollment/start").session(session).with(csrf())
                            .with(from(address)).param("currentPassword", password))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            final java.util.regex.Matcher secret = java.util.regex.Pattern
                    .compile("<span class=\"font-mono\">([A-Z2-7]+)</span>").matcher(page);
            assertTrue(secret.find(), "the page shows the key to enter");
            final long enrolledAt = currentStep();
            mockMvc.perform(post("/sign-in/mfa-enrollment/confirm").session(session).with(csrf()).with(from(address))
                            .param("code", code(secret.group(1), enrolledAt)))
                    .andExpect(redirectedUrl("/login?mfaEnrolled"));

            final MockHttpSession wrong = new MockHttpSession();
            assertEquals("/sign-in/mfa", signIn(wrong, address, user, password).getResponse().getRedirectedUrl());
            mockMvc.perform(post("/sign-in/mfa").session(wrong).with(csrf()).with(from(address)).param("code", "000000"))
                    .andExpect(redirectedUrl("/login?code"));

            final MockHttpSession right = new MockHttpSession();
            assertEquals("/sign-in/mfa", signIn(right, address, user, password).getResponse().getRedirectedUrl());
            final long step = nextStep(enrolledAt);
            mockMvc.perform(post("/sign-in/mfa").session(right).with(csrf()).with(from(address))
                            .param("code", code(secret.group(1), step)))
                    .andExpect(redirectedUrl("/"));
            mockMvc.perform(get("/settings").session(right).with(from(address))).andExpect(status().isOk());
        } finally {
            final UpdateAdminSettingsRequest off = new UpdateAdminSettingsRequest();
            off.setMfaRequired(false);
            bootstrap().updateAdminSettings(off);
        }
    }

    @Test
    void revocationInPhilterEndsTheArbiterSession() throws Exception {
        final String password = password();
        final String user = createSignedUpUser("revoke", password);
        final String address = newAddress();
        final MockHttpSession session = new MockHttpSession();
        assertEquals("/", signIn(session, address, user, password).getResponse().getRedirectedUrl());
        mockMvc.perform(get("/settings").session(session).with(from(address))).andExpect(status().isOk());

        bootstrap().revokeSessionKeys(user);

        mockMvc.perform(get("/settings").session(session).with(from(address)))
                .andExpect(redirectedUrl("/login?expired"));
        assertTrue(session.isInvalid());
    }

    @Test
    void expiryInPhilterEndsTheArbiterSession() throws Exception {
        final String password = password();
        final String user = createSignedUpUser("expire", password);
        final String address = newAddress();
        final MockHttpSession session = new MockHttpSession();
        assertEquals("/", signIn(session, address, user, password).getResponse().getRedirectedUrl());

        // Philter's idle timeout here is one minute; Arbiter checks on every request.
        Thread.sleep(Duration.ofSeconds(65).toMillis());

        mockMvc.perform(get("/settings").session(session).with(from(address)))
                .andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    void administratorManagesUsersThroughPhilter() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        assertEquals("/", signIn(session, address, "admin", ADMIN_PASSWORD).getResponse().getRedirectedUrl());
        final String username = "added-" + HexFormat.of().formatHex(bytes(3));

        mockMvc.perform(post("/admin/users").session(session).with(csrf()).with(from(address))
                        .param("username", username).param("password", password()))
                .andExpect(redirectedUrl("/admin/users"));
        assertTrue(bootstrap().getUser(username).isPasswordChangeRequired());

        mockMvc.perform(post("/admin/users/" + username + "/role").session(session).with(csrf()).with(from(address))
                .param("role", "admin")).andExpect(redirectedUrl("/admin/users"));
        assertEquals("admin", bootstrap().getUser(username).getRole());

        mockMvc.perform(post("/admin/users/" + username + "/deactivate").session(session).with(csrf())
                .with(from(address))).andExpect(redirectedUrl("/admin/users"));
        assertFalse(bootstrap().getUser(username).isActive());

        mockMvc.perform(post("/admin/users/me/sign-out").session(session).with(csrf()).with(from(address)))
                .andExpect(redirectedUrl("/admin/users"));
        mockMvc.perform(get("/admin/users").session(session).with(from(address)))
                .andExpect(status().isOk());
    }

    @Test
    void arbiterApiKeysAreRefused() throws Exception {
        // A genuine Arbiter key on an admin's record, as one made under local sign-in would be.
        final String key = "arbiter-key-" + HexFormat.of().formatHex(bytes(12));
        final ai.philterd.arbiter.model.User holder = new ai.philterd.arbiter.model.User();
        holder.setId(java.util.UUID.randomUUID().toString());
        holder.setEmail("key-holder-" + HexFormat.of().formatHex(bytes(3)));
        holder.setRoles(java.util.Set.of("ADMIN"));
        holder.setApiKey(apiKeyHashingService.hash(key));
        userRepository.save(holder);
        assertTrue(userRepository.findByApiKey(apiKeyHashingService.hash(key)).isPresent());

        mockMvc.perform(get("/api/v1/batches").header("Authorization", "Bearer " + key))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anAdministratorsApiKeyIsFlaggedUnderAdminPhilter() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        assertEquals("/", signIn(session, address, "admin", ADMIN_PASSWORD).getResponse().getRedirectedUrl());

        final String error = mockMvc.perform(post("/admin/philter/test").session(session).with(csrf()).with(from(address)))
                .andExpect(redirectedUrl("/admin/philter")).andReturn().getFlashMap().get("error").toString();
        assertTrue(error.contains("Philter 4.0.0") && error.contains("belongs to a Philter administrator"), error);

        final String page = mockMvc.perform(get("/admin/philter").session(session).with(from(address)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("Set; belongs to a Philter administrator"), "the page shows the flag");
        assertTrue(!page.contains(BOOTSTRAP_KEY), "the page never shows the key");
    }

    @Test
    void ingestRedactsThroughTheConfiguredPhilter() throws Exception {
        final String text = "Write to jdoe@example.com about the claim.";
        final ai.philterd.arbiter.core.model.RedactionResponse response =
                redactionService.redactText(text, null, null);

        assertTrue(response.getRedactions().stream()
                        .anyMatch(r -> "email-address".equals(r.getType()) && "jdoe@example.com".equals(r.getText())),
                "Philter's default policy finds the email address");
        assertFalse(response.getRedactedText().contains("jdoe@example.com"));
    }
}
