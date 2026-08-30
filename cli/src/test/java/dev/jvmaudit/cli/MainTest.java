package dev.jvmaudit.cli;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class MainTest {

  @Test
  void runsWithoutArguments() {
    assertThatCode(() -> Main.main(new String[0])).doesNotThrowAnyException();
  }
}
