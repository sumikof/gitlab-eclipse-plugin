package com.gitlab.eclipse.snippets

import com.gitlab.eclipse.api.SnippetBlob
import com.gitlab.eclipse.api.SnippetSummary

/**
 * One applicable patch file, which is a (snippet, blob) pair rather than a snippet: a snippet can
 * hold several `.patch` files and each is applied on its own.
 */
data class PatchSnippetCandidate(val snippet: SnippetSummary, val blob: SnippetBlob) {
  /** What the picker shows. The filename is always included — one snippet can offer several. */
  val label: String get() = "${snippet.title} — ${blob.name}"
}

/** Narrows a snippet listing to the files that could be a patch (design F4 step 2). */
object PatchSnippetFilter {
  private const val PATCH_SUFFIX = ".patch"

  fun candidates(snippets: List<SnippetSummary>): List<PatchSnippetCandidate> =
    snippets.flatMap { snippet ->
      snippet.blobs
        .filter { it.path.endsWith(PATCH_SUFFIX, ignoreCase = true) }
        .map { PatchSnippetCandidate(snippet, it) }
    }
}
