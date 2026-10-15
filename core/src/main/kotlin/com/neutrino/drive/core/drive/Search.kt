package com.neutrino.drive.core.drive

import com.neutrino.drive.core.api.NeutrinoApi
import java.text.Normalizer

/**
 * Splits text into search terms exactly as the web's `packages/search/src/tokenizer.ts` and the
 * iOS `SearchIndexTokenizer` do — NFC-normalised, lowercased, split on anything that is not a
 * letter or a digit — so a term found here is the term the other clients would index.
 */
object SearchTokenizer {
    fun words(text: String): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) {
                current.appendCodePoint(cp)
            } else if (current.isNotEmpty()) {
                out += current.toString()
                current.clear()
            }
            i += Character.charCount(cp)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}

/**
 * Searches file names on the device. There is no server-side search: the server holds file
 * names, but the platform's search index is an encrypted snapshot only clients can read
 * (`neutrino/agent_docs/search.md`). This builds an in-memory index from a walk of the drive;
 * syncing that snapshot with the web is a later phase (see `agent_docs/android-port.md`).
 *
 * A query matches a file when every query word is a prefix of some word in its name.
 */
class LocalSearchIndex {
    private data class Entry(val item: DriveItem, val words: List<String>)

    @Volatile
    private var entries: List<Entry> = emptyList()

    @Volatile
    var builtAtMillis: Long = 0
        private set

    val isEmpty get() = entries.isEmpty()

    fun replace(items: List<DriveItem>) {
        entries = items.filter { !it.isFolder }.map { Entry(it, SearchTokenizer.words(it.name)) }
        builtAtMillis = System.currentTimeMillis()
    }

    fun query(text: String, limit: Int = 100): List<DriveItem> {
        val terms = SearchTokenizer.words(text)
        if (terms.isEmpty()) return emptyList()
        return entries.asSequence()
            .filter { e -> terms.all { t -> e.words.any { it.startsWith(t) } } }
            .map { it.item }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
            .toList()
    }

    companion object {
        /**
         * Every file in the drive, by a recursive walk. `null` if any branch fails — a partial walk
         * treated as complete would make one flaky request look like a deleted subtree.
         */
        suspend fun walk(api: NeutrinoApi, folderId: String? = null, depth: Int = 0): List<DriveItem>? {
            if (depth > 32) return emptyList()
            val listing = runCatching { api.folder(folderId) }.getOrNull() ?: return null
            val result = listing.files.map { DriveItem.of(it) }.toMutableList()
            for (folder in listing.folders) {
                result += walk(api, folder.id, depth + 1) ?: return null
            }
            return result
        }
    }
}
