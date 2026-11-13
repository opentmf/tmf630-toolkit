#!/usr/bin/env bash
# The compatibility report (TOOLKIT-COMPATIBILITY-REPORT-AGAINST-RELEASE-1).
#
# Records what the released toolkit (R0) and the candidate (R1 defaults, R2-R5 opt-ins) answer to
# the combination suite's requests and the ledger's samples, one Maven invocation per run, then
# compares them. HEAVY: each run starts PostgreSQL and Mongo containers.
#
#   JAVA_HOME=/usr/lib64/openjdk-17 tmf630-toolkit-compat/run.sh
#
# COMPAT_RELEASE    the released version to compare against (default 3.5.2)
# COMPAT_CANDIDATE  an exact candidate version, e.g. a Nexus timestamped snapshot; when unset, the
#                   working tree is installed into the local repository (no tests) and used
#
# Output: target/compat/report.md (the gate's verdicts, every FAIL, the switch pages) and
# report.tsv, the answers of each run, its log and the wall time of each Maven invocation.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="$ROOT/target/compat"
RELEASE="${COMPAT_RELEASE:-3.5.2}"

root_version="$(mvn -B -q help:evaluate -Dexpression=project.version -DforceStdout)"
compat_parent="$(sed -n '/<parent>/,/<\/parent>/s:.*<version>\(.*\)</version>.*:\1:p' tmf630-toolkit-compat/pom.xml)"
if [ "$root_version" != "$compat_parent" ]; then
  echo "tmf630-toolkit-compat/pom.xml names parent $compat_parent, the root is $root_version:" \
    "update the compat module's parent version (the release plugin does not see this module)." >&2
  exit 2
fi

if [ -n "${COMPAT_CANDIDATE:-}" ]; then
  CANDIDATE="$COMPAT_CANDIDATE"
else
  CANDIDATE="$root_version"
  echo "installing the candidate $CANDIDATE into the local repository (no tests)"
  mvn -B -q install -DskipTests -DskipITs -Djacoco.skip=true
fi

rm -rf "$OUT"
mkdir -p "$OUT"

run() {
  local id="$1" version="$2" spring="$3"
  local start end
  start="$(date -u +%s)"
  echo "$id: toolkit $version ${spring:+($spring)}"
  mvn -B -Pcompat -pl tmf630-toolkit-compat clean verify \
    -Dcompat.toolkit.version="$version" \
    -Dcompat.run="$id" \
    -Dcompat.out="$OUT" \
    -Dcompat.springProperties="$spring" \
    -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
    > "$OUT/$id.log" 2>&1 || { echo "$id failed; see $OUT/$id.log" >&2; exit 1; }
  end="$(date -u +%s)"
  printf '%s\t%s\t%s\t%s\n' "$id" "$version" "$start" "$end" >> "$OUT/timings.tsv"
}

run R0 "$RELEASE" ""
run R1 "$CANDIDATE" ""
run R2 "$CANDIDATE" "opentmf.tmf630.jsonb.equality=containment"
run R3 "$CANDIDATE" "opentmf.tmf630.jsonb.cast-mode=lenient"
run R4 "$CANDIDATE" "opentmf.tmf630.jsonb.equality=containment,opentmf.tmf630.jsonb.cast-mode=lenient"
run R5 "$CANDIDATE" "opentmf.tmf630.jsonb.apply-regex-max-length=true"

echo "comparing"
status=0
mvn -B -Pcompat -pl tmf630-toolkit-compat test \
  -Dcompat.compare=true -Dcompat.out="$OUT" -Dtest=CompatComparatorTest \
  > "$OUT/compare.log" 2>&1 || status=$?
echo "report: $OUT/report.md"
exit "$status"
