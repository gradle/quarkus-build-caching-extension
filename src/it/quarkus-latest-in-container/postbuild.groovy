// The split native build against the Quarkus snapshot, in the plain configuration. This is a
// regression check on Quarkus itself: the augmentation has to stay reproducible enough for the
// native image generation to be reused, and the goals the extension relies on have to keep working.

String getContent(String fileName) {
    File f = new File(basedir, fileName)
    assert f.exists()
    return f.text
}

// the extension registers the config tracking the caching relies on
String first = getContent('01-latest-cacheable.log')
assert first.contains('[quarkus-build-caching-extension] Registered the track-config-changes goal on quarkus-test-latest')
assert first.contains('[quarkus-build-caching-extension] Quarkus native-sources build goal marked as not cacheable')
assert first.contains('[quarkus-build-caching-extension] Quarkus native-image build goal marked as cacheable')
assert first.contains('The sources for a subsequent native-image run')
// a Quarkus version below the supported minimum would say so, and 999-SNAPSHOT is above it
assert !first.contains('is below 3.39.3, the minimum this extension supports')

// repeating the build unchanged has to reuse the native image: if Quarkus stops producing a stable
// augmentation this is what catches it
String second = getContent('02-latest-cache-hit.log')
assert second.contains('[quarkus-build-caching-extension] Quarkus native-image build goal marked as cacheable')
assert !second.contains('Building native image from')

// disabling the caching leaves both executions alone
String disabled = getContent('03-latest-cache-disabled.log')
assert !disabled.contains('[quarkus-build-caching-extension] Quarkus native-image build goal marked as cacheable')
assert disabled.contains('Building native image from')

assert new File(basedir, 'target/quarkus-test-latest-0.1-SNAPSHOT-runner').exists()

println('Verification succeeded')
