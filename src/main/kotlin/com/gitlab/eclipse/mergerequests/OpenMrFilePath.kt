package com.gitlab.eclipse.mergerequests

import java.io.File
import java.io.IOException
import java.nio.file.InvalidPathException

/**
 * Joins [relPath] onto [workTree] and returns the resulting file ONLY IF its fully resolved
 * real path (symlinks and `..` segments resolved via `Path.toRealPath`) is still contained in
 * the work tree's real path (component-wise `Path.startsWith`, never a string prefix).
 *
 * This is the security gate for opening merge-request changed files (Phase 3 §8.6): a diff
 * path is server-supplied data, so `../`-traversal out of the work tree, an absolute path
 * (which `Path.resolve` returns as-is), or an in-tree symlink pointing outside must all be
 * rejected — each resolves to a real path that does not start with the work tree's and yields
 * null. A path that does not exist (deleted in the MR, or not checked out) also yields null:
 * `toRealPath` requires existence and its `NoSuchFileException` is an [IOException].
 */
internal fun resolveContainedRealPath(workTree: File, relPath: String): File? =
  try {
    val workTreeReal = workTree.toPath().toRealPath()
    val candidateReal = workTreeReal.resolve(relPath).toRealPath()
    if (candidateReal.startsWith(workTreeReal)) candidateReal.toFile() else null
  } catch (_: IOException) {
    null
  } catch (_: InvalidPathException) {
    null
  }
