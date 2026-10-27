package com.gitlab.eclipse.chat.quickchat.fix.ui

import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.chat.quickchat.duoChatAvailability
import com.gitlab.eclipse.chat.quickchat.fix.FixDiagnostic
import com.gitlab.eclipse.chat.quickchat.fix.FixMessage
import com.gitlab.eclipse.chat.quickchat.fix.FixRequest
import com.gitlab.eclipse.chat.quickchat.fix.MarkerFacts
import com.gitlab.eclipse.chat.quickchat.fix.TextSpan
import com.gitlab.eclipse.chat.quickchat.fix.fixRange
import com.gitlab.eclipse.chat.quickchat.fix.fixWithDuo
import com.gitlab.eclipse.chat.quickchat.fix.isFixEligible
import com.gitlab.eclipse.chat.quickchat.fix.lineSpanOf
import com.gitlab.eclipse.chat.quickchat.ui.QuickChatPopups
import com.gitlab.eclipse.chat.quickchat.ui.QuickChatUiTexts
import com.gitlab.eclipse.chat.quickchat.ui.anchorLineOf
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IMarker
import org.eclipse.core.runtime.Adapters
import org.eclipse.jface.text.ITextSelection
import org.eclipse.swt.graphics.Image
import org.eclipse.swt.widgets.Display
import org.eclipse.ui.IMarkerResolution
import org.eclipse.ui.IMarkerResolution2
import org.eclipse.ui.IMarkerResolutionGenerator2
import org.eclipse.ui.IMarkerResolutionRelevance
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.ide.IDE
import org.eclipse.ui.texteditor.ITextEditor

private val logger by lazy { logger<FixWithDuoMarkerResolution>() }

/**
 * Offers [FixWithDuoMarkerResolution] on the Problems view's Quick Fix and the editor's Ctrl+1 (design §5.1).
 * Registered without a `markerType` (plugin.xml): the extension matches one exact type only, so
 * [hasResolutions] narrows to problem markers on files with severity ERROR / WARNING (L2).
 * Problems asks per row on the UI thread: the cheap Duo Chat check runs first and nothing throws.
 */
class FixWithDuoMarkerResolutionGenerator(
  private val chatEnabled: () -> Boolean = { service<DuoChatStateService>().isEnabled },
  private val resolution: () -> IMarkerResolution = { FixWithDuoMarkerResolution() },
) : IMarkerResolutionGenerator2 {
  override fun hasResolutions(marker: IMarker): Boolean = isFixEligible(chatEnabled) { factsOf(marker) }

  override fun getResolutions(marker: IMarker): Array<IMarkerResolution> =
    if (hasResolutions(marker)) arrayOf(resolution()) else emptyArray()

  /** Cheapest first: a marker that no longer exists is not read any further. */
  private fun factsOf(marker: IMarker): MarkerFacts {
    if (!marker.exists()) return GONE
    return MarkerFacts(
      exists = true,
      isProblem = marker.isSubtypeOf(IMarker.PROBLEM),
      severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO),
      onFile = marker.resource is IFile,
    )
  }

  private companion object {
    val GONE = MarkerFacts(exists = false, isProblem = false, severity = IMarker.SEVERITY_INFO, onFile = false)
  }
}

/**
 * "Fix with GitLab Duo" on one marker. [run] only schedules [fix] (L7): the Quick Fix dialog and
 * the Ctrl+1 proposal still own the UI turn that calls it, so nothing opens inside it.
 */
class FixWithDuoMarkerResolution(
  private val schedule: (Runnable) -> Unit = ::asyncOnWorkbenchDisplay,
  private val fix: (IMarker) -> Unit = ::fixMarker,
) : IMarkerResolution2, IMarkerResolutionRelevance {
  override fun getLabel(): String = FixMessage.LABEL

  override fun getDescription(): String = DESCRIPTION

  override fun getImage(): Image? = null

  /** The platform default: never ranked above the language's own fixes. */
  override fun getRelevanceForResolution(): Int = RELEVANCE

  override fun run(marker: IMarker) = schedule { fix(marker) }

  companion object {
    const val DESCRIPTION = "Opens Quick Chat at this problem and asks GitLab Duo to fix it."
    const val RELEVANCE = 0
  }
}

/** Runs [runnable] later on the workbench's display, after the caller's UI turn. */
internal fun asyncOnWorkbenchDisplay(runnable: Runnable) {
  (Display.getCurrent() ?: PlatformUI.getWorkbench().display).asyncExec(runnable)
}

/** UI thread (inside [asyncOnWorkbenchDisplay]). Re-checks Duo Chat, opens the marker's editor and sends `/fix`. */
internal fun fixMarker(marker: IMarker) {
  fixWithDuo(
    unavailableReason = duoChatAvailability(service()),
    notify = NotificationUtils::showOnUiThread,
    prepare = { prepareMarker(marker) },
    send = { QuickChatPopups.openAndSend(it.editor, it.oneBasedLine, it.message) },
  )
}

/**
 * Opens [marker]'s editor at the marker, selects the range to fix (VS Code `#getCodeActionRange`)
 * and builds the request; null when the marker or a workbench page is gone, the editor is not a
 * text editor (notified) or anything fails (logged by exception type only).
 */
private fun prepareMarker(marker: IMarker): FixRequest<ITextEditor>? = try {
  prepareOpenedMarker(marker)
} catch (e: Exception) {
  logger.warn("Fix with GitLab Duo could not prepare the marker: exceptionType=${e.javaClass.name}")
  null
}

private fun prepareOpenedMarker(marker: IMarker): FixRequest<ITextEditor>? {
  if (!marker.exists()) return null
  val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage ?: return null
  val part = IDE.openEditor(page, marker, true) ?: return null
  val editor = Adapters.adapt(part, ITextEditor::class.java)
  if (editor == null) {
    NotificationUtils.showOnUiThread(QuickChatUiTexts.FIX_NOT_TEXT_EDITOR)
    return null
  }
  val document = editor.documentProvider?.getDocument(editor.editorInput) ?: return null
  // IDE.openEditor ran gotoMarker: the selection is the marker's range, or its line's start without one.
  val selection = editor.selectionProvider?.selection as? ITextSelection ?: return null
  val hasChars = marker.getAttribute(IMarker.CHAR_START, -1) >= 0 && marker.getAttribute(IMarker.CHAR_END, -1) >= 0
  val target = if (hasChars) TextSpan(selection.offset, selection.length) else TextSpan(selection.offset, 0)
  val lineSpan = lineSpanOf(document::getLineOfOffset, document::getLineOffset) { document.getLineInformation(it).length }
  val range = fixRange(null, target, lineSpan)
  editor.selectAndReveal(range.offset, range.length)
  val line = anchorLineOf(editor) ?: return null
  val diagnostic = FixDiagnostic(marker.getAttribute(IMarker.MESSAGE, ""), document.getLineOfOffset(target.offset) + 1)
  return FixRequest(editor, line, FixMessage.of(listOf(diagnostic)))
}
