// A project version carrying a commit id moves every build. The extension builds under a name
// without the version and links the executable back afterwards, so the native image generation is
// keyed on something that does not move.
//
// Discriminator: the native image generation ran => 'Building native image from'

String ARTIFACT = 'quarkus-test-dynamic-version'

String getContent(String fileName) {
    File f = new File(basedir, fileName)
    assert f.exists()
    return f.text
}

// the first commit populates the entry: cacheable, and native-image really runs
String first = getContent('01-first-commit.log')
assert first.contains("[quarkus-build-caching-extension] Building as '${ARTIFACT}' rather than '${ARTIFACT}-1.0-abc1234'")
assert first.contains('[quarkus-build-caching-extension] Quarkus native-image build goal marked as cacheable')
assert first.contains('Building native image from')
// the executable is put back under the name the rest of the build expects
assert first.contains("[quarkus-build-caching-extension] Linked ${ARTIFACT}-1.0-abc1234-runner to the cached ${ARTIFACT}-runner")

// the second commit is a different version, and still reuses the executable
String second = getContent('02-second-commit-cache-hit.log')
assert second.contains("[quarkus-build-caching-extension] Building as '${ARTIFACT}' rather than '${ARTIFACT}-1.0-def5678'")
assert !second.contains('Building native image from')
assert second.contains("[quarkus-build-caching-extension] Linked ${ARTIFACT}-1.0-def5678-runner to the cached ${ARTIFACT}-runner")

// without the recipe the version is back in the key, so the same change costs a full native build
String third = getContent('03-third-commit-recipe-off.log')
assert !third.contains('[quarkus-build-caching-extension] Building as ')
assert third.contains('[quarkus-build-caching-extension] Quarkus native-image build goal marked as cacheable')
assert third.contains('Building native image from')
// every invocation starts with clean, so only this last build's target is still around
assert new File(basedir, "target/${ARTIFACT}-1.0-9999999-runner").exists()

println('Verification succeeded')
