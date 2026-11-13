package org.opentmf.query.tmf630.jsonb.combo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The compatibility report's comparator: reads the answers {@link CompatRecorderIT} wrote for each
 * run (R0 the release, R1 the candidate under its defaults, R2–R4 with opt-ins) and writes
 * {@code <compat.out>/report.md} and {@code report.tsv}.
 *
 * <p>THE GATE, R0 against R1: per request SAME (the same status, and for a 200 the same rows), a
 * NAMED FIX (a difference one rule of {@code named-differences.tsv} names), or FAIL (any other
 * difference, or a request only one run answered). A rule that names no request is a FAIL too.
 * The test fails on any FAIL. The switch pages (R2–R4) describe what each opt-in changes and gate
 * nothing. Run only by {@code run.sh}, after the recorder runs.
 */
@EnabledIfSystemProperty(named = "compat.compare", matches = "true")
class CompatComparatorTest {

  private static final List<String> NOT_COVERED =
      List.of(
          "Write paths: the JSONB write executor (create, replace, merge patch, JSON patch, delete),"
              + " the version resolver, the split sub-resource controllers and child counter.",
          "Paging and the response: offset/limit bounds, the Link and count headers, fields="
              + " selection (@Tmf630Response), the undeclared query-parameter guard.",
          "Settings other than the defaults the suite runs with: isnull-semantics NULLISH, regex"
              + " disabled, allowlists, on-unknown-* policies other than the defaults, combine"
              + " modes, implicit-eq variants, the TMF-630 §4.4 encoded operator forms.",
          "JSONB sorts on a split collection and through an array of arrays; correlated sorts"
              + " (sort=arr[key=X].value) and the sort-key validation.",
          "Mongo: sorts and the aggregation module; split collections; dates and date-times stored"
              + " as BSON dates (the twin holds the suite's JSON, where they are strings).",
          "JPA: nested and array positions beyond the ledger's joined-collection samples; the Object,"
              + " Map and list kinds (a typed column cannot hold them); positional [N]; sorts.",
          "Backends other than PostgreSQL 18.1 and Mongo 8.0.5 (other PostgreSQL versions, H2, other"
              + " JPA databases).");

  @Test
  void releaseAgainstCandidate() throws IOException {
    Path out = Path.of(System.getProperty("compat.out", "target/compat"));
    Map<String, Map<String, CompatAnswer>> runs = new LinkedHashMap<>();
    for (String run : List.of("R0", "R1", "R2", "R3", "R4")) {
      runs.put(run, read(out.resolve(run).resolve("answers.tsv")));
    }
    List<CompatRule> rules = CompatRule.load();

    Map<String, Integer> sameByBackend = new TreeMap<>();
    Map<String, List<String>> named = new LinkedHashMap<>();
    rules.forEach(rule -> named.put(rule.id(), new ArrayList<>()));
    List<String> fails = new ArrayList<>();
    List<String> tsv = new ArrayList<>();
    tsv.add("backend\tshape\tposition\tkind\tentry\toperator\trequest"
        + "\tR0 status\tR0 answer\tR1 status\tR1 answer\tverdict");
    Map<String, CompatAnswer> release = runs.get("R0");
    Map<String, CompatAnswer> candidate = runs.get("R1");
    for (String key : union(release, candidate)) {
      CompatAnswer from = release.get(key);
      CompatAnswer to = candidate.get(key);
      String verdict;
      if (from == null || to == null) {
        verdict = "FAIL (asked in " + (from == null ? "R1" : "R0") + " only)";
        fails.add(key + " :: " + verdict);
      } else if (same(from, to)) {
        verdict = "SAME";
        sameByBackend.merge(key.split("\t")[0], 1, Integer::sum);
      } else {
        verdict = nameOf(rules, key, from, to);
        if (verdict == null) {
          verdict = "FAIL";
          fails.add(key + " :: R0 " + show(from) + " :: R1 " + show(to));
        } else {
          named.get(verdict).add(key + " :: R0 " + show(from) + " :: R1 " + show(to));
          verdict = "NAMED FIX " + verdict;
        }
      }
      tsv.add(key + "\t" + cell(from) + "\t" + cell(to) + "\t" + verdict);
    }
    named.forEach(
        (rule, requests) -> {
          if (requests.isEmpty()) {
            fails.add("rule " + rule + " :: names no request");
          }
        });

    Files.createDirectories(out);
    Files.write(out.resolve("report.tsv"), tsv);
    Files.writeString(
        out.resolve("report.md"), report(out, runs, rules, sameByBackend, named, fails));
    assertThat(fails).as("compatibility FAILs; see " + out.resolve("report.md")).isEmpty();
  }

  private static String nameOf(
      List<CompatRule> rules, String key, CompatAnswer from, CompatAnswer to) {
    String[] fields = key.split("\t", -1);
    for (CompatRule rule : rules) {
      if (rule.names(fields, from, to)) {
        return rule.id();
      }
    }
    return null;
  }

  /** The same status; for a 200 the same rows. A 4xx or a 5xx compares by status only. */
  private static boolean same(CompatAnswer a, CompatAnswer b) {
    return a.status() == b.status() && (a.status() != 200 || a.answer().equals(b.answer()));
  }

  private static TreeSet<String> union(Map<String, ?> a, Map<String, ?> b) {
    TreeSet<String> keys = new TreeSet<>(a.keySet());
    keys.addAll(b.keySet());
    return keys;
  }

  private static Map<String, CompatAnswer> read(Path answers) throws IOException {
    Map<String, CompatAnswer> map = new TreeMap<>();
    if (!Files.exists(answers)) {
      return map;
    }
    List<String> lines = Files.readAllLines(answers);
    for (String line : lines.subList(1, lines.size())) {
      String[] c = line.split("\t", -1);
      String key = String.join("\t", List.of(c).subList(0, 7));
      map.put(key, new CompatAnswer(Integer.parseInt(c[7]), c.length > 8 ? c[8] : ""));
    }
    return map;
  }

  private static String cell(CompatAnswer a) {
    return a == null ? "-\t-" : a.status() + "\t" + a.answer();
  }

  private static String show(CompatAnswer a) {
    String answer = a.answer().length() > 160 ? a.answer().substring(0, 160) + "…" : a.answer();
    return a.status() + " [" + answer + "]";
  }

  // ---- the report ---------------------------------------------------------------------------

  private static String report(
      Path out,
      Map<String, Map<String, CompatAnswer>> runs,
      List<CompatRule> rules,
      Map<String, Integer> sameByBackend,
      Map<String, List<String>> named,
      List<String> fails)
      throws IOException {
    StringBuilder md = new StringBuilder();
    md.append("# Compatibility report: ").append(meta(out, "R1", "toolkit")).append(" against ")
        .append(meta(out, "R0", "toolkit")).append("\n\n");
    md.append("## What this report does NOT cover\n\n");
    md.append("Silence is not compatibility: nothing below has a cell in this report.\n\n");
    NOT_COVERED.forEach(line -> md.append("- ").append(line).append('\n'));

    md.append("\n## Runs\n\n| run | toolkit | Spring properties | requests | seed ms | ask ms | wall s |\n");
    md.append("| --- | --- | --- | --- | --- | --- | --- |\n");
    Map<String, String> wall = wallTimes(out);
    for (String run : runs.keySet()) {
      md.append("| ").append(run).append(" | ").append(meta(out, run, "toolkit")).append(" | ")
          .append(meta(out, run, "springProperties")).append(" | ")
          .append(meta(out, run, "requests")).append(" | ").append(meta(out, run, "seededMillis"))
          .append(" | ").append(meta(out, run, "askedMillis")).append(" | ")
          .append(wall.getOrDefault(run, "?")).append(" |\n");
    }

    md.append("\n## The gate: R0 (release) against R1 (candidate, defaults)\n\n");
    md.append("- FAIL: **").append(fails.size()).append("**\n");
    md.append("- SAME, by backend: ").append(sameByBackend).append('\n');
    md.append("- NAMED FIX, by rule:\n");
    for (CompatRule rule : rules) {
      md.append("  - ").append(rule.id()).append(" (").append(rule.changelog()).append("): ")
          .append(named.get(rule.id()).size()).append('\n');
    }
    if (!fails.isEmpty()) {
      md.append("\n### Every FAIL\n\n");
      fails.forEach(f -> md.append("- `").append(f.replace('\t', ' ')).append("`\n"));
    }
    md.append("\n### The requests each rule named\n");
    named.forEach(
        (rule, requests) -> {
          md.append("\n#### ").append(rule).append(" (").append(requests.size()).append(")\n\n");
          requests.forEach(r -> md.append("- `").append(r.replace('\t', ' ')).append("`\n"));
        });

    md.append("\n## What each switch changes (descriptive, not a gate)\n");
    Map<String, CompatAnswer> defaults = runs.get("R1");
    for (String run : List.of("R2", "R3", "R4")) {
      Map<String, CompatAnswer> switched = runs.get(run);
      List<String> changed = new ArrayList<>();
      for (String key : union(defaults, switched)) {
        CompatAnswer a = defaults.get(key);
        CompatAnswer b = switched.get(key);
        if (a == null || b == null || !same(a, b)) {
          changed.add(key.replace('\t', ' ') + " :: R1 " + (a == null ? "-" : show(a)) + " :: "
              + run + " " + (b == null ? "-" : show(b)));
        }
      }
      md.append("\n### ").append(run).append(" (").append(meta(out, run, "springProperties"))
          .append("): ").append(changed.size()).append(" requests answer otherwise than R1\n\n");
      changed.forEach(c -> md.append("- `").append(c).append("`\n"));
    }
    return md.toString();
  }

  private static String meta(Path out, String run, String key) throws IOException {
    Path meta = out.resolve(run).resolve("meta.tsv");
    if (!Files.exists(meta)) {
      return "?";
    }
    for (String line : Files.readAllLines(meta)) {
      String[] c = line.split("\t", 2);
      if (c[0].equals(key)) {
        return c.length > 1 && !c[1].isEmpty() ? c[1] : "-";
      }
    }
    return "?";
  }

  /** The wall time of each run's Maven invocation, from run.sh's {@code timings.tsv}. */
  private static Map<String, String> wallTimes(Path out) throws IOException {
    Map<String, String> wall = new LinkedHashMap<>();
    Path timings = out.resolve("timings.tsv");
    if (Files.exists(timings)) {
      for (String line : Files.readAllLines(timings)) {
        String[] c = line.split("\t");
        if (c.length >= 4) {
          wall.put(c[0], String.valueOf(Long.parseLong(c[3]) - Long.parseLong(c[2])));
        }
      }
    }
    return wall;
  }
}
