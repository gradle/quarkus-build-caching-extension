# Troubleshooting

Back to the [README](../README.md).

Debug logging on the extension can be configured with the following property:

```shell
mvn -Dorg.slf4j.simpleLogger.log.com.gradle=debug clean package
```

The lines to look for, one per `build` goal execution:

| log line | meaning |
|---|---|
| `Quarkus build goal execution '<id>' classified as NATIVE_SOURCES` | recognized as the augmentation, never cached |
| `Quarkus build goal execution '<id>' classified as NATIVE_IMAGE` | recognized as the native image generation, the cached one |
| `Quarkus build goal execution '<id>' classified as UNSUPPORTED` | not a split native build, see [scope](scope.md#native-builds-only) |
| `Quarkus native-image build goal marked as cacheable` | the goal is cacheable this build |
| `Quarkus native-image build goal marked as not cacheable` | followed by the reason, see below |

Reasons the native image generation declines to be cached:

| message | cause |
|---|---|
| `target/native-sources/native-image.args not found, is the native build enabled?` | the augmentation produced no native sources — the build is not native, or the execution was run on its own |
| `Quarkus configuration dump not found, is quarkus.config-tracking.enabled set to true?` | config tracking is off |
| `Quarkus configuration dump was not recorded by the native-sources build goal` | the dump on disk is checked in or left over from an earlier build |
| `Quarkus build strategy is not in-container` | see [the in-container build strategy](scope.md#in-container-build-strategy) |

If the goal is cacheable but never hits, and the cache directory stays empty, the goal is being refused a store. The cause is an [overlapping output](https://docs.develocity.ai/maven/current/maven-extension/): a declared output that an earlier goal execution also writes. The Maven log says nothing about it — the build scan does.

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
