package dev.jvmaudit.cli;

import dev.jvmaudit.core.model.Citation;
import dev.jvmaudit.core.model.Confidence;
import dev.jvmaudit.core.model.Product;
import dev.jvmaudit.core.report.TableReport;
import dev.jvmaudit.core.rules.LicenseRule;
import dev.jvmaudit.core.rules.LicenseRulesEngine;
import dev.jvmaudit.core.rules.RuleSet;
import dev.jvmaudit.core.rules.RulesLoader;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code jvmaudit rules} - prints every rule, its plain-English meaning, its confidence and its
 * sources.
 *
 * <p>This command is the product's honesty check. A user who does not trust a verdict can read the
 * entire rule set, see which rules are inferences rather than quotations, and follow every source
 * URL, without cloning the repository.
 */
@Command(
    name = "rules",
    mixinStandardHelpOptions = true,
    description = "Print the licence rules, their sources, and which of them are inferences.")
public final class RulesCommand implements Callable<Integer> {

  @Option(
      names = "--raw",
      description = "Print the rule data file verbatim instead of the summary.")
  boolean raw;

  @Option(
      names = "--unverified-only",
      description =
          "Print only the rules JVMAudit could not trace to a published Oracle statement.")
  boolean unverifiedOnly;

  @CommandLine.Spec CommandLine.Model.CommandSpec spec;

  @Override
  public Integer call() {
    PrintWriter out = spec.commandLine().getOut();

    if (raw) {
      out.print(RulesLoader.readClasspathFile(RulesLoader.LICENSE_RULES_FILE));
      out.flush();
      return ExitCode.CLEAN;
    }

    RuleSet rules = LicenseRulesEngine.usingPackagedRules().ruleSet();

    out.println("Licence rules version " + rules.rulesVersion());
    out.println(
        "Product catalogue version "
            + rules.products().catalogVersion()
            + " ("
            + rules.products().products().size()
            + " distributions), "
            + rules.releases().size()
            + " known release dates");
    out.println();

    int printed = 0;
    for (LicenseRule rule : rules.rules()) {
      if (unverifiedOnly && rule.confidence() != Confidence.UNVERIFIED) {
        continue;
      }
      printed++;
      out.println(rule.id() + "  [" + rule.status().name() + "]");
      out.println(TableReport.wrap(rule.summary(), 96, "    "));
      if (rule.confidence() == Confidence.UNVERIFIED) {
        out.println(
            TableReport.wrap(
                "! UNVERIFIED - this is JVMAudit's inference, not a statement Oracle publishes.",
                96,
                "    "));
      }
      for (Citation citation : rule.citations()) {
        out.println("    source: " + citation.url());
      }
      out.println();
    }

    if (unverifiedOnly && printed == 0) {
      out.println("Every rule is traceable to a published Oracle statement.");
      out.println();
    }

    if (!unverifiedOnly) {
      out.println("Recognised distributions:");
      for (Product product : rules.products().products()) {
        out.println(
            "    "
                + pad(product.id(), 16)
                + pad(product.displayName(), 36)
                + (product.oracle() ? "Oracle" : "third party")
                + (product.matchConfidence() == Confidence.UNVERIFIED
                    ? "  (vendor string not yet confirmed against a real build)"
                    : ""));
      }
      out.println();
    }

    out.println(TableReport.wrap(rules.disclaimer(), 96, ""));
    out.flush();
    return ExitCode.CLEAN;
  }

  private static String pad(String text, int width) {
    return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
  }

  /**
   * The rules version, for {@code --version}, without letting a broken rule file stop the tool from
   * reporting what it is.
   *
   * @return the version, or a placeholder
   */
  static String rulesVersionQuietly() {
    try {
      return LicenseRulesEngine.usingPackagedRules().ruleSet().rulesVersion();
    } catch (RuntimeException e) {
      return "unavailable";
    }
  }
}
