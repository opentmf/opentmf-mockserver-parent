package org.opentmf.mockserver.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;

class PortIdentityProbeTests {

  private HttpServer foreign;

  @AfterEach
  void stopForeign() {
    if (foreign != null) {
      foreign.stop(0);
    }
  }

  @Test
  void aMockServerListingItsPort_answersAsMockServer() {
    ClientAndServer server = ClientAndServer.startClientAndServer(0);
    try {
      int port = server.getLocalPort();
      assertThat(PortIdentityProbe.answersAsMockServer("http://localhost:" + port, port)).isTrue();
    } finally {
      server.stop();
    }
  }

  @Test
  void a200ThatDoesNotListTheBoundPort_doesNotAnswerAsMockServer() throws IOException {
    int port = foreignAnswering(200, "{\"ports\":[1]}");

    assertThat(PortIdentityProbe.answersAsMockServer("http://localhost:" + port, port)).isFalse();
  }

  @Test
  void a200WithABodyThatIsNotJson_doesNotAnswerAsMockServer() throws IOException {
    int port = foreignAnswering(200, "<html>not a mock</html>");

    assertThat(PortIdentityProbe.answersAsMockServer("http://localhost:" + port, port)).isFalse();
  }

  @Test
  void aPortWithNoListener_doesNotAnswerAsMockServer() throws IOException {
    int port;
    try (ServerSocket released = new ServerSocket(0)) {
      port = released.getLocalPort();
    }

    assertThat(PortIdentityProbe.answersAsMockServer("http://localhost:" + port, port)).isFalse();
  }

  @Test
  void anInterruptedProbe_failsAndKeepsTheInterruptFlag() throws IOException {
    int port = foreignAnswering(200, "{\"ports\":[]}");
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(
              () -> PortIdentityProbe.answersAsMockServer("http://localhost:" + port, port))
          .isInstanceOf(IllegalStateException.class)
          .hasCauseInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
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
