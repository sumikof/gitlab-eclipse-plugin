package com.gitlab.eclipse.mergerequests.review

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.attributes.Attribute
import org.eclipse.jgit.attributes.Attributes
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.errors.NoWorkTreeException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.TreeWalk.OperationType
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.CancellationException

/** Whether line N of the editor's text is line N of the HEAD blob (design §9.3.2, gate G8). */
sealed interface BodyIdentity {
  object Same : BodyIdentity
  data class Different(val reason: BodyMismatch) : BodyIdentity
}

enum class BodyMismatch {
  FILTER_ATTRIBUTE,
  NOT_REGULAR_FILE,
  INDEX_FLAG,

  /** The disk text differs from the document text, or the re-read after G8c differs from the first read. */
  DISK_TEXT_DIFFERS,
  STATUS_NOT_CLEAN,

  /** The document text has a lone `\r`: Eclipse counts it as a line break, Git and GitLab do not. */
  UNSUPPORTED_LINE_DELIMITER,
  UNREADABLE,
}

/** Attributes that rewrite content between the blob and the working tree, breaking line correspondence. */
private val REWRITING_ATTRIBUTES = listOf("filter", "working-tree-encoding")

private const val BYTE_ORDER_MARK = '\uFEFF'

/** A carriage return not immediately followed by a line feed. */
private val LONE_CARRIAGE_RETURN = Regex("\r(?!\n)")

/**
 * Background only. Evaluates G8a'', G8a', G8a, G8b, G8c and the G8b re-read of design §9.3.2, in this order.
 *
 * Proves that [documentText] (decoded by the editor with [charset]) maps line-for-line onto the HEAD
 * blob of [repoRelativePath], whose working-tree file is [file]:
 *
 * - G8a'': the HEAD tree and index entries are regular or executable files (not a symlink/gitlink).
 * - G8a': the index entry is neither assume-valid nor skip-worktree (status would hide edits).
 * - G8a: no `filter` / `working-tree-encoding` attribute is set or valued for the path.
 * - G8b: [documentText] has no lone `\r` (else [BodyMismatch.UNSUPPORTED_LINE_DELIMITER]), and the
 *   file's bytes decoded with [charset], minus one leading U+FEFF, equal it exactly (newlines are not
 *   normalized).
 * - G8c: JGit status reports the path in none of its sets, so index and working tree both match HEAD
 *   up to newline conversion, which never changes line count or order.
 * - G8b again: the bytes re-read after G8c are identical to the first read.
 *
 * Before any gate, [file] must canonically be [repoRelativePath] inside the work tree; otherwise the
 * result is [BodyMismatch.UNREADABLE].
 *
 * Never throws: any I/O or JGit failure is [BodyMismatch.UNREADABLE]. Logs nothing.
 */
fun checkBodyIdentity(
  repository: Repository,
  repoRelativePath: String,
  file: File,
  documentText: String,
  charset: Charset,
): BodyIdentity = checkBodyIdentity(repository, repoRelativePath, file, documentText, charset) {}

/** [afterStatus] runs between G8c and the re-read; tests use it to rewrite the file mid-check. */
internal fun checkBodyIdentity(
  repository: Repository,
  repoRelativePath: String,
  file: File,
  documentText: String,
  charset: Charset,
  afterStatus: () -> Unit,
): BodyIdentity = try {
  evaluate(repository, repoRelativePath, file, documentText, charset, afterStatus)
    ?.let { BodyIdentity.Different(it) } ?: BodyIdentity.Same
} catch (_: IOException) {
  BodyIdentity.Different(BodyMismatch.UNREADABLE)
} catch (_: GitAPIException) {
  BodyIdentity.Different(BodyMismatch.UNREADABLE)
} catch (_: JGitInternalException) {
  BodyIdentity.Different(BodyMismatch.UNREADABLE)
} catch (_: NoWorkTreeException) {
  BodyIdentity.Different(BodyMismatch.UNREADABLE)
} catch (_: IllegalArgumentException) {
  BodyIdentity.Different(BodyMismatch.UNREADABLE)
} catch (e: CancellationException) {
  throw e
} catch (@Suppress("TooGenericExceptionCaught") _: RuntimeException) {
  // Any other JGit runtime failure fails closed rather than escaping to the caller.
  BodyIdentity.Different(BodyMismatch.UNREADABLE)
}

/** The first failing gate's mismatch, or `null` when every gate passes. */
private fun evaluate(
  repository: Repository,
  path: String,
  file: File,
  documentText: String,
  charset: Charset,
  afterStatus: () -> Unit,
): BodyMismatch? {
  if (file.canonicalFile != File(repository.workTree, path).canonicalFile) return BodyMismatch.UNREADABLE
  return indexGates(repository, path) ?: diskGates(repository, path, file, documentText, charset, afterStatus)
}

/** G8a'', G8a' and G8a: what HEAD, the index and the attributes say about [path]. */
private fun indexGates(repository: Repository, path: String): BodyMismatch? {
  val dirCache = repository.readDirCache()
  val indexEntry = dirCache.getEntry(path)
  return when {
    indexEntry == null || !isRegular(headFileMode(repository, path)) || !isRegular(indexEntry.fileMode) ->
      BodyMismatch.NOT_REGULAR_FILE
    indexEntry.isAssumeValid || indexEntry.isSkipWorkTree -> BodyMismatch.INDEX_FLAG
    attributes(repository, dirCache, path).let { attrs -> REWRITING_ATTRIBUTES.any { attrs.isSetOrValued(it) } } ->
      BodyMismatch.FILTER_ATTRIBUTE
    else -> null
  }
}

/** G8b, G8c and the G8b re-read. */
private fun diskGates(
  repository: Repository,
  path: String,
  file: File,
  documentText: String,
  charset: Charset,
  afterStatus: () -> Unit,
): BodyMismatch? {
  if (LONE_CARRIAGE_RETURN.containsMatchIn(documentText)) return BodyMismatch.UNSUPPORTED_LINE_DELIMITER
  val firstRead = file.readBytes()
  return when {
    String(firstRead, charset).removePrefix(BYTE_ORDER_MARK.toString()) != documentText -> BodyMismatch.DISK_TEXT_DIFFERS
    !isStatusClean(repository, path) -> BodyMismatch.STATUS_NOT_CLEAN
    else -> {
      afterStatus()
      if (file.readBytes().contentEquals(firstRead)) null else BodyMismatch.DISK_TEXT_DIFFERS
    }
  }
}

private fun isRegular(mode: FileMode?) = mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE

/** The path's mode in the HEAD tree, or `null` when HEAD is unborn or lacks the path. */
private fun headFileMode(repository: Repository, path: String): FileMode? {
  val head = repository.resolve(Constants.HEAD) ?: return null
  return RevWalk(repository).use { revWalk ->
    val tree = revWalk.parseCommit(head).tree
    TreeWalk.forPath(repository, path, tree)?.use { it.getFileMode(0) }
  }
}

/** Check-in attributes for [path], from working-tree and index `.gitattributes`, `info/attributes` and global. */
private fun attributes(repository: Repository, dirCache: DirCache, path: String): Attributes =
  TreeWalk(repository).use { walk ->
    walk.operationType = OperationType.CHECKIN_OP
    walk.addTree(FileTreeIterator(repository))
    walk.addTree(DirCacheIterator(dirCache))
    walk.filter = PathFilter.create(path)
    walk.isRecursive = true
    while (walk.next()) {
      if (walk.pathString == path) return@use walk.attributes
    }
    Attributes()
  }

private fun Attributes.isSetOrValued(key: String): Boolean =
  when (getState(key)) {
    Attribute.State.SET, Attribute.State.CUSTOM -> true
    Attribute.State.UNSET, Attribute.State.UNSPECIFIED, null -> false
  }

private fun isStatusClean(repository: Repository, path: String): Boolean {
  val status = Git.wrap(repository).status().addPath(path).call()
  return listOf(
    status.added,
    status.changed,
    status.removed,
    status.modified,
    status.missing,
    status.untracked,
    status.conflicting,
  ).none { path in it }
}
