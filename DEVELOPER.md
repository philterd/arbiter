# Developer guide

Engineering-side notes that complement the user-facing [README](README.md)
and the in-app docs under [`docs/docs/index.md`](docs/docs/index.md).

## Modules

Three Maven modules, dependency order top-to-bottom:

| Module             | Owns                                                                  |
| ------------------ | --------------------------------------------------------------------- |
| `arbiter-domain`   | POJOs: `model/`, `dto/`, `util/`, `core/model/`, `philter/`           |
| `arbiter-platform` | Spring services and Mongo repositories. No controllers                |
| `arbiter-webapp`   | Spring Boot entry point, MVC + REST controllers, security, templates  |

Module boundaries are organisational, not visibility — `@ComponentScan`
covers `ai.philterd.arbiter` regardless of where a class compiles from.

## Build, run, test

```sh
mvn package                 # full reactor build
mvn test                    # all modules
docker compose -f docker-compose.yaml up --build   # full stack: Mongo + OpenSearch + ES + Valkey + app on :8080
./build-image.sh            # multi-arch (amd64 + arm64) image, tagged with the pom version
./push-image.sh             # publish it to philterd/arbiter, by hand
```

`build-image.sh` loads `philterd/arbiter:<version>-amd64` and
`-arm64` locally; `push-image.sh` pushes those two and joins them into the
plain `<version>` tag in the registry. Both take a version argument; with
none they read the Maven project version from the root `pom.xml`, so the
tag matches what the jar reports at `/api/health`. Nothing publishes an
image automatically: run `push-image.sh` yourself from a machine holding the
Docker Hub credential. Set `ARCHES=amd64` to build just one architecture.

Running the webapp from the IDE needs at least Mongo and Valkey reachable:

```sh
docker compose -f docker-compose.yaml up -d mongodb valkey
# then run ai.philterd.arbiter.webapp.ArbiterApplication
```

Defaults in `arbiter-webapp/src/main/resources/application.properties`
point at `localhost`; the only env vars worth knowing about are
`SPRING_DATA_MONGODB_URI`, `SPRING_DATA_REDIS_HOST`/`_PORT`, and
**`arbiter.crypto.secret`** (now required: base64 of 32 random bytes,
e.g. `openssl rand -base64 32`; Arbiter refuses to start if it isn't
set or has the wrong shape). The OpenSearch endpoint used for
full-text search is configured at runtime under
**Admin → Settings → Full text search** rather than via an env var.

## Tests

JUnit 5 + Mockito. Mostly unit-style; `@WebMvcTest` slices for the
security/filter integration cases (`AuthorizationIntegrationTest`,
`RedactionControllerTest`).

A few patterns that bite:

- **`@WebMvcTest` slices must `@MockBean` every Mongo repository in
  the app**, not just the ones the controller calls. With
  `auto-index-creation=true`, Spring tries to create every repo's
  indexes on context load and fails if one is unmocked. Copy the list
  from `AuthorizationIntegrationTest`.
- **Spring Data Mongo can't compose two predicates on the same
  property** in method-name queries (`...AfterAndPropertyLessThanEqual`
  fails at runtime). Use the Criteria builder for ranges:
  `Criteria.where("createdAt").gt(start).lte(end)`.
- For executor-driven verifications, prefer
  `verify(mock, timeout(2000)).method(...)` over `Thread.sleep`.

## Architectural patterns to match

When adding similar functionality, follow the existing patterns —
they're load-bearing.

- **Atomic Mongo claims** for shared queues: see `IngestQueueService`
  and `DataImportDispatcher`. `findAndModify` flips a status field;
  multi-replica safe.
- **Partial unique indexes** for "at most one in-flight" rules: see
  `BackgroundJob`'s `uniq_running_data_import_per_batch`. Race-collide
  on the index, catch `DuplicateKeyException`, treat as "someone else
  got there first."
- **AES-GCM at-rest encryption** for credentials via `SymmetricCipher`.
  Store ciphertext on the entity, log only a `passwordSet` /
  `credentialsSet` boolean, render `Configured` / `Ambient` /
  `••••••` in the UI. Plaintext never leaves memory.
- **Audit-log every admin write path** through `AuditLogService.log(...)`.
  The action-name canon is in
  [`docs/docs/admin/audit-log.md`](docs/docs/admin/audit-log.md).

## Conventions

- `final` on parameters and locals.
- Comments on hidden invariants only — no restating-the-code.
- Tailwind utility classes in templates; avoid custom CSS.
- Plain JS in templates, inline at the bottom; no frameworks.
- Don't add features beyond what the task requires.

## Documentation

The mkdocs-material site under `docs/` is bundled into the jar at
`classpath:/static/docs/` and served at `/docs/`. Build locally with
`mkdocs build --strict` (the Docker `docs` stage runs the same
command) or preview with `mkdocs serve`.

Update the docs alongside the code. The strict build catches broken
cross-references.
