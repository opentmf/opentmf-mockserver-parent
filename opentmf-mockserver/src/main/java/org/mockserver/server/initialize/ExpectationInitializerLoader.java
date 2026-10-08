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

  private Expectation[] retrieveExpectationsFromJson() {
    return retrieveExpectationsFromFile(
            "loading JSON initialization file:{}",
            "exception while loading JSON initialization file, ignoring file:{}",
            "loaded expectations:{}from file:{}",
            Cause.Type.FILE_INITIALISER)
        .toArray(new Expectation[0]);
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
    String initializationYamlPath = configuration.initializationYamlPath();
    if (isBlank(initializationYamlPath)) {
      return new Expectation[0];
    }
    List<String> yamlPaths = FilePath.expandFilePathGlobs(initializationYamlPath);
    if (yamlPaths.isEmpty()) {
      throw new IllegalStateException(
          "YAML initialization path \""
              + initializationYamlPath
              + "\" (mockserver.initializationYamlPath) matched no files");
    }
    List<Expectation> loaded = new ArrayList<>();
    for (String yamlPath : yamlPaths) {
      if (MockServerLogger.isEnabled(INFO) && mockServerLogger != null) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(SERVER_CONFIGURATION)
                .setLogLevel(INFO)
                .setMessageFormat("loading YAML initialization file:{}")
                .setArguments(yamlPath));
      }
      Expectation[] expectations;
      try {
        String jsonExpectations =
            YAML_MAPPER.readTree(FileReader.readFileFromClassPathOrPath(yamlPath)).toString();
        expectations = deserializeWithStableIds(yamlPath, jsonExpectations);
      } catch (Throwable throwable) {
        throw new IllegalStateException(
            "failed to load YAML initialization file \""
                + yamlPath
                + "\" (mockserver.initializationYamlPath): "
                + throwable.getMessage(),
            throwable);
      }
      requestMatchers.update(expectations, new Cause(yamlPath, Cause.Type.FILE_INITIALISER));
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
