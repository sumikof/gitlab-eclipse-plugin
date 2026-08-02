package com.gitlab.eclipse.ci.lint

import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IFileEditorInput
import org.eclipse.ui.IURIEditorInput
import org.eclipse.ui.handlers.HandlerUtil
import org.eclipse.ui.texteditor.ITextEditor

/**
 * Snapshot of the active text editor captured in a single UI turn: full [text] plus the
 * stable [sourceId] (§8.7) used for [MergedYamlKey]/generation identity, so the pair can
 * never come from two different editors. [displayName] is display-only, never identity.
 */
data class ActiveEditorContent(val text: String, val sourceId: String, val displayName: String) {
  companion object {
    /** Extracts the active text editor's content, or null when there is no adaptable text editor/document. */
    fun of(event: ExecutionEvent): ActiveEditorContent? {
      val editor = HandlerUtil.getActiveEditor(event) ?: return null
      val textEditor = editor.getAdapter(ITextEditor::class.java) ?: (editor as? ITextEditor) ?: return null
      val input = textEditor.editorInput ?: return null
      val document = textEditor.documentProvider?.getDocument(input) ?: return null
      return ActiveEditorContent(document.get(), sourceIdOf(input), input.name)
    }
  }
}

/**
 * Stable source identity for [input] (§8.7): workspace-relative full path for
 * [IFileEditorInput] (distinguishes same basename in different projects), absolute URI for
 * [IURIEditorInput], else `name#identityHashCode` (stable per unsaved-editor input instance).
 * The bare basename is never used alone — same-name files in different paths would collide.
 */
internal fun sourceIdOf(input: IEditorInput): String = when (input) {
  is IFileEditorInput -> input.file.fullPath.toString()
  is IURIEditorInput -> input.uri.toString()
  else -> input.name + "#" + System.identityHashCode(input)
}
