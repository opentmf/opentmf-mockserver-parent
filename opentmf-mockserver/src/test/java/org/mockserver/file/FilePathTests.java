package org.mockserver.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins how {@link FilePath#expandFilePathGlobs(String)} expands a glob: files on disk through a
 * directory walk, plus classpath resources through the ClassGraph scan.
 */
class FilePathTests {

  @Test
  void classpathGlobMatchesResourcesWithItsExtensionInItsDirectoryOnly() {
    assertEquals(
        List.of("filepath-glob/glob-a.json", "filepath-glob/glob-b.json"),
        FilePath.expandFilePathGlobs("filepath-glob/glob-*.json"));
  }

  @Test
  void classpathDoubleStarGlobDescendsIntoSubdirectories() {
    assertEquals(
        List.of("filepath-glob/nested/glob-d.json"),
        FilePath.expandFilePathGlobs("filepath-glob/**/glob-*.json"));
  }

  @Test
  void classpathQuestionMarkGlobMatchesOneCharacter() {
    assertEquals(
        List.of("filepath-glob/glob-c.txt"),
        FilePath.expandFilePathGlobs("filepath-glob/glob-?.txt"));
  }

  @Test
  void classpathGlobWithoutMatchesIsEmpty() {
    assertTrue(FilePath.expandFilePathGlobs("filepath-glob/nothing-*.json").isEmpty());
  }

  @Test
  void fileSystemGlobMatchesFilesUnderItsStartingDirectory(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("one.json"), "{}");
    Files.writeString(dir.resolve("two.json"), "{}");
    Files.writeString(dir.resolve("three.yaml"), "a: 1");
    String root = dir.toAbsolutePath().toString();

    assertEquals(
        List.of(root + "/one.json", root + "/two.json"),
        FilePath.expandFilePathGlobs(root + "/*.json"));
  }

  @Test
  void pathWithoutGlobIsReturnedAsIs() {
    assertEquals(List.of("some/file.json"), FilePath.expandFilePathGlobs("some/file.json"));
  }

  @Test
  void blankOrExtensionlessPathIsEmpty() {
    assertTrue(FilePath.expandFilePathGlobs("").isEmpty());
    assertTrue(FilePath.expandFilePathGlobs("no-extension").isEmpty());
  }
}
