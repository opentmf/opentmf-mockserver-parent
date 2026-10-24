package org.opentmf.mockserver.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockserver.model.HttpClassCallback.callback;
import static org.mockserver.model.HttpRequest.request;
import static org.opentmf.mockserver.util.Constants.ID_FORMATS;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.opentmf.mockserver.callback.DynamicGetCallback;
import org.opentmf.mockserver.callback.DynamicPostCallback;
import org.opentmf.mockserver.util.JacksonUtil;
import tools.jackson.databind.JsonNode;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;

/**
 * Starts an in-process MockServer with the dynamic POST and GET callbacks wired as an expectation
 * file would wire them, configures {@code ID_FORMATS}, and proves that a generated id comes back
 * through {@code GET /<domain>/<id>} over real HTTP.
 *
 * <p>Runs during {@code mvn integration-test} or {@code mvn verify} (not during {@code mvn test}).
 */
class IdFormatRoundTripIT {

  private static final String URN_PREFIX = "urn:example-tmf:common:document-id:";
  private static final EnvironmentVariables ENVIRONMENT =
      new EnvironmentVariables(ID_FORMATS, "document=" + URN_PREFIX + "{tsidNumber},party=uuid");
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static ClientAndServer server;

  @BeforeAll
  static void start() throws Exception {
    ENVIRONMENT.setup();
    server = ClientAndServer.startClientAndServer(0);
    for (String domain : new String[] {"document", "party"}) {
      server
          .when(request().withMethod("POST").withPath("/" + domain))
          .respond(callback(DynamicPostCallback.class.getName()));
      server
          .when(request().withMethod("GET").withPath("/" + domain + "/.*"))
          .respond(callback(DynamicGetCallback.class.getName()));
    }
  }

  @AfterAll
  static void stop() throws Exception {
    if (server != null) {
      server.stop();
    }
    ENVIRONMENT.teardown();
  }

  @Test
  void templatedDomain_generatesFormattedId_andGetByThatIdRoundTrips() throws Exception {
    String id = postAndGetBack("document");

    assertTrue(id.matches(URN_PREFIX.replace(".", "\\.") + "\\d+"), id);
  }

  @Test
  void uuidDomain_generatesUuid_andGetByThatIdRoundTrips() throws Exception {
    String id = postAndGetBack("party");

    assertEquals(id, UUID.fromString(id).toString());
  }

  private static String postAndGetBack(String domain) throws Exception {
    HttpResponse<String> created =
        send(
            HttpRequest.newBuilder(uri("/" + domain))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"name\":\"round-trip\"}"))
                .build());
    assertEquals(201, created.statusCode(), created.body());
    JsonNode createdBody = JacksonUtil.readAsTree(created.body());
    String id = createdBody.get("id").asString();
    assertEquals("/" + domain + "/" + id, createdBody.get("href").asString());

    HttpResponse<String> fetched = send(HttpRequest.newBuilder(uri("/" + domain + "/" + id)).build());
    assertEquals(200, fetched.statusCode(), fetched.body());
    JsonNode fetchedBody = JacksonUtil.readAsTree(fetched.body());
    assertEquals(id, fetchedBody.get("id").asString());
    assertEquals("round-trip", fetchedBody.get("name").asString());
    return id;
  }

  private static HttpResponse<String> send(HttpRequest request)
      throws IOException, InterruptedException {
    return HTTP.send(request, BodyHandlers.ofString());
  }

  private static URI uri(String path) {
    return URI.create("http://localhost:" + server.getLocalPort() + path);
  }
}
