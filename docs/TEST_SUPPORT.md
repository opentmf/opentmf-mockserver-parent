# `opentmf-mockserver-test-support` — Usage Guide

Fluent JUnit 5 test helpers over `opentmf-mockserver`. Replaces the per-repo
`ClientAndServer` + `Dynamic*Callback` + JWKS + `@DynamicPropertySource`
glue that consumer integration tests keep hand-rolling.

- [Add the dependency](#add-the-dependency)
- [Two lifecycle modes](#two-lifecycle-modes)
  - [Per-class (default) — `create()`](#per-class-default--create)
  - [Per-JVM shared — `shared()`](#per-jvm-shared--shared)
  - [Which one should I pick?](#which-one-should-i-pick)
- [Spring Boot IT recipe](#spring-boot-it-recipe)
- [TMF callback registration — `mock.tmf(id)`](#tmf-callback-registration--mocktmfid)
- [Static stubs — `mock.stub()`](#static-stubs--mockstub)
- [Bounded matches + targeted clear — `.limit(N)` and `Registration`](#bounded-matches--targeted-clear--limitn-and-registration)
- [Verification — `mock.verify()`](#verification--mockverify)
- [OIDC / JWT — `mock.oidc()`, `mock.token(...)`, `mock.bearerHeader(...)`](#oidc--jwt--mockoidc-mocktoken-mockbearerheader)
- [Escape hatches](#escape-hatches)
- [Non-JUnit consumers (Cucumber / plain E2E)](#non-junit-consumers-cucumber--plain-e2e)
- [Migrating an existing hand-rolled harness](#migrating-an-existing-hand-rolled-harness)
- [Troubleshooting](#troubleshooting)

## Add the dependency

The module ships with the same coordinates as the server, so pin one version
via the `opentmf-versions` BOM and let both artifacts resolve from there:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.opentmf</groupId>
      <artifactId>opentmf-versions</artifactId>
      <version><!-- current opentmf-versions --></version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>org.opentmf.mockserver</groupId>
    <artifactId>opentmf-mockserver-test-support</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

Spring types (`spring-test`, `spring-boot-test`) are declared `provided` / `optional`
in the module — non-Spring consumers get JUnit + Nimbus + the server jar without
having Spring pulled in transitively. If your project already uses Spring, its
`spring-test` and `spring-boot-test` are on the test classpath anyway and the
`redirect*` helpers below just work.

## Two lifecycle modes

`MockServerSupport` is a JUnit 5 extension (implements `BeforeAllCallback`,
`AfterAllCallback`, `AfterEachCallback`) but the *instance* is what decides
how long the underlying MockServer lives.

### Per-class (default) — `create()`

```java
class DocumentServiceIT {
  @RegisterExtension
  static MockServerSupport mock = MockServerSupport.create();
  // …
}
```

- One MockServer per test class.
- Starts on a random free port when the class loader loads the class (which is
  before Spring's `@DynamicPropertySource` runs — safe to `redirect*` at property
  resolution time).
- Stopped by `afterAll` when the class finishes.
- Expectations are reset between `@Test` methods by `afterEach`. Call
  `mock.keepExpectationsBetweenTests()` in a `@BeforeAll` to opt out.

Pay ~1–2 s of startup per test class. Fine for a handful of ITs.

### Per-JVM shared — `shared()`

```java
class DocumentServiceIT {
  @RegisterExtension
  static MockServerSupport mock = MockServerSupport.shared();
  // …
}

class InvoiceServiceIT {
  @RegisterExtension
  static MockServerSupport mock = MockServerSupport.shared();   // same instance
  // …
}
```

- **One MockServer for the whole JVM.** `shared()` is lazily initialized on
  first call and returns the same instance forever.
- Safe to `@RegisterExtension` it in every test class: `afterAll` is a no-op
  on shared instances, so closing one class doesn't stop the server the next
  class needs.
- A JVM shutdown hook stops it cleanly at exit.
- `afterEach` still resets expectations at the end of every method — intra-class
  isolation is unchanged.
- Every test class sees the same `port()` / `baseUrl()`, so Spring's context
  cache can reuse a context across classes without re-resolving properties.

**Caveats.** `afterEach` reset is scoped to the underlying MockServer *client*,
not to the calling class — if two test classes are running in parallel, one
class's reset will clobber the other's expectations mid-test. This mode targets
sequential runs. If you have JUnit 5's `junit.jupiter.execution.parallel.enabled`
turned on across classes, use `create()` for those ITs or narrow the parallel
scope to methods within a class.

### Which one should I pick?

| Situation | Use |
|---|---|
| < 10 IT classes | `create()` — cost of startup is noise |
| Many IT classes, sequential runs | `shared()` — save startup time |
| Parallel test classes | `create()` (or narrow parallelism to methods) |
| Test classes with wildly different mock states you can't reset between | `create()` per class |
| You want one Spring context cached across classes | `shared()` so port/base-URL are stable |

## Spring Boot IT recipe

```java
@SpringBootTest
class DocumentServiceIT {

  @RegisterExtension
  static MockServerSupport mock = MockServerSupport.shared();

  @DynamicPropertySource
  static void redirect(DynamicPropertyRegistry registry) {
    // Every opentmf.api-clients.<id>.base-url → the mock; clears context-path.
    mock.redirectApiClients(registry, "onedms", "invoicing");

    // For raw HTTP clients bound to opentmf.http-clients.<id>.base-url instead:
    mock.redirectHttpClients(registry, "billing-gateway");

    // opentmf.security.jwk-set-uri → mock's /realms/realm1/protocol/openid-connect/certs
    mock.redirectJwks(registry);
  }

  @Autowired DocumentService svc;

  @Test
  void archiveDocument_returns201() {
    mock.tmf("onedms").crud("/document");     // POST/GET/GET-list/PUT/DELETE + jsonPatch + mergePatch
    mock.stub().get("/kba/{key}").pathParam("key", "42")
        .respondJson(200, "{\"value\":42}");

    svc.archive("42");

    mock.verify().post("/document").times(1);
    mock.verify().get("/kba/42").once();
  }
}
```

`DynamicPropertyRegistry.add` takes a supplier and Spring evaluates it at
property resolution — the port is already known by then because `create()` /
`shared()` starts eagerly.

## TMF callback registration — `mock.tmf(id)`

`mock.tmf("clientLabel")` returns a `TmfMockBuilder`. The `clientLabel` is a
free-form string (typically the api-client id you pass to `redirectApiClients`);
it does not affect the URL pattern.

```java
mock.tmf("onedms")
    .post("/document")            // POST /document              → DynamicPostCallback
    .get("/document")             // GET  /document/{id}         → DynamicGetCallback
    .getList("/document")         // GET  /document              → DynamicGetListCallback
    .put("/document")             // PUT  /document/{id}         → DynamicPutCallback
    .delete("/document")          // DELETE /document/{id}       → DynamicDeleteCallback
    .jsonPatch("/document")       // PATCH /document/{id}, application/json-patch+json
    .mergePatch("/document")      // PATCH /document/{id}, application/merge-patch+json
    .jsonPatchCollection("/document"); // PATCH /document, batch collection JSON Patch
```

Shortcut for the seven per-resource verbs typical TMF services expose:

```java
mock.tmf("onedms").crud("/document");
// same as .post("/document").get("/document").getList("/document").put("/document")
//              .delete("/document").jsonPatch("/document").mergePatch("/document")
```

Both PATCH flavors are included because PATCH is the de-facto update mechanism
in TMF v4 (PUT is described in TMF-630 but omitted from v4 API swaggers). PUT is
kept anyway — the expectation is harmless if no client sends PUT. `jsonPatchCollection`
is deliberately NOT included: it's a collection-level batch op (on `PATCH /document`,
not `PATCH /document/{id}`), a different shape from the seven per-resource verbs.
Register it separately when needed.

Notes:
- The `{id}` on GET/PUT/DELETE/PATCH is a regex (`[^/]+`) — arbitrary TSIDs match.
- Payloads round-trip through the server's in-memory cache with TMF-630 semantics
  (auto `id`/`href`/state, revision, versioning, `Content-Range` for list GETs, etc.).
  Registration is one line; behaviour is whatever the server does at runtime.

## Static stubs — `mock.stub()`

For non-TMF endpoints (KBA lookups, external gateways, retry paths):

```java
mock.stub().get("/kba/{key}").respondJson(200, "{\"v\":42}");
mock.stub().post("/sms").header("X-Api-Key", "s3cret").respondStatus(204);
mock.stub().get("/slow").respondDelayed(200, "{}", Duration.ofMillis(500));
mock.stub().get("/flaky").respondSequence(500, 500, 200);   // N-th call gets N-th status
```

Full builder surface:

| Method | Effect |
|---|---|
| `get / post / put / delete(path)` | Start defining an expectation on that verb+path |
| `method(verb, path)` | Same, for arbitrary verbs (`OPTIONS`, `HEAD`, `PATCH`, ...) |
| `limit(n)` | Cap matches to N (see [Bounded matches](#bounded-matches--targeted-clear--limitn-and-registration)) |
| `pathParam(name, value)` | Restrict a path template variable |
| `queryParam(name, value)` | Restrict a query parameter |
| `header(name, value)` | Restrict a header (regex accepted) |
| `jsonBody(body)` | Restrict to a specific JSON body |
| `respondJson(status, body)` | Terminate: JSON response — returns `Registration` |
| `respondStatus(status)` | Terminate: status only, no body — returns `Registration` |
| `respondDelayed(status, body, duration)` | Terminate: JSON response after a delay — returns `Registration` |
| `respondSequence(int...)` | Terminate: N expectations, N-th call gets N-th status — returns `Registration` |
| `respondSequence(HttpResponse...)` | Terminate: N expectations with full response control — returns `Registration` |

Every `respond*` is terminal — you cannot chain another call after it. Calling
a matcher (`pathParam`, `header`, ...) or a `respond*` before a verb-starter
throws `IllegalStateException`.

## Bounded matches + targeted clear — `.limit(N)` and `Registration`

Two knobs on top of the basic stub flow: cap how many times an expectation
matches, and remove a specific stub without wiping the whole server.

### `.limit(N)` — match at most N times

By default a stub matches every request that fits its shape, forever. Add
`.limit(N)` to make it retire after the N-th match:

```java
mock.stub().get("/flaky").limit(3).respondStatus(500);
mock.stub().get("/flaky").respondStatus(200);   // 4th call and onward
```

- The first three requests to `/flaky` get **500**.
- The fourth match falls through to the unlimited expectation → **200**.
- `.limit(N)` applies to the *next* `respond*` only; each new `get/post/...`
  resets to unlimited. So you never accidentally carry a limit across chains.
- `.limit(N)` on the same chain as `respondSequence(...)` is a no-op —
  sequences enforce their own `Times.exactly(1)` per element. Documented and
  covered by a test.

### `Registration` — targeted clear

Every `respond*` and `mock.expect(...)` returns a `Registration` handle:

```java
public final class Registration {
  public String id();          // first expectation id (or null if none)
  public List<String> ids();   // every id for this registration
  public void clear();         // MockServer.clear(id) for each id (idempotent)
}
```

Use it when `afterEach`'s "reset everything" is too coarse — for example
you want to change one specific stub mid-test without disturbing the others:

```java
@Test
void retriesUntilServerStopsFailing() throws Exception {
  Registration failing = mock.stub().get("/order/42").respondStatus(500);
  mock.stub().get("/inventory").respondJson(200, "{\"count\":7}");   // must survive

  service.tryOnce();          // sees 500, backs off
  service.tryOnce();          // 500 again
  failing.clear();            // stop failing
  mock.stub().get("/order/42").respondJson(200, "{\"id\":\"42\"}");

  service.tryOnce();          // now succeeds
  // /inventory expectation was never touched
}
```

`respondSequence(...)` returns a Registration whose `.ids()` carries every
per-element expectation id — one `.clear()` retires the whole sequence:

```java
Registration seq = mock.stub().get("/rollout").respondSequence(500, 500, 200);
// … partway through the test …
seq.clear();   // drops all three expectations at once
```

Batch clearing:

```java
Registration a = mock.stub().get("/a").respondStatus(200);
Registration b = mock.stub().get("/b").respondStatus(200);
Registration c = mock.stub().get("/c").respondStatus(200);
mock.clear(a, b, c);         // convenience for the "clear this pile" pattern
```

`clear` tolerates nulls in the array, and each `Registration.clear()` is
idempotent — calling it twice is a no-op.

### When you *don't* need `Registration`

If you're happy with the default per-test-method reset (`afterEach`
`server.reset()`), you can ignore the return value entirely — nothing else
changes. `Registration` matters when:
- You want to swap out one stub for another mid-test.
- You called `keepExpectationsBetweenTests()` and need to prune only some
  stubs at the end of a specific test.
- You have a helper that registers a big pile of stubs and wants to hand
  the caller a single handle to clean them all up.

## Verification — `mock.verify()`

```java
mock.verify().post("/document").times(1);
mock.verify().get("/kba/42").once();                                 // sugar for times(1)
mock.verify().delete("/document/nope").never();                      // sugar for times(0)
mock.verify().post("/sms").withHeader("X-Trace", "T1").atLeast(1);
mock.verify().put("/document").withQueryParam("dry-run", "true").atMost(3);
mock.verify().post("/invoice").withJsonBody("{\"amount\":100}").once();
```

Same verbs/matcher chain as `stub()`, terminated by `times / atLeast / atMost / never / once`.
All terminators throw `AssertionError` on mismatch — hook into your test-report the
same way as any other assertion.

## OIDC / JWT — `mock.oidc()`, `mock.token(...)`, `mock.bearerHeader(...)`

The mockserver has a built-in RSA key and JWKS endpoint. Mint real signed JWTs
verifiable against that JWKS:

```java
String tok    = mock.token("reader");                       // user "test-user", realm_access.roles=[reader]
String tokFor = mock.tokenFor("alice", "admin");            // user "alice"
String hdr    = mock.bearerHeader("reader", "writer");      // "Bearer <jwt>"
```

Tokens are Keycloak-shaped:
- `iss` → `realm1` (or the realm you configure)
- `azp` → `opentmf-mockserver`
- `preferred_username` → the username
- `realm_access.roles` → the provided roles
- `resource_access.<clientId>.roles` → same roles

Need a different realm / client / expiry?

```java
String tok = mock.oidc()
    .realm("realm42")
    .clientId("my-client")
    .expiresInSeconds(300)
    .tokenFor("bob", "admin");
```

`mock.redirectJwks(registry)` publishes the JWKS at
`/realms/<realm>/protocol/openid-connect/certs` on the mock and points
`opentmf.security.jwk-set-uri` at it, so `TokenEnforcer` verifies these tokens
against the same keys that signed them.

## Escape hatches

The fluent builders cover ~90% of cases. When you need raw MockServer:

```java
mock.client()                                          // the underlying MockServerClient
    .when(request().withPath("/x"))
    .respond(response().withStatusCode(200));

mock.expect(                                           // convenience one-liner
    request().withPath("/x"),
    response().withStatusCode(200));
```

`mock.client()` is the same instance the builders wrap — anything you do on it
composes with anything the builders do.

## Non-JUnit consumers (Cucumber / plain E2E)

`MockServerSupport` is not tied to JUnit's lifecycle — it just implements the
JUnit callbacks. Use it directly:

```java
public class E2eHarness {
  private final MockServerSupport mock = MockServerSupport.create();

  public void setup() {
    mock.tmf("onedms").crud("/document");
  }

  public void teardown() {
    mock.stop();
  }
}
```

Or share one across the whole Cucumber run:

```java
public final class SharedMock {
  private SharedMock() {}
  public static final MockServerSupport MOCK = MockServerSupport.shared();
}
```

`shared()` handles the shutdown hook itself — no `stop()` needed.

## Migrating an existing hand-rolled harness

Typical starting state in a downstream project — every IT has some flavour of:

```java
class OrchestrateE2eIT {
  static ClientAndServer server;
  static int port;

  @BeforeAll
  static void startMock() {
    port = PortFactory.findFreePort();                  // probe, then bind: another process
    server = ClientAndServer.startClientAndServer(port); // can take the port in between
    server.when(...)
          .respond(new DynamicPostCallback());
    // + 20 lines of JWKS wiring, TokenUtil, DynamicPropertyRegistry glue
  }

  @AfterAll
  static void stopMock() { server.stop(); }
}
```

Replace with:

```java
class OrchestrateE2eIT {
  @RegisterExtension
  static MockServerSupport mock = MockServerSupport.shared();

  @DynamicPropertySource
  static void redirect(DynamicPropertyRegistry registry) {
    mock.redirectApiClients(registry, "onedms");
    mock.redirectJwks(registry);
  }

  @BeforeAll
  static void wireEndpoints() {
    mock.tmf("onedms").crud("/document");
  }
}
```

The `TokenUtil` and `JwksExpectationInitializer` glue disappears — `mock.token(...)`
replaces the former, `mock.redirectJwks(registry)` the latter.

## Troubleshooting

**`java.net.BindException: Address already in use`** — you're re-running an IT
that used `create()` before the previous JVM released the port. Usually
transient; a rerun clears it. If you're stuck with it, switch to `shared()` —
the port is picked once per JVM and released only at shutdown.

**Test A's expectations still visible in test B (same class).** You probably
enabled `keepExpectationsBetweenTests()` and forgot. Without it, `afterEach`
resets everything.

**Test A's expectations still visible in test B (different classes) with `shared()`.**
Expected — `shared()` deliberately keeps one server. Reset happens per method,
not per class, so cross-class expectation leak between the *last* method of A
and the *first* method of B does not happen; if you're seeing it, you're likely
running in parallel — see [caveats above](#per-jvm-shared--shared).

**`@DynamicPropertySource` sees `port() == 0`.** You're calling into the mock
before it's started. This should not happen with `create()` / `shared()` (both
start eagerly), but if you construct `new MockServerSupport()` directly and forget
`.start()`, port stays 0. Use the factory methods.

**`redirectApiClients` doesn't propagate.** You're using
`opentmf.http-clients.<id>.*` bindings instead of `opentmf.api-clients.<id>.*`.
Use `redirectHttpClients` for those.
