package com.gitlab.eclipse.mergerequests

import java.io.File
import java.io.IOException

/** Resolved repository/project identity for the current workspace selection (Phase 3 §6.1). */
data class RepositoryContext(
  val gitDir: String,
  val workTree: String,
  val namespaceWithPath: String,
  val instanceUrl: String,
  val webUrl: String,
  val remoteName: String,
  val projectId: String,
) {
  companion object {
    /**
     * Encodes [namespaceWithPath] as a GitLab project id path segment.
     *
     * Only the `/` separator is replaced with `%2F`. The input segments are already
     * percent-escaped (they originate from a URI's raw path), so re-encoding them here
     * would turn `%` into `%25` and 404 every project API call.
     */
    fun encodeProjectId(namespaceWithPath: String): String = namespaceWithPath.replace("/", "%2F")

    /**
     * Removes duplicate [File]s that resolve to the same canonical path, preserving the
     * order of first occurrence. A multi-module git repository imported as several Eclipse
     * projects yields the same gitDir multiple times; this collapses those to one entry.
     */
    fun dedupByCanonicalPath(gitDirs: List<File>): List<File> {
      val seenKeys = HashSet<String>()
      val result = ArrayList<File>()
      for (gitDir in gitDirs) {
        val key = canonicalKeyOf(gitDir)
        if (seenKeys.add(key)) {
          result.add(gitDir)
        }
      }
      return result
    }

    private fun canonicalKeyOf(file: File): String =
      try {
        file.canonicalPath
      } catch (_: IOException) {
        file.absolutePath
      }
  }
}
