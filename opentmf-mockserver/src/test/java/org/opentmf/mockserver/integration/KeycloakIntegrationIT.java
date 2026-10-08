package org.opentmf.mockserver.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockserver.model.HttpRequest.request;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockserver.model.HttpResponse;
import org.opentmf.mockserver.token.TokenEnforcer;
import org.opentmf.mockserver.util.JacksonUtil;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;

/**
 * Integration test that starts a real Keycloak container, obtains real tokens, and validates them
 * with {@link TokenEnforcer}.
 *
 * <p>Runs during {@code mvn integration-test} or {@code mvn verify} (not during {@code mvn test}).
 * Requires Docker to be available.
 */
@Testcontainers
class KeycloakIntegrationIT {

  private static final String REALM = "realm1";

  @SuppressWarnings("resource")
  @Container
  static final GenericContainer<?> keycloak =
      new GenericContainer<>("quay.io/keycloak/keycloak:26.7.2")
          .withExposedPorts(8080)
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("keycloak-test-realm.json"),
              "/opt/keycloak/data/import/realm1.json")
          .withCommand("start-dev", "--import-realm")
          .waitingFor(Wait.forHttp("/realms/realm1").forPort(8080).forStatusCode(200))
          .withStartupTimeout(java.time.Duration.ofMinutes(3));

  private static String issuer;
  private static String tokenEndpoint;
  private static String jwksUri;

  @BeforeAll
  static void resolveEndpoints() {
    String keycloakBaseUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    issuer = keycloakBaseUrl + "/realms/" + REALM;
    tokenEndpoint = issuer + "/protocol/openid-connect/token";
    jwksUri = issuer + "/protocol/openid-connect/certs";
  }

  // ---- client_credentials grant ----

  @Test
  void clientCredentialsToken_validatedByJwksUri() throws Exception {
    String token =
        obtainToken("grant_type=client_credentials&client_id=client1&client_secret=client1Secret");
    assertNotNull(token);

    TokenEnforcer enforcer = new TokenEnforcer(true, "", jwksUri);

    assertNull(
        enforcer.validate(
            request().withPath("/api/test").withHeader("Authorization", "Bearer " + token)),
        "Valid client_credentials token should pass validation");

    SignedJWT jwt = SignedJWT.parse(token);
    JWTClaimsSet claims = jwt.getJWTClaimsSet();
    assertEquals(issuer, claims.getIssuer());
    assertNotNull(claims.getExpirationTime());
    assertNotNull(claims.getSubject());
  }

  // ---- password grant ----

  @Test
  void passwordGrantToken_validatedByJwksUri() throws Exception {
    String token =
        obtainToken(
            "grant_type=password&client_id=client2&client_secret=client2Secret"
                + "&username=admin_usr&password=admin_pwd");
    assertNotNull(token);

    TokenEnforcer enforcer = new TokenEnforcer(true, "", jwksUri);

    assertNull(
        enforcer.validate(
            request().withPath("/api/test").withHeader("Authorization", "Bearer " + token)),
        "Valid password grant token should pass validation");

    SignedJWT jwt = SignedJWT.parse(token);
    assertEquals("admin_usr", jwt.getJWTClaimsSet().getStringClaim("preferred_username"));
  }

  @Test
  void passwordGrantToken_readerUser_hasClaims() throws Exception {
    String token =
        obtainToken(
            "grant_type=password&client_id=uiClient" + "&username=reader_usr&password=reader_pwd");
    assertNotNull(token);

    TokenEnforcer enforcer = new TokenEnforcer(true, "", jwksUri);

    assertNull(
        enforcer.validate(
            request().withPath("/api/test").withHeader("Authorization", "Bearer " + token)));

    SignedJWT jwt = SignedJWT.parse(token);
    assertEquals("reader_usr", jwt.getJWTClaimsSet().getStringClaim("preferred_username"));
  }

  // ---- OIDC auto-discovery via TOKEN_ISSUER ----

  @Test
  void tokenValidated_viaOidcDiscovery() throws Exception {
    String token =
        obtainToken("grant_type=client_credentials&client_id=client1&client_secret=client1Secret");

    TokenEnforcer enforcer = new TokenEnforcer(true, issuer, "");

    assertNull(
        enforcer.validate(
            request().withPath("/api/test").withHeader("Authorization", "Bearer " + token)),
        "Token should be validated via auto-discovered JWKS URI");
  }

  // ---- issuer validation ----

  @Test
  void wrongIssuerConfig_rejectsValidToken() throws Exception {
    String token =
        obtainToken("grant_type=client_credentials&client_id=client1&client_secret=client1Secret");

    TokenEnforcer enforcer = new TokenEnforcer(true, "https://wrong-issuer", jwksUri);

    HttpResponse resp =
        enforcer.validate(
            request().withPath("/api/test").withHeader("Authorization", "Bearer " + token));
    assertNotNull(resp, "Token with wrong issuer should be rejected");
    assertEquals(401, resp.getStatusCode());
    assertTrue(resp.getBodyAsString().contains("Issuer mismatch"));
  }

  @Test
  void correctIssuerConfig_acceptsValidToken() throws Exception {
    String token =
        obtainToken(
            "grant_type=password&client_id=client2&client_secret=client2Secret"
                + "&username=writer_usr&password=writer_pwd");

    TokenEnforcer enforcer = new TokenEnforcer(true, issuer, jwksUri);

    assertNull(
        enforcer.validate(
            request().withPath("/api/test").withHeader("Authorization", "Bearer " + token)),
        "Token with correct issuer should pass");
  }

  // ---- tampered token ----

  @Test
  void tamperedToken_isRejected() throws Exception {
    String token =
        obtainToken("grant_type=client_credentials&client_id=client1&client_secret=client1Secret");

    String tampered = token.substring(0, token.lastIndexOf('.')) + ".dGFtcGVyZWQ";

    TokenEnforcer enforcer = new TokenEnforcer(true, "", jwksUri);

    HttpResponse resp =
        enforcer.validate(
            request().withPath("/api/test").withHeader("Authorization", "Bearer " + tampered));
    assertNotNull(resp, "Tampered token should be rejected");
    assertEquals(401, resp.getStatusCode());
  }

  // ---- mock-signed token rejected by real Keycloak keys ----

  @Test
  void mockSignedToken_rejectedByRealKeycloakKeys() {
    Map<String, java.io.Serializable> claims = new java.util.LinkedHashMap<>();
    claims.put("iss", issuer);
    claims.put("sub", "fake-user");
    claims.put("exp", System.currentTimeMillis() / 1000 + 3600);
    String mockToken = org.opentmf.mockserver.token.JwtKeyProvider.getInstance().signJwt(claims);

    TokenEnforcer enforcer = new TokenEnforcer(true, "", jwksUri);

    HttpResponse resp =
        enforcer.validate(
            request().withPath("/api/test").withHeader("Authorization", "Bearer " + mockToken));
    assertNotNull(
        resp, "Mock-signed token must be rejected when validating against real Keycloak JWKS");
    assertEquals(401, resp.getStatusCode());
  }

  // ---- helper ----

  private String obtainToken(String formBody) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(tokenEndpoint).toURL().openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

    try (OutputStream os = conn.getOutputStream()) {
      os.write(formBody.getBytes(StandardCharsets.UTF_8));
    }

    assertEquals(
        200, conn.getResponseCode(), "Token request failed: " + readStream(conn.getErrorStream()));

    String responseBody;
    try (InputStream is = conn.getInputStream()) {
      responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    JsonNode json = JacksonUtil.readAsTree(responseBody);
    return json.get("access_token").asString();
  }

  private String readStream(InputStream is) {
    if (is == null) {
      return "<no body>";
    }
    try {
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "<read error: " + e.getMessage() + ">";
    }
  }
}
