package org.opentmf.mockserver.callback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentmf.mockserver.util.Constants.CACHE_DURATION_MILLIS;
import static org.opentmf.mockserver.util.Constants.ID_FORMATS;
import static org.opentmf.mockserver.util.Constants.THREE_SECONDS;

import io.hypersistence.tsid.TSID;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.opentmf.mockserver.util.JacksonUtil;
import org.opentmf.mockserver.util.PayloadCache;
import tools.jackson.databind.JsonNode;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

/** The {@code ID_FORMATS} generated-id format, through the dynamic callbacks. */
@ExtendWith(SystemStubsExtension.class)
class IdFormatCallbackTests {

  private static final String URN_PREFIX = "urn:example-tmf:common:document-id:";

  @SystemStub
  private final EnvironmentVariables environmentVariables =
      new EnvironmentVariables(CACHE_DURATION_MILLIS, THREE_SECONDS);

  private final DynamicPostCallback postCallback = new DynamicPostCallback();
  private final DynamicGetCallback getCallback = new DynamicGetCallback();

  @Test
  void post_withoutIdFormats_generatesBareTsid() {
    String domain = randomDomain();

    JsonNode created = created(post(domain, "{\"name\":\"a\"}"));

    assertTrue(TSID.isValid(created.get("id").asString()), created.toString());
  }

  @Test
  void post_withConfiguredFormat_generatesFormattedId_andGetByThatIdRoundTrips() {
    String domain = randomDomain();
    environmentVariables.set(ID_FORMATS, domain + "=" + URN_PREFIX + "{tsidNumber}");

    JsonNode created = created(post(domain, "{\"name\":\"a\"}"));
    String id = created.get("id").asString();

    assertTrue(id.matches(URN_PREFIX.replace(".", "\\.") + "\\d+"), id);
    assertEquals("/" + domain + "/" + id, created.get("href").asString());

    HttpResponse fetched = getCallback.handle(new HttpRequest().withPath("/" + domain + "/" + id));

    assertEquals(200, fetched.getStatusCode());
    JsonNode body = JacksonUtil.readAsTree(fetched.getBodyAsString());
    assertEquals(id, body.get("id").asString());
    assertEquals("a", body.get("name").asString());
  }

  @Test
  void post_withClientSuppliedId_keepsItEvenWhenFormatIsConfigured() {
    String domain = randomDomain();
    environmentVariables.set(ID_FORMATS, domain + "=" + URN_PREFIX + "{tsidNumber}");

    JsonNode created = created(post(domain, "{\"id\":\"given-1\",\"name\":\"a\"}"));

    assertEquals("given-1", created.get("id").asString());
  }

  @Test
  void post_withMalformedIdFormats_returns500NamingTheEntry_andCachesNothing() {
    String domain = randomDomain();
    environmentVariables.set(ID_FORMATS, domain + "=" + URN_PREFIX);

    HttpResponse response = post(domain, "{\"name\":\"a\"}");

    assertEquals(500, response.getStatusCode());
    assertTrue(response.getBodyAsString().contains("Invalid ID_FORMATS entry"),
        response.getBodyAsString());
    assertTrue(PayloadCache.getInstance().getAll(domain).isEmpty());
  }

  @Test
  void collectionPatch_withMalformedIdFormats_returns500_andCachesNothing() {
    String domain = randomDomain();
    environmentVariables.set(ID_FORMATS, domain + "=x-{unknown}");
    HttpRequest request =
        new HttpRequest()
            .withMethod("PATCH")
            .withPath("/" + domain)
            .withHeader("Content-Type", "application/json-patch+json")
            .withBody("[{\"op\":\"add\",\"path\":\"/\",\"value\":{\"name\":\"a\"}}]");

    HttpResponse response = new DynamicJsonPatchCollectionCallback().handle(request);

    assertEquals(500, response.getStatusCode());
    assertTrue(PayloadCache.getInstance().getAll(domain).isEmpty());
  }

  private HttpResponse post(String domain, String body) {
    return postCallback.handle(
        new HttpRequest().withMethod("POST").withPath("/" + domain).withBody(body));
  }

  private static JsonNode created(HttpResponse response) {
    assertEquals(201, response.getStatusCode(), response.getBodyAsString());
    return JacksonUtil.readAsTree(response.getBodyAsString());
  }

  private static String randomDomain() {
    return RandomStringUtils.insecure().nextAlphabetic(8);
  }
}
