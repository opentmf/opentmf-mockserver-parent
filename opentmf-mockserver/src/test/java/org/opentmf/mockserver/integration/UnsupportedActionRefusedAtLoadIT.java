package org.opentmf.mockserver.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.integration.ClientAndServer;

/**
 * OTMF-MOCKSERVER-RESPONSE-TEMPLATE-ACCEPTED-THEN-HANGS-1: this build serves no response or
 * forward templates. An expectation carrying one is refused at load with a 400 naming the action
 * type, never accepted with 201 and then left hanging; a call to its path is answered at once.
 *
 * <p>Runs during {@code mvn integration-test} or {@code mvn verify} (not during {@code mvn test}).
 */
class UnsupportedActionRefusedAtLoadIT {

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final Duration ANSWER_WITHIN = Duration.ofSeconds(5);

  private static ClientAndServer server;
  private static String base;

  @BeforeAll
  static void start() {
    server = ClientAndServer.startClientAndServer(0);
    base = "http://localhost:" + server.getLocalPort();
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.stop();
    }
  }

  private static String template(String kind) {
    return "{\"templateType\":\"" + kind + "\",\"template\":\"x\"}";
  }

  private static HttpResponse<String> putExpectation(String body) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(base + "/mockserver/expectation"))
            .timeout(ANSWER_WITHIN)
            .PUT(BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build(),
        BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(String root, String path) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(root + path)).timeout(ANSWER_WITHIN).GET().build(),
        BodyHandlers.ofString());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"VELOCITY", "MUSTACHE", "JAVASCRIPT"})
  void aResponseTemplate_isRefusedAtLoad_andItsPathAnswersAtOnce(String kind) throws Exception {
    String path = "/template/response/" + kind.toLowerCase();
    var refused =
        putExpectation(
            "{\"httpRequest\":{\"path\":\""
                + path
                + "\"},\"httpResponseTemplate\":"
                + template(kind)
                + "}");

    assertEquals(400, refused.statusCode(), refused.body());
    assertTrue(refused.body().contains("RESPONSE_TEMPLATE"), refused.body());
    assertEquals(404, get(base, path).statusCode());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"VELOCITY", "MUSTACHE", "JAVASCRIPT"})
  void aForwardTemplate_isRefusedAtLoad_andItsPathAnswersAtOnce(String kind) throws Exception {
    String path = "/template/forward/" + kind.toLowerCase();
    var refused =
        putExpectation(
            "{\"httpRequest\":{\"path\":\""
                + path
                + "\"},\"httpForwardTemplate\":"
                + template(kind)
                + "}");

    assertEquals(400, refused.statusCode(), refused.body());
    assertTrue(refused.body().contains("FORWARD_TEMPLATE"), refused.body());
    assertEquals(404, get(base, path).statusCode());
  }

  /** A load is all or nothing: the static expectation beside a template one is not added. */
  @Test
  void aLoadHoldingATemplate_addsNothing() throws Exception {
    var refused =
        putExpectation(
            "[{\"httpRequest\":{\"path\":\"/template/mixed/static\"},"
                + "\"httpResponse\":{\"statusCode\":200}},"
                + "{\"httpRequest\":{\"path\":\"/template/mixed/velocity\"},"
                + "\"httpResponseTemplate\":"
                + template("VELOCITY")
                + "}]");

    assertEquals(400, refused.statusCode(), refused.body());
    assertEquals(404, get(base, "/template/mixed/static").statusCode());
  }

  @Test
  void aStaticExpectation_isStillAcceptedAndAnswered() throws Exception {
    var accepted =
        putExpectation(
            "{\"httpRequest\":{\"path\":\"/template/static\"},"
                + "\"httpResponse\":{\"statusCode\":200,\"body\":\"ok\"}}");

    assertEquals(201, accepted.statusCode(), accepted.body());
    assertEquals("ok", get(base, "/template/static").body());
  }

  /**
   * An initialization file is not answered by a 400, so a template expectation it holds may
   * still be loaded; a matching call is then answered with a 501 naming the action type instead
   * of hanging.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"VELOCITY", "MUSTACHE", "JAVASCRIPT"})
  void aTemplateFromAnInitializationFile_isAnsweredWith501_neverHangs(
      String kind, @TempDir Path dir) throws Exception {
    Path file = dir.resolve("expectations.json");
    Files.writeString(
        file,
        "[{\"httpRequest\":{\"path\":\"/init/response\"},\"httpResponseTemplate\":"
            + template(kind)
            + "},{\"httpRequest\":{\"path\":\"/init/forward\"},\"httpForwardTemplate\":"
            + template(kind)
            + "}]");
    String previous = ConfigurationProperties.initializationJsonPath();
    ConfigurationProperties.initializationJsonPath(file.toString());
    ClientAndServer initialized = ClientAndServer.startClientAndServer(0);
    try {
      String root = "http://localhost:" + initialized.getLocalPort();

      var response = get(root, "/init/response");
      assertEquals(501, response.statusCode(), response.body());
      assertTrue(response.body().contains("RESPONSE_TEMPLATE"), response.body());

      var forward = get(root, "/init/forward");
      assertEquals(501, forward.statusCode(), forward.body());
      assertTrue(forward.body().contains("FORWARD_TEMPLATE"), forward.body());
    } finally {
      initialized.stop();
      ConfigurationProperties.initializationJsonPath(previous);
    }
  }
}
