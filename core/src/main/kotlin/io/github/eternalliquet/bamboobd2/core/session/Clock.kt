package io.github.eternalliquet.bamboobd2.core.session

import java.util.UUID

/** Wall-clock time for record timestamps only. Deadlines use coroutine delays. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}

/** Session id source. Ids must stay file-name safe: letters, digits and '-'. */
fun interface IdGenerator {
    fun newId(): String

    companion object {
        val Random: IdGenerator = IdGenerator { UUID.randomUUID().toString() }
    }
}
