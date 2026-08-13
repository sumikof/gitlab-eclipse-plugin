package com.gitlab.eclipse.snippets

/**
 * Turns a working-tree diff into a patch snippet body (design F3).
 *
 * SWT-free and I/O-free: the diff text and the commit descriptor are produced by the caller, so
 * every rule below — naming, the description, and the binary refusal — is unit-testable.
 */
object SnippetPatchBuilder {
  /** Title prefix and file suffix are the reference extension's (`constants.ts`). */
  private const val TITLE_PREFIX = "patch: "
  private const val FILE_SUFFIX = ".patch"

  /**
   * JGit's `DiffFormatter` emits one of these instead of a payload for a binary change, so such a
   * patch could never reproduce the post-image on apply. Refusing here keeps a snippet that cannot
   * be applied from ever being created (design section 3).
   */
  private val BINARY_MARKERS = listOf("Binary files ", "GIT binary patch")

  sealed interface Result {
    data class Ok(val payload: SnippetPayload) : Result
    data object NoChanges : Result
    data object BinaryNotSupported : Result
    data object InvalidName : Result
  }

  fun build(
    name: String,
    diff: String,
    commitDescriptor: String,
    visibility: SnippetVisibility,
  ): Result {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty()) return Result.InvalidName
    if (diff.isBlank()) return Result.NoChanges
    if (BINARY_MARKERS.any { diff.contains(it) }) return Result.BinaryNotSupported

    val patchFileName = "$trimmedName$FILE_SUFFIX"
    return Result.Ok(
      SnippetPayload(
        title = "$TITLE_PREFIX$trimmedName",
        fileName = patchFileName,
        visibility = visibility.wireValue,
        content = diff,
        description = describe(commitDescriptor, patchFileName),
      ),
    )
  }

  /** Mirrors the reference extension's wording: what the patch is against, and how to apply it. */
  private fun describe(commitDescriptor: String, patchFileName: String): String = """
    |This snippet contains suggested changes for $commitDescriptor.
    |
    |Apply this snippet:
    |
    |- In Eclipse with the GitLab plugin installed:
    |  - Run `GitLab: Apply Snippet Patch` and select this snippet
    |- Using the `git` command:
    |  - Download the `$patchFileName` file to your project folder
    |  - In your project folder, run
    |
    |    ~~~sh
    |    git apply '$patchFileName'
    |    ~~~
  """.trimMargin()
}
