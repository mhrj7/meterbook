package com.meterbook.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The mutation-testing shards (.github/workflows/mutation.yml) together cover every package and
 * top-level class of the service, so a new package cannot silently escape mutation testing.
 */
class MutationShardsTest {

  private static final Path WORKFLOW = Path.of("../../.github/workflows/mutation.yml");
  private static final Path SOURCES = Path.of("src/main/java/com/meterbook/identity");
  private static final String BASE = "com.meterbook.identity.";

  @SuppressWarnings("unchecked")
  private static List<String> targets() throws IOException {
    Map<String, Object> workflow;
    try (InputStream in = Files.newInputStream(WORKFLOW)) {
      workflow = new Yaml().load(in);
    }
    var job = (Map<String, Object>) ((Map<String, Object>) workflow.get("jobs")).get("pitest");
    var matrix = (Map<String, Object>) ((Map<String, Object>) job.get("strategy")).get("matrix");
    List<String> targets = new ArrayList<>();
    for (Map<String, String> shard : (List<Map<String, String>>) matrix.get("shard")) {
      targets.addAll(List.of(shard.get("targets").split(",")));
    }
    return targets;
  }

  @Test
  void every_package_and_top_level_class_is_in_a_shard() throws IOException {
    List<String> targets = targets();
    List<String> expected = new ArrayList<>();
    try (Stream<Path> entries = Files.list(SOURCES)) {
      entries.forEach(
          entry -> {
            String name = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
              expected.add(BASE + name + ".*");
            } else if (name.endsWith(".java")) {
              expected.add(BASE + name.substring(0, name.length() - ".java".length()));
            }
          });
    }

    assertThat(targets).containsAll(expected).doesNotHaveDuplicates();
  }
}
