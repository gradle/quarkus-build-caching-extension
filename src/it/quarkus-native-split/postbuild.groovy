// Assertions for a native build split into two executions of the Quarkus build goal.
//
// Discriminators used below:
//  - the augmentation ran            => 'sources for a subsequent native-image run'
//  - the native image generation ran => 'Building native image from'

String getContent(String fileName) {
    File f = new File(basedir, fileName)
    assert f.exists()
    return f.text
}

void assertAugmentationExecuted(String log) {
    assert log.contains('The sources for a subsequent native-image run')
}

void assertNativeImageCacheable(String logFile) {
    println("Verifying native image is cacheable on ${logFile}...")
    String log = getContent(logFile)
    // no .quarkus/quarkus-prod-config-dump is checked in, yet the goal is cacheable from the first build
    assert log.contains('[quarkus-build-caching-extension] Quarkus native-image build goal marked as cacheable')
    assertAugmentationExecuted(log)
}

void assertNativeImageCacheHit(String logFile) {
    println("Verifying native image cache hit on ${logFile}...")
    String log = getContent(logFile)
    assert log.contains('[quarkus-build-caching-extension] Quarkus native-image build goal marked as cacheable')
    // the augmentation is not cached by default, it runs on every build
    assertAugmentationExecuted(log)
    assert !log.contains('Building native image from')
}

void assertNativeImageCacheDisabled(String logFile) {
    println("Verifying caching disabled on ${logFile}...")
    String log = getContent(logFile)
    assert !log.contains('[quarkus-build-caching-extension] Quarkus native-image build goal marked as cacheable')
    assertAugmentationExecuted(log)
    assert log.contains('Building native image from')
}

void assertAugmentationNotCached(String logFile) {
    String log = getContent(logFile)
    // the augmentation is never cached, only the native image generation is
    assert log.contains('[quarkus-build-caching-extension] Quarkus native-sources build goal marked as not cacheable')
}

void assertNativeExecutableExists() {
    File exe = new File(basedir, 'target/quarkus-test-native-split-0.1-SNAPSHOT-runner')
    assert exe.exists()
    assert exe.length() > 0
}

// The native image generation is cacheable from the very first build: it does not rely on a
// Quarkus configuration dump recorded by a previous build.
// Whether this first invocation is a hit or a miss depends on what target/build-cache already
// holds, so only cacheability is asserted here.
assertNativeImageCacheable('01-split-native-build-cacheable.log')
assertAugmentationNotCached('01-split-native-build-cacheable.log')

assertNativeImageCacheHit('02-split-native-build-cache-hit.log')

// Adding a provided dependency changes the compile classpath but not the jar the native image is
// built from, so the expensive step is still reused
assertNativeImageCacheHit('03-split-native-build-changed-classpath-cache-hit.log')

assertNativeImageCacheDisabled('04-split-native-build-cache-disabled.log')

// The executable is restored by the cache, not only produced by a real native-image run
assertNativeExecutableExists()

println('Verification succeeded')
