JVMAudit __VERSION__

Find every Java installation on a machine and see which ones are Oracle-licensed.
Runs entirely offline: no network calls, no telemetry.

## Which download

- **jvmaudit.jar** - needs Java 17 or later already installed:
  `java -jar jvmaudit.jar scan`
- **jvmaudit-__VERSION__-<os>-<arch>.tar.gz / .zip** - self-contained, bundles its own runtime.
  Use this where no Java is on PATH. Unpack it and run `bin/jvmaudit scan`.

Verify your download against `SHA256SUMS.txt`.

## Licensing of these downloads

JVMAudit itself is Apache-2.0. The bundled runtime in the self-contained archives is built with
jlink from Eclipse Temurin, which is GPLv2 with the Classpath Exception and may be redistributed.
No Oracle-licensed code is shipped in any artifact here.

JVMAudit is an inventory tool, not legal advice. Licence conclusions depend on your contracts with
Oracle.
