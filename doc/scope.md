# Scope and requirements

What the extension caches, and what it needs. Back to the [README](../README.md).

## Native builds only

Only the `native` [packaging type](https://quarkus.io/guides/maven-tooling#quarkus-package-pkg-package-config_quarkus.package.type), declared as a split build, is made cacheable. JVM packaging (`jar`, `fast-jar`, `uber-jar`, `legacy-jar`) is not: the augmentation producing those is a few seconds of work whose cache key would have to span the whole compile classpath.

A `build` execution the extension does not recognize as part of a split native build is explicitly marked not cacheable, and the log says so:

```
[quarkus-build-caching-extension] Quarkus build goal marked as not cacheable, declare it as a split native build to make the native image generation cacheable
```

## In-container build strategy

By default, the native image generation is cacheable only if the in-container build strategy (`quarkus.native.container-build=true`) is configured along with a fixed build image (`quarkus.native.builder-image`).
The builder image is what pins the whole toolchain, which is why the extension keys on it and leaves the OS and JDK out of the key — making the entries shareable between a developer machine and CI.

The restriction can be lifted, at the cost of the toolchain no longer being part of the cache key. See [lifting the in-container requirement](configuration.md#lifting-the-in-container-requirement).

Pin `quarkus.native.builder-image` to explicit coordinates rather than an alias such as `mandrel`: an alias floats, and the recorded configuration would not change when it resolves elsewhere. Setting `quarkus.native.builder-image.pull=missing` also stops Quarkus re-pulling the image on every build.

> [!NOTE]
> When the in-container build strategy is used as a fallback the caching feature will be disabled. The fallback may happen due to GraalVM requirements not met. The recommendation is to explicitly set the in-container strategy (`quarkus.native.container-build=true`) to benefit from caching

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
