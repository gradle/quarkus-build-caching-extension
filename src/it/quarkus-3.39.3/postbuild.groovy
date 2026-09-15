// A native build with the local toolchain, which is cacheable only once the in-container
// requirement is lifted, and warns that the toolchain is then absent from the cache key.

String getContent(String fileName) {
    File f = new File(basedir, fileName)
    assert f.exists()
    return f.text
}

String warning = 'The GraalVM or Mandrel version is not part of the cache key'

// the requirement is lifted in the pom, so the goal is cacheable and the warning is raised
String first = getContent('01-local-toolchain-cacheable.log')
assert first.contains('[quarkus-build-caching-extension] Quarkus native image is built with a local toolchain')
assert first.contains(warning)
assert first.contains('[quarkus-build-caching-extension] Quarkus native-image build goal marked as cacheable')
// no builder image, so the OS and the JDK are part of the key instead
assert !first.contains('native-builder.image')

// and it is reused on the next build
String second = getContent('02-local-toolchain-cache-hit.log')
assert second.contains(warning)
assert !second.contains('Building native image from')

// putting the requirement back makes the same build uncacheable
String required = getContent('03-local-toolchain-in-container-required.log')
assert required.contains('[quarkus-build-caching-extension] Quarkus build strategy is not in-container')
assert required.contains('[quarkus-build-caching-extension] Quarkus native-image build goal marked as not cacheable')
assert !required.contains(warning)
assert required.contains('Building native image from')

assert new File(basedir, 'target/quarkus-test-local-toolchain-0.1-SNAPSHOT-runner').exists()

println('Verification succeeded')
