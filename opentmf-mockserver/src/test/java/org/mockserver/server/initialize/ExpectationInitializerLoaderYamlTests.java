package org.mockserver.server.initialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;

class ExpectationInitializerLoaderYamlTests {

  private static final String JSON = "initializer/expectations.json";
  private static final String YAML = "initializer/expectations.yaml";

  private final MockServerLogger logger = new MockServerLogger(getClass());
  private final List<HttpState> started = new ArrayList<>();

  @AfterEach
  void stop() {
    started.forEach(HttpState::stop);
  }

  private HttpState boot(Configuration configuration) {
    HttpState httpState =
        new HttpState(configuration, logger, new Scheduler(configuration, logger));
    started.add(httpState);
    return httpState;
  }

  private List<Expectation> active(HttpState httpState) {
    return httpState.getRequestMatchers().retrieveActiveExpectations(null);
  }

  private static String shape(Expectation expectation) {
    return String.join(
        "|",
        String.valueOf(expectation.getHttpRequest()),
        String.valueOf(expectation.getAction()),
        String.valueOf(expectation.getTimes()),
        String.valueOf(expectation.getPriority()));
  }

  @Test
  void yamlWithCommentsLoadsTheSameExpectationsAsItsJsonTwin() {
    List<Expectation> fromJson =
        active(boot(Configuration.configuration().initializationJsonPath(JSON)));
    List<Expectation> fromYaml =
        active(boot(Configuration.configuration().initializationYamlPath(YAML)));

    assertEquals(2, fromYaml.size());
    assertEquals(
        fromJson.stream().map(ExpectationInitializerLoaderYamlTests::shape).sorted().toList(),
        fromYaml.stream().map(ExpectationInitializerLoaderYamlTests::shape).sorted().toList());
  }

  @Test
  void jsonAndYamlPathsMayBeSetTogether() {
    List<Expectation> both =
        active(
            boot(
                Configuration.configuration()
                    .initializationJsonPath(JSON)
                    .initializationYamlPath(YAML)));

    assertEquals(4, both.size());
  }

  @Test
  void missingYamlFileFailsBootWithThePath() {
    String path = "initializer/does-not-exist.yaml";
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> boot(Configuration.configuration().initializationYamlPath(path)));
    assertTrue(failure.getMessage().contains(path), failure.getMessage());
  }

  @Test
  void yamlGlobMatchingNothingFailsBootWithThePath() {
    String path = "initializer/nothing-here-*.yaml";
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> boot(Configuration.configuration().initializationYamlPath(path)));
    assertTrue(failure.getMessage().contains(path), failure.getMessage());
  }

  @Test
  void unparseableYamlFailsBootWithThePath() {
    String path = "initializer/broken-syntax.yaml";
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> boot(Configuration.configuration().initializationYamlPath(path)));
    assertTrue(failure.getMessage().contains(path), failure.getMessage());
  }

  @Test
  void yamlThatIsNotAnExpectationDocumentFailsBootWithThePath() {
    String path = "initializer/broken-model.yaml";
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> boot(Configuration.configuration().initializationYamlPath(path)));
    assertTrue(failure.getMessage().contains(path), failure.getMessage());
  }
}
