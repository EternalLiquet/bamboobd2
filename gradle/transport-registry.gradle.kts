// G2 transport registry check, shared by the root build and the core-only build.
// Every AdapterTransport implementation in any module's src/ tree (Kotlin and Java,
// main and test) must be listed with its declared TransportKind in
// transport-registry.txt. Anonymous implementations are not allowed, so every
// transport has a name to pin. This is a source-text scan: an implementation that
// reaches AdapterTransport only through another base type is not detected, so new
// base types must not be introduced without updating this check.
// Adding a transport or changing its kind fails this check until the pinned file
// is updated in review. A LIVE kind additionally needs its own live-gate review.

val repoRoot: File = listOf(rootDir, rootDir.parentFile).first { File(it, "transport-registry.txt").isFile }

val verifyTransportRegistry by tasks.registering {
    group = "verification"
    description = "Checks AdapterTransport implementations against transport-registry.txt (G2)."
    val sources = repoRoot.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith(".") && it.name != "build" }
        .map { File(it, "src") }
        .filter { it.isDirectory }
    val pinnedFile = File(repoRoot, "transport-registry.txt")
    inputs.files(sources.map { fileTree(it) { include("**/*.kt", "**/*.java") } })
    inputs.file(pinnedFile)
    doLast {
        // A declaration's supertypes: Kotlin "class X(...) : A, B" after the balanced
        // constructor parentheses, or Java "extends/implements" before the body.
        val header = Regex("""\b(?:class|object)\s+(\w+)""")
        fun skipBalanced(text: String, start: Int, open: Char, close: Char): Int {
            var depth = 0
            for (i in start until text.length) {
                if (text[i] == open) depth++
                if (text[i] == close && --depth == 0) return i + 1
            }
            return text.length
        }
        fun supertypes(text: String, from: Int): String {
            var i = from
            fun skipSpace() { while (i < text.length && text[i].isWhitespace()) i++ }
            skipSpace()
            if (i < text.length && text[i] == '<') i = skipBalanced(text, i, '<', '>')
            // Constructor modifiers, e.g. "internal constructor(...)".
            while (true) {
                skipSpace()
                val word = Regex("""(private|internal|protected|public|constructor)\b""").matchAt(text, i) ?: break
                i = word.range.last + 1
            }
            skipSpace()
            if (i < text.length && text[i] == '(') i = skipBalanced(text, i, '(', ')')
            val end = listOf(text.indexOf('{', i), text.indexOf("\n\n", i)).filter { it >= 0 }.minOrNull() ?: text.length
            val tail = text.substring(i, end).trimStart()
            return if (tail.startsWith(":") || tail.startsWith("extends") || tail.startsWith("implements")) tail else ""
        }
        val anonymous = Regex("""object\s*:\s*[^{]*?\bAdapterTransport\b|new\s+AdapterTransport\s*\(""")
        val anyDeclaration = Regex("""\b(?:class|object|interface)\s+\w+""")
        val kind = Regex("""TransportKind\.(\w+)""")
        val found = sortedSetOf<String>()
        val problems = mutableListOf<String>()
        sources.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { file ->
                val text = file.readText()
                val path = file.relativeTo(repoRoot).invariantSeparatorsPath
                anonymous.findAll(text).forEach { problems += "anonymous AdapterTransport in $path; give it a name and pin it" }
                header.findAll(text).filter { Regex("""\bAdapterTransport\b""").containsMatchIn(supertypes(text, it.range.last + 1)) }.forEach { m ->
                    // Look for the kind only inside this declaration, up to the next one.
                    val end = anyDeclaration.find(text, m.range.last + 1)?.range?.first ?: text.length
                    val declared = kind.find(text.substring(m.range.last, end))?.groupValues?.get(1) ?: "UNDECLARED"
                    found += "$path ${m.groupValues[1]} $declared"
                }
            }
        }
        val pinned = pinnedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSortedSet()
        (found - pinned).forEach { problems += "not in transport-registry.txt: $it" }
        (pinned - found).forEach { problems += "pinned but not found: $it" }
        found.filter { it.endsWith(" LIVE") || it.endsWith(" UNDECLARED") }.forEach { problems += "needs live-gate review or a declared kind: $it" }
        check(problems.isEmpty()) { problems.joinToString("\n") }
        logger.lifecycle("Transport registry: ${found.size} implementation(s), none LIVE, all pinned")
    }
}

tasks.matching { it.name == "check" }.configureEach { dependsOn(verifyTransportRegistry) }
