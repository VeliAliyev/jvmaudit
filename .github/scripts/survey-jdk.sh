#!/usr/bin/env bash
# Records everything a real JDK installation says about itself, for the artifact survey.
#
# Writes three files per installation into survey/:
#   <id>.release   the release file, verbatim
#   <id>.version   the output of `java -version`
#   <id>.licence   which licence texts ship in the installation root, and their first lines
#
# The third file is the second candidate discriminator: an Oracle JDK ships the NFTC or OTN text
# where an Oracle OpenJDK build ships GPLv2 with the Classpath Exception.

set -euo pipefail

id="${1:?usage: survey-jdk.sh <id>}"
: "${JAVA_HOME:?JAVA_HOME is not set; the setup-java step must run first}"

mkdir -p survey

{
  echo "# id: ${id}"
  echo "# JAVA_HOME: ${JAVA_HOME}"
  echo
  if [ -f "${JAVA_HOME}/release" ]; then
    # MODULES is hundreds of names long and tells us nothing about licensing.
    grep -v '^MODULES=' "${JAVA_HOME}/release"
  else
    echo "# no release file"
  fi
} > "survey/${id}.release"

{
  echo "# id: ${id}"
  "${JAVA_HOME}/bin/java" -version 2>&1 || echo "# java -version failed"
} > "survey/${id}.version"

{
  echo "# id: ${id}"
  echo "# licence-bearing files in the installation root and under legal/:"
  # Depth 3, because Oracle's OpenJDK builds ship no LICENSE in the installation root at all -
  # theirs live at legal/<module>/LICENSE - while Oracle JDK ships one in the root. That difference
  # is itself the signal, so both depths have to be visible here.
  find "${JAVA_HOME}" -maxdepth 3 \
       \( -iname 'LICENSE*' -o -iname 'COPYRIGHT*' -o -iname 'NOTICE*' -o -iname '*license*.txt' \) \
       -type f 2>/dev/null | sort | head -40 | while read -r file; do
    relative="${file#"${JAVA_HOME}"/}"
    size=$(wc -c < "${file}" | tr -d ' ')
    echo
    echo "## ${relative} (${size} bytes)"
    # The first non-empty lines are enough to name the licence.
    grep -m 8 -v '^[[:space:]]*$' "${file}" | head -8 || true
  done
} > "survey/${id}.licence"

echo "Surveyed ${id}:"
sed -n '1,20p' "survey/${id}.release"
