package io.github.eternalliquet.bamboobd2.negative

import java.nio.file.Files
import java.nio.file.Path

// Deliberately uses java.nio.file.Files.readString, which Android API 26 does
// not provide. Never shipped; only proves the API-26 check catches it.
fun readLaterApi(path: Path): String = Files.readString(path)
