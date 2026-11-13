package org.opentmf.query.tmf630.jsonb.combo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The compatibility report's comparator: reads the answers {@link CompatRecorderIT} wrote for each
 * run (R0 the release, R1 the candidate under its defaults, R2–R5 with opt-ins) and writes
 * {@code <compat.out>/report.md} and {@code report.tsv}.
 *
 * <p>THE GATE, R0 against R1: per request SAME (the same status, and for a 200 the same rows), a
 * NAMED FIX (a difference one rule of {@code named-differences.tsv} names, its evidence holding),
 * or FAIL (any other difference, a request only one run answered, a named fix whose evidence does
 * not hold, a named fix on Mongo or JPA without evidence). A rule that names no request fails, and
 * so does a rule with a transition from a 2xx to a 4xx or a 5xx: a request that answered must not
 * answer an error after the upgrade, whatever its old rows were. The test fails on any FAIL.
 *
 * <p>Described, not gated: the requests that answer the same rows in both versions but not the
 * oracle's (defects of the release, unless a documented reason is shown), and what each opt-in
 * changes (R2–R5 against R1). Run only by {@code run.sh}, after the recorder runs.
 */
@EnabledIfSystemProperty(named = "compat.compare", matches = "true")
class CompatComparatorTest {

  private static final List<String> RUNS = List.of("R0", "R1", "R2", "R3", "R4", "R5");
  private static final List<String> SWITCHES = List.of("R2", "R3", "R4", "R5");

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

  private static final Path SUITE_PINS =
      Path.of("../tmf630-toolkit-jsonb/src/test/resources/combination/expected-defaults.tsv");

  /** One run's answers: request key to answer, and request key to the oracle's rows. */
  private record Run(Map<String, CompatAnswer> answers, Map<String, String> oracle) {}

  @Test
  void releaseAgainstCandidate() throws IOException {
    Path out = Path.of(System.getProperty("compat.out", "target/compat"));
    Map<String, Run> runs = new LinkedHashMap<>();
    for (String run : RUNS) {
      runs.put(run, read(out.resolve(run).resolve("answers.tsv")));
    }
    List<CompatRule> rules = CompatRule.load();
    Gate gate = new Gate(rules, runs.get("R0"), runs.get("R1"), pins());
    gate.judge();

    Files.createDirectories(out);
    Files.write(out.resolve("report.tsv"), gate.tsv);
    Files.writeString(out.resolve("report.md"), new Report(out, runs, rules, gate).markdown());
    assertThat(gate.fails).as("compatibility FAILs; see " + out.resolve("report.md")).isEmpty();
  }

  // ---- the gate ---------------------------------------------------------------------------

  /** The gate's verdicts, R0 against R1. */
  private static final class Gate {

    private final List<CompatRule> rules;
    private final Run release;
    private final Run candidate;
    private final Map<String, String> pins;
    final Map<String, Integer> sameByBackend = new TreeMap<>();
    final Map<String, List<String>> named = new LinkedHashMap<>();
    final List<String> fails = new ArrayList<>();
    final Map<String, List<String>> sameButWrong = new TreeMap<>();
    final List<String> tsv = new ArrayList<>();

    Gate(List<CompatRule> rules, Run release, Run candidate, Map<String, String> pins) {
      this.rules = rules;
      this.release = release;
      this.candidate = candidate;
      this.pins = pins;
      rules.forEach(rule -> named.put(rule.id(), new ArrayList<>()));
    }

    void judge() {
      refuseAnsweredToErrorRules();
      tsv.add("backend\tshape\tposition\tkind\tentry\toperator\trequest"
          + "\tR0 status\tR0 answer\tR1 status\tR1 answer\toracle\tverdict");
      for (String key : union(release.answers(), candidate.answers())) {
        tsv.add(key + "\t" + cell(release.answers().get(key)) + "\t"
            + cell(candidate.answers().get(key)) + "\t" + oracleOf(key) + "\t" + verdict(key));
      }
      named.forEach(
          (rule, requests) -> {
            if (requests.isEmpty()) {
              fails.add("rule " + rule + " :: names no request");
            }
          });
    }

    private void refuseAnsweredToErrorRules() {
      for (CompatRule rule : rules) {
        for (String transition : rule.answeredToError()) {
          fails.add("rule " + rule.id() + " :: names " + transition
              + ": a request that answered must not answer an error after the upgrade");
        }
      }
    }

    private String verdict(String key) {
      CompatAnswer from = release.answers().get(key);
      CompatAnswer to = candidate.answers().get(key);
      if (from == null || to == null) {
        return fail(key, "asked in " + (from == null ? "R1" : "R0") + " only");
      }
      if (same(from, to)) {
        sameByBackend.merge(fields(key)[0], 1, Integer::sum);
        noteSameButWrong(key, to);
        return "SAME";
      }
      CompatRule rule = nameOf(key, from, to);
      if (rule == null) {
        return fail(key, "R0 " + show(from) + " :: R1 " + show(to) + twinNote(key));
      }
      String problem = evidenceProblem(rule, key, to);
      if (problem != null) {
        return fail(key, "R0 " + show(from) + " :: R1 " + show(to) + " :: named by " + rule.id()
            + ", but " + problem);
      }
      named.get(rule.id()).add(oneLine(key) + " :: R0 " + show(from) + " :: R1 " + show(to)
          + evidenceNote(rule, key));
      return "NAMED FIX " + rule.id();
    }

    private String fail(String key, String why) {
      fails.add(oneLine(key) + " :: " + why);
      return "FAIL";
    }

    private CompatRule nameOf(String key, CompatAnswer from, CompatAnswer to) {
      String[] f = fields(key);
      for (CompatRule rule : rules) {
        if (rule.names(f, from, to)) {
          return rule;
        }
      }
      return null;
    }

    /** Null when the rule's evidence holds for the candidate's answer; else what does not hold. */
    private String evidenceProblem(CompatRule rule, String key, CompatAnswer to) {
      String backend = fields(key)[0];
      return switch (rule.evidence()) {
        case CompatRule.NAME_TWIN -> twinProblem(key, to);
        case CompatRule.ORACLE -> oracleProblem(key, to);
        default ->
            "MONGO".equals(backend) || "JPA".equals(backend)
                ? "a named fix on " + backend + " needs evidence (name-twin or oracle)"
                : null;
      };
    }

    private String twinProblem(String key, CompatAnswer to) {
      CompatAnswer twin = release.answers().get(twinKey(key));
      if (twinKey(key).equals(key) || twin == null) {
        return "no request by the constant's name was asked";
      }
      if (twin.status() != 200) {
        return "3.5.2 answers it by the constant's name with " + show(twin);
      }
      return twin.answer().equals(to.answer())
          ? null
          : "3.5.2 answers it by the constant's name with other rows: " + show(twin);
    }

    private String oracleProblem(String key, CompatAnswer to) {
      String oracle = oracleOf(key);
      if ("-".equals(oracle)) {
        return "the oracle has no rows for it";
      }
      return oracle.equals(to.answer()) ? null : "the oracle's rows are [" + clip(oracle) + "]";
    }

    private String evidenceNote(CompatRule rule, String key) {
      return switch (rule.evidence()) {
        case CompatRule.NAME_TWIN -> " :: R0 by name " + show(release.answers().get(twinKey(key)));
        case CompatRule.ORACLE -> " :: oracle [" + clip(oracleOf(key)) + "]";
        default -> "";
      };
    }

    private String twinNote(String key) {
      CompatAnswer twin = twinKey(key).equals(key) ? null : release.answers().get(twinKey(key));
      return twin == null ? "" : " :: R0 by name " + show(twin);
    }

    private String oracleOf(String key) {
      return candidate.oracle().getOrDefault(key, "-");
    }

    /** A 200 the same in both versions whose rows are not the oracle's. */
    private void noteSameButWrong(String key, CompatAnswer to) {
      String oracle = oracleOf(key);
      if (to.status() != 200 || "-".equals(oracle) || oracle.equals(to.answer())) {
        return;
      }
      String reason = knownReason(fields(key));
      sameButWrong
          .computeIfAbsent(reason == null ? "" : reason, r -> new ArrayList<>())
          .add(oneLine(key) + " :: both [" + clip(to.answer()) + "] :: oracle [" + clip(oracle)
              + "]");
    }

    /** A documented reason the backend answers otherwise than the oracle, or null. */
    private String knownReason(String[] f) {
      if ("MONGO".equals(f[0])) {
        return mongoReason(f);
      }
      if ("JSONB".equals(f[0])) {
        String pin = pins.get(String.join("\t", f[1], f[2], f[3], f[4], f[5], f[6]));
        return pin == null ? null : "pinned in the suite: " + pin;
      }
      return null;
    }

    private static String mongoReason(String[] f) {
      if (Set.of("DATE", "DATE_TIME").contains(f[3])) {
        return "Mongo: the twin holds dates as strings (not covered)";
      }
      if ("WIRE_ENUM".equals(f[3])) {
        return "Mongo: Spring Data writes an enum by its name; the twin holds wire values";
      }
      if (Set.of("NE", "NEI", "NIN").contains(f[5])) {
        return "Mongo: ne / nin / != (README, \"What ne, nin and != mean on each backend\")";
      }
      return null;
    }
  }

  // ---- the report -------------------------------------------------------------------------

  /** report.md. */
  private record Report(Path out, Map<String, Run> runs, List<CompatRule> rules, Gate gate) {

    String markdown() throws IOException {
      StringBuilder md = new StringBuilder();
      md.append("# Compatibility report: ").append(meta("R1", "toolkit")).append(" against ")
          .append(meta("R0", "toolkit")).append("\n\n");
      notCovered(md);
      runTable(md);
      gateSummary(md);
      sameButWrong(md);
      switchPages(md);
      return md.toString();
    }

    private void notCovered(StringBuilder md) {
      md.append("## What this report does NOT cover\n\n");
      md.append("Silence is not compatibility: nothing below has a cell in this report.\n\n");
      NOT_COVERED.forEach(line -> md.append("- ").append(line).append('\n'));
    }

    private void runTable(StringBuilder md) throws IOException {
      md.append("\n## Runs\n\n| run | toolkit | Spring properties | requests | seed ms | ask ms"
          + " | wall s |\n| --- | --- | --- | --- | --- | --- | --- |\n");
      Map<String, String> wall = wallTimes();
      for (String run : runs.keySet()) {
        md.append("| ").append(run).append(" | ").append(meta(run, "toolkit")).append(" | ")
            .append(meta(run, "springProperties")).append(" | ").append(meta(run, "requests"))
            .append(" | ").append(meta(run, "seededMillis")).append(" | ")
            .append(meta(run, "askedMillis")).append(" | ").append(wall.getOrDefault(run, "?"))
            .append(" |\n");
      }
    }

    private void gateSummary(StringBuilder md) {
      md.append("\n## The gate: R0 (release) against R1 (candidate, defaults)\n\n");
      md.append("- FAIL: **").append(gate.fails.size()).append("**\n");
      md.append("- SAME, by backend: ").append(gate.sameByBackend).append('\n');
      md.append("- NAMED FIX, by rule (evidence):\n");
      for (CompatRule rule : rules) {
        md.append("  - ").append(rule.id()).append(" (").append(rule.changelog()).append("; ")
            .append(rule.evidence()).append("): ").append(gate.named.get(rule.id()).size())
            .append('\n');
      }
      if (!gate.fails.isEmpty()) {
        md.append("\n### Every FAIL\n\n");
        gate.fails.forEach(f -> md.append("- `").append(f).append("`\n"));
      }
      md.append("\n### The requests each rule named\n");
      gate.named.forEach(
          (rule, requests) -> {
            md.append("\n#### ").append(rule).append(" (").append(requests.size()).append(")\n\n");
            requests.forEach(r -> md.append("- `").append(r).append("`\n"));
          });
    }

    private void sameButWrong(StringBuilder md) {
      md.append("\n## The same rows in both versions, but not the oracle's (described, not a"
          + " gate)\n\n");
      md.append("Defects of the release, not of the candidate, unless a documented reason is"
          + " shown. The oracle is the suite's (ComboOracle) over the documents each backend holds;"
          + " it reads the JSON as JSONB stores it. The suite's pins are those of"
          + " expected-defaults.tsv as committed.\n\n");
      gate.sameButWrong.forEach(
          (reason, cells) ->
              md.append("- ").append(reason.isEmpty() ? "**no documented reason**" : reason)
                  .append(": ").append(cells.size()).append('\n'));
      gate.sameButWrong.forEach(
          (reason, cells) -> {
            md.append("\n### ").append(reason.isEmpty() ? "No documented reason" : reason)
                .append(" (").append(cells.size()).append(")\n\n");
            cells.forEach(c -> md.append("- `").append(c).append("`\n"));
          });
    }

    private void switchPages(StringBuilder md) throws IOException {
      md.append("\n## What each switch changes (descriptive, not a gate)\n");
      Map<String, CompatAnswer> defaults = runs.get("R1").answers();
      for (String run : SWITCHES) {
        Map<String, CompatAnswer> switched = runs.get(run).answers();
        List<String> changed = new ArrayList<>();
        for (String key : union(defaults, switched)) {
          CompatAnswer a = defaults.get(key);
          CompatAnswer b = switched.get(key);
          if (a == null || b == null || !same(a, b)) {
            changed.add(oneLine(key) + " :: R1 " + (a == null ? "-" : show(a)) + " :: " + run
                + " " + (b == null ? "-" : show(b)));
          }
        }
        md.append("\n### ").append(run).append(" (").append(meta(run, "springProperties"))
            .append("): ").append(changed.size()).append(" requests answer otherwise than R1\n\n");
        changed.forEach(c -> md.append("- `").append(c).append("`\n"));
      }
    }

    private String meta(String run, String key) throws IOException {
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
    private Map<String, String> wallTimes() throws IOException {
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

  // ---- helpers ----------------------------------------------------------------------------

  /** The same status; for a 200 the same rows. A 4xx or a 5xx compares by status only. */
  private static boolean same(CompatAnswer a, CompatAnswer b) {
    return a.status() == b.status() && (a.status() != 200 || a.answer().equals(b.answer()));
  }

  private static String[] fields(String key) {
    return key.split("\t", -1);
  }

  /** The key of the request with each wire value written as its constant's name. */
  private static String twinKey(String key) {
    String[] f = fields(key);
    f[6] = CompatNames.byName(f[6]);
    return String.join("\t", f);
  }

  private static TreeSet<String> union(Map<String, ?> a, Map<String, ?> b) {
    TreeSet<String> keys = new TreeSet<>(a.keySet());
    keys.addAll(b.keySet());
    return keys;
  }

  private static Run read(Path answers) throws IOException {
    Map<String, CompatAnswer> map = new TreeMap<>();
    Map<String, String> oracle = new TreeMap<>();
    if (!Files.exists(answers)) {
      return new Run(map, oracle);
    }
    List<String> lines = Files.readAllLines(answers);
    for (String line : lines.subList(1, lines.size())) {
      String[] c = line.split("\t", -1);
      String key = String.join("\t", List.of(c).subList(0, 7));
      map.put(key, new CompatAnswer(Integer.parseInt(c[7]), c.length > 8 ? c[8] : ""));
      oracle.put(key, c.length > 9 ? c[9] : "-");
    }
    return new Run(map, oracle);
  }

  /** The suite's pinned findings (defaults), by shape … request, as "verdict: reason". */
  private static Map<String, String> pins() throws IOException {
    Map<String, String> pins = new TreeMap<>();
    if (!Files.exists(SUITE_PINS)) {
      return pins;
    }
    for (String line : Files.readAllLines(SUITE_PINS)) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] c = line.split("\t", -1);
      if (c.length >= 9) {
        pins.put(String.join("\t", c[1], c[2], c[3], c[4], c[5], c[6]), c[0] + ": " + c[8]);
      }
    }
    return pins;
  }

  private static String cell(CompatAnswer a) {
    return a == null ? "-\t-" : a.status() + "\t" + a.answer();
  }

  private static String show(CompatAnswer a) {
    return a.status() + " [" + clip(a.answer()) + "]";
  }

  private static String clip(String text) {
    return text.length() > 160 ? text.substring(0, 160) + "…" : text;
  }

  private static String oneLine(String key) {
    return key.replace('\t', ' ');
  }
}
