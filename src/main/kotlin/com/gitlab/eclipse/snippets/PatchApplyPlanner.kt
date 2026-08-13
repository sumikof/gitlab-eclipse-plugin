package com.gitlab.eclipse.snippets

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.patch.FileHeader
import org.eclipse.jgit.patch.Patch
import org.eclipse.jgit.patch.PatchApplier
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.nio.charset.StandardCharsets

/** What a single path in the change set becomes. RENAME/COPY never appear — see [PatchApplyPlanner]. */
enum class PatchChangeKind { ADD, MODIFY, DELETE }

/**
 * One path the patch changes, with its post-image already materialised.
 *
 * [content] is the literal bytes to put on disk: for [FileMode.SYMLINK] that is the link target
 * string, not a file body. It is null exactly when [kind] is [PatchChangeKind.DELETE], as is [mode].
 * The content is carried in memory rather than as a blob id so the object database stays untouched
 * until the write phase has fully succeeded (design §12.2).
 */
data class PatchChange(
  val path: String,
  val kind: PatchChangeKind,
  val content: ByteArray?,
  val mode: FileMode?,
) {
  // Generated equals/hashCode would compare the ByteArray by identity; tests and dedup need value
  // semantics, and detekt flags a data class with an array property that does not override these.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is PatchChange) return false
    return path == other.path &&
      kind == other.kind &&
      mode == other.mode &&
      content.contentEquals(other.content)
  }

  override fun hashCode(): Int {
    var result = path.hashCode()
    result = 31 * result + kind.hashCode()
    result = 31 * result + (mode?.hashCode() ?: 0)
    result = 31 * result + content.contentHashCode()
    return result
  }
}

/** Outcome of [PatchApplyPlanner.plan]. Only [Ok] permits the working tree to be touched. */
sealed interface PatchPlan {
  data class Ok(val changes: List<PatchChange>, val headTreeId: ObjectId) : PatchPlan

  /** No commit to apply against. */
  data object NoHead : PatchPlan

  /** The text parsed to no files, or applying it produced no difference from HEAD. */
  data object Empty : PatchPlan

  /** A20: the patch carries a binary hunk, which this feature never applies. */
  data object BinaryNotSupported : PatchPlan

  /** A23: the patch touches a submodule; fetching one is out of scope. */
  data object GitlinkNotSupported : PatchPlan

  /** The text is not a well-formed patch. */
  data class Malformed(val errorCount: Int) : PatchPlan

  /** The patch does not apply to HEAD. [errorCount] is a count only — the messages quote paths. */
  data class ApplyFailed(val errorCount: Int) : PatchPlan

  /** A19: [count] target paths differ between the index and HEAD, so applying would lose them. */
  data class StagedChanges(val count: Int) : PatchPlan

  /** The post-images would not fit in the backup area, so the apply must not begin. */
  data class TooLarge(val bytes: Long) : PatchPlan
}

/**
 * Works out what a patch would do, without touching the working tree or the index (design §9.4-5).
 *
 * JGit's [PatchApplier] has a working-tree mode that writes files and commits the index itself;
 * that mode cannot mediate the individual writes and rewrites the index even when it collected
 * errors (design §12.1 / §12.2). So the in-core constructor is used instead: it produces a tree
 * object and nothing else. Diffing that tree against HEAD yields exactly the set of writes to make,
 * which [WorkTreePatchWriter] then performs itself.
 *
 * Blocking local I/O — call from a background thread. Never throws for a bad patch; every rejection
 * is a [PatchPlan] value. Never puts a JGit message in a return value or a log (A9).
 */
class PatchApplyPlanner {
  fun plan(repo: Repository, patchText: String): PatchPlan {
    val bytes = patchText.toByteArray(StandardCharsets.UTF_8)
    val patch = Patch().apply { parse(bytes, 0, bytes.size) }
    if (patch.errors.isNotEmpty()) return PatchPlan.Malformed(patch.errors.size)
    if (patch.files.isEmpty()) return PatchPlan.Empty
    // A20. F3 rejects on the formatter's output markers because the working-tree blob is not in the
    // object database; here the patch itself is parsed, so its declared type is the exact signal.
    if (patch.files.any { it.patchType != FileHeader.PatchType.UNIFIED }) {
      return PatchPlan.BinaryNotSupported
    }
    // A23, checked on the parsed headers so the rejection lands before PatchApplier runs at all.
    if (patch.files.any { it.oldMode == FileMode.GITLINK || it.newMode == FileMode.GITLINK }) {
      return PatchPlan.GitlinkNotSupported
    }
    val headTreeId = repo.resolve(HEAD_TREE) ?: return PatchPlan.NoHead
    return RevWalk(repo).use { walk ->
      repo.newObjectInserter().use { inserter ->
        val applied = PatchApplier(repo, walk.parseTree(headTreeId), inserter).applyPatch(patch)
        if (applied.errors.isNotEmpty()) {
          // Error.toString() carries oldFileName; only the count ever leaves this class (A9).
          PatchPlan.ApplyFailed(applied.errors.size)
        } else {
          inserter.newReader().use { reader ->
            changeSet(repo, reader, headTreeId, applied.treeId)
          }
        }
      }
    }
  }

  /**
   * Turns "HEAD tree vs applied tree" into the list of writes to perform.
   *
   * Rename detection is deliberately off: with it on, a moved file arrives as a single RENAME entry
   * and the deletion of the old path stops being an explicit write. As ADD + DELETE both paths get
   * their own comparison, backup and rollback (design §9.4.1).
   */
  private fun changeSet(
    repo: Repository,
    reader: ObjectReader,
    headTreeId: ObjectId,
    appliedTreeId: ObjectId,
  ): PatchPlan {
    val entries = DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
      formatter.setReader(reader, repo.config)
      formatter.setDetectRenames(false)
      formatter.scan(headTreeId, appliedTreeId)
    }
    if (entries.isEmpty()) return PatchPlan.Empty
    // Belt and braces with the header check above: a tree entry could reach GITLINK without the
    // patch declaring the mode on a header this parser recognised.
    if (entries.any { it.oldMode == FileMode.GITLINK || it.newMode == FileMode.GITLINK }) {
      return PatchPlan.GitlinkNotSupported
    }

    var total = 0L
    val changes = entries.map { entry ->
      if (entry.changeType == DiffEntry.ChangeType.DELETE) {
        PatchChange(entry.oldPath, PatchChangeKind.DELETE, null, null)
      } else {
        val content = reader.open(entry.newId.toObjectId()).cachedBytes
        total += content.size
        if (total > PatchQuarantine.MAX_TOTAL_BYTES) return PatchPlan.TooLarge(total)
        val kind =
          if (entry.changeType == DiffEntry.ChangeType.ADD) PatchChangeKind.ADD else PatchChangeKind.MODIFY
        PatchChange(entry.newPath, kind, content, entry.newMode)
      }
    }

    // A19, and it has to be the last gate: applying over a staged change would make the index
    // update below discard it. Nothing has been written at this point.
    val staged = PatchIndexUpdater.countIndexEntriesDifferingFromHead(
      repo,
      changes.map { it.path },
      headTreeId,
    )
    if (staged > 0) return PatchPlan.StagedChanges(staged)

    return PatchPlan.Ok(changes, headTreeId)
  }

  private companion object {
    private const val HEAD_TREE = "HEAD^{tree}"
  }
}
