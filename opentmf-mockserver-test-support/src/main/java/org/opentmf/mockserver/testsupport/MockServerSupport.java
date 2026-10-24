package org.opentmf.mockserver.testsupport;

import java.util.Objects;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Test-support facade around an in-process MockServer, plus JUnit 5 extension for lifecycle.
 *
 * <p>Typical usage in a Spring Boot integration test:
 *
 * <pre>{@code
 * @RegisterExtension
 * static MockServerSupport mock = MockServerSupport.create();
 *
 * @DynamicPropertySource
 * static void redirect(DynamicPropertyRegistry registry) {
 *   mock.redirectApiClients(registry, "onedms");
 *   mock.redirectJwks(registry);
 * }
 *
 * @Test
 * void archive_returns201() {
 *   mock.tmf("onedms").crud("/document");
 *   mock.stub().get("/kba/{key}").respondJson(200, "{}");
 *   // ... exercise SUT, then:
 *   mock.verify().post("/document").times(1);
 * }
 * }</pre>
 *
 * <p>The server is started eagerly in {@link #create()} so a fixed port is available to
 * {@code @DynamicPropertySource} at Spring context load time (which runs before this
 * extension's {@code beforeAll}). {@link BeforeAllCallback} is a defensive no-op; the
 * server is stopped by {@link AfterAllCallback}.
 *
 * <p>For projects with many integration tests, prefer {@link #shared()} over {@link #create()}
 * so a single MockServer serves every test class in the JVM (started lazily on first call,
 * stopped by a JVM shutdown hook). Per-test {@code afterEach} reset still runs.
 *
 * <p>Also usable outside JUnit via {@link #start()} / {@link #stop()} for Cucumber or plain
 * E2E harnesses.
 */
// java:S6548 — the JVM-wide singleton via shared() is deliberate; see the shared()
// javadoc for the "one MockServer per JVM" rationale.
@SuppressWarnings("java:S6548")
public class MockServerSupport
    implements BeforeAllCallback, AfterAllCallback, AfterEachCallback {

  private static final Logger LOG = LoggerFactory.getLogger(MockServerSupport.class);

  @SuppressWarnings("java:S3077") // DCL singleton — volatile+synchronized is correct here.
  private static volatile MockServerSupport sharedInstance;

  private ClientAndServer server;
  private int port;
  private boolean keepExpectationsBetweenTests;
  private boolean sharedLifecycle;

  private MockServerSupport() {}

  /** Create a support instance and start MockServer on a random free port. */
  public static MockServerSupport create() {
    return new MockServerSupport().start();
  }

  /**
   * Return a JVM-wide shared instance, starting it lazily on first call. Every subsequent
   * call returns the same instance. The shared instance is safe to
   * {@code @RegisterExtension} in every test class: its {@link #afterAll} is a no-op, so
   * closing one class's boundary does not stop the server. A JVM shutdown hook stops it
   * cleanly at exit.
   *
   * <p>Use this to avoid paying MockServer's ~1-2s startup cost per test class in projects
   * with many integration tests. Trade-off: expectations set by one test class are visible
   * to concurrent tests in other classes. This mode is meant for sequential test runs;
   * enabling JUnit's parallel execution across classes on a shared instance will race
   * expectations. {@code afterEach} still resets expectations at the end of every method
   * (unless {@link #keepExpectationsBetweenTests()} is on), keeping intra-class isolation
   * intact.
   */
  public static MockServerSupport shared() {
    if (sharedInstance == null) {
      synchronized (MockServerSupport.class) {
        if (sharedInstance == null) {
          MockServerSupport instance = new MockServerSupport().start();
          instance.sharedLifecycle = true;
          Runtime.getRuntime()
              .addShutdownHook(new Thread(instance::stop, "MockServerSupport-shared-shutdown"));
          sharedInstance = instance;
        }
      }
    }
    return sharedInstance;
  }

  /**
   * Start MockServer on a random free port. Idempotent — subsequent calls are no-ops.
   *
   * <p>Binds port 0 and reads back the port the OS assigned. Probing for a free port first and
   * binding it afterwards left a window in which another process could take the port, failing the
   * start with a {@code BindException} under parallel builds.
   */
  public MockServerSupport start() {
    if (server != null && server.isRunning()) {
      return this;
    }
    server = ClientAndServer.startClientAndServer(0);
    port = server.getLocalPort();
    LOG.info("MockServerSupport started on port {}", port);
    return this;
  }

  /** Stop MockServer if running. Idempotent. */
  public void stop() {
    if (server != null && server.isRunning()) {
      server.stop();
      LOG.info("MockServerSupport stopped (port {})", port);
    }
  }

  /**
   * By default, expectations are reset after every {@code @Test} method. Call this before
   * tests run to keep expectations across the class.
   */
  public MockServerSupport keepExpectationsBetweenTests() {
    this.keepExpectationsBetweenTests = true;
    return this;
  }

  /** Random free port the server is listening on. */
  public int port() {
    return port;
  }

  /** {@code http://localhost:<port>} — the base URL of the mock. */
  public String baseUrl() {
    return "http://localhost:" + port;
  }

  /** Underlying MockServer client for escape-hatch access. */
  public MockServerClient client() {
    return Objects.requireNonNull(server, "MockServer not started");
  }

  /** Fluent DynamicPost/Get/Put/Delete/JsonPatch/MergePatch/JsonPatchCollection registration. */
  public TmfMockBuilder tmf(String apiClientId) {
    return new TmfMockBuilder(client(), apiClientId);
  }

  /** Fluent static-expectation builder for non-TMF endpoints. */
  public StubBuilder stub() {
    return new StubBuilder(client());
  }

  /** Fluent verification builder. */
  public VerifyBuilder verify() {
    return new VerifyBuilder(client());
  }

  /** JWT minting + JWKS access for OIDC-secured consumers. */
  public OidcMockSupport oidc() {
    return new OidcMockSupport(client(), baseUrl());
  }

  /**
   * Convenience — mints a JWT with the given realm-access roles for user {@code "test-user"}.
   * Signed with the mockserver's built-in RSA key; verifiable against the JWKS served at
   * {@link OidcMockSupport#jwksPath()}.
   */
  public String token(String... roles) {
    return oidc().token(roles);
  }

  /** Convenience — same as {@link #token(String...)} but for a specific username. */
  public String tokenFor(String username, String... roles) {
    return oidc().tokenFor(username, roles);
  }

  /** {@code "Bearer " + token(roles)} — drop straight into an Authorization header. */
  public String bearerHeader(String... roles) {
    return "Bearer " + token(roles);
  }

  /**
   * Redirect each {@code opentmf.api-clients.<id>.base-url} at this mock and clear the
   * corresponding {@code context-path}. Suppliers are lazy: Spring evaluates them at
   * property resolution time.
   */
  public void redirectApiClients(DynamicPropertyRegistry registry, String... clientIds) {
    for (String id : clientIds) {
      registry.add("opentmf.api-clients." + id + ".base-url", this::baseUrl);
      registry.add("opentmf.api-clients." + id + ".context-path", () -> "");
    }
  }

  /**
   * Redirect {@code opentmf.http-clients.<id>.base-url} at this mock. For non-TMF raw HTTP
   * clients that use the {@code opentmf.http-clients.*} binding instead of
   * {@code opentmf.api-clients.*}.
   */
  public void redirectHttpClients(DynamicPropertyRegistry registry, String... clientIds) {
    for (String id : clientIds) {
      registry.add("opentmf.http-clients." + id + ".base-url", this::baseUrl);
    }
  }

  /** Redirect {@code opentmf.security.jwk-set-uri} at this mock's JWKS endpoint. */
  public void redirectJwks(DynamicPropertyRegistry registry) {
    redirectJwks(registry, OidcMockSupport.DEFAULT_REALM);
  }

  /** Redirect {@code opentmf.security.jwk-set-uri} at this mock's JWKS endpoint for {@code realm}. */
  public void redirectJwks(DynamicPropertyRegistry registry, String realm) {
    OidcMockSupport support = oidc().realm(realm);
    support.registerJwksEndpoint();
    registry.add("opentmf.security.jwk-set-uri", support::jwksUri);
  }

  /**
   * Register an arbitrary expectation directly. Escape hatch. Returns a {@link Registration}
   * so the caller can {@link Registration#clear()} just this expectation later without
   * wiping the whole server.
   */
  public Registration expect(HttpRequest request, HttpResponse response) {
    return new Registration(client(), client().when(request).respond(response));
  }

  /**
   * Clear the given {@link Registration}s from the server (calls
   * {@link Registration#clear()} on each). Idempotent. Convenience wrapper for the common
   * "clear a small pile of stubs at the end of a test" pattern.
   */
  public void clear(Registration... registrations) {
    for (Registration r : registrations) {
      if (r != null) {
        r.clear();
      }
    }
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    start();
  }

  @Override
  public void afterAll(ExtensionContext context) {
    if (sharedLifecycle) {
      // Shared instance — lifecycle is bound to the JVM, not to any single test class.
      // The shutdown hook installed by shared() will stop the server at JVM exit.
      return;
    }
    stop();
  }

  @Override
  public void afterEach(ExtensionContext context) {
    if (server != null && !keepExpectationsBetweenTests) {
      server.reset();
    }
  }
}
