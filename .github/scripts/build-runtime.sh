#!/usr/bin/env bash
# Builds the self-contained JVMAudit distribution for the OS this runs on.
#
# The result is an archive that works on a host with no Java installed at all: a jlink runtime, the
# fat jar, and a launcher. That matters because the machines most likely to be hiding a surprise
# Oracle JDK are also the ones where nobody wants to install another JDK just to audit them.
#
# Usage: build-runtime.sh <version> <os-label> <arch-label>

set -euo pipefail

version="${1:?usage: build-runtime.sh <version> <os> <arch>}"
os="${2:?}"
arch="${3:?}"

jar="cli/target/jvmaudit.jar"
[ -f "$jar" ] || { echo "build-runtime: $jar is missing; run mvn package first" >&2; exit 1; }

name="jvmaudit-${version}-${os}-${arch}"
staging="dist/${name}"

rm -rf "$staging"
mkdir -p "$staging/bin" "$staging/lib"

# Ask jdeps what the jar actually needs rather than hardcoding a module list that silently rots as
# dependencies change. --ignore-missing-deps keeps optional references (Jackson touches java.sql and
# java.desktop for types JVMAudit never uses) from failing the build.
modules=$(jdeps --print-module-deps --ignore-missing-deps --multi-release 21 "$jar")
echo "build-runtime: jlink modules = ${modules}"

jlink \
  --add-modules "${modules}" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress=zip-6 \
  --output "$staging/runtime"

cp "$jar" "$staging/lib/jvmaudit.jar"
cp packaging/jvmaudit "$staging/bin/jvmaudit"
cp packaging/jvmaudit.bat "$staging/bin/jvmaudit.bat"
chmod +x "$staging/bin/jvmaudit"
cp LICENSE README.md "$staging/"

mkdir -p dist
if [ "$os" = "windows" ]; then
  archive="dist/${name}.zip"
  rm -f "$archive"
  # The JDK's own jar tool writes zip archives and is guaranteed to be here, since jlink and jdeps
  # just ran. Preferred over 7z or Compress-Archive, neither of which is guaranteed anywhere.
  jar --create --no-manifest --file "$archive" -C dist "${name}"
else
  archive="dist/${name}.tar.gz"
  rm -f "$archive"
  tar -czf "$archive" -C dist "${name}"
fi

echo "build-runtime: wrote ${archive} ($(du -h "${archive}" | cut -f1))"
echo "ARCHIVE=${archive}" >> "${GITHUB_ENV:-/dev/null}"
echo "DIST_NAME=${name}" >> "${GITHUB_ENV:-/dev/null}"
