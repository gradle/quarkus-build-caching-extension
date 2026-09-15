# How it works

The two goals, what the cached one is keyed on, and what the split changes. Back to the [README](../README.md).

The logic is isolated to the [QuarkusBuildCache](../src/main/java/com/gradle/QuarkusBuildCache.java) class, and the classification of a `build` execution to [QuarkusBuildGoalMode](../src/main/java/com/gradle/QuarkusBuildGoalMode.java).

## What the extension sets up for you

Three things the caching relies on are mechanical consequences of wanting it, so the extension takes care of them rather than making every project repeat them. All of them only ever apply to a project declaring the split layout above.

**Quarkus config tracking.** The native image generation is keyed on the configuration the augmentation recorded, which Quarkus only writes when `quarkus.config-tracking.enabled` is set and the `track-config-changes` goal is bound. The extension sets the property and registers the goal on the project's own `quarkus-maven-plugin` — so it inherits the version already declared:

```
[INFO] [quarkus-build-caching-extension] Enabled quarkus.config-tracking.enabled on rest-villains
[INFO] [quarkus-build-caching-extension] Registered the track-config-changes goal on rest-villains
```

Declare either yourself and the extension leaves it alone, including when you declare `quarkus.config-tracking.enabled=false`.

**The Quarkus artifact descriptor.** `target/quarkus-artifact.properties` tells `@QuarkusIntegrationTest` what to launch. The augmentation writes it as `type=native-sources`, pointing at the source jar, and only the native image generation corrects it — but that is the cached execution, so on a cache hit it does not run and native integration tests would launch the jar instead of the executable. It cannot be a declared output of that execution either: the augmentation writes it too, which makes it an [overlapping output](https://docs.develocity.ai/maven/current/maven-extension/), and Develocity resolves that by refusing to store the goal at all.

The extension rewrites it after the native image generation instead:

```
[INFO] [quarkus-build-caching-extension] Restored quarkus-artifact.properties, which the augmentation left describing the native sources
```

A descriptor that already describes the executable is left untouched, so the `metadata.graalvm.version.*` entries a real native build records survive a cache miss. They are not reconstructed on a cache hit; `@QuarkusIntegrationTest` reads only `type` and `path`.

**The test goal inputs.** Quarkus adds dependencies to the build dynamically, so they sit on no classpath Develocity would fingerprint on its own, yet a `@QuarkusTest` runs against them. They are listed in `target/quarkus-prod-dependencies.txt` by the `track-config-changes` goal, and declared as an input of the surefire and failsafe goals with a `CLASSPATH` normalization strategy; failsafe also gets `quarkus-artifact.properties`. See [automatic configuration](configuration.md#automatic-configuration) for the per-module override.

Set `DEVELOCITY_QUARKUS_AUTO_CONFIGURE=false` to turn all three off and configure everything in the pom yourself. Note that an injected execution does not show up in `mvn help:effective-pom`, which is why the extension logs what it registers.

> [!NOTE]
> An in-container build produces a Linux executable. Running `@QuarkusIntegrationTest` against it therefore requires a Linux host — on macOS the launcher reports `cannot execute binary file`. This is a property of the in-container strategy, not of caching.

## The two goals

```
process-resources
 └── quarkus:track-config-changes  (track-prod-config-changes)      not cacheable
       └── writes target/quarkus-prod-config-check

package
 ├── quarkus:build  (quarkus-jar)                          NOT CACHEABLE by design
 │     quarkus.native.sources-only=true                    always runs
 │     └── writes target/native-sources/  *.jar, lib/**,
 │                                        native-image.args,
 │                                        native-builder.image
 │         writes .quarkus/quarkus-prod-config-dump
 │         writes target/quarkus-artifact.properties   type=native-sources
 │
 └── quarkus:build  (quarkus-native-image)                        CACHEABLE
       inputs  ◄── target/native-sources/**  +  the recorded configuration
       │
       ├── cache miss → runs native-image, then UPX, then rewrites
       │                target/quarkus-artifact.properties   type=native
       └── cache hit  → restores target/<finalName>-runner, runs nothing
```

The augmentation is never cached. It is inexpensive — 1.96s against the minutes `native-image` takes — while `target/native-sources` holds the runner jar along with every runtime dependency and would make a far larger cache entry than the executable itself. Always running it is also what keeps the configuration dump describing the current build.

The first build of a project is already cacheable: nothing here depends on a previous build having run.

## The cacheable goal

### Inputs

- `target/native-sources/*.jar` and `target/native-sources/lib/**`, with a `CLASSPATH` normalization strategy: the jar the native image is built from
- `target/native-sources/native-image.args`, with a `RELATIVE_PATH` strategy: every argument passed to `native-image`
- `target/native-sources/native-builder.image`, with a `RELATIVE_PATH` strategy: the builder image, hence the whole toolchain, for an in-container build
- one `quarkusRecordedConfig.<property>` property per entry of the configuration dump the augmentation recorded, minus the [ignored ones](#the-quarkus-configuration-dump)
- one fileSet per Quarkus file property actually set, with a `RELATIVE_PATH` strategy, so the file content is part of the key:
  - `quarkus.docker.dockerfile-native-path`
  - `quarkus.docker.dockerfile-jvm-path`
  - `quarkus.openshift.jvm-dockerfile`
  - `quarkus.openshift.native-dockerfile`
- the mojo parameters
- OS details and the JDK version, only for a non in-container build — the builder image pins both otherwise

Notably absent: the compile classpath, the config-check file and the Quarkus dependency files. All of them only matter through their effect on the jar, the arguments and the recorded configuration, all of which are already inputs. Keeping them would widen the key back and defeat the split.

### Outputs

`target/<project.build.finalName>-runner`, the native executable, and nothing else. Every other artifact of the build is produced by the augmentation, which always runs.


## The Quarkus configuration dump

[Config tracking](#what-the-extension-sets-up-for-you) must be enabled: the native image generation keys on the configuration dump, and refuses to be cached without one.

Not every Quarkus property reaches `native-image.args`. The build steps running *after* `native-image` are the reason: `quarkus.native.compression.*` drives the UPX compression of the produced executable, is recorded in the dump, and appears in neither the jar nor the arguments. Keying on the jar and the arguments alone would hand back an executable compressed with the wrong settings.

The dump does not have to be checked in or restored. The augmentation runs first and is never cached, so by the time the second execution's key is computed the dump on disk describes **this** build. Nothing is compared against a previous build, which also means a Quarkus configuration change costs exactly one rebuild rather than leaving the next build uncacheable too.

The extension verifies that: the dump has to record `quarkus.native.sources-only=true`, which only an augmentation-only execution writes. A dump left over from an earlier build, or checked in from a single-execution setup, is rejected and the native image generation is not cached.

Recording only the augmentation loses nothing, which is worth spelling out since the dump is not a snapshot of the whole configuration. `ConfigTrackingInterceptor` collects each option *as it is read*, and `ConfigTrackingWriter` keeps those that are build-time or build-time-fixed and not excluded. What makes the two modes agree is that the build-time config roots are all mapped when the augmentation starts, not lazily by the build step that needs them, so the set of options read does not depend on which build steps run.

Measured on the `rest-villains` service of the Quarkus super-heroes workshop, comparing a single `build` execution run with and without `quarkus.native.sources-only`:

| | sources-only | native |
|---|---|---|
| `native-image` ran | no | yes |
| properties recorded | 249 | 249 |
| keys present in one dump only | none | none |
| values differing | `quarkus.native.sources-only` only | |

Native-specific properties are all there, output type included: `quarkus.package.jar.type`, `quarkus.native.debug.enabled` and `quarkus.native.compression.enabled` are recorded with the value they were given, whether or not the build step reading them runs. The one differing property is ignored by the extension, along with the two below.

Two details of how the dump is keyed on:
- properties are added as individual goal inputs rather than as a file, so a cache miss names the property responsible
- `quarkus.native.graalvm-home`, `quarkus.native.java-home` and `quarkus.native.sources-only` are [ignored](configuration.md#ignoring-properties-in-the-configuration-dump). The first two hold absolute paths, and the third differs by design between the two executions

## The impact of a split build

The augmentation runs twice: once with `quarkus.native.sources-only=true` to produce the cache key, once as part of the native image generation. Everything in between — code generation, bytecode generation, reflection registration, Kubernetes manifests, Helm charts — is done identically both times.

In Quarkus terms, only three build steps differ between the two modes ([`NativeBuild`](https://github.com/quarkusio/quarkus/blob/main/core/deployment/src/main/java/io/quarkus/deployment/pkg/steps/NativeBuild.java) vs [`NativeSourcesBuild`](https://github.com/quarkusio/quarkus/blob/main/core/deployment/src/main/java/io/quarkus/deployment/pkg/steps/NativeSourcesBuild.java)):

| build step | runs in |
|---|---|
| `NativeImageBuildStep.result` — produces the `native` artifact result, with the GraalVM version | the native image generation only |
| `NativeImageBuildStep.resolveNativeImageBuildRunner` — the real local or container runner | the native image generation only |
| `UpxCompressionBuildStep` — UPX compression | the native image generation only |
| `NativeImageBuildStep.nativeSourcesResult` — writes `target/native-sources`, produces the `native-sources` artifact result | the augmentation only |
| `NativeImageBuildStep.dummyNativeImageBuildRunner` — a no-op runner, so native sources need no `native-image` or container runtime | the augmentation only |

Everything else, including every build step that reads configuration, runs in both. That is what makes the configuration dump recorded by the augmentation a faithful record of the build, and it is why the cache key can be trusted.

Two consequences are worth knowing before adopting:

**`target` differs depending on whether the native image was rebuilt or restored.** On a cache hit the native image generation does not run, so whatever it would have written is missing:

| | after a cache miss | after a cache hit |
|---|---|---|
| `target/<finalName>-runner` | built | restored from the cache |
| `target/quarkus-artifact.properties` | `type=native` | `type=native-sources` unless [restored](#what-the-extension-sets-up-for-you) |
| `metadata.graalvm.version.*` in that descriptor | present | absent |
| `target/<finalName>-native-image-source-jar/` | present | absent — the augmentation deletes it, and only the native image generation recreates it |

Nothing in Quarkus core consumes that directory, and the descriptor can be [put back](#what-the-extension-sets-up-for-you). Anything of your own that reads them should not assume a cache miss.

**Only the executable is a cached output.** Everything else the build produces is written by the augmentation, which always runs, so it is there on every build regardless. It could not be cached anyway: a file both executions write is an overlapping output, and declaring one stops the native image generation from being stored at all.

## Limitations

- **`target/quarkus-artifact.properties` cannot be a declared output**, since the augmentation writes it too. The extension [restores it](#what-the-extension-sets-up-for-you) after the native image generation instead, but the `metadata.graalvm.version.*` entries are not reconstructed on a cache hit.
- **`quarkus.package.output-directory` cannot be used to work around it.** Relocating the augmentation output fails in `sources-only` mode (`NoSuchFileException` on the `lib` directory), reported against Quarkus 3.39.3.
- **Only the executable is a cached output.** Everything else under `target` is written by the augmentation, which always runs, so it is present on every build whether the native image was rebuilt or restored. Declaring any of it as an output of the native image generation would be an [overlapping output](https://docs.develocity.ai/maven/current/maven-extension/) and would stop the goal being stored at all — which is why Kubernetes manifests and Helm charts, both written by the augmentation, are deliberately left alone.
- **The local GraalVM version is not part of the key** for a non in-container build. `native-sources/graalvm.version` holds the version Quarkus *supports*, a hardcoded constant, not the installed one. The in-container build strategy, required by default, pins the whole toolchain through `native-sources/native-builder.image`.
- Absolute paths appearing in `native-image.args`, which `quarkus.native.agent-configuration-directory` and PGO profiles introduce, make the key machine-specific.
- **Run the two executions together.** The native image generation is keyed on whatever `target/native-sources` holds, so invoking it alone (`mvn quarkus:build@quarkus-native-image` without the augmentation, on a dirty `target`) keys it on a previous build's jar and can restore a stale executable. Always let the `package` phase run both.
- **`-Dquarkus.native.sources-only=true` on the command line applies to both executions**, which leaves the build without a native executable. The property belongs in the first execution's `systemProperties`, nowhere else.
- **The native executable itself is not reproducible.** [Ordering the generated configuration](configuration.md#ordering-the-native-image-configuration) stabilizes the cache key, not what Quarkus and `native-image` produce: two builds of the same sources still yield executables that differ in size. A cache hit therefore hands back one of several equivalent builds, which is what any cache does, but it is worth knowing before comparing checksums across machines.
- **Parallel builds.** The `build` goal applies its `systemProperties` to the JVM running Maven, so `-T` carries the race [the goal already warns about](https://github.com/quarkusio/quarkus/blob/main/devtools/maven/src/main/java/io/quarkus/maven/BuildMojo.java) across modules of a multi-module build.

[android-cache-fix-plugin]: https://github.com/gradle/android-cache-fix-gradle-plugin
[ccud-gradle-plugin]: https://github.com/gradle/common-custom-user-data-gradle-plugin
[ccud-maven-extension]: https://github.com/gradle/common-custom-user-data-maven-extension
[ccud-sbt-plugin]: https://github.com/gradle/common-custom-user-data-sbt-plugin
[develocity-build-config-samples]: https://github.com/gradle/develocity-build-config-samples
[develocity-build-validation-scripts]: https://github.com/gradle/develocity-build-validation-scripts
[develocity-oss-projects]: https://github.com/gradle/develocity-oss-projects
[quarkus-build-caching-extension]: https://github.com/gradle/quarkus-build-caching-extension
[develocity]: https://develocity.ai
[apache-license]: https://www.apache.org/licenses/LICENSE-2.0.html
