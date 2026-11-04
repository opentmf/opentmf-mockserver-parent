package org.opentmf.mockserver.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks that a port on localhost is answered by the MockServer that bound it, and not by another
 * process listening on the same port (OTMF-MOCKSERVER-SUPPORT-PORT-SHADOW-1).
 */
final class PortIdentityProbe {

  private static final Logger LOG = LoggerFactory.getLogger(PortIdentityProbe.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private static final ObjectMapper JSON = new ObjectMapper();

  private PortIdentityProbe() {}

  /**
   * Sends {@code PUT /mockserver/status} to {@code baseUrl}, which is resolved exactly as the code
   * under test resolves it.
   *
   * @return {@code true} when the answer is a MockServer status that lists {@code boundPort}
   */
  static boolean answersAsMockServer(String baseUrl, int boundPort) {
    HttpRequest status =
        HttpRequest.newBuilder(URI.create(baseUrl + "/mockserver/status"))
            .timeout(TIMEOUT)
            .PUT(BodyPublishers.noBody())
            .build();
    try {
      HttpResponse<String> response = HTTP.send(status, BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return false;
      }
      for (JsonNode listed : JSON.readTree(response.body()).path("ports")) {
        if (listed.asInt() == boundPort) {
          return true;
        }
      }
      return false;
    } catch (IOException | JacksonException e) {
      LOG.debug("Identity probe on {} failed", baseUrl, e);
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while probing " + baseUrl, e);
    }
  }
}
