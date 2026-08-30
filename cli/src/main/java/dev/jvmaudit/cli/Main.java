package dev.jvmaudit.cli;

import dev.jvmaudit.core.BuildInfo;

/** Entry point. Replaced by the full picocli command tree in M3. */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    System.out.println("jvmaudit " + BuildInfo.version());
  }
}
