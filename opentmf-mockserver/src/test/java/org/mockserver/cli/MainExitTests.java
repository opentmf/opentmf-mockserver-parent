package org.mockserver.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.configuration.ConfigurationProperties;

class MainExitTests {

  private final List<Integer> exitCodes = new ArrayList<>();
  private IntConsumer originalExit;
  private PrintStream originalOut;
  private PrintStream originalErr;

  @BeforeEach
  void captureExitAndOutput() {
    originalExit = Main.exit;
    originalOut = Main.systemOut;
    originalErr = Main.systemErr;
    Main.exit = exitCodes::add;
    Main.systemOut = new PrintStream(new ByteArrayOutputStream());
    Main.systemErr = new PrintStream(new ByteArrayOutputStream());
    Main.usageShown = false;
  }

  @AfterEach
  void restore() {
    Main.exit = originalExit;
    Main.systemOut = originalOut;
    Main.systemErr = originalErr;
    ConfigurationProperties.initializationYamlPath("");
    System.clearProperty("mockserver.initializationYamlPath");
  }

  @Test
  void startupFailureExitsWithNonZeroStatus() {
    ConfigurationProperties.initializationYamlPath("initializer/does-not-exist.yaml");

    Main.main("-serverPort", "0");

    assertEquals(List.of(1), exitCodes);
  }
}
