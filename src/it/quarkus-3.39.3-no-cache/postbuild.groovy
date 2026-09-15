// A lone `build` execution is not a split native build, so the extension leaves it uncached.

String getContent(String fileName) {
    File f = new File(basedir, fileName)
    assert f.exists()
    return f.text
}

void assertNotCacheable(String logFile) {
    println("Verifying the build goal is not cacheable on ${logFile}...")
    String log = getContent(logFile)
    assert log.contains("[quarkus-build-caching-extension] Quarkus build goal execution 'quarkus-build' classified as UNSUPPORTED")
    assert log.contains('[quarkus-build-caching-extension] Quarkus build goal marked as not cacheable, declare it as a split native build to make the native image generation cacheable')
    // never cached means the augmentation runs every time
    assert log.contains('Quarkus augmentation completed')
    // not a split native build, so the test goals are left alone too
    assert log.contains('TestConfiguration{addQuarkusInputs=false')
}

assertNotCacheable('01-single-execution.log')
assertNotCacheable('02-single-execution-rebuild.log')

println('Verification succeeded')
