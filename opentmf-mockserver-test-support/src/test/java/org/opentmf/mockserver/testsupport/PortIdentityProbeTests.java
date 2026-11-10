package org.opentmf.mockserver.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.opentmf.mockserver.testsupport.PortIdentityProbe.Outcome;
import org.opentmf.mockserver.testsupport.PortIdentityProbe.Result;

class PortIdentityProbeTests {

  private HttpServer foreign;

  @AfterEach
  void stopForeign() {
    if (foreign != null) {
      foreign.stop(0);
    }
  }

  @Test
  void aMockServerListingItsPort_isMockServer() {
    ClientAndServer server = ClientAndServer.startClientAndServer(0);
    try {
      int port = server.getLocalPort();

      Result result = PortIdentityProbe.probe("http://localhost:" + port, port);

      assertThat(result.outcome()).isEqualTo(Outcome.MOCK_SERVER);
      assertThat(result.isMockServer()).isTrue();
    } finally {
      server.stop();
    }
  }

  @Test
  void aNon200_isAForeignAnswerNamingTheStatusAndBody() throws IOException {
    int port = foreignAnswering(404, "no such context");

    Result result = PortIdentityProbe.probe("http://localhost:" + port, port);

    assertThat(result.outcome()).isEqualTo(Outcome.FOREIGN_ANSWER);
    assertThat(result.isMockServer()).isFalse();
    assertThat(result.toString()).startsWith("FOREIGN_ANSWER (HTTP 404 'no such context', ");
  }

  @Test
  void a200ThatDoesNotListTheBoundPort_isAForeignAnswer() throws IOException {
    int port = foreignAnswering(200, "{\"ports\":[1]}");

    Result result = PortIdentityProbe.probe("http://localhost:" + port, port);

    assertThat(result.outcome()).isEqualTo(Outcome.FOREIGN_ANSWER);
    assertThat(result.detail()).contains("HTTP 200").contains("lists no port " + port);
  }

  @Test
  void a200WithABodyThatIsNotJson_isUnparsable() throws IOException {
    int port = foreignAnswering(200, "<html>not a mock</html>");

    Result result = PortIdentityProbe.probe("http://localhost:" + port, port);

    assertThat(result.outcome()).isEqualTo(Outcome.UNPARSABLE);
    assertThat(result.detail()).startsWith("HTTP 200 '<html>not a mock</html>'");
  }

  @Test
  void a200WithJsonThatIsNotAStatus_isUnparsable() throws IOException {
    int port = foreignAnswering(200, "{\"status\":\"UP\"}");

    Result result = PortIdentityProbe.probe("http://localhost:" + port, port);

    assertThat(result.outcome()).isEqualTo(Outcome.UNPARSABLE);
  }

  @Test
  void aLongBody_isCutInTheDetail() throws IOException {
    int port = foreignAnswering(503, "x".repeat(500));

    Result result = PortIdentityProbe.probe("http://localhost:" + port, port);

    assertThat(result.detail())
        .contains("'" + "x".repeat(80) + "...'")
        .doesNotContain("x".repeat(81));
  }

  @Test
  void aPortWithNoListener_isRefused() throws IOException {
    int port;
    try (ServerSocket released = new ServerSocket(0)) {
      port = released.getLocalPort();
    }

    Result result = PortIdentityProbe.probe("http://localhost:" + port, port);

    assertThat(result.outcome()).isEqualTo(Outcome.REFUSED);
    assertThat(result.detail())
        .startsWith("ConnectException")
        .doesNotContainIgnoringCase("reset")
        .endsWith(" ms");
  }

  @Test
  void aListenerThatResetsTheConnection_isReset() throws IOException {
    try (ServerSocket resetting = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      Thread acceptor = new Thread(() -> resetEvery(resetting), "probe-test-reset");
      acceptor.setDaemon(true);
      acceptor.start();
      int port = resetting.getLocalPort();

      Result result = PortIdentityProbe.probe("http://127.0.0.1:" + port, port);

      assertThat(result.outcome()).isEqualTo(Outcome.RESET);
      // The JDK reports an RST as "reset" (~97 %), an EOF or a broken pipe: all are RESET.
      assertThat(result.detail()).endsWith(" ms");
    }
  }

  @Test
  void aListenerThatNeverAnswers_isATimeout() throws IOException {
    // The kernel completes the handshake from the backlog; nothing ever reads or answers.
    try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      int port = silent.getLocalPort();

      Result result =
          PortIdentityProbe.probe("http://127.0.0.1:" + port, port, Duration.ofMillis(300));

      assertThat(result.outcome()).isEqualTo(Outcome.TIMEOUT);
      assertThat(result.detail()).startsWith("HttpTimeoutException");
    }
  }

  @Test
  void anInterruptedProbe_failsAndKeepsTheInterruptFlag() throws IOException {
    int port = foreignAnswering(200, "{\"ports\":[]}");
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(
              () -> PortIdentityProbe.probe("http://localhost:" + port, port))
          .isInstanceOf(IllegalStateException.class)
          .hasCauseInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  /** Accepts every connection and closes it with an RST (SO_LINGER 0) before answering. */
  private static void resetEvery(ServerSocket listener) {
    while (!listener.isClosed()) {
      try (Socket accepted = listener.accept()) {
        accepted.setSoLinger(true, 0);
      } catch (IOException closed) {
        return;
      }
    }
  }

  private int foreignAnswering(int status, String body) throws IOException {
    foreign = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    foreign.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(status, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    foreign.start();
    return foreign.getAddress().getPort();
  }
}
