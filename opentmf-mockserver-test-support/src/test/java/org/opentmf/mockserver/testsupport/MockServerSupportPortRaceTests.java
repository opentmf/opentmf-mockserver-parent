package org.opentmf.mockserver.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockserver.socket.PortFactory;

/**
 * start() used to probe a free port and then bind it, so another process could take the port in
 * between and start() failed with a BindException. This test makes the probe return a port that
 * is already taken; start() must still end with a running, reachable server.
 */
class MockServerSupportPortRaceTests {

  @Test
  void start_whenTheProbedPortIsTakenBeforeTheBind_stillStartsAServer() throws Exception {
    try (ServerSocket taken = new ServerSocket(0);
        MockedStatic<PortFactory> probe = mockStatic(PortFactory.class, CALLS_REAL_METHODS)) {
      probe.when(PortFactory::findFreePort).thenReturn(taken.getLocalPort());

      MockServerSupport support = MockServerSupport.create();
      try {
        assertThat(support.port()).isPositive().isNotEqualTo(taken.getLocalPort());
        assertThat(support.client().hasStarted()).isTrue();

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
}
