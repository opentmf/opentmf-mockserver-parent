package org.mockserver.server.initialize;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.log.model.LogEntry.LogMessageType.SERVER_CONFIGURATION;
import static org.slf4j.event.Level.*;

import com.google.common.annotations.VisibleForTesting;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ArrayUtils;
import org.mockserver.cache.LRUCache;
import org.mockserver.configuration.Configuration;
import org.mockserver.file.FilePath;
import org.mockserver.file.FileReader;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause;
import org.mockserver.serialization.ExpectationSerializer;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * @author jamesdbloom
 */
public class ExpectationInitializerLoader {

  private static final LRUCache<String, List<String>> EXPANDED_INITIALIZATION_JSON_PATHS =
      new LRUCache<>(new MockServerLogger(LRUCache.class), 10, TimeUnit.HOURS.toMillis(1));
  private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
  private final Configuration configuration;
  private final ExpectationSerializer expectationSerializer;
  private final MockServerLogger mockServerLogger;
  private final RequestMatchers requestMatchers;

  public ExpectationInitializerLoader(
      Configuration configuration,
      MockServerLogger mockServerLogger,
      RequestMatchers requestMatchers) {
    this.configuration = configuration;
    this.expectationSerializer = new ExpectationSerializer(mockServerLogger);
    this.mockServerLogger = mockServerLogger;
    this.requestMatchers = requestMatchers;
    addExpectationsFromInitializer();
  }

  public static List<String> expandedInitializationJsonPaths(String initializationJsonPath) {
    if (isNotBlank(initializationJsonPath)) {
      List<String> expandedInitializationJsonPaths =
          EXPANDED_INITIALIZATION_JSON_PATHS.get(initializationJsonPath);
      if (expandedInitializationJsonPaths == null) {
        expandedInitializationJsonPaths = FilePath.expandFilePathGlobs(initializationJsonPath);
        EXPANDED_INITIALIZATION_JSON_PATHS.put(
            initializationJsonPath, expandedInitializationJsonPaths);
      }
      return expandedInitializationJsonPaths;
    } else {
      return Collections.emptyList();
    }
  }

  private void addExpectationsFromInitializer() {
    retrieveExpectationsFromJson();
    retrieveExpectationsFromYaml();
    for (Expectation expectation : retrieveExpectationsFromInitializerClass()) {
      requestMatchers.add(expectation, new Cause("", Cause.Type.CLASS_INITIALISER));
    }
  }

  private Expectation[] retrieveExpectationsFromInitializerClass() {
    Expectation[] expectations = new Expectation[0];
    String initializationClass = configuration.initializationClass();
    try {
      if (isNotBlank(initializationClass)) {
        if (MockServerLogger.isEnabled(INFO) && mockServerLogger != null) {
          mockServerLogger.logEvent(
              new LogEntry()
                  .setType(SERVER_CONFIGURATION)
                  .setLogLevel(INFO)
                  .setMessageFormat("loading class initialization file:{}")
                  .setArguments(initializationClass));
        }
        ClassLoader contextClassLoader = ExpectationInitializerLoader.class.getClassLoader();
        if (contextClassLoader != null && isNotBlank(initializationClass)) {
          Constructor<?> initializerClassConstructor =
              contextClassLoader.loadClass(initializationClass).getDeclaredConstructor();
          Object expectationInitializer = initializerClassConstructor.newInstance();
          if (expectationInitializer instanceof ExpectationInitializer) {
            expectations =
                ((ExpectationInitializer) expectationInitializer).initializeExpectations();
          }
        }
      }
      if (expectations.length > 0) {
        if (MockServerLogger.isEnabled(TRACE) && mockServerLogger != null) {
          mockServerLogger.logEvent(
              new LogEntry()
                  .setLogLevel(TRACE)
                  .setMessageFormat("loaded expectations:{}from class:{}")
                  .setArguments(Arrays.asList(expectations), initializationClass));
        }
        requestMatchers.update(
            expectations,
            new MockServerMatcherNotifier.Cause(initializationClass, Cause.Type.CLASS_INITIALISER));
      }
    } catch (Throwable throwable) {
      if (MockServerLogger.isEnabled(WARN) && mockServerLogger != null) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(SERVER_CONFIGURATION)
                .setLogLevel(WARN)
                .setMessageFormat(
                    "exception while loading JSON initialization class, ignoring class:{}")
                .setArguments(initializationClass)
                .setThrowable(throwable));
      }
    }
    return expectations;
  }

  // opentmf: at startup a configured JSON path is strict, like the YAML one. The lenient
  // retrieveExpectationsFromFile below stays for the file watcher's hot reload, where a
  // bad edit must not take down a running server.
  private Expectation[] retrieveExpectationsFromJson() {
    return retrieveExpectationsStrictly(
        "JSON",
        "mockserver.initializationJsonPath",
        configuration.initializationJsonPath(),
        content -> content);
  }

  public List<Expectation> retrieveExpectationsFromFile(
      String initialLogMessage,
      String expectationLogMessage,
      String completedLogMessage,
      Cause.Type causeType) {
    List<String> initializationJsonPaths =
        ExpectationInitializerLoader.expandedInitializationJsonPaths(
            configuration.initializationJsonPath());
    return initializationJsonPaths.stream()
        .flatMap(
            initializationJsonPath -> {
              Expectation[] expectations = new Expectation[0];
              if (isNotBlank(initializationJsonPath)) {
                if (isNotBlank(initialLogMessage) && MockServerLogger.isEnabled(INFO)) {
                  mockServerLogger.logEvent(
                      new LogEntry()
                          .setType(SERVER_CONFIGURATION)
                          .setLogLevel(INFO)
                          .setMessageFormat(initialLogMessage)
                          .setArguments(initializationJsonPath));
                }
                try {
                  String jsonExpectations =
                      FileReader.readFileFromClassPathOrPath(initializationJsonPath);
                  if (isNotBlank(jsonExpectations)) {
                    expectations =
                        deserializeWithStableIds(initializationJsonPath, jsonExpectations);
                  }
                } catch (Throwable throwable) {
                  if (MockServerLogger.isEnabled(WARN) && mockServerLogger != null) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(SERVER_CONFIGURATION)
                            .setLogLevel(WARN)
                            .setMessageFormat(expectationLogMessage)
                            .setArguments(initializationJsonPath)
                            .setThrowable(throwable));
                  }
                }
              }
              if (MockServerLogger.isEnabled(TRACE) && mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(TRACE)
                        .setMessageFormat(completedLogMessage)
                        .setArguments(Arrays.asList(expectations), initializationJsonPath));
              }
              requestMatchers.update(expectations, new Cause(initializationJsonPath, causeType));
              return Arrays.stream(expectations);
            })
        .collect(Collectors.toList());
  }

  private Expectation[] retrieveExpectationsFromYaml() {
    return retrieveExpectationsStrictly(
        "YAML",
        "mockserver.initializationYamlPath",
        configuration.initializationYamlPath(),
        content -> YAML_MAPPER.readTree(content).toString());
  }

  /**
   * Loads every file the configured path (or glob) names. An unset path is a no-op and an empty
   * file loads nothing; a path that matches no file, or a file that does not parse into
   * expectations, fails startup by name.
   */
  private Expectation[] retrieveExpectationsStrictly(
      String format,
      String property,
      String configuredPath,
      UnaryOperator<String> toJsonExpectations) {
    if (isBlank(configuredPath)) {
      return new Expectation[0];
    }
    List<String> paths = FilePath.expandFilePathGlobs(configuredPath);
    if (paths.isEmpty()) {
      throw new IllegalStateException(
          format
              + " initialization path \""
              + configuredPath
              + "\" ("
              + property
              + ") matched no files");
    }
    List<Expectation> loaded = new ArrayList<>();
    for (String path : paths) {
      if (MockServerLogger.isEnabled(INFO) && mockServerLogger != null) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(SERVER_CONFIGURATION)
                .setLogLevel(INFO)
                .setMessageFormat("loading " + format + " initialization file:{}")
                .setArguments(path));
      }
      Expectation[] expectations;
      try {
        String content = FileReader.readFileFromClassPathOrPath(path);
        // An existing but empty file loads nothing rather than failing: persistExpectations
        // creates the (shared) persistence file empty before this loader runs on first boot.
        expectations =
            isBlank(content)
                ? new Expectation[0]
                : deserializeWithStableIds(path, toJsonExpectations.apply(content));
      } catch (Throwable throwable) {
        throw new IllegalStateException(
            "failed to load "
                + format
                + " initialization file \""
                + path
                + "\" ("
                + property
                + "): "
                + throwable.getMessage(),
            throwable);
      }
      requestMatchers.update(expectations, new Cause(path, Cause.Type.FILE_INITIALISER));
      loaded.addAll(Arrays.asList(expectations));
    }
    return loaded.toArray(new Expectation[0]);
  }

  private Expectation[] deserializeWithStableIds(String path, String jsonExpectations) {
    List<String> expectationIds = new ArrayList<>();
    return expectationSerializer.deserializeArray(
        jsonExpectations,
        true,
        (expectationString, deserialisedExpectations) -> {
          for (int i = 0; i < deserialisedExpectations.size(); i++) {
            int counter = 0;
            String expectationId;
            do {
              expectationId =
                  UUID.nameUUIDFromBytes(
                          String.valueOf(Objects.hash(path, expectationString, i, counter++))
                              .getBytes(StandardCharsets.UTF_8))
                      .toString();
            } while (expectationIds.contains(expectationId) && counter < 50);
            expectationIds.add(expectationId);
            deserialisedExpectations.get(i).withIdIfNull(expectationId);
          }
          return deserialisedExpectations;
        });
  }

  @VisibleForTesting
  public Expectation[] loadExpectations() {
    final Expectation[] expectationsFromInitializerClass =
        retrieveExpectationsFromInitializerClass();
    final Expectation[] expectationsFromJson = retrieveExpectationsFromJson();
    return ArrayUtils.addAll(
        ArrayUtils.addAll(expectationsFromInitializerClass, expectationsFromJson),
        retrieveExpectationsFromYaml());
  }
}
