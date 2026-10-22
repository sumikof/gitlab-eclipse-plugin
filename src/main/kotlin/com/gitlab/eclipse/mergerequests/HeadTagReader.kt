package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.utils.logger
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

/**
 * Lists the local tags that point at a given commit (CI-05 tag pipelines). Read-only JGit access, local I/O
 * only: call from a background thread.
 */
class HeadTagReader {
  private val logger by lazy { logger<HeadTagReader>() }

  /**
   * Short names (no `refs/tags/`), sorted, of the local tags whose peeled commit equals [headSha]; annotated
   * tags are peeled. Never throws: any failure is logged by exception class only (tag names and paths stay
   * out of the log) and yields an empty list.
   */
  fun tagsAt(gitDir: File, headSha: String): List<String> =
    try {
      FileRepositoryBuilder().findGitDir(gitDir).setMustExist(true).build().use { repo ->
        repo.refDatabase.getRefsByPrefix(Constants.R_TAGS)
          .filter { ref -> repo.refDatabase.peel(ref).let { it.peeledObjectId ?: it.objectId }?.name == headSha }
          .map { it.name.removePrefix(Constants.R_TAGS) }
          .sorted()
      }
    } catch (e: Exception) {
      logger.warn("Could not read tags at HEAD: ${e.javaClass.name}")
      emptyList()
    }
}
