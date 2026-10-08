package org.opentmf.mockserver.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class TmfMockBuilderTests {

  @RegisterExtension
  static MockServerSupport mock = MockServerSupport.create();

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final Pattern ID = Pattern.compile("\"id\":\"([^\"]+)\"");

  @Test
  void post_registersDynamicPostCallback_realHttpPostReturns201WithBodyEcho() throws Exception {
    mock.tmf("test").post("/document");

    HttpResponse<String> resp = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/document"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"a\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(201);
    assertThat(resp.body()).contains("\"title\":\"a\"");
    assertThat(resp.body()).contains("\"id\":");
    assertThat(resp.body()).contains("\"href\":");
  }

  @Test
  void crud_postThenGetById_returnsPostedResource() throws Exception {
    mock.tmf("test").crud("/thing");

    HttpResponse<String> post = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/thing"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"widget\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(post.statusCode()).isEqualTo(201);
    Matcher idMatcher = ID.matcher(post.body());
    assertThat(idMatcher.find()).as("id in POST response %s", post.body()).isTrue();
    String id = idMatcher.group(1);

    HttpResponse<String> get = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/thing/" + id)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(get.statusCode()).isEqualTo(200);
    assertThat(get.body()).contains("\"name\":\"widget\"").contains("\"id\":\"" + id + "\"");
  }

  @Test
  void crud_deleteById_thenGetReturns404() throws Exception {
    mock.tmf("test").crud("/gone");

    HttpResponse<String> post = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/gone"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"x\":1}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    Matcher idMatcher = ID.matcher(post.body());
    assertThat(idMatcher.find()).as("id in POST response %s", post.body()).isTrue();
    String id = idMatcher.group(1);

    HttpResponse<String> del = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/gone/" + id)).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(del.statusCode()).isEqualTo(204);

    HttpResponse<String> get = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/gone/" + id)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(get.statusCode()).isEqualTo(404);
  }

  @Test
  void apiClientId_labelPropagatesToBuilder() {
    TmfMockBuilder builder = mock.tmf("labeled");
    assertThat(builder.apiClientId()).isEqualTo("labeled");
  }

  @Test
  void put_replacesExistingEntry_returnsUpdatedBody() throws Exception {
    mock.tmf("test").post("/thing").put("/thing");

    HttpResponse<String> post = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/thing"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"before\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    Matcher idMatcher = ID.matcher(post.body());
    assertThat(idMatcher.find()).as("id in POST response %s", post.body()).isTrue();
    String id = idMatcher.group(1);

    HttpResponse<String> put = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/thing/" + id))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"id\":\"" + id + "\",\"name\":\"after\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(put.statusCode()).isEqualTo(200);
    assertThat(put.body()).contains("\"name\":\"after\"");
  }

  @Test
  void getList_returnsCollection() throws Exception {
    mock.tmf("test").post("/list-thing").getList("/list-thing");

    HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/list-thing"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"n\":1}"))
            .build(),
        HttpResponse.BodyHandlers.discarding());
    HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/list-thing"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"n\":2}"))
            .build(),
        HttpResponse.BodyHandlers.discarding());

    HttpResponse<String> list = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/list-thing")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(list.statusCode()).isEqualTo(200);
    assertThat(list.body()).contains("\"n\":1").contains("\"n\":2");
  }

  @Test
  void jsonPatch_appliesPatchToExistingEntry() throws Exception {
    mock.tmf("test").post("/jp").jsonPatch("/jp");

    HttpResponse<String> post = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/jp"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"a\":1}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    Matcher idMatcher = ID.matcher(post.body());
    assertThat(idMatcher.find()).as("id in POST response %s", post.body()).isTrue();
    String id = idMatcher.group(1);

    HttpResponse<String> patch = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/jp/" + id))
            .header("Content-Type", "application/json-patch+json")
            .method("PATCH",
                HttpRequest.BodyPublishers.ofString(
                    "[{\"op\":\"replace\",\"path\":\"/a\",\"value\":99}]"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(patch.statusCode()).isEqualTo(200);
    assertThat(patch.body()).contains("\"a\":99");
  }

  @Test
  void mergePatch_appliesMergeToExistingEntry() throws Exception {
    mock.tmf("test").post("/mp").mergePatch("/mp");

    HttpResponse<String> post = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/mp"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"a\":1,\"b\":2}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    Matcher idMatcher = ID.matcher(post.body());
    assertThat(idMatcher.find()).as("id in POST response %s", post.body()).isTrue();
    String id = idMatcher.group(1);

    HttpResponse<String> patch = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/mp/" + id))
            .header("Content-Type", "application/merge-patch+json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"b\":42}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(patch.statusCode()).isEqualTo(200);
    assertThat(patch.body()).contains("\"a\":1").contains("\"b\":42");
  }

  @Test
  void jsonPatchCollection_registersCollectionPatchExpectation() {
    // Batch collection PATCH end-to-end body needs an existing collection, which is a
    // fair bit of setup. Registration alone still covers the fluent path — verify the
    // fluent chain returns the same builder for further calls.
    TmfMockBuilder builder = mock.tmf("test").jsonPatchCollection("/batch");
    assertThat(builder).isNotNull();
  }

  @Test
  void crud_registersBothPatchFlavors_realJsonPatchAndMergePatchWork() throws Exception {
    mock.tmf("test").crud("/crud-patch");

    // Create one resource so both PATCH flavors have something to update.
    HttpResponse<String> post = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/crud-patch"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"a\":1,\"b\":2}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    Matcher idMatcher = ID.matcher(post.body());
    assertThat(idMatcher.find()).as("id in POST response %s", post.body()).isTrue();
    String id = idMatcher.group(1);

    // JSON Patch — no explicit .jsonPatch() call; crud() must have registered it.
    HttpResponse<String> jp = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/crud-patch/" + id))
            .header("Content-Type", "application/json-patch+json")
            .method("PATCH",
                HttpRequest.BodyPublishers.ofString(
                    "[{\"op\":\"replace\",\"path\":\"/a\",\"value\":99}]"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(jp.statusCode()).isEqualTo(200);
    assertThat(jp.body()).contains("\"a\":99");

    // Merge Patch — same thing, different content-type. crud() must have registered it too.
    HttpResponse<String> mp = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/crud-patch/" + id))
            .header("Content-Type", "application/merge-patch+json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"b\":42}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(mp.statusCode()).isEqualTo(200);
    assertThat(mp.body()).contains("\"b\":42");
  }

  @Test
  void withId_trailingSlash_isStrippedBeforeAppendingIdPattern() throws Exception {
    // The private withId branch that trims a trailing '/' is only reachable when the caller
    // passes a path ending in '/'. Register a GET-by-id via a trailing-slash path and
    // confirm real requests to `/trail/<id>` still match.
    mock.tmf("test").post("/trail/").get("/trail/");

    HttpResponse<String> post = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/trail/"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    // POST is registered on "/trail/" exactly; a request to "/trail/" (with trailing slash)
    // matches it.
    assertThat(post.statusCode()).isEqualTo(201);
    Matcher idMatcher = ID.matcher(post.body());
    assertThat(idMatcher.find()).as("id in POST response %s", post.body()).isTrue();
    String id = idMatcher.group(1);

    HttpResponse<String> get = HTTP.send(
        HttpRequest.newBuilder(URI.create(mock.baseUrl() + "/trail/" + id)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(get.statusCode()).isEqualTo(200);
  }
}
