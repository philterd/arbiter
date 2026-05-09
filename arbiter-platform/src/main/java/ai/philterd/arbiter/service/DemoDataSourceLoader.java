/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.ElasticsearchDataSource;
import ai.philterd.arbiter.model.LocalDirectoryDataSource;
import ai.philterd.arbiter.model.OpenSearchDataSource;
import ai.philterd.arbiter.repository.ElasticsearchDataSourceRepository;
import ai.philterd.arbiter.repository.LocalDirectoryDataSourceRepository;
import ai.philterd.arbiter.repository.OpenSearchDataSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Seeds the OpenSearch and Elasticsearch backends shipped with the demo Docker compose: each gets
 * a {@code documents} index with a {@code document}/{@code filename} mapping and 100 synthetic
 * records. Then registers three Arbiter data sources ("Demo OpenSearch", "Demo Elasticsearch",
 * and "Demo Local Directory") pointing at those backends via their Docker service names — the
 * Local Directory source points at the {@code /app/local-files} mount that {@code docker-compose}
 * provides as a test fixture.
 *
 * <p>Idempotent — if the indexes already have data or the data sources already exist, the seed
 * step is skipped. Best-effort: if either search backend is unreachable (e.g. running tests
 * without the Docker compose stack) the loader logs and moves on. The local-directory source
 * is registered regardless of whether the directory currently exists; the ingest job will
 * surface a clear error if an admin tries to use it without the mount in place.
 */
@Component
@Order(3)
@ConditionalOnProperty(name = "arbiter.demo-data.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataSourceLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSourceLoader.class);
    private static final int DEMO_DOCUMENT_COUNT = 250;
    private static final String INDEX_NAME = "documents";

    private final OpenSearchDataSourceRepository openSearchRepository;
    private final ElasticsearchDataSourceRepository elasticsearchRepository;
    private final LocalDirectoryDataSourceRepository localDirectoryRepository;
    private final String opensearchEndpoint;
    private final String elasticsearchEndpoint;
    private final String localDirectoryPath;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public DemoDataSourceLoader(final OpenSearchDataSourceRepository openSearchRepository,
                                final ElasticsearchDataSourceRepository elasticsearchRepository,
                                final LocalDirectoryDataSourceRepository localDirectoryRepository,
                                @Value("${arbiter.demo-data.opensearch-endpoint:http://opensearch:9200}") final String opensearchEndpoint,
                                @Value("${arbiter.demo-data.elasticsearch-endpoint:http://elasticsearch:9200}") final String elasticsearchEndpoint,
                                @Value("${arbiter.demo-data.local-directory-path:/app/local-files}") final String localDirectoryPath) {
        this.openSearchRepository = openSearchRepository;
        this.elasticsearchRepository = elasticsearchRepository;
        this.localDirectoryRepository = localDirectoryRepository;
        this.opensearchEndpoint = opensearchEndpoint;
        this.elasticsearchEndpoint = elasticsearchEndpoint;
        this.localDirectoryPath = localDirectoryPath;
    }

    @Override
    public void run(final ApplicationArguments args) {
        seedBackend("OpenSearch", opensearchEndpoint);
        seedBackend("Elasticsearch", elasticsearchEndpoint);
        ensureOpenSearchDataSource();
        ensureElasticsearchDataSource();
        ensureLocalDirectoryDataSource();
    }

    private void seedBackend(final String label, final String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            log.info("Skipping {} demo seed — no endpoint configured.", label);
            return;
        }
        try {
            // Skip if the index already has documents — this lets the loader run safely on
            // every restart without re-seeding.
            final HttpResponse<String> count = send(HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(endpoint) + "/" + INDEX_NAME + "/_count"))
                    .timeout(Duration.ofSeconds(10))
                    .GET());
            if (count.statusCode() / 100 == 2 && count.body() != null
                    && count.body().contains("\"count\":") && !count.body().contains("\"count\":0")) {
                log.info("{} demo index '{}' already has documents; skipping seed.", label, INDEX_NAME);
                return;
            }
        } catch (Exception e) {
            // Index probably doesn't exist yet — fall through to create + seed.
        }

        try {
            createIndex(label, endpoint);
            indexDocuments(label, endpoint);
        } catch (Exception e) {
            log.warn("Could not seed {} demo data at {}: {}", label, endpoint, e.getMessage());
        }
    }

    private void createIndex(final String label, final String endpoint) throws Exception {
        final String mapping = """
                {
                  "mappings": {
                    "properties": {
                      "document": { "type": "text" },
                      "filename": { "type": "keyword" }
                    }
                  }
                }
                """;
        final HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(endpoint) + "/" + INDEX_NAME))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapping, StandardCharsets.UTF_8)));
        // 200 = created, 400 with resource_already_exists_exception = already there.
        if (resp.statusCode() / 100 != 2
                && !(resp.body() != null && resp.body().contains("resource_already_exists_exception"))) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        log.info("Ensured {} index '{}'.", label, INDEX_NAME);
    }

    private void indexDocuments(final String label, final String endpoint) throws Exception {
        final Random rng = new Random();
        for (int i = 0; i < DEMO_DOCUMENT_COUNT; i++) {
            final String filename = "demo-" + i + "-" + randomWord(rng) + ".txt";
            final String document = randomDocument(rng);
            final String body = "{\"document\":" + jsonString(document)
                    + ",\"filename\":" + jsonString(filename) + "}";
            final HttpResponse<String> resp = send(HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(endpoint) + "/" + INDEX_NAME + "/_doc?refresh=false"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
            }
        }
        // One refresh at the end so the documents are immediately searchable for the demo.
        send(HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(endpoint) + "/" + INDEX_NAME + "/_refresh"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody()));
        log.info("Indexed {} demo documents into {} index '{}'.", DEMO_DOCUMENT_COUNT, label, INDEX_NAME);
    }

    private void ensureOpenSearchDataSource() {
        final String name = "Demo OpenSearch";
        if (openSearchRepository.findFirstByNameIgnoreCase(name).isPresent()) {
            return;
        }
        final OpenSearchDataSource ds = new OpenSearchDataSource();
        ds.setId(UUID.randomUUID().toString());
        ds.setName(name);
        ds.setEndpoint("http://opensearch:9200");
        ds.setQuery(INDEX_NAME + "/_search { \"query\": { \"match_all\": {} } }");
        ds.setTextField("document");
        ds.setFilenameField("filename");
        ds.setCreatedAt(LocalDateTime.now());
        openSearchRepository.save(ds);
        log.info("Registered demo OpenSearch data source '{}'.", name);
    }

    private void ensureElasticsearchDataSource() {
        final String name = "Demo Elasticsearch";
        if (elasticsearchRepository.findFirstByNameIgnoreCase(name).isPresent()) {
            return;
        }
        final ElasticsearchDataSource ds = new ElasticsearchDataSource();
        ds.setId(UUID.randomUUID().toString());
        ds.setName(name);
        ds.setEndpoint("http://elasticsearch:9200");
        ds.setQuery(INDEX_NAME + "/_search { \"query\": { \"match_all\": {} } }");
        ds.setTextField("document");
        ds.setFilenameField("filename");
        ds.setCreatedAt(LocalDateTime.now());
        elasticsearchRepository.save(ds);
        log.info("Registered demo Elasticsearch data source '{}'.", name);
    }

    private void ensureLocalDirectoryDataSource() {
        final String name = "Demo Local Directory";
        if (localDirectoryRepository.findFirstByNameIgnoreCase(name).isPresent()) {
            return;
        }
        final LocalDirectoryDataSource ds = new LocalDirectoryDataSource();
        ds.setId(UUID.randomUUID().toString());
        ds.setName(name);
        // Path inside the container — docker-compose mounts ./local-files to /app/local-files.
        // The default '*.txt' glob picks up the bundled fixtures; an admin can edit the source
        // to '**.pdf' or another pattern to ingest different files dropped into the same dir.
        ds.setDirectoryPath(localDirectoryPath);
        ds.setFilenameGlob("*.txt");
        ds.setCreatedAt(LocalDateTime.now());
        localDirectoryRepository.save(ds);
        log.info("Registered demo local-directory data source '{}' at {}.", name, localDirectoryPath);
    }

    private HttpResponse<String> send(final HttpRequest.Builder b) throws Exception {
        return httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String stripTrailingSlash(final String s) {
        return s.replaceAll("/+$", "");
    }

    // ---- random data ------------------------------------------------------

    private static final List<String> NAMES = List.of(
            "Alice Robertson", "Bob Patel", "Charlie Nguyen", "Dana Cohen", "Erik Müller",
            "Fatima al-Rashid", "Grace O'Connor", "Hiroshi Tanaka", "Ines García", "Jamal Carter");
    private static final List<String> CITIES = List.of(
            "Atlanta", "Boston", "Chicago", "Denver", "El Paso", "Fresno", "Greenville", "Honolulu");
    private static final List<String> SUBJECTS = List.of(
            "the quarterly report", "the project update", "the customer onboarding flow",
            "the security review", "the budget request", "the team retro", "the migration plan");

    private static String randomDocument(final Random rng) {
        final String name = NAMES.get(rng.nextInt(NAMES.size()));
        final String city = CITIES.get(rng.nextInt(CITIES.size()));
        final String subject = SUBJECTS.get(rng.nextInt(SUBJECTS.size()));
        final String email = ("user" + rng.nextInt(10000) + "@example.com").toLowerCase();
        final String phone = String.format("(%03d) %03d-%04d",
                200 + rng.nextInt(799), rng.nextInt(1000), rng.nextInt(10000));
        final String ssn = String.format("%03d-%02d-%04d",
                100 + rng.nextInt(800), 10 + rng.nextInt(89), 1000 + rng.nextInt(9000));
        return "Hello,\n\n"
                + "I'm following up with " + name + " in " + city + " regarding " + subject + ". "
                + "Please reach out at " + email + " or " + phone + ". "
                + "For records, the reference SSN is " + ssn + ".\n\n"
                + "Thanks,\nDemo Generator";
    }

    private static String randomWord(final Random rng) {
        final String[] words = {"alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf",
                "hotel", "india", "juliet", "kilo", "lima"};
        return words[rng.nextInt(words.length)];
    }

    private static String jsonString(final String value) {
        final StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
