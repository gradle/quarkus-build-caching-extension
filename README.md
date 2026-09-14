> _This repository is maintained by the Develocity Solutions team, as one of several publicly available repositories:_
> - _[Android Cache Fix Gradle Plugin][android-cache-fix-plugin]_
> - _[Common Custom User Data Gradle Plugin][ccud-gradle-plugin]_
> - _[Common Custom User Data Maven Extension][ccud-maven-extension]_
> - _[Common Custom User Data sbt Plugin][ccud-sbt-plugin]_
> - _[Develocity Build Configuration Samples][develocity-build-config-samples]_
> - _[Develocity Build Validation Scripts][develocity-build-validation-scripts]_
> - _[Develocity Open Source Projects][develocity-oss-projects]_
> - _[Quarkus Build Caching Extension][quarkus-build-caching-extension]  (this repository)_

# Custom Maven Extension to make the Quarkus native image generation cacheable
This Maven extension makes the expensive half of a Quarkus native build — the `native-image` run — cacheable, by keying it on the jar the augmentation produces rather than on everything that feeds the augmentation.

It requires the native build to be declared as [two `build` goal executions](#the-quarkus-maven-plugin-configuration). Nothing else is made cacheable: a project with a single `build` execution, JVM packaging included, is left alone.

This project performs programmatic configuration of the [Develocity Build Cache](https://docs.develocity.ai/maven/current/maven-extension/#using_the_build_cache) through a Maven extension. See [here](https://docs.develocity.ai/maven/current/maven-extension/#custom_extension) for more details.

*Note:*<br>
A native executable can be a very large file. Copying it from/to the local cache, or transferring it from/to the remote cache can be an expensive operation that has to be balanced with the duration of the work being avoided.

## Requirements
Quarkus 3.9.0 and above, which brings [`quarkus.native.sources-only`](https://quarkus.io/guides/native-reference#build-native-image-separately) and the [track-config-changes goal](https://quarkus.io/guides/config-reference#tracking-build-time-configuration-changes-between-builds).

> [!IMPORTANT]
> Quarkus 3.39.3 or later is strongly recommended. The cache key is the jar the augmentation produces, so it is only as stable as that jar. Before the Quarkus reproducibility work, two identical builds emitted dozens of differing generated-bytecode classes and the cache never hit. On 3.33.2, measured on a REST + Hibernate ORM Panache application, 55 of 614 jar entries differed between two identical builds.

## Limitations

### Native builds only
Only the `native` [packaging type](https://quarkus.io/guides/maven-tooling#quarkus-package-pkg-package-config_quarkus.package.type), declared as a split build, is made cacheable. JVM packaging (`jar`, `fast-jar`, `uber-jar`, `legacy-jar`) is not: the augmentation producing those is a few seconds of work whose cache key would have to span the whole compile classpath.

A `build` execution the extension does not recognize as part of a split native build is explicitly marked not cacheable, and the log says so:

```
[quarkus-build-caching-extension] Quarkus build goal marked as not cacheable, declare it as a split native build to make the native image generation cacheable
```

### Build strategy
By default, the `native` packaging is cacheable only if the in-container build strategy (`quarkus.native.container-build=true`) is configured along with a fixed build image (`quarkus.native.builder-image`).
The builder image is what pins the whole toolchain, which is why the extension keys on it and leaves the OS and JDK out of the key — making the entries shareable between a developer machine and CI.

If the build environments are strictly identical, this restriction can be removed by setting `DEVELOCITY_QUARKUS_NATIVE_BUILD_IN_CONTAINER_REQUIRED=false`, at the cost of the toolchain no longer being part of the cache key. See [configuration section](#build-strategy-1) for the warning that comes with it.

Pin `quarkus.native.builder-image` to explicit coordinates rather than an alias such as `mandrel`: an alias floats, and the recorded configuration would not change when it resolves elsewhere. Setting `quarkus.native.builder-image.pull=missing` also stops Quarkus re-pulling the image on every build.

> [!NOTE]
> When the in-container build strategy is used as a fallback the caching feature will be disabled. The fallback may happen due to GraalVM requirements not met. The recommendation is to explicitly set the in-container strategy (`quarkus.native.container-build=true`) to benefit from caching

## Usage

### Extension declaration

Reference the extension in `.mvn/extensions.xml` (this extension requires the develocity-maven-extension):

```xml
<extensions>
    <extension>
        <groupId>com.gradle</groupId>
        <artifactId>develocity-maven-extension</artifactId>
        <version>2.5.0</version>
    </extension>
    <extension>
        <groupId>com.gradle</groupId>
        <artifactId>quarkus-build-caching-extension</artifactId>
        <version>1.12</version>
    </extension>
</extensions>
```

Note on the Compatibility with The Develocity extension:

| Extension                                      | Compatible version |
|------------------------------------------------|--------------------|
| `com.gradle:develocity-maven-extension`        | 1.+                |
| `com.gradle:gradle-enterprise-maven-extension` | 0.12               |

### The `quarkus-maven-plugin` configuration

Enable [Quarkus config tracking](https://quarkus.io/guides/config-reference#dumping-build-time-configuration-options-read-during-the-build) in `pom.xml`:

```xml
<properties>
    <quarkus.config-tracking.enabled>true</quarkus.config-tracking.enabled>
</properties>
```

Add the `track-prod-config-changes` execution to the `quarkus-maven-plugin` configuration:

```xml
<plugin>
    <groupId>${quarkus.platform.group-id}</groupId>
    <artifactId>quarkus-maven-plugin</artifactId>
    <version>${quarkus.platform.version}</version>
    <extensions>true</extensions>
    <executions>
        <execution>
            <id>track-prod-config-changes</id>
            <phase>process-resources</phase>
            <goals>
                <goal>track-config-changes</goal>
            </goals>
            <configuration>
                <dumpCurrentWhenRecordedUnavailable>true</dumpCurrentWhenRecordedUnavailable>
            </configuration>
        </execution>
        <!-- Step 1: augmentation only, produces target/native-sources -->
        <execution>
            <id>quarkus-jar</id>
            <phase>package</phase>
            <goals>
                <goal>build</goal>
            </goals>
            <configuration>
                <systemProperties>
                    <quarkus.native.sources-only>true</quarkus.native.sources-only>
                </systemProperties>
            </configuration>
        </execution>
        <!-- Step 2: native image generation, cached -->
        <execution>
            <id>quarkus-native-image</id>
            <phase>package</phase>
            <goals>
                <goal>build</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

A native build does two very different things: it augments the application into a jar, then hands that jar to `native-image`. The first part takes seconds, the second one minutes. Declared as one execution, the expensive part would be keyed on the inputs of the cheap one, the compile classpath in particular. [`quarkus.native.sources-only`](https://quarkus.io/guides/native-reference#build-native-image-separately) makes Quarkus stop right after the augmentation, leaving in `target/native-sources` the runner jar, its dependencies, the `native-image` arguments and, for an in-container build, the builder image to use.

The extension recognizes this layout on its own: no configuration flag turns it on. It applies as soon as a project declares several `build` executions of which exactly one requests `native-sources` through the mojo's `systemProperties`.

The execution ids are free, the extension does not match on them.

### What this buys

The second execution does not consume the first one's jar, it re-runs the augmentation itself. What it inherits is a *cache key*: the jar and the `native-image` arguments the first execution just produced. Since the [Quarkus reproducibility work](https://github.com/quarkusio/quarkus/pull/54895) makes the augmentation output stable, two builds whose classpath differs but whose augmentation output does not now share the native image.

Measured on the [Quarkus super-heroes workshop](https://quarkus.io/quarkus-workshops/super-heroes/) `rest-villains` service (REST + Hibernate ORM Panache + PostgreSQL + OpenAPI + health), Quarkus 3.39.3, in-container build:

| change | `native-image` re-runs | build |
|---|---|---|
| nothing | no | 7s |
| application code | yes | 83s |
| a dependency's runtime module | yes | 81s |
| a dependency's deployment module, without changing what the augmentation emits | **no** | **8s** |
| a `provided` dependency added | **no** | **8s** |
| a compile dependency added or upgraded | yes | 83s |
| a Quarkus build-time property | yes | 82s |

The two bold rows are the point: a change on the build classpath that does not reach the runner jar costs 8s instead of 83s.

Splitting costs nothing measurable. On the same application the duplicated augmentation is 1.96s, and the second augmentation runs ~0.6s faster than a single one would (same JVM, already warm), for a net 1.4s on an ~81s build — inside the run-to-run spread.

### The configuration dump, which does not have to be checked in

[Config tracking](#the-quarkus-maven-plugin-configuration) must be enabled: the native image generation keys on the configuration dump, and refuses to be cached without one.

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

Native-specific properties are all there, output type included: `quarkus.package.jar.type`, `quarkus.native.debug.enabled` and `quarkus.native.compression.enabled` are recorded with the value they were given, whether or not the build step reading them runs. The one differing property is [ignored](#the-configuration-dump-which-does-not-have-to-be-checked-in) by the extension.

Two details of how the dump is keyed on:
- properties are added as individual goal inputs rather than as a file, so a cache miss names the property responsible
- `quarkus.native.graalvm-home`, `quarkus.native.java-home` and `quarkus.native.sources-only` are [ignored](#ignore-properties-in-quarkus-configuration-dump). The first two hold absolute paths, and the third differs by design between the two executions

### The augmentation is never cached

Only the native image generation is cached. The augmentation is inexpensive, a couple of seconds against the minutes `native-image` takes, while `target/native-sources` holds every runtime dependency and would make a far larger cache entry than the native executable itself.

Always executing it also has two consequences the second execution relies on: `target/quarkus-artifact.properties` stays present, which a cache hit on the augmentation would not achieve since the native image generation cannot declare that file as an output either (see [Limitations](#limitations-1)), and the configuration dump is rewritten on every build.

### Restoring the Quarkus artifact descriptor

`target/quarkus-artifact.properties` tells `@QuarkusIntegrationTest` what to launch. The augmentation writes it as `type=native-sources`, pointing at the source jar, and only the native image generation corrects it to `type=native`. That execution is the cached one, so on a cache hit it does not run and the descriptor is left describing the wrong artifact — native integration tests would then silently run against the jar, passing or failing depending on cache state.

The descriptor cannot simply be declared as an output of the native image generation. The augmentation also writes it, which makes it an [overlapping output](https://docs.develocity.ai/maven/current/maven-extension/); Develocity resolves that by refusing to store the later execution, so the goal is never cached at all. Measured on a project of this shape, three consecutive builds all re-ran `native-image` and the cache directory stayed at 20 KB — no warning in the Maven log, only on the build scan.

Rewriting the descriptor after the `package` phase is the way around it:

```xml
<plugin>
    <artifactId>maven-antrun-plugin</artifactId>
    <version>3.1.0</version>
    <executions>
        <execution>
            <id>restore-quarkus-artifact-descriptor</id>
            <phase>package</phase>
            <goals>
                <goal>run</goal>
            </goals>
            <configuration>
                <target xmlns:if="ant:if">
                    <available file="${project.build.directory}/${project.build.finalName}-runner"
                               property="quarkus.native.executable.present"/>
                    <echo if:set="quarkus.native.executable.present"
                          file="${project.build.directory}/quarkus-artifact.properties">type=native
path=${project.build.finalName}-runner
</echo>
                </target>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Declare it after the `quarkus-maven-plugin`, so it runs once both `build` executions are done. The `available` guard leaves the descriptor alone when the build produced no native executable.

Two things to know about it:
- the `metadata.graalvm.version.*` entries a real native build records are not restored. They are informational; `@QuarkusIntegrationTest` only reads `type` and `path`.
- it also stabilizes the failsafe goal's own cache key, since the extension declares this descriptor as an input to that goal when `addQuarkusInputs` is set.

> [!NOTE]
> An in-container build produces a Linux executable. Running `@QuarkusIntegrationTest` against it therefore requires a Linux host — on macOS the launcher reports `cannot execute binary file`. This is a property of the in-container strategy, not of caching.

### Limitations

- **`target/quarkus-artifact.properties` has to be restored, see [below](#restoring-the-quarkus-artifact-descriptor).** Quarkus writes this descriptor at the end of every augmentation, so both executions produce it, and it cannot be declared as an output of the native image generation.
- **`quarkus.package.output-directory` cannot be used to work around it.** Relocating the augmentation output fails in `sources-only` mode (`NoSuchFileException` on the `lib` directory), reported against Quarkus 3.39.3.
- **The same applies to any other file both executions write**: it cannot be declared as an output of the native image generation. Since the augmentation always runs, such files are produced on every build, just with the configuration of an augmentation-only build. [Extra outputs](#extra-outputs) that fall in this case are detected and left undeclared.
- **The local GraalVM version is not part of the key** for a non in-container build. `native-sources/graalvm.version` holds the version Quarkus *supports*, a hardcoded constant, not the installed one. The in-container build strategy, required by default, pins the whole toolchain through `native-sources/native-builder.image`.
- Absolute paths appearing in `native-image.args`, which `quarkus.native.agent-configuration-directory` and PGO profiles introduce, make the key machine-specific.
- **Run the two executions together.** The native image generation is keyed on whatever `target/native-sources` holds, so invoking it alone (`mvn quarkus:build@quarkus-native-image` without the augmentation, on a dirty `target`) keys it on a previous build's jar and can restore a stale executable. Always let the `package` phase run both.
- **`-Dquarkus.native.sources-only=true` on the command line applies to both executions**, which leaves the build without a native executable. The property belongs in the first execution's `systemProperties`, nowhere else.
- **The cache key is only as reproducible as the augmentation.** On Quarkus 3.39.3 two identical builds still emit `META-INF/native-image/reflect-config.json` and `resource-config.json` with a couple of entries in a different order — same entries, same contents. Each ordering is a separate cache key, so an unchanged source tree can need a few `native-image` runs before it settles; measured on the workshop application, 3 of 8 identical builds from a cold cache. Sorting those two files upstream would remove it.
- **Parallel builds.** The `build` goal applies its `systemProperties` to the JVM running Maven, so `-T` carries the race [the goal already warns about](https://github.com/quarkusio/quarkus/blob/main/devtools/maven/src/main/java/io/quarkus/maven/BuildMojo.java) across modules of a multi-module build.

## Configuration

Configuration can be set with (listed in order of precedence ):
- [Environment variables](#environment-variables)
- [Maven properties](#maven-properties)
- [Configuration file](#configuration-file)

### Environment variables

#### Feature toggle

The caching can be disabled by setting:
```properties
DEVELOCITY_QUARKUS_CACHE_ENABLED=false
```

#### Quarkus configuration dump

By default, the values below are used to compute the dump-config (`.quarkus/quarkus-prod-config-dump`) and
config-check (`target/quarkus-prod-config-check`) file names:
- _build profile_: prod
- _file prefix_: quarkus
- _file suffix_: config-dump

Those values can be overridden, when CI and local have different Quarkus properties for instance:
```properties
DEVELOCITY_QUARKUS_BUILD_PROFILE=prod
DEVELOCITY_QUARKUS_DUMP_CONFIG_PREFIX=quarkus
DEVELOCITY_QUARKUS_DUMP_CONFIG_SUFFIX=config-dump-ci
```

If the default values are overridden, the Quarkus properties need to be set accordingly:
```xml
<quarkus.config-tracking.file-suffix>-config-dump-ci</quarkus.config-tracking.file-suffix>
<quarkus.recorded-build-config.file>.quarkus/quarkus-prod-config-dump-ci</quarkus.recorded-build-config.file>
```

It is also possible to use subfolders in `.quarkus` to organize the different dump-config files. For instance, to have the dump-config at `.quarkus/ci/quarkus-prod-config-dump`:
```properties
DEVELOCITY_QUARKUS_DUMP_CONFIG_SUBFOLDER=ci
```
Quarkus configuration has to be aligned in such case to store the dump-config in the subfolder:
```xml
<quarkus.config-tracking.directory>.quarkus/ci</quarkus.config-tracking.directory>
```

#### Extra outputs

Outputs the native image generation produces beyond the executable can be declared, relative to the `target` folder.

Directories (csv list):
```properties
DEVELOCITY_QUARKUS_EXTRA_OUTPUT_DIRS=helm
```
or specific files (csv list):
```properties
DEVELOCITY_QUARKUS_EXTRA_OUTPUT_FILES=helm/kubernetes/my-project/Chart.yaml,helm/kubernetes/my-project/values.yaml
```

> [!IMPORTANT]
> Only declare files the **augmentation does not also write**. A file produced by both executions is an [overlapping output](https://docs.develocity.ai/maven/current/maven-extension/), and Develocity resolves that by refusing to store the native image generation at all — the goal then re-runs `native-image` on every build, with nothing said about it in the Maven log. The build scan is where the reason shows up, and an empty cache directory is the symptom.
>
> Check whether the augmentation already produces the path before declaring it, by running the augmentation on its own:
>
> ```shell
> mvn clean package -Dquarkus.native.sources-only=true
> ```
>
> Anything present under `target` afterwards was written by the augmentation. Note that it is regenerated on every build regardless, since the augmentation is never cached.

#### Build strategy

The default is to enable caching only when the in-container build strategy is used.
If the build environments are strictly identical build over build, the restriction can be removed by setting:
```properties
DEVELOCITY_QUARKUS_NATIVE_BUILD_IN_CONTAINER_REQUIRED=false
```

> [!WARNING]
> With the restriction lifted, the native toolchain is not part of the cache key. The OS and the JDK version are added as goal inputs, but the GraalVM or Mandrel version cannot be: `target/native-sources/graalvm.version` holds the version Quarkus *supports*, a constant, not the one installed — it reads `25.0.0` on a machine whose toolchain is Mandrel `25.0.4.1`. Upgrading GraalVM therefore does not invalidate anything, and an executable built by a different toolchain will be restored.
>
> Only use this when every environment sharing the cache runs an identical native toolchain. The extension warns on each such build:
>
> ```
> [WARNING] [quarkus-build-caching-extension] Quarkus native image is built with a local toolchain, as DEVELOCITY_QUARKUS_NATIVE_BUILD_IN_CONTAINER_REQUIRED is disabled
> [WARNING] [quarkus-build-caching-extension] The GraalVM or Mandrel version is not part of the cache key: only share these entries between environments running an identical native toolchain, otherwise an executable built by another toolchain will be restored
> ```

### Maven properties

The same configuration can be achieved with Maven properties:
```xml
<properties>
    <develocity.quarkus.cache.enabled>true</develocity.quarkus.cache.enabled>
    <develocity.quarkus.build.profile>prod</develocity.quarkus.build.profile>
    <develocity.quarkus.dump.config.prefix>quarkus</develocity.quarkus.dump.config.prefix>
    <develocity.quarkus.dump.config.suffix>config-dump-ci</develocity.quarkus.dump.config.suffix>
    <develocity.quarkus.extra.output.dirs>helm</develocity.quarkus.extra.output.dirs>
    <develocity.quarkus.extra.output.files>helm/kubernetes/${project.artifactId}/Chart.yaml,helm/kubernetes/${project.artifactId}/values.yaml</develocity.quarkus.extra.output.files>
    <develocity.quarkus.native.build.in.container.required>false</develocity.quarkus.native.build.in.container.required>
</properties>
```

Any of them can also be passed on the command line, which takes precedence over the same property declared in the pom:

```shell
mvn clean package -Ddevelocity.quarkus.cache.enabled=false
```

### Configuration file

A configuration file can be used instead by defining its location (relative to the project root folder) either:
- as an environment variable:
`DEVELOCITY_QUARKUS_CONFIG_FILE=.quarkus/develocity-ci.properties`
- as a maven property:
`<develocity.quarkus.config.file>.quarkus/extension-local.properties</develocity.quarkus.config.file>`

Its content can be created like described in the [environment variables](#environment-variables) section.

### Ignore properties in Quarkus configuration dump

It is also possible to configure some properties to be excluded from configuration tracking (more details in the [Quarkus documentation](https://quarkus.io/guides/config-reference#filtering-configuration-options)). 
This is relevant when a property is volatile but does not impact the produced artifact, see [this section](#quarkus-configuration-dump) for more details.

```xml
<properties>
    <quarkus.config-tracking.exclude>quarkus.container-image.tag,quarkus.application.version</quarkus.config-tracking.exclude>
</properties>
```

## Troubleshooting
Debug logging on the extension can be configured with the following property

```shell
mvn -Dorg.slf4j.simpleLogger.log.com.gradle=debug clean install
```

## Implementation details

The logic to make the Quarkus `build` goal cacheable is isolated to the [QuarkusBuildCache](./src/main/java/com/gradle/QuarkusBuildCache.java) class.

### Quarkus configuration dump
The Quarkus configuration dump `.quarkus/quarkus-prod-config-dump` is generated by the Quarkus `build` goal when the Maven property `quarkus.config-tracking.enabled` is `true`. It holds every Quarkus property the build resolved.

In a split build the augmentation is never cached, so it runs on every build and rewrites the dump before the native image generation is fingerprinted. The dump therefore always describes the current build and does not have to be checked in.

The extension verifies that: the dump has to record `quarkus.native.sources-only=true`, which only an augmentation-only execution writes. A dump left over from an earlier build, or checked in from elsewhere, is rejected and the native image generation is not cached.

The `track-config-changes` goal is still required, as it is what enables the dump to be written.

### Sequence of operations

Every build runs the same three steps:

1. `track-config-changes` (`process-resources`) writes `target/quarkus-prod-config-check`.
2. The **augmentation** execution runs — always, never cached — producing `target/native-sources` and rewriting `.quarkus/quarkus-prod-config-dump`.
3. The **native image generation** execution is fingerprinted against what step 2 just wrote, then either restored from the cache or executed.

The first build of a project is already cacheable: unlike a configuration that compares two dumps, nothing here depends on a previous build having run.

### Goal inputs and outputs

#### The augmentation (`native-sources`) execution
Never cacheable, hence no declared inputs or outputs. It is inexpensive compared to the size of `target/native-sources`, which holds the runner jar along with every runtime dependency and would make a far larger cache entry than the native executable itself.

#### The native image generation execution
Inputs:
- `target/native-sources/*.jar` and `target/native-sources/lib/**`, with a `CLASSPATH` normalization strategy: the jar the native image is built from
- `target/native-sources/native-image.args`, with a `RELATIVE_PATH` strategy: every argument passed to `native-image`
- `target/native-sources/native-builder.image`, with a `RELATIVE_PATH` strategy: the builder image, hence the whole toolchain, for an in-container build
- one `quarkusRecordedConfig.<property>` property per entry of the configuration dump the augmentation recorded, minus the [ignored ones](#the-configuration-dump-which-does-not-have-to-be-checked-in)
- one fileSet per Quarkus file property actually set, with a `RELATIVE_PATH` strategy, so the file content is part of the key:
  - `quarkus.docker.dockerfile-native-path`
  - `quarkus.docker.dockerfile-jvm-path`
  - `quarkus.openshift.jvm-dockerfile`
  - `quarkus.openshift.native-dockerfile`
- the mojo parameters
- OS details and the JDK version, only for a non in-container build — the builder image pins both otherwise

Notably absent: the compile classpath, the config-check file and the Quarkus dependency files. All of them only matter through their effect on the jar, the arguments and the recorded configuration, all of which are already inputs. Keeping them would widen the key back and defeat the split.

Output: `target/<project.build.finalName>-runner`, the native executable, and nothing else. Every other artifact of the build is produced by the augmentation, which always runs.

## Quarkus Test goals

When the test goals (`maven-surefire-plugin` and `maven-failsafe-plugin`) are running some `@QuarkusTest` or `@QuarkusIntegrationTest`, 
it is important for consistency to add [implicit dependencies](#quarkus-extra-dependencies) as goal [additional input](https://docs.develocity.ai/maven/current/maven-extension/#declaring_additional_inputs).

Specifically for `maven-failsafe-plugin`, the Quarkus artifact descriptor `quarkus-artifact.properties` also needs to be added. 

With Quarkus 3.9.0+, This can be achieved by declaring a property `addQuarkusInputs` on the test goal:

```xml
<plugins>
    <plugin>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
            <properties>
                <addQuarkusInputs>true</addQuarkusInputs>>
            </properties>
        </configuration>
    </plugin>
    <plugin>
        <artifactId>maven-failsafe-plugin</artifactId>
        <configuration>
            <properties>
                <addQuarkusInputs>true</addQuarkusInputs>>
            </properties>
        </configuration>
    </plugin>
</plugins>
```

Prior to Quarkus 3.9.0:
```xml
<plugins>
    <plugin>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
            <properties>
                <addQuarkusPackageInputs>true</addQuarkusPackageInputs>>
            </properties>
        </configuration>
    </plugin>
    <plugin>
        <artifactId>maven-failsafe-plugin</artifactId>
        <configuration>
            <properties>
                <addQuarkusPackageInputs>true</addQuarkusPackageInputs>>
            </properties>
        </configuration>
    </plugin>
</plugins>
```

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
