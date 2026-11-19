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
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.integration.ClientAndServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OTMF-MOCKSERVER-RESPONSE-TEMPLATE-ACCEPTED-THEN-HANGS-1 part (2): a Mustache response template
 * is served, so a stand-in can echo a request's JSON body field, path and header into its answer.
 * Every other template (Velocity and JavaScript responses, every forward template) is still
 * refused as in part (1).
 *
 * <p>Runs during {@code mvn integration-test} or {@code mvn verify} (not during {@code mvn test}).
 */
class MustacheResponseTemplateIT {

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final Duration ANSWER_WITHIN = Duration.ofSeconds(5);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Echoes the correlation id of the JSON body, the path and the X-Trace-Id header. */
  private static final String ECHO_TEMPLATE =
      "{\"statusCode\":202,"
          + "\"headers\":{\"Content-Type\":[\"application/json\"]},"
          + "\"body\":{\"correlationId\":\"{{#jsonPath}}$.correlationId{{/jsonPath}}"
          + "{{jsonPathResult}}\",\"path\":\"{{request.path}}\","
          + "\"trace\":\"{{request.headers.X-Trace-Id.0}}\"}}";

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

  private static String expectation(String path, String actionField, String kind, String text)
      throws Exception {
    return "{\"httpRequest\":{\"path\":\""
        + path
        + "\"},\""
        + actionField
        + "\":{\"templateType\":\""
        + kind
        + "\",\"template\":"
        + JSON.writeValueAsString(text)
        + "}}";
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

  private static HttpResponse<String> post(String root, String path, String body)
      throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(root + path))
            .timeout(ANSWER_WITHIN)
            .POST(BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .header("X-Trace-Id", "trace-7")
            .build(),
        BodyHandlers.ofString());
  }

  private static void assertEchoed(HttpResponse<String> answer, String path) throws Exception {
    assertEquals(202, answer.statusCode(), answer.body());
    JsonNode body = JSON.readTree(answer.body());
    assertEquals("corr-42", body.path("correlationId").asString(), answer.body());
    assertEquals(path, body.path("path").asString(), answer.body());
    assertEquals("trace-7", body.path("trace").asString(), answer.body());
  }

  @Test
  void aMustacheResponseTemplate_isAccepted_andEchoesBodyFieldPathAndHeader() throws Exception {
    String path = "/mustache/echo";
    var accepted =
        putExpectation(expectation(path, "httpResponseTemplate", "MUSTACHE", ECHO_TEMPLATE));
    assertEquals(201, accepted.statusCode(), accepted.body());

    assertEchoed(post(base, path, "{\"correlationId\":\"corr-42\",\"other\":1}"), path);
  }

  /** Each call is rendered from its own request: nothing of an earlier call leaks into the next. */
  @Test
  void eachCall_isRenderedFromItsOwnRequest() throws Exception {
    String path = "/mustache/each";
    assertEquals(
        201,
        putExpectation(expectation(path, "httpResponseTemplate", "MUSTACHE", ECHO_TEMPLATE))
            .statusCode());

    assertEchoed(post(base, path, "{\"correlationId\":\"corr-42\"}"), path);
    var second = post(base, path, "{\"correlationId\":\"corr-43\"}");
    assertEquals("corr-43", JSON.readTree(second.body()).path("correlationId").asString());
  }

  /** The example of MOCKSERVER.md, "Mustache response templates", as written there. */
  @Test
  void theDocumentedExample_echoesIntoAHeaderAndTheBody() throws Exception {
    String template =
        "{\"statusCode\": 202, \"headers\": {\"X-Correlation-Id\":"
            + " [\"{{request.headers.X-Correlation-Id.0}}\"]}, \"body\": {\"correlationId\":"
            + " \"{{#jsonPath}}$.correlationId{{/jsonPath}}{{jsonPathResult}}\", \"id\":"
            + " \"{{request.pathParameters.id.0}}\"}}";
    var accepted =
        putExpectation(
            "{\"httpRequest\": {\"method\": \"POST\", \"path\": \"/push/{id}\","
                + " \"pathParameters\": {\"id\": [\".*\"]}},"
                + " \"httpResponseTemplate\": {\"templateType\": \"MUSTACHE\", \"template\": "
                + JSON.writeValueAsString(template)
                + "}}");
    assertEquals(201, accepted.statusCode(), accepted.body());

    var answer =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(base + "/push/55"))
                .timeout(ANSWER_WITHIN)
                .POST(BodyPublishers.ofString("{\"correlationId\":\"corr-42\"}"))
                .header("X-Correlation-Id", "hdr-9")
                .build(),
            BodyHandlers.ofString());

    assertEquals(202, answer.statusCode(), answer.body());
    assertEquals("hdr-9", answer.headers().firstValue("X-Correlation-Id").orElse(null));
    JsonNode body = JSON.readTree(answer.body());
    assertEquals("corr-42", body.path("correlationId").asString(), answer.body());
    assertEquals("55", body.path("id").asString(), answer.body());
  }

  /** {@code mockserver.mustacheDisallowedText} refuses a template at call time, at once. */
  @Test
  void aTemplateWithDisallowedText_answersAtOnce() throws Exception {
    String path = "/mustache/disallowed";
    assertEquals(
        201,
        putExpectation(expectation(path, "httpResponseTemplate", "MUSTACHE", ECHO_TEMPLATE))
            .statusCode());
    String previous = ConfigurationProperties.mustacheDisallowedText();
    ConfigurationProperties.mustacheDisallowedText("jsonPath");
    try {
      assertEquals(404, post(base, path, "{\"correlationId\":\"corr-42\"}").statusCode());
    } finally {
      ConfigurationProperties.mustacheDisallowedText(previous == null ? "" : previous);
    }
    assertEchoed(post(base, path, "{\"correlationId\":\"corr-42\"}"), path);
  }

  @Test
  void aMustacheForwardTemplate_isStillRefusedAtLoad() throws Exception {
    var refused =
        putExpectation(
            expectation("/mustache/forward", "httpForwardTemplate", "MUSTACHE", "{}"));

    assertEquals(400, refused.statusCode(), refused.body());
    assertTrue(refused.body().contains("FORWARD_TEMPLATE"), refused.body());
  }

  /** A template that renders no valid response answers at once, never hangs. */
  @Test
  void aTemplateRenderingNoValidResponse_answersAtOnce() throws Exception {
    String path = "/mustache/broken";
    assertEquals(
        201,
        putExpectation(expectation(path, "httpResponseTemplate", "MUSTACHE", "not json"))
            .statusCode());

    assertEquals(404, post(base, path, "{}").statusCode());
  }

  @Test
  void aMustacheResponseTemplateFromAnInitializationFile_isServed(@TempDir Path dir)
      throws Exception {
    Path file = dir.resolve("expectations.json");
    Files.writeString(
        file,
        "["
            + expectation("/init/mustache", "httpResponseTemplate", "MUSTACHE", ECHO_TEMPLATE)
            + ","
            + expectation("/init/mustache-forward", "httpForwardTemplate", "MUSTACHE", "{}")
            + "]");
    String previous = ConfigurationProperties.initializationJsonPath();
    ConfigurationProperties.initializationJsonPath(file.toString());
    ClientAndServer initialized = ClientAndServer.startClientAndServer(0);
    try {
      String root = "http://localhost:" + initialized.getLocalPort();

      assertEchoed(
          post(root, "/init/mustache", "{\"correlationId\":\"corr-42\"}"), "/init/mustache");

      var forward = post(root, "/init/mustache-forward", "{}");
      assertEquals(501, forward.statusCode(), forward.body());
      assertTrue(forward.body().contains("FORWARD_TEMPLATE"), forward.body());
    } finally {
      initialized.stop();
      ConfigurationProperties.initializationJsonPath(previous);
    }
  }
}
