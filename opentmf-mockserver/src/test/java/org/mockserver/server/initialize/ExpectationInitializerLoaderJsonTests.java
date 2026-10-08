package org.mockserver.server.initialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;

class ExpectationInitializerLoaderJsonTests {

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

  @Test
  void validJsonLoadsItsExpectations() {
    HttpState httpState =
        boot(Configuration.configuration().initializationJsonPath("initializer/expectations.json"));

    assertEquals(2, httpState.getRequestMatchers().retrieveActiveExpectations(null).size());
  }

  @Test
  void unsetJsonPathIsANoOp() {
    HttpState httpState =
        boot(Configuration.configuration().initializationJsonPath("").initializationYamlPath(""));

    assertEquals(0, httpState.getRequestMatchers().retrieveActiveExpectations(null).size());
  }

  @Test
  void persistingToTheInitializationFileWorksOnFirstBoot(@TempDir Path dir) {
    Path shared = dir.resolve("expectations.json");
    Configuration configuration =
        Configuration.configuration()
            .persistExpectations(true)
            .persistedExpectationsPath(shared.toString())
            .initializationJsonPath(shared.toString());

    HttpState httpState = boot(configuration);

    assertTrue(Files.exists(shared));
    assertEquals(0, httpState.getRequestMatchers().retrieveActiveExpectations(null).size());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "initializer/does-not-exist.json",
        "initializer/nothing-here-*.json",
        "initializer/broken-syntax.json",
        "initializer/broken-model.json"
      })
  void badJsonPathFailsBootWithThePath(String path) {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> boot(Configuration.configuration().initializationJsonPath(path)));
    assertTrue(failure.getMessage().contains(path), failure.getMessage());
    assertTrue(
        failure.getMessage().contains("(mockserver.initializationJsonPath)"), failure.getMessage());
  }
}
