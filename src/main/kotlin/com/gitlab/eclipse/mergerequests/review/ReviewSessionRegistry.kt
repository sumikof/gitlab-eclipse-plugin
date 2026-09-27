package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.mergerequests.discussions.DiscussionGenerationRegistry
import com.gitlab.eclipse.mergerequests.review.ReviewSessionState.BeginResult
import com.gitlab.eclipse.mergerequests.review.ReviewSessionState.Phase
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.inlinethread.ThreadAnnotationAttacher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.jface.text.IDocument
import org.eclipse.swt.SWTException
import org.eclipse.ui.texteditor.ITextEditor

/**
 * The UI-thread shell around [ReviewSessionState] (design §8.2, §9.1, §9.5, §9.6, FR-11): runs
 * the background load of a session, attaches the resulting annotations to the document, keeps the
 * ruler listeners of the connected editors, and releases all of it when the last editor of a
 * document closes or the bundle stops.
 *
 * **UI-thread confined**, like [DiscussionGenerationRegistry] whose lifecycle it shares: every
 * public method and every callback runs on the UI thread; only [ReviewSessionLoader.load] runs on
 * the shared background scope, and its result is marshaled back with `asyncExec`. On arrival the
 * runnable re-checks that the display is alive, that the bundle is still active
 * ([DiscussionGenerationRegistry.active], flipped on the UI thread by the stop hook) and — through
 * [ReviewSessionState.onLoaded] / [ReviewSessionState.onLoadFailed] — that the generation is still
 * the latest and an editor is still connected (design §17, latest-wins). Never `syncExec`, never a
 * network wait on the UI thread.
 *
 * The session key is the editor's [IDocument] (design §8.2): editors on the same file share one
 * document through the file-buffer document provider, so they share one session.
 *
 * ### Integration points for the popup layer
 * - [threadOpener]: invoked on a ruler left-click on a line that currently carries a thread
 *   annotation (`(editor, oneBasedLine)`, UI thread). The line is the clicked one — annotations
 *   follow edits (E4) while [ReviewSessionSnapshot.placements] keep the loaded lines.
 * - [onEditorReleased]: invoked once when a connected editor closes (or its input changed), so a
 *   popup anchored to that editor can close (design §9.6). It runs after this registry's own
 *   bookkeeping for that close: the editor is no longer tracked ([snapshotFor] returns `null` for
 *   it — use the document argument) and, if it was the last editor, the annotations are already gone.
 * - [onSessionReleased]: invoked when a document's session ends — its last editor closed, another
 *   identity replaced it (design §9.5 step 3), or the bundle stopped — after its annotations are gone.
 * All three are optional, are not reset by [clear], and are called under a guard: an exception from
 * a hook is logged (class name only) and never disturbs the session, listener or sub-model bookkeeping.
 */
object ReviewSessionRegistry {
  private val logger by lazy { logger<ReviewSessionRegistry>() }
  private val coroutineScope by lazyService<CoroutineScope>()
  private val loader by lazy { ReviewSessionLoader() }
  private val state = ReviewSessionState<IDocument, ITextEditor>()
  private val attacher = ThreadAnnotationAttacher()
  private val tracker = ReviewEditorTracker(
    onEditorClosed = ::handleEditorClosed,
    onRulerClick = { editor, document, oneBasedLine ->
      if (DiscussionGenerationRegistry.active && attacher.hasAnnotationAt(document, oneBasedLine)) {
        guarded("threadOpener hook") { threadOpener?.invoke(editor, oneBasedLine) }
      }
    },
  )

  /** What a document's session was begun with; a reload (FR-10) loads the same thing again. */
  private class Origin(val ref: MergeRequestRef, val relPath: String, val headSha: String) {
    val identity: SessionIdentity
      get() = SessionIdentity(ref.instanceUrl, ref.authFingerprint, ref.projectId, ref.mrIid, headSha, relPath)
  }

  private val origins = HashMap<IDocument, Origin>()

  /** See the class comment. Set by the popup layer; `null` means ruler clicks do nothing. */
  var threadOpener: ((editor: ITextEditor, oneBasedLine: Int) -> Unit)? = null

  /** See the class comment. */
  var onEditorReleased: ((editor: ITextEditor, document: IDocument) -> Unit)? = null

  /** See the class comment. */
  var onSessionReleased: ((document: IDocument) -> Unit)? = null

  /**
   * UI thread. Connects [editor] to the review session of its document for the MR [ref], the
   * file's repository-relative path [relPath] (the diff entry's `new_path`) and the head commit
   * [expectedHeadSha] the working tree was checked against (design §9.1 [UI], FR-1 / FR-2). The
   * identity is built from the same [ref] instance the loader receives, so the loaded snapshot's
   * identity equals it. Starts the background load when the state asks for one; a session with
   * another identity on the same document is replaced first (its annotations removed and
   * [onSessionReleased] fired, design §9.5). Ignored once the bundle is deactivated or when the
   * editor has no document.
   */
  fun begin(editor: ITextEditor, ref: MergeRequestRef, relPath: String, expectedHeadSha: String) {
    if (!DiscussionGenerationRegistry.active) return
    val document = editor.documentProvider?.getDocument(editor.editorInput)
    if (document == null) {
      logger.warn("reviewSession begin skipped: the editor has no document.")
      return
    }
    tracker.track(editor, document)
    val origin = Origin(ref, relPath, expectedHeadSha)
    val previous = origins.put(document, origin)
    if (previous != null && previous.identity != origin.identity) {
      attacher.detach(document)
      guarded("onSessionReleased hook") { onSessionReleased?.invoke(document) }
      logger.info("reviewSession replaced: another identity for the same document.")
    }
    when (val result = state.begin(document, editor, origin.identity)) {
      is BeginResult.Joined -> logger.info("reviewSession joined: phase=${state.phase(document)}")
      is BeginResult.StartLoad -> launchLoad(document, result.generation, origin, notifyPartial = true)
    }
  }

  /**
   * UI thread. Reloads the session of [editor]'s document after a write (FR-10, design §9.4),
   * provided the document still has a session for [identity]. Returns `false` when it does not
   * (replaced or released: the reload is skipped, design §9.5) or the bundle is deactivated. The
   * current annotations stay until the reload lands; a failed reload keeps them and notifies once.
   */
  fun refresh(editor: ITextEditor, identity: SessionIdentity): Boolean {
    if (!DiscussionGenerationRegistry.active) return false
    val document = tracker.documentOf(editor) ?: return false
    val origin = origins[document]?.takeIf { it.identity == identity } ?: return false
    val generation = state.refresh(document, identity) ?: return false
    launchLoad(document, generation, origin, notifyPartial = false)
    return true
  }

  /** UI thread. The last snapshot applied to [editor]'s document, or `null` (no session, or not loaded yet). */
  fun snapshotFor(editor: ITextEditor): ReviewSessionSnapshot? = tracker.documentOf(editor)?.let(state::snapshot)

  /**
   * UI thread, from the stop hook (`GitLabEclipseStartup.shutdownJobLog`, in the same `syncExec`
   * that deactivates [DiscussionGenerationRegistry]). Releases every editor (ruler and part
   * listeners), every session and every sub-model, firing [onEditorReleased] / [onSessionReleased]
   * as for a close (FR-11). Never throws, and no step depends on the previous one having
   * succeeded: a failing hook or listener removal is logged and the remaining editors, sessions
   * and sub-models are still released.
   */
  fun clear() {
    tracker.releaseAll()
    val leftovers = guarded("clear sessions") { state.clear() }.orEmpty()
    guarded("detach annotations") { attacher.detachAll() }
    origins.clear()
    leftovers.forEach { document -> guarded("onSessionReleased hook") { onSessionReleased?.invoke(document) } }
    logger.info("reviewSession cleared.")
  }

  /**
   * Runs [block], logging (class name only) and swallowing any exception, so that bookkeeping
   * never depends on foreign code — the popup hooks — or on a widget's state at shutdown.
   */
  private inline fun <T> guarded(step: String, block: () -> T): T? = try {
    block()
  } catch (e: Exception) {
    logger.error("reviewSession $step failed: exceptionType=${e.javaClass.name}")
    null
  }

  private fun launchLoad(document: IDocument, generation: Long, origin: Origin, notifyPartial: Boolean) {
    coroutineScope.launch {
      // Shared scope with a plain Job (see OpenMrFileHandler): nothing may escape this launch.
      try {
        val result = loader.load(origin.ref, origin.relPath, origin.headSha, conn = null)
        val display = currentDisplay
        display.asyncExec {
          if (display.isDisposed || !DiscussionGenerationRegistry.active) return@asyncExec
          try {
            when (result) {
              is LoadResult.Loaded -> applyLoaded(document, generation, result, notifyPartial)
              is LoadResult.Refused -> applyRefused(document, generation, result)
            }
          } catch (e: Exception) {
            logger.error("reviewSession apply failed: exceptionType=${e.javaClass.name}")
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: SWTException) {
        /* display disposed between lookup and asyncExec: the workbench is going away */
      } catch (_: IllegalStateException) {
        /* workbench torn down (off-thread display lookup): nothing left to show */
      } catch (e: Exception) {
        logger.error("reviewSession load failed: exceptionType=${e.javaClass.name}")
      }
    }
  }

  /** UI thread. Design §9.1 [UI]: apply only when still latest and still connected (the state decides). */
  private fun applyLoaded(document: IDocument, generation: Long, result: LoadResult.Loaded, notifyPartial: Boolean) {
    val editors = state.onLoaded(document, generation, result.snapshot)
    if (editors == null) {
      logger.info("reviewSession load discarded: superseded or released.")
      return
    }
    val annotations = lineAnnotationsOf(result.snapshot.placements)
    val model = editors.firstNotNullOfOrNull { it.documentProvider?.getAnnotationModel(it.editorInput) }
    val shown = attacher.replace(document, model, annotations)
    logger.info(
      "reviewSession loaded: annotations=${annotations.size} shown=$shown" +
        " editors=${editors.size} complete=${result.complete}",
    )
    // Design §9.1.1: a partial fetch is shown, and said once, at session establishment.
    if (notifyPartial && !result.complete) NotificationUtils.showOnUiThread(PARTIAL_LOAD_MESSAGE)
  }

  /**
   * UI thread. The state ignores a stale generation silently, so "applied" is read off the phase:
   * a pending latest generation always belongs to a LOADING or READY entry (a FAILED entry has no
   * pending latest load), and only the latest generation's failure turns it FAILED. Only an
   * applied failure is worth a notification; a superseded one is logged and dropped.
   */
  private fun applyRefused(document: IDocument, generation: Long, result: LoadResult.Refused) {
    val wasFailed = state.phase(document) == Phase.FAILED
    state.onLoadFailed(document, generation)
    val applied = !wasFailed && state.phase(document) == Phase.FAILED
    val cause = result.cause
    if (cause == null) {
      logger.info("reviewSession refused: applied=$applied reason=${result.reason}")
    } else {
      // Class name only — never cause.message, which can carry server text (design §19).
      logger.error("reviewSession load failed: applied=$applied exceptionType=${cause.javaClass.name}")
    }
    if (applied) NotificationUtils.showOnUiThread(result.reason)
  }

  /** UI thread, from the tracker. Design §9.6: the last editor releases the session, whatever its phase. */
  private fun handleEditorClosed(editor: ITextEditor, document: IDocument) {
    // Own bookkeeping first, hooks last and guarded: a throwing popup hook must not leave the
    // editor counted, the sub-model attached or the origin alive until bundle stop.
    val hadSession = state.phase(document) != null
    state.editorClosed(document, editor)
    val released = hadSession && state.phase(document) == null
    if (released) {
      origins.remove(document)
      attacher.detach(document)
    }
    guarded("onEditorReleased hook") { onEditorReleased?.invoke(editor, document) }
    if (released) {
      guarded("onSessionReleased hook") { onSessionReleased?.invoke(document) }
      logger.info("reviewSession released: last editor closed.")
    }
  }

  /** Fixed text, no server data (design §9.1.1). */
  const val PARTIAL_LOAD_MESSAGE =
    "Some merge request threads could not be loaded for this file; check the merge request in GitLab."
}
