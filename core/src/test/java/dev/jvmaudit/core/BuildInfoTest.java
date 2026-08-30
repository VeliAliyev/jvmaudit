package dev.jvmaudit.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildInfoTest {

  @Test
  void exposesTheMavenProjectVersion() {
    assertThat(BuildInfo.version()).isNotBlank().isNotEqualTo("unknown").startsWith("0.1.0");
  }
}
