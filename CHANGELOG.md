# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.1.12] - 2026-09-30

### Security

- **netty 4.2.16.Final → 4.2.18.Final** — fixes CRITICAL CVE-2026-75595 in
  `netty-handler` (fixed in 4.2.17.Final), present in the published
  `ghcr.io/opentmf/opentmf-mockserver:2.1.11` image.
- **Jackson 3.2.1 → 3.2.3** (`jackson-bom`) — fixes HIGH CVE-2026-68497 in
  `tools.jackson.core:jackson-databind` (fixed in 3.2.2).

Trivy (HIGH/CRITICAL, fixable, fresh DB 2026-09-29): 2.1.11 image 1 CRITICAL +
1 HIGH → 2.1.12 image 0.

### Changed

- Dependency bumps: netty-tcnative 2.0.84.Final, Bouncy Castle 1.86,
  nimbus-jose-jwt 10.10, json-schema-validator 3.0.7, commons-lang3 3.21.0,
  commons-codec 1.22.1, Guava 33.7.2-jre, ClassGraph 4.8.196, SLF4J 2.0.20,
  JUnit Jupiter 6.1.3.
- `opentmf-mockserver-test-support` compiles against Spring Framework 6.2.19
  and Spring Boot 3.5.16 (patch bumps within the existing lines; both remain
  `provided`/optional, so consumers keep their own versions).
- Build plugins: maven-compiler 3.16.0, surefire/failsafe 3.6.0, maven-jar
  3.5.1, maven-deploy 3.2.0, exec-maven 3.6.4, sonar-maven 5.8.0.7211; Trivy
  scanner image in the `docker` profile 0.74.0.

## [2.1.11] - 2026-07-30

### Fixed

- **Release Docker image GHCR coordinate.** `release-image.yml` computed the
  image name from `${{ github.repository }}`, so when the GitHub repo was
  renamed from `opentmf-mockserver` to `opentmf-mockserver-parent` (to match
  the reactor artifact-id after the 2.1.9 multi-module split), the 2.1.10
  image published to `ghcr.io/opentmf/opentmf-mockserver-parent:2.1.10`
  instead of the historical `ghcr.io/opentmf/opentmf-mockserver:2.1.10`,
  breaking consumers pinning the old coordinate. From 2.1.11 the workflow
  hard-codes `IMAGE: opentmf/opentmf-mockserver`, so the ghcr path is stable
  regardless of future repo renames. Consumers stuck on 2.1.10 should either
  upgrade to 2.1.11 or temporarily pin
  `ghcr.io/opentmf/opentmf-mockserver-parent:2.1.10` until they can.

## [2.1.10] - 2026-07-30

### Fixed

- **Release Docker image build.** `Dockerfile_release` at repo root had a stray
  `COPY docker-entrypoint.sh ...` line that pre-dated the 2.1.9 multi-module split.
  After the split, the script lives under `opentmf-mockserver/docker-entrypoint.sh`,
  but the workflow (`release-image.yml`) still uses the repo root as build context,
  so `docker buildx` failed with `"/docker-entrypoint.sh": not found` and the 2.1.9
  release image never published to GHCR. Path corrected to
  `opentmf-mockserver/docker-entrypoint.sh`; every other `COPY` in the file was
  already relative to the repo root context and unaffected.

## [2.1.9] - 2026-07-30

### Added

- **New artifact `org.opentmf.mockserver:opentmf-mockserver-test-support`** — a fluent JUnit
  5 test-support layer over opentmf-mockserver, intended for services that today hand-roll
  the same `ClientAndServer` + `Dynamic*Callback` + JWKS + `@DynamicPropertySource` glue in
  every integration test. One implementation, versioned with the server it drives; no drift
  across consumers.
    - `MockServerSupport` — JUnit 5 `@RegisterExtension`-compatible facade that starts an
      in-process MockServer on a random free port and reset expectations after every
      `@Test`. Also usable without JUnit via `start()` / `stop()` (Cucumber / E2E harnesses).
    - `TmfMockBuilder` (`mock.tmf(...).post/get/getList/put/delete/jsonPatch/mergePatch/
      jsonPatchCollection/crud(...)`) — fluent registration of the repo's `Dynamic*Callback`
      classes on a given TMF resource path. `crud(path)` registers the seven per-resource
      verbs typical TMF v4 services expose (POST + GET + GET-list + PUT + DELETE +
      jsonPatch + mergePatch) — both PATCH flavors are included because PATCH is the
      de-facto update mechanism in TMF v4. `jsonPatchCollection` is intentionally NOT
      part of `crud` (it's a collection-level batch op).
    - `StubBuilder` (`mock.stub().get(...).respondJson/respondStatus/respondDelayed/
      respondSequence(...)`) — fluent static expectations for non-TMF endpoints (KBA
      lookups, gateways, retry-path stubs).
    - `VerifyBuilder` (`mock.verify().post(...).times/never/atLeast/atMost/once(...)`) —
      fluent wrapper over `MockServerClient.verify` so consumers stop importing
      `org.mockserver.verify.VerificationTimes` directly.
    - `OidcMockSupport` (`mock.oidc()`) — JWT minting via the built-in `JwtKeyProvider`
      with Keycloak-shaped claims (`realm_access.roles`, `resource_access.<clientId>.roles`,
      `azp`, `preferred_username`) and JWKS endpoint registration at
      `/realms/<realm>/protocol/openid-connect/certs`. Convenience shortcuts on the facade:
      `mock.token(...)`, `mock.tokenFor(...)`, `mock.bearerHeader(...)`.
    - Spring redirect helpers (`mock.redirectApiClients`, `mock.redirectHttpClients`,
      `mock.redirectJwks`) that populate a Spring `DynamicPropertyRegistry` with
      `opentmf.api-clients.<id>.base-url` / `.context-path`,
      `opentmf.http-clients.<id>.base-url`, and `opentmf.security.jwk-set-uri`. Spring is a
      `provided`/`optional` dependency — non-Spring consumers can use the rest of the
      module without pulling Spring in.
- **`MockServerSupport.shared()`** — first-class JVM-singleton mode. Lazily starts a
  single MockServer on first call, returns the same instance for every subsequent call,
  and installs a JVM shutdown hook to stop it at exit. Safe to `@RegisterExtension` in
  every test class — `afterAll` is a no-op on shared instances, so closing one class's
  boundary does not tear down the server the next class needs. `afterEach` reset still
  runs, keeping intra-class isolation. Use to avoid paying MockServer's ~1–2 s startup
  cost per test class in projects with many ITs. Trade-off: not safe for tests running in
  parallel across classes (documented in the guide below).
- **`docs/TEST_SUPPORT.md`** — full usage guide for
  `opentmf-mockserver-test-support`. Covers dependency setup, per-class vs JVM-shared
  lifecycle, Spring Boot IT recipe, full builder references (`TmfMockBuilder`,
  `StubBuilder`, `VerifyBuilder`, `OidcMockSupport`), non-JUnit usage, a migration recipe
  for existing hand-rolled harnesses (`dsync-engine` style), and troubleshooting. The
  README's Test Support section now links to it and shows only a quick taste.
- **`StubBuilder.limit(int)`** — cap how many times an expectation matches
  (`Times.exactly(N)`). Applies to the next `respond*` only; each new
  `get/post/put/delete/method` resets to unlimited. Enables the "N calls with response A,
  then hand off to response B" pattern without `respondSequence`.
- **`Registration` handles + targeted clear.** `StubBuilder`'s `respond*` methods and
  `MockServerSupport.expect(request, response)` now return a `Registration` carrying the
  expectation id(s) MockServer minted. `Registration.clear()` removes just that
  expectation (idempotent) — no more "reset the whole server to unregister one stub".
  `MockServerSupport.clear(Registration...)` is a batch-clear convenience that tolerates
  null entries. Callers that ignore the return value keep compiling — the change is
  source-compatible; and since the whole test-support module is unreleased, there is no
  binary-compat concern. Motivation: consumers coming from a hand-rolled
  `MockServerUtils.expectPost(path, count, status, body)` helper get the same "specify
  count + return the id for cleanup" ergonomics, minus the combinatorial method surface.

### Changed

- **JaCoCo `check` rule enforced on every `mvn verify`** (both modules). Rules per
  bundle: INSTRUCTION, LINE, and BRANCH each ≥ 80% covered; 0 missed classes.
  Vendored MockServer code excluded (`org/mockserver/**`). Rules live in the aggregator's
  `<pluginManagement>` and each child module opts in with a bare plugin reference.
  Getting the codebase past the rule surfaced real gaps and drove the additions below.
- **Coverage push across both modules.**
  - `opentmf-mockserver`: BRANCH 66.5% → **80.2%**, INSTRUCTION 84.2% → **93.6%**, LINE
    84.6% → **93.7%**, missed classes **3 → 0** (`TokenUtil`, `CacheQuery.Criterion`,
    `PayloadCache.CacheEvictTimer` now covered; `IdempotencyCache.EvictTimer` driven
    via reflection). New test files: `TokenUtilTests`, `CacheQueryTests` (36 tests over
    the query engine), `IdempotencyCacheTests` (Record + EvictTimer). Extended:
    `PayloadCacheTests` (+17), `TokenEnforcerTests` (+23 branch cases across
    `validateForRequest`, custom `rolesClaimPath`, `extractRolesAtPath` failure modes,
    `readTopLevelRoles` fallback, malformed-JWKS init-error path, empty required roles).
    227 → 316 tests.
  - `opentmf-mockserver-test-support`: pushed all three counters over 80%. Extended:
    `TmfMockBuilderTests` (put / getList / jsonPatch / mergePatch / jsonPatchCollection /
    trailing-slash `withId`), `StubBuilderTests` (put / delete / method / pathParam /
    queryParam / jsonBody / respondSequence(HttpResponse…)), `VerifyBuilderTests`
    (put / delete / method / atLeast / atMost / withJsonBody / withQueryParam),
    `MockServerSupportTests` (keepExpectationsBetweenTests / expect / token variants /
    non-JUnit start-stop cycle). 25 → 47 tests.
- **Shared preamble extracted from PATCH/DELETE callbacks.** `DynamicDeleteCallback`,
  `DynamicJsonPatchCallback`, and `DynamicMergePatchCallback` all began with the same
  auth check → idempotency precheck → RequestContext init → cache lookup → 404 short-circuit
  → version resolution. Moved to `ExistingEntryLoader.load(request)`, which returns either
  the short-circuit `HttpResponse` or `(RequestContext, JsonNode)`. Eliminates three
  Sonar-flagged duplication blocks (25/23/23 lines).
- **3 S2245 security hotspots marked SAFE.** `AuditFieldUtil` (2) and `DynamicPostCallback`
  (1) call `RandomStringUtils.insecure().next*` to fabricate mock audit fields
  (`createdBy` / `updatedBy`) and filler payload values. No cryptographic role.
  Explicitly reviewed and marked SAFE in Sonar with a justification comment on each.
- **`docker` Maven profile now builds a local image AND runs a Trivy scan.** After the
  existing `docker build -t local/opentmf-mockserver:${project.version}` step, the profile
  runs two additional executions via `exec-maven-plugin`:
  1. `trivy-html-report` — full LOW/MEDIUM/HIGH/CRITICAL scan rendered to
     `opentmf-mockserver/target/trivy-report.html` via a bundled Go template
     (`opentmf-mockserver/ci/trivy-html.tpl`). Never fails the build; includes unfixed
     findings so developers see the full picture.
  2. `trivy-gate` — HIGH/CRITICAL scan with `--ignore-unfixed`; exits 1 on any fixable
     finding. Explicitly-accepted CVEs go in `opentmf-mockserver/.trivyignore` with a
     comment block recording rationale.
  Trivy runs from the pinned `aquasec/trivy:0.72.0` container against the docker socket,
  with `~/.cache/trivy` mounted for cross-run vulnerability-DB caching. Nothing on the
  default build path changed — the scan only fires under `-Pdocker`.
- **`maven-enforcer-plugin` now pins the toolchain exactly.** `requireJavaVersion` tightened
  from `17` (minimum) to `[17,18)` (exact 17.x) and `requireMavenVersion` from `3.9.0` to
  `[3.9,3.10)`. Newer JDKs/Maven versions are rejected at `validate` phase with an explicit
  message. The rule runs once on the aggregator (`inherited=false`) since JDK/Maven versions
  are per-invocation.
- **Sonar cleanup — 276 findings driven to zero.** First run of the new `sonar` profile
  against local SonarQube surfaced 276 open findings (0 BUGs after triage; the initial
  BUG-typed volatile-singleton warnings were confirmed as false positives for the DCL
  pattern and suppressed with a comment). Bulk-fixed classes:
  Jackson 3 API migrations (`asText` → `asString`, `isTextual` → `isString`, `getText` →
  `getString`, `new URL(...)` → `URI.create(...).toURL()`),
  `Stream.collect(Collectors.toList())` → `Stream.toList()`,
  `RandomStringUtils.random*` → `.insecure().next*`. Structural changes:
  `CacheQuery.Criterion` promoted to a `record`; `CacheQuery.matchesGrouped`,
  `CacheQuery.readPath`, `DurationUtil.formatDuration`,
  `TokenEnforcer.validateWithRoles`, `TokenEnforcer.extractRoles`,
  `DynamicJsonPatchCollectionCallback.handle` all extracted into smaller helpers to
  clear the cognitive-complexity gate. Renamed:
  `IdempotencyGuard.record(request, response, ctx)` → `IdempotencyGuard.store(...)` to
  stop shadowing the `record` restricted identifier — call-sites updated. Constants
  extracted for duplicated string literals across the Keycloak/JWKS callbacks. Log-arg
  computation guarded with `isInfoEnabled()` where args required work. Suppressions were
  used only where the finding conflicted with an intentional pattern (DCL singletons,
  the `Id` JavaBean field, the well-known `/realms/` OIDC path constant,
  parameterized-vs-separate test style). **No behavioural change:** all 227 server unit
  tests and 25 test-support tests still pass.
- **Repository is now a multi-module Maven build.** A new aggregator pom
  `org.opentmf.mockserver:opentmf-mockserver-parent` (packaging=pom) sits at the repository
  root and reactor-builds the existing `org.opentmf.mockserver:opentmf-mockserver` jar under
  the `opentmf-mockserver/` subdirectory. **The server artifact's coordinates
  (`groupId:artifactId:version`) are unchanged**, so no consumer changes are required. The
  restructure exists to host the new `opentmf-mockserver-test-support` sibling artifact
  without polluting the server jar's dependency surface with JUnit and Spring. Plugin versions
  and configuration now live in the aggregator's `<pluginManagement>`; release-only plugins
  (`maven-source-plugin`, `maven-javadoc-plugin`, `maven-gpg-plugin`,
  `central-publishing-maven-plugin`) live in the aggregator's `release` profile.

## [2.1.8] - 2026-07-15

### Added

- **`PayloadCache.putIfAbsent`** — atomic insert-if-absent under the cache lock. Returns `true`
  on insert, `false` when the id was already present. Used by every create callback (POST,
  PUT-create, batch collection PATCH) in place of the historical `CACHE.get` → `CACHE.put`
  idiom, which had a check-then-act window that surfaced as spurious `500`s under concurrent
  duplicate-id creates.

### Fixed

- **Concurrent creates with the same client-supplied id no longer leak a `500`.**
  `DynamicPostCallback`, `DynamicPutCallback`'s create branch, and
  `DynamicJsonPatchCollectionCallback` used a `CACHE.get(...) != null` uniqueness check
  followed by a separate `CACHE.put(...)`. Two callbacks racing on the same id both saw "not
  present," both reached `put()`, and the second raised
  `IllegalArgumentException("Key: [...] already exists in cache for domain ")` — surfaced by
  MockServer as `500 Internal Server Error`, not the intended `400`/`409`. All three paths now
  use `PayloadCache.putIfAbsent` under the cache lock. POST losers get their intended
  `400 "already exists"`. PUT losers degrade to an idempotent replace (`200`) on the winning
  creator's resource — PUT is idempotent, so the two paths converge to the same observable
  state. Batch collection PATCH losers get `409` and every item the losing batch had already
  inserted is rolled back so the batch stays atomic per RFC 5789. Regression tests fire 8–20
  concurrent racers per callback and assert exactly one winner, correct loser status, and zero
  `500`s.
- **`DynamicGetCallback` no longer mutates the cached `JsonNode` in place on first-observation
  state transition.** When a GET landed on an entity still in its initial state (e.g.
  `status: "created"`), the callback cast the live cached node to `ObjectNode` and directly
  `put`'d the final state plus `updatedDate` / `updatedBy` / `revision++` on it — outside any
  cache lock. The 2.1.7 snapshot fix rests on the invariant "write paths replace nodes, never
  mutate them in place," which is what makes it safe for the list-GET path to iterate
  `PayloadCache.getAll()`'s snapshot outside the cache lock: readers keep sharing the same
  `JsonNode` references, so those nodes must be effectively immutable. GET breaking that
  invariant meant a concurrent single-GET's transition could race a list-GET's iteration or
  serialization of the same node — Jackson's `ObjectNode` is backed by a plain
  `LinkedHashMap`, so concurrent mutation during another thread's walk is undefined
  (`ConcurrentModificationException`, or a serialised payload with `revision` bumped but
  `updatedDate` not yet stamped). The transition now runs on a deep-copy, and the cache
  reference is swapped atomically via `PayloadCache.update`; readers holding the old reference
  see a stable, pre-transition node. Regression test in `DynamicGetCallbackTests` pins the
  reference-replacement semantics.

### Changed

- Bumped runtime dependencies: Netty 4.2.16.Final, netty-tcnative 2.0.80.Final,
  json-schema-validator 3.0.6.

## [2.1.7] - 2026-07-07

### Fixed

- **Filtered list GETs no longer intermittently miss freshly created entities under concurrent
  load.** `PayloadCache.getAll()` was `synchronized` but returned the <em>live</em> internal
  `TreeMap`; `DynamicGetListCallback` then iterated and filtered that map outside the cache lock,
  concurrently with `put()`s mutating the same tree. Under parallel clients this raced the tree's
  structural changes and intermittently skipped entries that had already been created and
  acknowledged with 201 — observed as ~1% `404 Not Found` on `GET ?id=…&version=…` list queries
  seconds after the entity's creation (dsync parallel rehearsal, 2026-07-07: 26 of 2,385 such
  queries failed; two orchestrated updates exhausted their retries against these phantom 404s).
  Sequential clients could never hit this, which is why the defect survived every serial test.
  `getAll()` now returns a shallow snapshot taken under the lock. The `JsonNode` values remain
  shared: the write paths replace nodes and never mutate them in place, so a snapshot reader
  always sees a consistent entity. Regression tests pin the snapshot semantics
  (`PayloadCacheTests`).

## [2.1.6] - 2026-06-26

### Added

- **Configurable required roles per HTTP method.** Five new env vars `ROLES_GET`, `ROLES_POST`,
  `ROLES_PUT`, `ROLES_PATCH`, `ROLES_DELETE` accept a comma-separated list of role names; a request
  whose token carries any one of the listed roles passes. Defaults preserve the historical
  behaviour (`reader,writer,admin` for GET; `writer,admin` for POST/PUT/PATCH; `admin` for DELETE).
  Setting a variable to the empty string disables the role check for that method while still
  validating signature, expiry, and issuer. The Keycloak Admin REST API endpoints remain hard-coded
  to require `admin`.
- **Configurable roles claim path.** New env var `ROLES_CLAIM_PATH` accepts a dotted JSON path into
  the token payload (e.g. `resource_access.my-client.roles`, `groups`, or a namespaced claim like
  `https://example.com/roles`). The leaf must resolve to a JSON array of strings. When unset, the
  enforcer keeps its previous fallback chain (`realm_access.roles`, then top-level `roles`); when
  set, the configured path is the single source of truth and the default fallback is not consulted.

### Changed

- `TokenEnforcer` gains a `validateForRequest(HttpRequest)` helper that resolves the required
  roles for the request's HTTP method from the configured per-method map. All eight TMF dynamic
  callbacks (`DynamicGetCallback`, `DynamicGetListCallback`, `DynamicPostCallback`,
  `DynamicPutCallback`, `DynamicJsonPatchCallback`, `DynamicMergePatchCallback`,
  `DynamicJsonPatchCollectionCallback`, `DynamicDeleteCallback`) now route through it instead of
  passing a hard-coded role list.

## [2.1.5] - 2026-06-19

### Added

- **`Idempotency-Key` request header** is now recognised on every mutating callback (POST, PUT,
  PATCH `application/merge-patch+json`, PATCH `application/json-patch+json` on a single resource,
  PATCH `application/json-patch+json` on a collection, DELETE). When a client retries with the same
  key on the same `(method, path)`, the server replays the original 2xx response verbatim with an
  `X-Idempotent-Replay: true` marker and resets the underlying resource's cache TTL counter — a
  "touch" — so the resource lives at least as long as clients keep retrying. Missing/blank keys are
  ignored (clients without idempotency awareness behave exactly as before). Same key reused on a
  different `(method, path)` returns **422 Unprocessable Entity**; keys longer than 255 characters
  return **400**. Idempotency records expire on the same TTL as `PayloadCache`
  (`CACHE_DURATION_MILLIS`, default 2 h) and are also evicted eagerly when the underlying resource
  is removed by TTL, so a same-key retry after eviction is treated as a fresh request.

### Changed

- `PayloadCache#evictOldItems` now notifies `IdempotencyCache` for each evicted `(domain, id)` so
  stale replays cannot survive their underlying resource.
- New public helper `PayloadCache#touchByResource(domain, id)` performs a point-touch (the existing
  `touch(ctx)` does a range-touch); used by the idempotency replay path.

## [2.1.4] - 2026-05-28

### Added

- **HTTP PUT endpoint**: New `DynamicPutCallback` implements RFC 9110 §9.3.4 PUT semantics on
  `PUT /{basePath}/{id}`. The request body is the complete desired state of the resource; the
  operation is idempotent. Creates the resource (**201**) when not yet cached and replaces it
  wholesale (**200**) when it exists. The URI's id (and optional `:(version=XYZ)`) is
  authoritative — a conflicting body `id`/`version` returns **400**. On replace, `id`, `version`,
  `href`, `createdBy`, and `createdDate` are carried over from the existing entry, `revision` is
  incremented, and `updatedBy`/`updatedDate` are stamped.

### Changed

- `DynamicPostCallback` exposes a new public static helper `prepareForCacheWithHref(ctx, body,
  href)` so callers whose request path already contains the resource id (e.g. PUT) can reuse the
  same prep flow without the doubled-id `href` that `ctx.toHref()` would produce.

## [2.1.3] - 2026-05-10

### Added

- **TMF630 §6.2 bulk-create endpoint**: New `DynamicJsonPatchCollectionCallback` implements
  TMF630 Part 1 §6.2 "Creating Multiple Resources". Bound to `PATCH /{basePath}` (the collection
  URL, no id segment) with `Content-Type: application/json-patch+json`. Body is a non-empty JSON
  array of `{"op":"add", "path":"/", "value":{...}}` operations; each value goes through the same
  flow as a single POST (id generation, `href`, initial state, audit fields, `ADDITIONAL_FIELDS`).
  Atomic per RFC 5789: a duplicate id within the batch or against the cache returns 409 Conflict
  with no resources committed. Response is 200 with the array of created resources, honoring
  `?fields=` (including the `fields=none` sentinel).
- **`fields=none` support on GET callbacks**: `DynamicGetCallback` and `DynamicGetListCallback`
  now recognize `?fields=none` (case-insensitive) as a TMF630 sentinel that projects each
  returned resource to only `id` and `href`. Mixed lists like `fields=none,description` continue
  to be treated as literal field names.

### Changed

- `DynamicPostCallback` extracts a public static helper `prepareForCache(ctx, parsedBody)` so the
  bulk-create callback can reuse the per-item POST flow without going through the cache.
- Bumped runtime dependencies: Jackson 3.1.3, Netty 4.2.13.Final, netty-tcnative 2.0.77.Final,
  BouncyCastle 1.84, nimbus-jose-jwt 10.9, json-schema-validator 3.0.2, Commons Codec 1.22.0,
  Guava 33.6.0-jre.

## [2.1.2] - 2026-03-25

### Added

- **Configurable token lifetime**: `expiresIn` (seconds) can be set per realm or per client in the
  Keycloak configuration. Client-level overrides realm-level; default is 3600s when both are omitted.

## [2.1.1] - 2026-03-24

### Added

- **Keycloak Admin REST API mock**: Read-only `GET /admin/realms/{realm}/...` endpoints returning
  Keycloak-compatible JSON representations for users, groups, roles, and clients. Includes
  filtering (`username`, `search`), pagination (`first`, `max`), and sub-resource navigation
  (user role-mappings, user groups, group members, role users). Requires `admin` role when
  `ENFORCE_TOKEN=true`.
- **Group support**: New `GroupConfig` model with `name` and `subGroups`. Groups are configurable
  per realm and exposed via the Admin API.
- **Extended user metadata**: `UserConfig` now supports `email`, `firstName`, `lastName`, and
  `groups` fields, included in Admin API user representations and the default configuration.
- **Enriched default configuration**: `default-keycloak-config.json` now includes groups
  (`admins`, `developers`, `viewers`), user metadata (email, name), and explicit
  `serviceAccountRoles` for all clients.

### Changed

- Marked `jsr305` (compile-time annotations) and `slf4j-jdk14` (SLF4J binding) as
  `<optional>true</optional>` so they are not pulled transitively by consumers.

## [2.1.0] - 2026-03-22

### Changed

- **Jackson 3.x migration**: Migrated from Jackson 2.x (`com.fasterxml.jackson`) to Jackson 3.x
  (`tools.jackson`). This project no longer pulls any Jackson 2.x transitive dependencies, making
  it compatible with Spring Boot 4.x and other Jackson 3.x consumers.
- **Embedded MockServer core**: Source code from
  [mock-server/mockserver](https://github.com/mock-server/mockserver) v5.15.0 (Apache-2.0, by
  James D Bloom) is now bundled directly instead of depending on the external `mockserver-netty`
  artifact. This enabled the Jackson 3.x migration and the removal of unused features.
- **JSON Patch and Merge Patch** now use `org.opentmf:opentmf-json-patch:1.1.0`.
- Upgraded all runtime dependencies to their latest versions. All dependency versions are now
  managed via Maven properties for easy tracking with `mvn versions:display-property-updates`.
  Notable updates: Netty 4.2.10.Final, BouncyCastle 1.83, nimbus-jose-jwt 10.8,
  json-schema-validator 3.0.1, json-path 3.0.0, Guava 33.5.0-jre, Commons Lang3 3.20.0,
  SLF4J 2.0.17.

### Removed

- External `org.mock-server:mockserver-netty` and `org.mock-server:mockserver-client-java`
  dependencies.
- UI dashboard, proxy/SOCKS support, template engines (JavaScript/Velocity), XML/XPath/XmlSchema
  body matching, OpenAPI/Swagger expectation support, and Prometheus metrics -- these features are
  not needed for TMF mock usage and carried heavy transitive dependencies.
- `javax.servlet` dependency.

## [2.0.0] - 2026-03-21

### Added

- **Real JWT token generation**: `KeycloakTokenCallback` produces real, parsable, RSA-signed JWTs.
- **JWKS endpoint**: `GET /.well-known/jwks.json` is served automatically at startup via
  `JwksExpectationInitializer`, allowing clients to validate issued tokens.
- **Keycloak mock**: Configurable multi-realm Keycloak simulation with support for
  `client_credentials`, `password`, and `refresh_token` grant types. Realm-specific endpoints:
    - `GET /realms/{realm}/protocol/openid-connect/certs` (JWKS)
    - `GET /realms/{realm}/.well-known/openid-configuration` (OIDC discovery)
    - `POST /realms/{realm}/protocol/openid-connect/token` (token endpoint)
- **Keycloak configuration**: Realms, clients (public/confidential), users, roles, and grant types
  are configurable via a JSON file (`KEYCLOAK_CONFIG` env var or
  `keycloak.config.path` system property). Ships with a sensible default configuration.
- **Token enforcement**: All dynamic callbacks (`Post`, `Get`, `GetList`, `JsonPatch`,
  `MergePatch`, `Delete`) can enforce Bearer JWT validation via the `ENFORCE_TOKEN` environment
  variable. Supports validation against:
    - Built-in mock JWKS keys
    - An external JWKS URI (`JWKS_URI` env var)
    - OIDC auto-discovery via issuer (`TOKEN_ISSUER` env var)
- **Issuer validation**: When `TOKEN_ISSUER` is set, the `iss` claim is validated in addition to
  the signature.
- **Role-based authorization**: When `ENFORCE_TOKEN=true`, each dynamic callback enforces
  role requirements from the JWT's `realm_access.roles` claim:
    - GET / GET List: requires `reader`, `writer`, or `admin`
    - POST / PATCH: requires `writer` or `admin`
    - DELETE: requires `admin`
- **Integration tests**: Keycloak Testcontainers integration test (`KeycloakIntegrationIT`) runs
  during `mvn integration-test` / `mvn verify` via the Maven Failsafe plugin, validating real
  Keycloak tokens against `TokenEnforcer`.

### Removed

- `OpenidTokenCallback`, `OpenidTokenGenerator`, `TokenGenerator`, and `TokenException` -- superseded
  by `KeycloakTokenCallback` which provides Keycloak-compatible token issuance with strict validation.

### Fixed

- `DynamicPostCallback` now returns HTTP 201 Created instead of 200 OK.
- `DynamicDeleteCallback` now retrieves version from payload when needed for versioned entities.
- `DynamicGetListCallback` `compare()` handles null values without throwing.
- `DynamicGetListCallback` sort extraction now uses `LinkedHashSet` to preserve sort order.
- `DynamicJsonPatchCallback` and `DynamicMergePatchCallback` now call `setUpdateFields()` after
  patching to correctly set `updatedDate`, `updatedBy`, and `revision`.
- `PayloadCache` is now thread-safe (singleton pattern, synchronized access, bounds-checked
  `allOf()`), with correct TTL eviction and `touch()` behavior.
- Removed unused `PayloadCache.clear(String, Id)` method.

## [1.1.1] - 2025-11-27

### Fixed

- Started returning 416 Range Not Satisfiable when `offset > 0` and `offset >= totalCount`.
- Started supporting `CONTENT_RANGE_OFFSET_BASE` environment variable (accepts 0 or 1, default 1).
- Started setting `X-Result-Count` header on `getList`.

## [1.1.0] - 2025-11-07

### Fixed

- Fixes the Docker image again. 1.0.8 and 1.0.9 did not behave as expected.

## [1.0.9] - 2025-11-07

### Fixed

- Fixes the Docker image.

## [1.0.8] - 2025-11-07

### Changed

- Applies query parameters filter to the cached domain payloads in GET LIST.

## [1.0.7] - 2025-09-28

### Fixed

- `RequestContext` initialization is performed on the decoded URL string.

## [1.0.6] - 2025-05-29

### Added

- Version resolution from query parameters (`?version=XYZ`).
- `ADDITIONAL_FIELDS` environment variable support.
- `CACHE_DURATION_MILLIS` environment variable support.

## [1.0.5] - 2025-05-25

### Fixed

- Fixed the target JAR file path in the release Dockerfile.

## [1.0.4] - 2025-05-22

### Added

- Introduced a release Dockerfile.

## [1.0.3] - 2025-05-22

### Changed

- Enhanced the version resolving algorithm.

## [1.0.2] - 2025-04-11

### Changed

- First open-source version.

## [1.0.1]

### Added

- Support for versioned entities (TMF-630 Part 4.2).

## [1.0.0]

### Added

- Initial release.

[2.1.9]: https://github.com/opentmf/opentmf-mockserver/compare/2.1.8...2.1.9

[2.1.8]: https://github.com/opentmf/opentmf-mockserver/compare/2.1.7...2.1.8

[2.1.7]: https://github.com/opentmf/opentmf-mockserver/compare/2.1.6...2.1.7

[2.1.6]: https://github.com/opentmf/opentmf-mockserver/compare/2.1.5...2.1.6

[2.1.5]: https://github.com/opentmf/opentmf-mockserver/compare/2.1.4...2.1.5

[2.1.4]: https://github.com/opentmf/opentmf-mockserver/compare/2.1.3...2.1.4

[2.1.3]: https://github.com/opentmf/opentmf-mockserver/compare/2.1.2...2.1.3

[2.1.2]: https://github.com/opentmf/opentmf-mockserver/compare/2.1.1...2.1.2

[2.1.1]: https://github.com/opentmf/opentmf-mockserver/compare/2.1.0...2.1.1

[2.1.0]: https://github.com/opentmf/opentmf-mockserver/compare/2.0.0...2.1.0

[2.0.0]: https://github.com/opentmf/opentmf-mockserver/compare/1.1.1...2.0.0

[1.1.1]: https://github.com/opentmf/opentmf-mockserver/compare/1.1.0...1.1.1

[1.1.0]: https://github.com/opentmf/opentmf-mockserver/compare/1.0.9...1.1.0

[1.0.9]: https://github.com/opentmf/opentmf-mockserver/compare/1.0.8...1.0.9

[1.0.8]: https://github.com/opentmf/opentmf-mockserver/compare/opentmf-mockserver-1.0.7...1.0.8

[1.0.7]: https://github.com/opentmf/opentmf-mockserver/compare/opentmf-mockserver-1.0.6...opentmf-mockserver-1.0.7

[1.0.6]: https://github.com/opentmf/opentmf-mockserver/compare/opentmf-mockserver-1.0.5...opentmf-mockserver-1.0.6

[1.0.5]: https://github.com/opentmf/opentmf-mockserver/compare/opentmf-mockserver-1.0.4...opentmf-mockserver-1.0.5

[1.0.4]: https://github.com/opentmf/opentmf-mockserver/compare/opentmf-mockserver-1.0.3...opentmf-mockserver-1.0.4

[1.0.3]: https://github.com/opentmf/opentmf-mockserver/compare/opentmf-mockserver-1.0.2...opentmf-mockserver-1.0.3

[1.0.2]: https://github.com/opentmf/opentmf-mockserver/tag/opentmf-mockserver-1.0.2

[1.0.1]: https://github.com/opentmf/opentmf-mockserver/releases/tag/1.0.1

[1.0.0]: https://github.com/opentmf/opentmf-mockserver/releases/tag/1.0.0
