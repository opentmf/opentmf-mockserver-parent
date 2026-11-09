package org.opentmf.mockserver.testsupport;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks that a port on localhost is answered by the MockServer that bound it, and not by another
 * process listening on the same port (OTMF-MOCKSERVER-SUPPORT-PORT-SHADOW-1). The probe reports
 * what answered, so a failed check says why it failed (OTMF-MOCKSERVER-PROBE-WARN-CAUSE-1).
 *
 * <p>A connection that gets no HTTP answer is classified by its cause chain, not by the exception
 * class. The JDK {@code HttpClient} wraps a refused connect and a reset during connect in the same
 * {@code ConnectException}: a refused one carries no message, a reset one says "Connection reset
 * by peer". Measured on JDK 17 against a listener that resets every connection (SO_LINGER 0), 193
 * of 200 probes named the reset in the chain and 7 surfaced as an EOF or a broken pipe; all 200
 * are {@link Outcome#RESET}.
 */
final class PortIdentityProbe {

  private static final Logger LOG = LoggerFactory.getLogger(PortIdentityProbe.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int BODY_EXCERPT_LENGTH = 80;

  /** What answered the probe. Only {@link #MOCK_SERVER} means the port is this mock's. */
  enum Outcome {
    /** A MockServer status that lists the bound port. */
    MOCK_SERVER,
    /** An HTTP answer that is not this mock: a non-200, or a status without the bound port. */
    FOREIGN_ANSWER,
    /** A 200 whose body is not a MockServer status (not JSON, or no {@code ports} list). */
    UNPARSABLE,
    /** Nothing listens: the connection was refused. */
    REFUSED,
    /** The connection was reset or closed before an HTTP answer, or failed in another way. */
    RESET,
    /** No answer within the probe timeout. */
    TIMEOUT
  }

  /**
   * The probe's outcome and a one-line detail: the HTTP status and the start of the body for an
   * answer, the exception class and message for no answer, and the elapsed time.
   */
  record Result(Outcome outcome, String detail) {

    boolean isMockServer() {
      return outcome == Outcome.MOCK_SERVER;
    }

    @Override
    public String toString() {
      return outcome + " (" + detail + ")";
    }
  }

  private PortIdentityProbe() {}

  /**
   * Sends {@code PUT /mockserver/status} to {@code baseUrl}, which is resolved exactly as the code
   * under test resolves it.
   */
  static Result probe(String baseUrl, int boundPort) {
    return probe(baseUrl, boundPort, TIMEOUT);
  }

  static Result probe(String baseUrl, int boundPort, Duration timeout) {
    HttpRequest status =
        HttpRequest.newBuilder(URI.create(baseUrl + "/mockserver/status"))
            .timeout(timeout)
            .PUT(BodyPublishers.noBody())
            .build();
    long started = System.nanoTime();
    try {
      HttpResponse<String> response = HTTP.send(status, BodyHandlers.ofString());
      return classify(response, boundPort, started);
    } catch (IOException e) {
      return noAnswer(baseUrl, e, started);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while probing " + baseUrl, e);
    }
  }

  private static Result classify(HttpResponse<String> response, int boundPort, long started) {
    String answer = "HTTP " + response.statusCode() + " " + excerpt(response.body());
    if (response.statusCode() != 200) {
      return new Result(Outcome.FOREIGN_ANSWER, answer + ", " + elapsed(started));
    }
    JsonNode ports;
    try {
      ports = JSON.readTree(response.body()).path("ports");
    } catch (JacksonException e) {
      LOG.debug("Identity probe answer is not JSON: {}", answer, e);
      return new Result(Outcome.UNPARSABLE, answer + ", " + elapsed(started));
    }
    if (!ports.isArray()) {
      return new Result(Outcome.UNPARSABLE, answer + ", " + elapsed(started));
    }
    for (JsonNode listed : ports) {
      if (listed.asInt() == boundPort) {
        return new Result(Outcome.MOCK_SERVER, answer + ", " + elapsed(started));
      }
    }
    return new Result(
        Outcome.FOREIGN_ANSWER,
        answer + ", lists no port " + boundPort + ", " + elapsed(started));
  }

  private static Result noAnswer(String baseUrl, IOException e, long started) {
    Outcome outcome = noAnswerOutcome(e);
    LOG.debug("Identity probe on {} got no answer: {}", baseUrl, outcome, e);
    return new Result(outcome, describe(e) + ", " + elapsed(started));
  }

  /**
   * The JDK client wraps both a refused connect and a reset during connect in a {@code
   * ConnectException}; a refused one carries no message, a reset one says "Connection reset by
   * peer". So the cause chain decides, not the class.
   */
  private static Outcome noAnswerOutcome(IOException e) {
    if (e instanceof HttpTimeoutException) {
      return Outcome.TIMEOUT;
    }
    for (Throwable t = e; t != null; t = t.getCause()) {
      String message = t.getMessage();
      if (message != null && message.toLowerCase(Locale.ROOT).contains("reset")) {
        return Outcome.RESET;
      }
    }
    return e instanceof ConnectException ? Outcome.REFUSED : Outcome.RESET;
  }

  /** The exception and, when it differs, its root cause: {@code A: m / caused by B: n}. */
  private static String describe(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String outer = name(e);
    String inner = name(root);
    return outer.equals(inner) ? outer : outer + " / caused by " + inner;
  }

  private static String name(Throwable t) {
    String simpleName = t.getClass().getSimpleName();
    return t.getMessage() == null ? simpleName : simpleName + ": " + t.getMessage();
  }

  private static String excerpt(String body) {
    String oneLine = body == null ? "" : body.strip().replaceAll("\\s+", " ");
    return oneLine.length() <= BODY_EXCERPT_LENGTH
        ? "'" + oneLine + "'"
        : "'" + oneLine.substring(0, BODY_EXCERPT_LENGTH) + "...'";
  }

  private static String elapsed(long started) {
    return Duration.ofNanos(System.nanoTime() - started).toMillis() + " ms";
  }
}
