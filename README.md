# Readable Call Graph — Maven + Java 17

IntelliJ IDEA plugin showing project Java classes with their methods and arrows from the
method owner to the calling class. Designed for spacious, readable diagrams (focus mode,
orthogonal routing, SVG export, zoom/pan, click-to-code).

## Build in IntelliJ IDEA (no Gradle needed)

1. Open `pom.xml` as a Maven project (`File > Open`).
2. Set project SDK and Maven runner JDK to **JDK 17**.
3. Open **Maven** tool window > **Lifecycle** > **package**.
4. Install **`target/ReadableCallGraph-0.2.0.zip`** from
   `Settings > Plugins > gear > Install Plugin from Disk`.
5. Restart IDE; open `View > Tool Windows > Call Graph`.

Or, with Maven installed in PATH, run `mvn clean package` (Windows: `build.cmd`).

**One-time download:** Maven downloads the IDEA Community 2024.1 SDK ZIP (~600 MB)
from the public JetBrains repository and caches it in `~/.m2/repository`. This is
necessary to compile against the IntelliJ Platform API. Subsequent builds can reuse
it. Java 17 is used for compilation and bytecode. No Gradle or Gradle Wrapper.

The SDK version is 2024.1 because IntelliJ Platform 2024.2+ is built with Java 21.
The plugin descriptor declares since-build 241 (2024.1). Later IDEs may load the
plugin, but they have not been tested for binary/API compatibility.

**Why Ant inside Maven?** The JetBrains SDK is published as an IDEA ZIP containing many
JAR files (including Java PSI APIs), not as one classpath JAR. The Maven Antrun plugin
invokes `javac --release 17` with all SDK library JARs. Maven still runs the entire
build, tests, packaging, and produces an installable ZIP using `mvn package`.

## Run UI

Choose `View > Tool Windows > Call Graph`. Press **Обновить** to scan project source,
select a class and a direction, and inspect the outgoing arrows. The distance slider
controls spacing. Press **Экспорт SVG** to save a vector diagram.

## Layout geometry smoke test

Executed automatically by `mvn package` (a standalone Java geometry test; requires no
running IntelliJ instance). SVG and PNG previews appear in `target/preview/`.

## Important limitations

- Project Java sources are scanned; not the entire external library universe.
- PSI analysis is not a whole-program runtime/dynamic dispatch call graph.
- Uncrossed edges are optimized; a general graph cannot always be routed without crossings.
- A compile in the actual IDEA SDK and live plugin installation has not been verified here.
- JetBrains officially recommends Gradle for plugin development; this Maven build is a
  custom alternative, not an officially supported Maven plugin toolchain.
