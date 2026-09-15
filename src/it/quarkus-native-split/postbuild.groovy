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

void assertAutoConfigured(String logFile) {
    String log = getContent(logFile)
    // the native-image configuration is ordered before the next execution is keyed on the jar
    assert log.contains('[quarkus-build-caching-extension] Ordered the native-image configuration of')
    // the test goals are keyed on the dependencies Quarkus adds dynamically, without asking for it
    assert log.contains('TestConfiguration{addQuarkusInputs=true')
    assert log.contains('[quarkus-build-caching-extension] Enabled quarkus.config-tracking.enabled on quarkus-test-native-split')
    assert log.contains('[quarkus-build-caching-extension] Registered the track-config-changes goal on quarkus-test-native-split')
    // the goal the extension registered really is bound and runs
    assert log =~ /track-config-changes \(track-prod-config-changes\)/
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

void assertNativeImageCacheMiss(String logFile) {
    println("Verifying native image cache miss on ${logFile}...")
    String log = getContent(logFile)
    assert log.contains('[quarkus-build-caching-extension] Quarkus native-image build goal marked as cacheable')
    assertAugmentationExecuted(log)
    assert log.contains('Building native image from')
}

void assertNativeImageNotCacheable(String logFile, String reason) {
    println("Verifying native image is not cacheable on ${logFile}...")
    String log = getContent(logFile)
    assert log.contains('[quarkus-build-caching-extension] Quarkus native-image build goal marked as not cacheable')
    assert log.contains(reason)
    assert log.contains('Building native image from')
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

void assertNativeImageNotCacheableWithoutNativeSources(String logFile) {
    println("Verifying native image is not cacheable on ${logFile}...")
    String log = getContent(logFile)
    assert log.contains('target/native-sources/native-image.args not found, is the native build enabled?')
    assert log.contains('[quarkus-build-caching-extension] Quarkus native-image build goal marked as not cacheable')
    assert !log.contains('The sources for a subsequent native-image run')
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
// the pom declares only the two build executions: the rest is registered by the extension
assertAutoConfigured('01-split-native-build-cacheable.log')
assertNativeImageCacheable('01-split-native-build-cacheable.log')
assertAugmentationNotCached('01-split-native-build-cacheable.log')

assertNativeImageCacheHit('02-split-native-build-cache-hit.log')

// Adding a provided dependency changes the compile classpath but not the jar the native image is
// built from, so the expensive step is still reused
assertNativeImageCacheHit('03-split-native-build-changed-classpath-cache-hit.log')

assertNativeImageCacheDisabled('04-split-native-build-cache-disabled.log')

assertNativeImageNotCacheableWithoutNativeSources('05-split-native-build-native-disabled.log')

// A Quarkus property the augmentation records but that reaches neither the jar nor
// native-image.args still has to invalidate the native image generation
assertNativeImageCacheMiss('06-split-native-build-changed-config-cache-miss.log')

// A dump recorded by an earlier build is rejected rather than trusted
assertNativeImageNotCacheable('07-split-native-build-stale-config-dump.log',
        'Quarkus configuration dump was not recorded by the native-sources build goal')

void assertArtifactDescriptorDescribesTheExecutable() {
    // @QuarkusIntegrationTest launches whatever this file describes: type=native-sources, which is
    // what the augmentation writes, would launch the source jar instead of the native executable
    File descriptor = new File(basedir, 'target/quarkus-artifact.properties')
    assert descriptor.exists()
    Properties descriptorProperties = new Properties()
    descriptor.withInputStream { descriptorProperties.load(it) }
    assert descriptorProperties.getProperty('type') == 'native'
    assert descriptorProperties.getProperty('path') == 'quarkus-test-native-split-0.1-SNAPSHOT-runner'
}

// Case 9 repeats case 8, so native-image is skipped and the descriptor was put back by the
// extension rather than by the build goal re-running the augmentation
assertNativeImageCacheHit('09-split-native-build-artifact-descriptor-cache-hit.log')
assert getContent('09-split-native-build-artifact-descriptor-cache-hit.log')
        .contains('[quarkus-build-caching-extension] Restored quarkus-artifact.properties')
assertArtifactDescriptorDescribesTheExecutable()

// Disabling the cache with -D on the command line has to work as well as the pom property does
assertNativeImageCacheDisabled('10-split-native-build-cache-disabled-cli.log')

// A configured extra output is declared on the native image generation
println('Verifying the extra output is declared on 11-split-native-build-extra-output.log...')
assert getContent('11-split-native-build-extra-output.log')
        .contains('[quarkus-build-caching-extension] Adding extra output file quarkus-artifact.properties')

// An explicit declaration is left alone: the goal runs once, registered by the project
String explicit = getContent('12-split-native-build-explicit-config-tracking.log')
assert explicit =~ /track-config-changes \(my-own-config-tracking\)/
assert !explicit.contains('[quarkus-build-caching-extension] Registered the track-config-changes goal')
assert !explicit.contains('[quarkus-build-caching-extension] Enabled quarkus.config-tracking.enabled')

// The ordering can be turned off
println('Verifying the native-image configuration ordering can be disabled...')
assert !getContent('13-split-native-build-no-json-ordering.log')
        .contains('[quarkus-build-caching-extension] Ordered the native-image configuration of')

// Whether restored or rebuilt, the executable has to be there at the end
assertNativeExecutableExists()

println('Verification succeeded')
