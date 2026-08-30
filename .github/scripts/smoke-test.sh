#!/usr/bin/env bash
# Smoke-tests a built JVMAudit artifact by running it as a user would.
#
# This is the M5 gate: every artifact the release workflow produces is unpacked and exercised on its
# own operating system before it is attached to a release. A jar that builds but will not run, or a
# jlink runtime missing a module, is exactly the kind of thing only running it catches.
#
# Usage: smoke-test.sh <command...>
#   smoke-test.sh dist/jvmaudit-0.1.0-linux-x64/bin/jvmaudit
#   smoke-test.sh java -jar cli/target/jvmaudit.jar

set -uo pipefail

[ "$#" -ge 1 ] || { echo "usage: smoke-test.sh <command...>" >&2; exit 2; }

work=$(mktemp -d 2>/dev/null || mktemp -d -t jvmaudit)
trap 'rm -rf "$work"' EXIT

failures=0
check() {
  local what="$1"
  shift
  if "$@"; then
    echo "  ok   ${what}"
  else
    echo "  FAIL ${what}" >&2
    failures=$((failures + 1))
  fi
}

run() { "$@" >"$work/out.txt" 2>"$work/err.txt"; }

# ---------------------------------------------------------------- a fixture estate
# A Temurin that is plainly free, and an Oracle JDK 17.0.13 that is plainly not. The Oracle one
# carries the static discriminator - a SOURCE with an "open:git:" component and OTN licence text -
# so this also proves the packaged artifact classifies Oracle builds without executing them.
estate="$work/estate"
mkdir -p "$estate/temurin-21/bin" "$estate/oracle-jdk-17/bin" "$estate/oracle-jdk-17/legal/java.base"

cat > "$estate/temurin-21/release" <<'EOF'
IMPLEMENTOR="Eclipse Adoptium"
IMPLEMENTOR_VERSION="Temurin-21.0.4+7"
JAVA_VERSION="21.0.4"
JAVA_VERSION_DATE="2024-07-16"
EOF
printf '#!/bin/sh\nexit 0\n' > "$estate/temurin-21/bin/java"

cat > "$estate/oracle-jdk-17/release" <<'EOF'
IMPLEMENTOR="Oracle Corporation"
JAVA_VERSION="17.0.13"
JAVA_VERSION_DATE="2024-10-15"
JAVA_RUNTIME_VERSION="17.0.13+10-LTS-58"
SOURCE=".:git:0531bcd287a8 open:git:38d1cef19db8"
EOF
printf '#!/bin/sh\nexit 0\n' > "$estate/oracle-jdk-17/bin/java"
cat > "$estate/oracle-jdk-17/legal/java.base/LICENSE" <<'EOF'
Oracle Technology Network License Agreement for Oracle Java SE

Your use of the software is governed by the terms below.
EOF

echo "Smoke-testing: $*"

# ---------------------------------------------------------------- the checks

check "--version reports a version" bash -c '
  "$@" --version > "'"$work"'/v.txt" 2>&1 && grep -q "jvmaudit " "'"$work"'/v.txt"' _ "$@"

check "--version names the licence rules" bash -c '
  grep -q "licence rules" "'"$work"'/v.txt"' _

check "--help lists every subcommand" bash -c '
  "$@" --help > "'"$work"'/h.txt" 2>&1 &&
  grep -q "scan" "'"$work"'/h.txt" &&
  grep -q "diff" "'"$work"'/h.txt" &&
  grep -q "rules" "'"$work"'/h.txt"' _ "$@"

check "rules prints citations" bash -c '
  "$@" rules > "'"$work"'/r.txt" 2>&1 &&
  grep -q "https://www.oracle.com/java/technologies/javase/jdk-faqs.html" "'"$work"'/r.txt"' _ "$@"

check "scan of the fixture estate finds both installations" bash -c '
  "$@" scan --paths "'"$estate"'" > "'"$work"'/s.txt" 2>&1 &&
  grep -q "2 JVMs found" "'"$work"'/s.txt"' _ "$@"

check "scan identifies Temurin as free" bash -c '
  grep -q "Eclipse Temurin" "'"$work"'/s.txt"' _

check "scan identifies the Oracle JDK without executing it" bash -c '
  grep -q "Oracle JDK" "'"$work"'/s.txt" &&
  grep -q "ORACLE PAID LIKELY" "'"$work"'/s.txt"' _

check "json output is well formed" bash -c '
  "$@" scan --paths "'"$estate"'" --format json --out "'"$work"'/scan.json" >/dev/null 2>&1 &&
  grep -q "\"schemaVersion\"" "'"$work"'/scan.json" &&
  grep -q "\"citations\"" "'"$work"'/scan.json"' _ "$@"

check "csv output has a header and two rows" bash -c '
  "$@" scan --paths "'"$estate"'" --format csv > "'"$work"'/scan.csv" 2>/dev/null &&
  [ "$(grep -c . "'"$work"'/scan.csv")" -eq 3 ]' _ "$@"

check "html output is self-contained" bash -c '
  "$@" scan --paths "'"$estate"'" --format html --out "'"$work"'/report.html" >/dev/null 2>&1 &&
  grep -q "<!DOCTYPE html>" "'"$work"'/report.html" &&
  ! grep -q "<script" "'"$work"'/report.html"' _ "$@"

check "diff of a scan against itself reports no change" bash -c '
  "$@" diff "'"$work"'/scan.json" "'"$work"'/scan.json" > "'"$work"'/d.txt" 2>&1 &&
  grep -q "No change" "'"$work"'/d.txt"' _ "$@"

check "evidence pack is written" bash -c '
  "$@" scan --paths "'"$estate"'" --evidence "'"$work"'/evidence.zip" >/dev/null 2>&1 &&
  [ -s "'"$work"'/evidence.zip" ]' _ "$@"

# ---------------------------------------------------------------- exit codes
"$@" scan --paths "$estate" --format csv --fail-on none >/dev/null 2>&1
code=$?
check "--fail-on none exits 0 (got $code)" test "$code" -eq 0

"$@" scan --paths "$estate" --format csv --fail-on oracle-paid >/dev/null 2>&1
code=$?
check "--fail-on oracle-paid exits 1 (got $code)" test "$code" -eq 1

"$@" scan --paths "$estate/temurin-21" --format csv --fail-on oracle-any >/dev/null 2>&1
code=$?
check "--fail-on oracle-any exits 0 on a clean estate (got $code)" test "$code" -eq 0

echo
if [ "$failures" -eq 0 ]; then
  echo "Smoke test passed."
else
  echo "Smoke test FAILED: ${failures} check(s)." >&2
fi
exit "$failures"
