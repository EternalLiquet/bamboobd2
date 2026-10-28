// G2 transport registry check, shared by the root build and the core-only build.
// Every AdapterTransport implementation in any module's src/ tree (Kotlin and Java,
// main and test) must be listed with its declared TransportKind in
// transport-registry.txt. Anonymous implementations are not allowed, so every
// transport has a name to pin. This is a source-text scan: an implementation that
// reaches AdapterTransport only through another base type is not detected, so new
// base types must not be introduced without updating this check.
// Adding a transport or changing its kind fails this check until the pinned file
// is updated in review. A LIVE entry must also carry "reviewed:<reference>" naming
// the review that pinned it. That records code review only: running a LIVE
// transport on hardware still needs the separate live-stage go (LIVE_TEST_GATE.md).

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
        // Each class/object header runs from its name to its body "{" or the next
        // declaration. If the header names AdapterTransport, it must be either a
        // parsed supertype (Kotlin ": ..." after the constructor, Java "extends/
        // implements") or only a constructor parameter inside cleanly balanced
        // parentheses. Anything else fails closed as unparseable.
        // Comments and string/char literals become spaces (newlines kept), so words
        // or brackets inside them cannot shape the scan.
        fun blankNonCode(src: String): String {
            val out = StringBuilder(src)
            fun blank(from: Int, to: Int) { for (k in from until minOf(to, src.length)) if (src[k] != '\n') out.setCharAt(k, ' ') }
            var i = 0
            while (i < src.length) {
                val start = i
                when {
                    src.startsWith("//", i) -> { i = src.indexOf('\n', i).let { if (it < 0) src.length else it }; blank(start, i) }
                    src.startsWith("/*", i) -> { i = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }; blank(start, i) }
                    src.startsWith("\"\"\"", i) -> { i = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }; blank(start, i) }
                    src[i] == '"' || src[i] == '\'' -> {
                        val quote = src[i]
                        i++
                        while (i < src.length && src[i] != quote && src[i] != '\n') i += if (src[i] == '\\') 2 else 1
                        i = minOf(i + 1, src.length)
                        blank(start, i)
                    }
                    else -> i++
                }
            }
            return out.toString()
        }
        val anyDeclaration = Regex("""\b(?:class|object|interface)\s+\w+""")
        val header = Regex("""\b(?:class|object)\s+(\w+)""")
        val transportName = Regex("""\bAdapterTransport\b""")
        val modifier = Regex("""(private|internal|protected|public|constructor)\b|@[\w.]+""")
        // Index after the matching close, or -1 if it does not close before [limit].
        fun skipBalanced(text: String, start: Int, limit: Int, open: Char, close: Char): Int {
            var depth = 0
            for (i in start until limit) {
                if (text[i] == open) depth++
                if (text[i] == close && --depth == 0) return i + 1
            }
            return -1
        }
        // The supertype text of a header, "" for none, or null when the header is not understood.
        fun supertypes(text: String, from: Int, limit: Int): String? {
            var i = from
            fun skipSpace() { while (i < limit && text[i].isWhitespace()) i++ }
            skipSpace()
            if (i < limit && text[i] == '<') i = skipBalanced(text, i, limit, '<', '>').takeIf { it >= 0 } ?: return null
            while (true) {
                skipSpace()
                val word = modifier.matchAt(text, i)?.takeIf { it.range.last < limit } ?: break
                i = word.range.last + 1
                if (word.value.startsWith("@") && i < limit && text[i] == '(') i = skipBalanced(text, i, limit, '(', ')').takeIf { it >= 0 } ?: return null
            }
            skipSpace()
            if (i < limit && text[i] == '(') i = skipBalanced(text, i, limit, '(', ')').takeIf { it >= 0 } ?: return null
            val tail = text.substring(i, limit).trim()
            return when {
                tail.isEmpty() -> ""
                tail.startsWith(":") || tail.startsWith("extends") || tail.startsWith("implements") -> tail
                else -> null
            }
        }
        val anonymous = Regex("""object\s*:\s*[^{]*?\bAdapterTransport\b|new\s+AdapterTransport\s*\(""")
        val kind = Regex("""TransportKind\.(\w+)""")
        val found = sortedSetOf<String>()
        val problems = mutableListOf<String>()
        sources.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { file ->
                val text = blankNonCode(file.readText())
                val path = file.relativeTo(repoRoot).invariantSeparatorsPath
                anonymous.findAll(text).forEach { problems += "anonymous AdapterTransport in $path; give it a name and pin it" }
                header.findAll(text).forEach { m ->
                    val from = m.range.last + 1
                    val next = anyDeclaration.find(text, from)?.range?.first ?: text.length
                    val brace = text.indexOf('{', from).takeIf { it >= 0 } ?: text.length
                    val limit = minOf(next, brace)
                    if (!transportName.containsMatchIn(text.substring(from, limit))) return@forEach
                    val supers = supertypes(text, from, limit)
                    when {
                        supers != null && transportName.containsMatchIn(supers) -> {
                            // Look for the kind only inside this declaration, up to the next one.
                            val declared = kind.find(text.substring(m.range.last, next))?.groupValues?.get(1) ?: "UNDECLARED"
                            found += "$path ${m.groupValues[1]} $declared"
                        }
                        supers != null -> Unit // named only as a constructor parameter
                        else -> problems += "cannot parse the declaration of ${m.groupValues[1]} in $path; simplify its header or update this check"
                    }
                }
            }
        }
        // Each line: <file> <class> <kind> [reviewed:<reference>]
        val lines = pinnedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val reviewed = mutableSetOf<String>()
        val pinned = lines.map { line ->
            val parts = line.split(Regex("""\s+"""))
            val key = parts.take(3).joinToString(" ")
            val extra = parts.drop(3)
            if (extra.isNotEmpty() && (extra.size > 1 || !Regex("""reviewed:\S+""").matches(extra.single()))) {
                problems += "unexpected text in transport-registry.txt: $line"
            }
            if (extra.isNotEmpty()) reviewed += key
            key
        }.toSortedSet()
        (found - pinned).forEach { problems += "not in transport-registry.txt: $it" }
        (pinned - found).forEach { problems += "pinned but not found: $it" }
        found.filter { it.endsWith(" UNDECLARED") }.forEach { problems += "needs a declared kind: $it" }
        found.filter { it.endsWith(" LIVE") && it !in reviewed }.forEach { problems += "LIVE transport needs a reviewed:<reference> entry: $it" }
        check(problems.isEmpty()) { problems.joinToString("\n") }
        val live = found.count { it.endsWith(" LIVE") }
        logger.lifecycle("Transport registry: ${found.size} implementation(s), all pinned; $live LIVE, each with a review reference")
    }
}

tasks.matching { it.name == "check" }.configureEach { dependsOn(verifyTransportRegistry) }
