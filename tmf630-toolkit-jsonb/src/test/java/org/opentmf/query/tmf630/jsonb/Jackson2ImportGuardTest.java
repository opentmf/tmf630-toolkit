package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guard against Jackson 2 creeping back into this module. The jsonb module is on
 * Jackson 3 ({@code tools.jackson}); importing {@code com.fasterxml.jackson.databind}
 * or {@code com.fasterxml.jackson.core} would silently reintroduce the Jackson 2
 * mapper-bean requirement on consumers. The annotations namespace
 * ({@code com.fasterxml.jackson.annotation}) is shared between Jackson 2 and 3 and
 * stays legal.
 */
class Jackson2ImportGuardTest {

  private static final String BANNED_PREFIX = "com.fasterxml.jackson.";
  private static final String ALLOWED_NAMESPACE = "com.fasterxml.jackson.annotation.";

  @Test
  @DisplayName("no Jackson 2 databind/core imports in main or test sources")
  void noJackson2Imports() throws IOException {
    List<String> violations =
        Stream.of(Path.of("src", "main", "java"), Path.of("src", "test", "java"))
            .flatMap(Jackson2ImportGuardTest::javaFiles)
            .flatMap(Jackson2ImportGuardTest::bannedImportLines)
            .toList();
    assertThat(violations)
        .as("Jackson 2 imports are banned in tmf630-toolkit-jsonb — use tools.jackson")
        .isEmpty();
  }

  private static Stream<Path> javaFiles(Path root) {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(p -> p.toString().endsWith(".java")).toList().stream();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot walk source tree " + root, e);
    }
  }

  private static Stream<String> bannedImportLines(Path file) {
    try {
      return Files.readAllLines(file).stream()
          .map(String::strip)
          .filter(Jackson2ImportGuardTest::isBannedImport)
          .map(line -> file + ": " + line);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read " + file, e);
    }
  }

  private static boolean isBannedImport(String line) {
    if (!line.startsWith("import ")) {
      return false;
    }
    String imported = line.substring("import ".length()).replaceFirst("^static\\s+", "");
    return imported.startsWith(BANNED_PREFIX) && !imported.startsWith(ALLOWED_NAMESPACE);
  }
}
