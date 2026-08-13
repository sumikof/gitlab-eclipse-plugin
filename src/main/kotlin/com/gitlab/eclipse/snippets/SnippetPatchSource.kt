package com.gitlab.eclipse.snippets

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/** The working-tree diff plus the human-readable description of what it is against. */
data class PatchSource(val diff: String, val commitDescriptor: String)

/**
 * Reads the working-tree diff for a patch snippet (design F3).
 *
 * All local I/O — call it from a background thread. Returns null when the repository has no commit
 * yet: there is nothing to diff against, and the reference extension asserts on the same condition.
 */
class SnippetPatchSource {
  fun read(gitDir: File): PatchSource? =
    FileRepositoryBuilder().setGitDir(gitDir).setMustExist(true).build().use { repo ->
      val headTree = repo.resolve("HEAD^{tree}") ?: return null
      PatchSource(diffAgainst(repo, headTree), describe(repo))
    }

  private fun diffAgainst(repo: Repository, headTree: ObjectId): String {
    val output = ByteArrayOutputStream()
    repo.newObjectReader().use { reader ->
      val oldTree = CanonicalTreeParser().apply { reset(reader, headTree) }
      Git(repo).use { git ->
        git.diff()
          .setOldTree(oldTree)
          .setNewTree(FileTreeIterator(repo))
          .setOutputStream(output)
          .call()
      }
    }
    return output.toString(StandardCharsets.UTF_8)
  }

  /**
   * Mirrors the reference extension's descriptor: branch first, then a tag pointing at HEAD, then
   * the bare commit. On a detached HEAD `Repository.getBranch()` returns the object id itself,
   * which is how the branch case is ruled out.
   */
  private fun describe(repo: Repository): String {
    val head = repo.resolve(Constants.HEAD) ?: return "an unknown commit"
    val shortSha = head.abbreviate(ABBREVIATED_SHA_LENGTH).name()
    val branch = repo.branch
    if (branch != null && branch != head.name) return "branch $branch (commit: $shortSha)"
    val tag = repo.refDatabase.getRefsByPrefix(Constants.R_TAGS)
      .firstOrNull { ref -> repo.refDatabase.peel(ref).let { it.peeledObjectId ?: it.objectId } == head }
      ?.name
      ?.removePrefix(Constants.R_TAGS)
    return if (tag != null) "tag $tag (commit: $shortSha)" else "commit $shortSha"
  }

  private companion object {
    private const val ABBREVIATED_SHA_LENGTH = 8
  }
}
