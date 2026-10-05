package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClientIpResolverParityTest {
  private static String body(Path file) throws IOException {
    return Files.readString(file).replaceFirst("(?m)^package .*;$", "");
  }

  @Test
  void shouldMatchRedirectCopy_whenResolverSourcesAreCompared() throws IOException {
    Path mine = Path.of("src/main/java/com/tcrm/admin/shared/ClientIpResolver.java");
    Path other =
        Path.of("../redirect/src/main/java/com/tcrm/redirect/config/ClientIpResolver.java");
    assertThat(other).exists();
    assertThat(body(mine)).isEqualTo(body(other));
  }
}
