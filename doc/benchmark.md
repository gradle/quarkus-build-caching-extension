# Benchmark

Every application of the [Quarkus super-heroes workshop](https://quarkus.io/quarkus-workshops/super-heroes/) built natively, with one `build` execution and with two. Back to the [README](../README.md).

Quarkus 3.39.3, in-container builds on `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25`, local build cache, `mvn clean package -DskipTests`. Apple M-series, Docker given 8 CPUs. Each figure is one `clean package` of that application.

## Overall build time

| app | one execution | split, miss | split, hit |
|---|---|---|---|
| `event-statistics` | 102s | 83s | **8s** |
| `rest-fights` | 95s | 86s | **8s** |
| `rest-heroes` | 77s | 79s | **8s** |
| `rest-narration` | 72s | 66s | **8s** |
| `rest-villains` | 73s | 80s | **8s** |
| `ui-super-heroes` | 88s | 65s | **12s** |
| **whole repository** | **507s** | **459s** | **52s** |

The middle column is the honest comparison for a cold cache: splitting is not slower. Across the repository it came out 48s faster, which is noise from `native-image` itself rather than a real gain — individual applications move both ways, `rest-villains` 7s slower and `ui-super-heroes` 23s faster.

The right column is the point. **Rebuilding the whole repository with nothing changed goes from 507s to 52s**, because no application re-runs `native-image`.

## Where the time goes

`native-image` runs inside the augmentation, so the augmentation figure carries almost the whole build:

| | one execution | split, miss | split, hit |
|---|---|---|---|
| augmentations per build | 1 | 2 | 1 |
| augmentation time | 55–75s | 57–76s | **1.9–6.8s** |

On a cache miss the split augments twice, and the extra pass is the only work it adds: 1.9s to 2.1s for five of the six applications, 6.8s for `ui-super-heroes`. Against a 65–86s build that is inside the run-to-run spread, which is why the totals come out even.

On a cache hit only the first augmentation runs. That is the whole 8s: the augmentation, plus restoring an executable of 40–90MB.

## Where to apply it

**CI, on the main branch: yes, and this is where it pays.** The main branch builds the same commit every other pipeline touches, and it is what populates the cache for everyone else. A remote cache turns the 507s above into 52s for any build whose applications have not changed, and into a per-application saving when only some have.

**Locally: yes, with a remote cache.** A developer building natively pays 70–100s per application. Reading from the same remote cache CI populates makes that 8s for everything they have not touched. Without a remote cache the local cache only helps on a rebuild of something already built on that machine, which is still the common case when iterating.

**Where it does not pay:** an application whose runner jar changes on every build. A project version derived from a commit id does exactly that — see [a version that moves every build](dynamic-version.md). Check that before concluding the cache does not work.

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
