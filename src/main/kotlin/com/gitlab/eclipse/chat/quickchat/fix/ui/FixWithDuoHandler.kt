package com.gitlab.eclipse.chat.quickchat.fix.ui

import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.chat.quickchat.duoChatAvailability
import com.gitlab.eclipse.chat.quickchat.fix.AnnotationFacts
import com.gitlab.eclipse.chat.quickchat.fix.FixMessage
import com.gitlab.eclipse.chat.quickchat.fix.FixRequest
import com.gitlab.eclipse.chat.quickchat.fix.TextSpan
import com.gitlab.eclipse.chat.quickchat.fix.fixRange
import com.gitlab.eclipse.chat.quickchat.fix.fixWithDuo
import com.gitlab.eclipse.chat.quickchat.fix.lineSpanOf
import com.gitlab.eclipse.chat.quickchat.fix.selectFixDiagnostics
import com.gitlab.eclipse.chat.quickchat.ui.QuickChatPopups
import com.gitlab.eclipse.chat.quickchat.ui.anchorLineOf
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.text.ITextSelection
import org.eclipse.jface.text.source.IAnnotationModel
import org.eclipse.ui.texteditor.DefaultMarkerAnnotationAccess
import org.eclipse.ui.texteditor.ITextEditor

private val logger by lazy { logger<FixWithDuoHandler>() }

/**
 * "Fix with GitLab Duo" in the editor (design §5.1): sends `/fix` for the error / warning annotations
 * of the selection, or of the caret's line without one, through a fresh Quick Chat. Enabled while
 * `duo_chat_enabled` (plugin.xml), re-checked when it runs. UI thread.
 */
@Suppress("unused")
class FixWithDuoHandler : AbstractHandler() {
  override fun execute(event: ExecutionEvent): Any? {
    fixWithDuo(
      unavailableReason = duoChatAvailability(service<DuoChatStateService>()),
      notify = NotificationUtils::showOnUiThread,
      prepare = ::prepareEditor,
      send = { QuickChatPopups.openAndSend(it.editor, it.oneBasedLine, it.message) },
    )
    return null
  }
}

/**
 * Selects the range to fix in the active text editor (VS Code `#getCodeActionRange`) and builds the
 * request from the annotations that touch it; null without a text editor, a document or a text
 * selection, or when anything fails (logged by exception type only).
 */
private fun prepareEditor(): FixRequest<ITextEditor>? = try {
  PlatformUtils().getActiveTextEditor()?.let(::prepareActiveEditor)
} catch (e: Exception) {
  logger.warn("Fix with GitLab Duo could not prepare the editor: exceptionType=${e.javaClass.name}")
  null
}

private fun prepareActiveEditor(editor: ITextEditor): FixRequest<ITextEditor>? {
  val input = editor.editorInput ?: return null
  val provider = editor.documentProvider ?: return null
  val document = provider.getDocument(input) ?: return null
  val selection = editor.selectionProvider?.selection as? ITextSelection ?: return null
  val lineSpan = lineSpanOf(document::getLineOfOffset, document::getLineOffset) { document.getLineInformation(it).length }
  val range = fixRange(
    selection.takeIf { it.length > 0 }?.let { TextSpan(it.offset, it.length) },
    TextSpan(selection.offset, 0),
    lineSpan,
  )
  val facts = annotationFactsOf(provider.getAnnotationModel(input))
  val diagnostics = selectFixDiagnostics(facts, range, document::getLineOfOffset, DefaultMarkerAnnotationAccess()::isSubtype)
  editor.selectAndReveal(range.offset, range.length)
  val line = anchorLineOf(editor) ?: return null
  return FixRequest(editor, line, FixMessage.of(diagnostics))
}

/** The model's annotations with a position; none without a model. */
private fun annotationFactsOf(model: IAnnotationModel?): List<AnnotationFacts> {
  model ?: return emptyList()
  return model.annotationIterator.asSequence().mapNotNull { annotation ->
    val position = model.getPosition(annotation) ?: return@mapNotNull null
    val type = annotation.type ?: return@mapNotNull null
    AnnotationFacts(type, annotation.text, position.offset, position.length, annotation.isMarkedDeleted)
  }.toList()
}
