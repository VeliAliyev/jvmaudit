package dev.jvmaudit.core.report;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What changed between two scans of the same host.
 *
 * <p>This is the creep-back detector. Run a scan weekly, diff it against last week's, and alert on
 * anything that appeared or whose licence status got worse. An Oracle JDK that reappears on a host
 * six months after it was removed is exactly the finding a customer needs and would never spot by
 * re-reading a full inventory.
 */
public record ScanDiff(
    ScanSnapshot before,
    ScanSnapshot after,
    List<ScanSnapshot.Entry> appeared,
    List<ScanSnapshot.Entry> disappeared,
    List<Change> changed) {

  /**
   * One installation that is in both scans but is not the same.
   *
   * @param before how it looked in the older scan
   * @param after how it looks now
   */
  public record Change(ScanSnapshot.Entry before, ScanSnapshot.Entry after) {

    /** Whether the licence status itself changed, as opposed to only the version. */
    public boolean statusChanged() {
      return !Objects.equals(before.status(), after.status());
    }

    /** Whether the installation was upgraded or downgraded in place. */
    public boolean versionChanged() {
      return !Objects.equals(before.version(), after.version());
    }

    /** A one-line description of what moved. */
    public String describe() {
      List<String> parts = new ArrayList<>(2);
      if (versionChanged()) {
        parts.add("version " + before.version() + " -> " + after.version());
      }
      if (statusChanged()) {
        parts.add("status " + before.status() + " -> " + after.status());
      }
      return String.join(", ", parts);
    }
  }

  public ScanDiff {
    appeared = List.copyOf(Objects.requireNonNullElse(appeared, List.of()));
    disappeared = List.copyOf(Objects.requireNonNullElse(disappeared, List.of()));
    changed = List.copyOf(Objects.requireNonNullElse(changed, List.of()));
  }

  /**
   * Compares two scans.
   *
   * @param before the older scan
   * @param after the newer scan
   * @return what changed
   */
  public static ScanDiff between(ScanSnapshot before, ScanSnapshot after) {
    List<ScanSnapshot.Entry> appeared = new ArrayList<>();
    List<ScanSnapshot.Entry> disappeared = new ArrayList<>();
    List<Change> changed = new ArrayList<>();

    Set<String> allPaths = new LinkedHashSet<>(before.paths());
    allPaths.addAll(after.paths());

    for (String path : allPaths) {
      ScanSnapshot.Entry old = before.jvms().get(path);
      ScanSnapshot.Entry current = after.jvms().get(path);
      if (old == null) {
        appeared.add(current);
      } else if (current == null) {
        disappeared.add(old);
      } else if (!Objects.equals(old.status(), current.status())
          || !Objects.equals(old.version(), current.version())) {
        changed.add(new Change(old, current));
      }
    }
    return new ScanDiff(before, after, appeared, disappeared, changed);
  }

  /** Whether anything at all changed. */
  public boolean isEmpty() {
    return appeared.isEmpty() && disappeared.isEmpty() && changed.isEmpty();
  }

  /**
   * Whether the diff contains something worth waking somebody for: a new Oracle-licensed
   * installation, or an existing one whose status moved to a paid licence.
   *
   * <p>Deliberately narrower than "anything changed". A weekly cron that alerts on every version
   * bump gets muted within a month, and then it catches nothing at all.
   */
  public boolean hasNewOracleExposure() {
    for (ScanSnapshot.Entry entry : appeared) {
      if (isOracleLicensed(entry.status())) {
        return true;
      }
    }
    for (Change change : changed) {
      if (!isOracleLicensed(change.before().status())
          && isOracleLicensed(change.after().status())) {
        return true;
      }
      if (!"ORACLE_PAID_LIKELY".equals(change.before().status())
          && "ORACLE_PAID_LIKELY".equals(change.after().status())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isOracleLicensed(String status) {
    return switch (status == null ? "" : status) {
      case "ORACLE_PAID_LIKELY", "ORACLE_FREE_NFTC", "ORACLE_FREE_GFTC", "LEGACY_BCL" -> true;
      default -> false;
    };
  }

  /**
   * Renders the diff as a console report.
   *
   * @return the report, ending in a newline
   */
  public String render() {
    String nl = "\n";
    StringBuilder out = new StringBuilder();
    out.append("Comparing ")
        .append(before.host())
        .append(" at ")
        .append(before.startedAt())
        .append(nl)
        .append("     with ")
        .append(after.host())
        .append(" at ")
        .append(after.startedAt())
        .append(nl);
    if (!before.host().equals(after.host())) {
      out.append(nl)
          .append("warning: these two scans are from different hosts, so this is a comparison of")
          .append(nl)
          .append("         two machines rather than of one machine over time.")
          .append(nl);
    }
    if (!before.rulesVersion().equals(after.rulesVersion())) {
      out.append(nl)
          .append("note: the licence rules changed between these scans (")
          .append(before.rulesVersion())
          .append(" -> ")
          .append(after.rulesVersion())
          .append("), so a status may have moved without the installation changing.")
          .append(nl);
    }
    out.append(nl);

    if (isEmpty()) {
      out.append("No change. ")
          .append(after.jvms().size())
          .append(" installations, all as before.")
          .append(nl);
      return out.toString();
    }

    for (ScanSnapshot.Entry entry : appeared) {
      out.append("+ APPEARED    ")
          .append(entry.status())
          .append("  ")
          .append(entry.product())
          .append(' ')
          .append(entry.version())
          .append(nl)
          .append("              ")
          .append(entry.path())
          .append(nl);
    }
    for (ScanSnapshot.Entry entry : disappeared) {
      out.append("- DISAPPEARED ")
          .append(entry.status())
          .append("  ")
          .append(entry.product())
          .append(' ')
          .append(entry.version())
          .append(nl)
          .append("              ")
          .append(entry.path())
          .append(nl);
    }
    for (Change change : changed) {
      out.append("~ CHANGED     ")
          .append(change.describe())
          .append(nl)
          .append("              ")
          .append(change.after().path())
          .append(nl);
    }

    out.append(nl)
        .append(appeared.size())
        .append(" appeared, ")
        .append(disappeared.size())
        .append(" disappeared, ")
        .append(changed.size())
        .append(" changed.")
        .append(nl);
    if (hasNewOracleExposure()) {
      out.append(nl)
          .append("An Oracle-licensed installation appeared, or an existing one moved to a paid")
          .append(nl)
          .append("licence. This is the case worth acting on.")
          .append(nl);
    }
    return out.toString();
  }
}
