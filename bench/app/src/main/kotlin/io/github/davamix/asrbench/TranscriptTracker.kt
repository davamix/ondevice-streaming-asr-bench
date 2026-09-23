package io.github.davamix.asrbench

/**
 * Tracks the evolving hypothesis and counts how much of it gets *rewritten*.
 *
 * `partial_instability` is the metric that a WER table cannot show. A chunked
 * offline model can land a perfect final transcript while flickering badly on
 * the way there -- text appears, is read, and is then silently replaced. That
 * is what makes a live transcriber feel broken, and it is invisible to every
 * accuracy metric.
 *
 * Definition used here: on each update, find the longest common word prefix
 * between the previous hypothesis and the new one. Every previously displayed
 * word beyond that prefix has been revised, and counts.
 *
 *   "the quick"        -> "the quick brown"   : 0 revisions (pure extension)
 *   "the quick brown"  -> "the quick red"     : 1 revision  ("brown" changed)
 *   "the quick brown"  -> "a slow dog"        : 3 revisions (all of it)
 *
 * Pure extension is free, which is right: appending text is what a streaming
 * model is supposed to do. Only rewriting is charged for.
 */
class TranscriptTracker {

    private var previous: List<String> = emptyList()

    /** Number of previously-displayed words that were later changed or removed. */
    var revisions: Int = 0
        private set

    /** How many times the hypothesis was updated at all. */
    var updates: Int = 0
        private set

    /** The latest hypothesis text, as last set. */
    var text: String = ""
        private set

    fun update(newText: String) {
        val cur = tokenize(newText)
        updates++

        var k = 0
        while (k < previous.size && k < cur.size && previous[k] == cur[k]) k++
        revisions += previous.size - k

        previous = cur
        text = newText
    }

    /**
     * Instability normalised by final length, so a long utterance is not
     * automatically judged less stable than a short one.
     */
    fun revisionsPerWord(): Double =
        if (previous.isEmpty()) 0.0 else revisions.toDouble() / previous.size

    private fun tokenize(s: String): List<String> =
        s.trim().lowercase().split(WS).filter { it.isNotEmpty() }

    private companion object {
        val WS = Regex("\\s+")
    }
}
