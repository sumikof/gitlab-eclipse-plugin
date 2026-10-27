package com.gitlab.eclipse.ci.joblog

import org.eclipse.jface.text.IDocument
import java.util.IdentityHashMap

/**
 * One viewer showing a job-log document: the document shared by the key's editors (L-B2-1 (A)) or
 * one of its own (B). [JobLogUiApplier] changes each document once and decorates every viewer on
 * it. UI thread only.
 */
internal interface JobLogTextSurface {
  val document: IDocument

  /** The first visible line is the widget's top line; kept across a [JobLogUpdate.Full] when not following. */
  var topIndex: Int

  /** [isTailVisible] for the viewer's bottom line. */
  fun isTailVisible(): Boolean

  /** Switches to [runs] and forgets what was colored from [from] on. Invalidates nothing. */
  fun swapRuns(runs: StyleRunIndex, from: Int)

  /** Invalidates only the visible parts that are not colored yet. */
  fun refreshVisible()

  /** Adds fold annotations for [folds]; the existing ones (and how the user left them) stay. */
  fun addFolds(folds: List<FoldRegion>)

  /** The folds and colors of a [JobLogUpdate.Full] whose text the document already holds. */
  fun applyRendered(result: RenderResult)

  /** Scrolls to the end of the document without moving the caret or the selection. */
  fun revealEnd()
}

/**
 * Puts a [JobLogUpdate] on the screen (design §5.3, R-B2-7). The document is changed once per
 * document, then every surface showing it gets its runs, folds and scrolling. Only a surface whose
 * last line was on screen before the change follows the tail, so scrolling up stops the following
 * and coming back to the end resumes it. UI thread only.
 */
internal object JobLogUiApplier {
  /**
   * [followOnFull] is the first [JobLogUpdate.Full] of a live job: it opens at the end, as in VS Code.
   * @return false when an [JobLogUpdate.Append] does not fit a document (the session then renders
   *   from scratch); nothing is changed in that case.
   */
  fun apply(update: JobLogUpdate, surfaces: List<JobLogTextSurface>, followOnFull: Boolean): Boolean {
    val groups = byDocument(surfaces)
    return when (update) {
      is JobLogUpdate.Append -> {
        if (groups.any { !fits(update.replaceFrom, it.first().document) }) return false
        groups.forEach { append(update, it) }
        true
      }
      is JobLogUpdate.Full -> {
        groups.forEach { full(update.result, it, followOnFull) }
        true
      }
    }
  }

  private fun append(update: JobLogUpdate.Append, group: List<JobLogTextSurface>) {
    val document = group.first().document
    val from = update.replaceFrom
    val wasTail = group.map { it.isTailVisible() }
    // Before the replace: the presentation it triggers synchronously must already use the new runs.
    group.forEach { it.swapRuns(update.runs, from) }
    document.replace(from, document.length - from, update.text)
    group.forEach {
      it.addFolds(update.newFolds)
      it.refreshVisible()
    }
    // After the folds: a section closed just now moves the lines up.
    group.forEachIndexed { i, surface -> if (wasTail[i]) surface.revealEnd() }
  }

  private fun full(result: RenderResult, group: List<JobLogTextSurface>, followOnFull: Boolean) {
    val document = group.first().document
    // An empty document is the loading page: only followOnFull decides where a new log opens.
    val hadText = document.length > 0
    val wasTail = group.map { hadText && it.isTailVisible() }
    val tops = group.map { it.topIndex }
    val text = result.text
    if (document.length != text.length || document.get() != text) document.set(text)
    group.forEach { it.applyRendered(result) }
    group.forEachIndexed { i, surface ->
      if (wasTail[i] || followOnFull) {
        surface.revealEnd()
      } else {
        surface.topIndex = minOf(tops[i], document.numberOfLines - 1).coerceAtLeast(0)
      }
    }
  }

  // R2-4: an append never starts inside the omitted-output header or past the end.
  private fun fits(replaceFrom: Int, document: IDocument): Boolean =
    replaceFrom in headerLength(document)..document.length

  private fun headerLength(document: IDocument): Int {
    if (document.length < OMITTED_HEADER.length || document.get(0, OMITTED_HEADER.length) != OMITTED_HEADER) return 0
    return document.getLineLength(0)
  }

  private fun byDocument(surfaces: List<JobLogTextSurface>): List<List<JobLogTextSurface>> {
    val groups = IdentityHashMap<IDocument, MutableList<JobLogTextSurface>>()
    val order = ArrayList<MutableList<JobLogTextSurface>>()
    for (surface in surfaces) {
      groups.getOrPut(surface.document) { ArrayList<JobLogTextSurface>().also { order += it } }.add(surface)
    }
    return order
  }
}

/**
 * True when the line holding the document's last character (or the line before an empty last line)
 * reaches the screen, [bottomIndexEndOffset] being the model end offset of the viewer's bottom line.
 */
internal fun isTailVisible(bottomIndexEndOffset: Int, document: IDocument): Boolean {
  val length = document.length
  if (length == 0) return true
  val lastLine = document.numberOfLines - 1
  val lastStart = document.getLineOffset(lastLine)
  val tailStart = if (lastStart == length && lastLine > 0) document.getLineOffset(lastLine - 1) else lastStart
  return bottomIndexEndOffset >= tailStart
}
