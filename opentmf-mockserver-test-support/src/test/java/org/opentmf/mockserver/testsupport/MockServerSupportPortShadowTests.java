package org.opentmf.mockserver.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockserver.integration.ClientAndServer;

/**
 * On macOS a wildcard bind (what MockServer does) can share its port with another process that
 * listens on 127.0.0.1 on the same port, and loopback requests then reach that other listener. A
 * Linux kernel refuses that combination, so these tests reproduce the outcome instead: the server
 * that start() gets reports the port of a foreign listener that answers 404 to everything.
 */
class MockServerSupportPortShadowTests {

  private HttpServer foreign;
  private int shadowedPort;
  private final List<ClientAndServer> started = new ArrayList<>();

  @BeforeEach
  void startForeignListener() throws Exception {
    foreign = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    foreign.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    foreign.start();
    shadowedPort = foreign.getAddress().getPort();
  }

  @AfterEach
  void stopEverything() {
    foreign.stop(0);
    started.forEach(ClientAndServer::stop);
  }

  @Test
  void start_whenTheBoundPortIsShadowed_restartsOnAPortThatAnswersAsThisMock() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (MockedStatic<ClientAndServer> starter = shadowFirst(calls, 1, shadowedPort)) {
      MockServerSupport support = MockServerSupport.create();
      try {
        assertThat(support.port()).isPositive().isNotEqualTo(shadowedPort);
        assertThat(calls).hasValue(2);

        support.stub().get("/ping").respondStatus(204);
        HttpResponse<String> response =
            HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI.create(support.baseUrl() + "/ping")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(204);
      } finally {
        support.stop();
      }
    }
  }

  @Test
  void start_whenEveryAttemptIsShadowed_failsWithANamedReason() {
    AtomicInteger calls = new AtomicInteger();
    try (MockedStatic<ClientAndServer> starter =
        shadowFirst(calls, Integer.MAX_VALUE, shadowedPort)) {
      assertThatThrownBy(MockServerSupport::create)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("MOCKSERVER-SUPPORT-PORT-SHADOW-1")
          .hasMessageContaining(String.valueOf(shadowedPort))
          .hasMessageContaining("FOREIGN_ANSWER (HTTP 404");
      assertThat(calls).hasValue(MockServerSupport.MAX_START_ATTEMPTS);
      assertThat(started).allSatisfy(server -> assertThat(server.isRunning()).isFalse());
    }
  }

  @Test
  void start_whenTheBoundPortIsShadowed_warnsWithTheForeignStatus() {
    AtomicInteger calls = new AtomicInteger();
    try (MockedStatic<ClientAndServer> starter = shadowFirst(calls, 1, shadowedPort)) {
      String log = stderrOf(() -> MockServerSupport.create().stop());

      assertThat(log)
          .contains("port " + shadowedPort)
          .contains("FOREIGN_ANSWER (HTTP 404")
          .contains("attempt 1/" + MockServerSupport.MAX_START_ATTEMPTS);
    }
  }

  @Test
  void start_whenTheBoundPortRefuses_warnsWithTheRefusal() throws IOException {
    int refusingPort;
    try (ServerSocket released = new ServerSocket(0)) {
      refusingPort = released.getLocalPort();
    }
    AtomicInteger calls = new AtomicInteger();
    try (MockedStatic<ClientAndServer> starter = shadowFirst(calls, 1, refusingPort)) {
      String log = stderrOf(() -> MockServerSupport.create().stop());

      assertThat(log)
          .contains("port " + refusingPort)
          .contains("REFUSED (ConnectException")
          .doesNotContain("FOREIGN_ANSWER");
    }
  }

  /** slf4j-simple writes to the current {@code System.err} on every call. */
  private static String stderrOf(Runnable action) {
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      action.run();
    } finally {
      System.setErr(original);
    }
    return captured.toString(StandardCharsets.UTF_8);
  }

  /** The first {@code shadowed} starts report {@code reportedPort}; later ones are real. */
  private MockedStatic<ClientAndServer> shadowFirst(
      AtomicInteger calls, int shadowed, int reportedPort) {
    MockedStatic<ClientAndServer> starter = mockStatic(ClientAndServer.class, CALLS_REAL_METHODS);
    starter
        .when(() -> ClientAndServer.startClientAndServer(any(Integer[].class)))
        .thenAnswer(
            invocation -> {
              ClientAndServer real = (ClientAndServer) invocation.callRealMethod();
              started.add(real);
              if (calls.incrementAndGet() > shadowed) {
                return real;
              }
              ClientAndServer shadowedServer = spy(real);
              doReturn(reportedPort).when(shadowedServer).getLocalPort();
              return shadowedServer;
            });
    return starter;
  }
}
