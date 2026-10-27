package com.gitlab.eclipse.ci.joblog

import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.commands.HandlerEvent
import org.eclipse.core.expressions.IEvaluationContext
import org.eclipse.swt.widgets.Display
import org.eclipse.ui.IEditorPart
import org.eclipse.ui.ISources
import org.eclipse.ui.handlers.HandlerUtil

/** True when [input] belongs to a job log whose session can retry its job now ([JobLogSession.canRetry]). */
internal fun jobLogRetryEnabled(input: JobLogEditorInput?, sessionOf: (JobLogKey) -> JobLogSession?): Boolean =
  input?.key?.let(sessionOf)?.canRetry == true

/**
 * "Retry Job from Log" (L4): retries the job of the active job log editor ([JobLogRetry]), from its
 * toolbar or context menu; on success the new job's log replaces the editor (D-3 (b)). Not in VS
 * Code, whose log viewer has no Retry.
 *
 * Enabled only while the active job log's session can retry. The handler decides that itself, in
 * [setEnabled] (the active editor of the evaluation context), and announces a change of it when a
 * session's Retry state changes ([JobLogRetryEnablement]): a job finishing fires nothing the
 * handler service would re-evaluate on. Asked off the UI thread, it keeps its last answer and
 * touches nothing. Pressing re-checks, so a stale enablement never retries twice.
 */
@Suppress("unused")
class RetryJobFromLogHandler internal constructor(
  private val sessionOf: (JobLogKey) -> JobLogSession?,
  private val inputOf: (Any?) -> JobLogEditorInput?,
  private val retry: (IEditorPart, JobLogEditorInput) -> Unit,
  private val onUiThread: () -> Boolean,
) : AbstractHandler() {
  constructor() : this(
    JobLogSessionManager::sessionFor,
    { (it as? JobLogEditor)?.jobLogInput },
    ::retryFromEditor,
    { Display.getCurrent() != null },
  )

  // The key the last evaluation saw, so that a change can be recomputed without a context.
  private var shownKey: JobLogKey? = null

  /** Runs on the UI thread, where sessions change their Retry state. */
  private val enablementChanged: () -> Unit = {
    setBaseEnabled(shownKey?.let(sessionOf)?.canRetry == true)
    fireHandlerChanged(HandlerEvent(this, true, false))
  }

  init {
    JobLogRetryEnablement.addListener(enablementChanged)
  }

  override fun setEnabled(evaluationContext: Any?) {
    if (!onUiThread()) return
    val editor = (evaluationContext as? IEvaluationContext)?.getVariable(ISources.ACTIVE_EDITOR_NAME)
    val input = inputOf(editor)
    shownKey = input?.key
    setBaseEnabled(jobLogRetryEnabled(input, sessionOf))
  }

  override fun execute(event: ExecutionEvent): Any? {
    val editor = HandlerUtil.getActiveEditor(event) ?: return null
    val input = inputOf(editor) ?: return null
    retry(editor, input)
    return null
  }

  override fun dispose() {
    JobLogRetryEnablement.removeListener(enablementChanged)
    super.dispose()
  }
}

/** UI thread. Nothing happens when the job cannot be retried now ([JobLogRetry.press] says false). */
private fun retryFromEditor(editor: IEditorPart, input: JobLogEditorInput) {
  JobLogRetry().press(input, closeOrigin = { editor.site.page.closeEditor(editor, false) }, setBusy = {})
}
