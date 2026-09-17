# Configuration

Every switch the extension has. Back to the [README](../README.md).

Configuration can be set as an [environment variable](#environment-variables) or as a
[Maven property](#maven-properties), the Maven property winning when both are given.

## Environment variables

### Feature toggle

The caching can be disabled by setting:
```properties
DEVELOCITY_QUARKUS_CACHE_ENABLED=false
```

### Automatic configuration

The extension registers the Quarkus goals its caching relies on, restores the artifact descriptor after a cache hit, and declares the Quarkus dependencies as inputs of the test goals — see [what the extension sets up for you](how-it-works.md#what-the-extension-sets-up-for-you). To configure everything in the pom instead:
```properties
DEVELOCITY_QUARKUS_AUTO_CONFIGURE=false
```

The test goal inputs can also be overridden per module with the `addQuarkusInputs` property on `maven-surefire-plugin` and `maven-failsafe-plugin`, which is worth turning off where the tests do not use Quarkus and the wider key would only cost re-runs:
```xml
<properties>
    <addQuarkusInputs>false</addQuarkusInputs>
</properties>
```

### Ordering the native-image configuration

Quarkus writes `META-INF/native-image/*.json` into the runner jar from unordered collections, so two builds of untouched sources produce the same registrations in a different order. Since that jar is the cache key, each ordering would be a separate cache entry.

The extension orders those files after the augmentation and before the native image generation is keyed on them:

```
[INFO] [quarkus-build-caching-extension] Ordered the native-image configuration of my-app-1.0-runner.jar: serialization-config.json, reflect-config.json, resource-config.json
```

Only `target/native-sources` is touched, which the native image generation does not consume — it re-runs the augmentation and builds its own jar — so this cannot change the executable that is produced. Ordering is sound where ignoring those files would not be: a registration added or removed still changes the key, only the order it came out in is discarded. A file that cannot be parsed is left exactly as Quarkus wrote it.

To turn it off:
```properties
DEVELOCITY_QUARKUS_NORMALIZE_NATIVE_IMAGE_CONFIG=false
```

### A project version that moves every build

A version derived from a commit id changes the cache key on every commit. See [a version that moves every build](dynamic-version.md).

```properties
DEVELOCITY_QUARKUS_VERSION_INDEPENDENT_BUILD=true
```

### Lifting the in-container requirement

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

## Maven properties

The same configuration can be achieved with Maven properties:
```xml
<properties>
    <develocity.quarkus.cache.enabled>true</develocity.quarkus.cache.enabled>
    <develocity.quarkus.auto.configure>true</develocity.quarkus.auto.configure>
    <develocity.quarkus.normalize.native.image.config>true</develocity.quarkus.normalize.native.image.config>
    <develocity.quarkus.version.independent.build>false</develocity.quarkus.version.independent.build>
    <develocity.quarkus.native.build.in.container.required>false</develocity.quarkus.native.build.in.container.required>
</properties>
```

Any of them can also be passed on the command line, which takes precedence over the same property declared in the pom:

```shell
mvn clean package -Ddevelocity.quarkus.cache.enabled=false
```

## Ignoring properties in the configuration dump

It is also possible to configure some properties to be excluded from configuration tracking (more details in the [Quarkus documentation](https://quarkus.io/guides/config-reference#filtering-configuration-options)). 
This is relevant when a property is volatile but does not impact the produced artifact, see [this section](how-it-works.md#the-quarkus-configuration-dump) for more details.

```xml
<properties>
    <quarkus.config-tracking.exclude>quarkus.container-image.tag,quarkus.application.version</quarkus.config-tracking.exclude>
</properties>
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
