// G2 transport registry check, shared by the root build and the core-only build.
// Every AdapterTransport implementation in core/ and app/ (main and test sources)
// must be listed with its declared TransportKind in transport-registry.txt.
// Adding a transport or changing its kind fails this check until the pinned file
// is updated in review. A LIVE kind additionally needs its own live-gate review.

val repoRoot: File = listOf(rootDir, rootDir.parentFile).first { File(it, "transport-registry.txt").isFile }

val verifyTransportRegistry by tasks.registering {
    group = "verification"
    description = "Checks AdapterTransport implementations against transport-registry.txt (G2)."
    val sources = listOf("core/src", "app/src").map { File(repoRoot, it) }.filter { it.isDirectory }
    val pinnedFile = File(repoRoot, "transport-registry.txt")
    inputs.files(sources.map { fileTree(it) { include("**/*.kt") } })
    inputs.file(pinnedFile)
    doLast {
        val declaration = Regex("""(?:class|object)\s+(\w+)[^{]*?:\s*[^{]*?\bAdapterTransport\b""")
        val kind = Regex("""TransportKind\.(\w+)""")
        val found = sortedSetOf<String>()
        sources.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                declaration.findAll(text).forEach { m ->
                    val declared = kind.find(text, m.range.last)?.groupValues?.get(1) ?: "UNDECLARED"
                    found += "${file.relativeTo(repoRoot).invariantSeparatorsPath} ${m.groupValues[1]} $declared"
                }
            }
        }
        val pinned = pinnedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSortedSet()
        val problems = mutableListOf<String>()
        (found - pinned).forEach { problems += "not in transport-registry.txt: $it" }
        (pinned - found).forEach { problems += "pinned but not found: $it" }
        found.filter { it.endsWith(" LIVE") || it.endsWith(" UNDECLARED") }.forEach { problems += "needs live-gate review or a declared kind: $it" }
        check(problems.isEmpty()) { problems.joinToString("\n") }
        logger.lifecycle("Transport registry: ${found.size} implementation(s), all FAKE and pinned")
    }
}

tasks.matching { it.name == "check" }.configureEach { dependsOn(verifyTransportRegistry) }
