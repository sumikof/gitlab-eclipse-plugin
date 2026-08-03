package com.gitlab.eclipse.mergerequests.discussions

import org.eclipse.jface.dialogs.Dialog
import org.eclipse.jface.dialogs.IDialogConstants
import org.eclipse.swt.SWT
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Control
import org.eclipse.swt.widgets.Label
import org.eclipse.swt.widgets.Shell
import org.eclipse.swt.widgets.Text

/**
 * Multi-line comment editor shared by every text-entering discussion flow: create, reply, edit,
 * retry, and send-again (design §7.5). JFace's `InputDialog` hosts a single-line `Text`, which is
 * unusable for a code-review comment, hence this small [Dialog] subclass.
 *
 * The dialog is a pure input surface: it never logs, notifies, or touches the network. The caller
 * reads [body] after `open()` returns `Window.OK` and decides what happens with it.
 *
 * @param title shell (window) title.
 * @param prompt rendered as a label above the text area.
 * @param initialBody pre-fills the text area (edit and retry re-open with existing content);
 *   the caret is placed at the end so the user can keep typing.
 * @param errorMessage when non-null, rendered as a label above the prompt — how a failed send
 *   explains itself while keeping the user's text (design §9.2 / §9.3).
 * @param okLabel label for the OK button, so callers can render `Retry`, `Send again`, or `Save`
 *   without a second dialog class.
 */
class CommentInputDialog(
  shell: Shell?,
  private val title: String,
  private val prompt: String,
  private val initialBody: String = "",
  private val errorMessage: String? = null,
  private val okLabel: String = IDialogConstants.OK_LABEL,
) : Dialog(shell) {

  /**
   * The entered comment text. Captured from the widget in [okPressed] (the widget is disposed
   * once the dialog closes); remains [initialBody] if the dialog is cancelled.
   */
  var body: String = initialBody
    private set

  private var textWidget: Text? = null

  override fun configureShell(newShell: Shell) {
    super.configureShell(newShell)
    newShell.text = title
  }

  override fun createDialogArea(parent: Composite): Control {
    val container = super.createDialogArea(parent) as Composite
    if (errorMessage != null) {
      Label(container, SWT.WRAP).apply {
        text = errorMessage
        layoutData = GridData(SWT.FILL, SWT.CENTER, true, false)
      }
    }
    Label(container, SWT.WRAP).apply {
      text = prompt
      layoutData = GridData(SWT.FILL, SWT.CENTER, true, false)
    }
    val text = Text(container, SWT.MULTI or SWT.WRAP or SWT.V_SCROLL or SWT.BORDER)
    // Without explicit hints a multi-line Text collapses to a single line.
    text.layoutData = GridData(SWT.FILL, SWT.FILL, true, true).apply {
      heightHint = convertHeightInCharsToPixels(TEXT_HEIGHT_IN_LINES)
      widthHint = convertWidthInCharsToPixels(TEXT_WIDTH_IN_CHARS)
    }
    text.text = initialBody
    // Caret at the end: edit and retry re-open with existing content and the user keeps typing.
    text.setSelection(initialBody.length)
    text.addModifyListener {
      // Null-safe: modify events can fire before createButtonsForButtonBar has run.
      getButton(IDialogConstants.OK_ID)?.isEnabled = isSubmittable(text.text)
    }
    textWidget = text
    return container
  }

  override fun createButtonsForButtonBar(parent: Composite) {
    createButton(parent, IDialogConstants.OK_ID, okLabel, true)
    createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false)
    // Initial enablement: a blank dialog must not offer OK; a pre-filled one (edit/retry) must.
    getButton(IDialogConstants.OK_ID)?.isEnabled = isSubmittable(initialBody)
  }

  override fun okPressed() {
    // Copy BEFORE super.okPressed(): super closes the dialog, disposing the widget — reading it
    // after would return garbage or throw once the caller inspects [body].
    body = textWidget?.text ?: initialBody
    super.okPressed()
  }

  override fun isResizable(): Boolean = true

  private companion object {
    /** Roughly eight lines of comment text, converted via the dialog's font metrics. */
    const val TEXT_HEIGHT_IN_LINES = 8

    /** Wide enough for prose. */
    const val TEXT_WIDTH_IN_CHARS = 80
  }
}

/**
 * A comment body is submittable when it has non-whitespace content. Whitespace-only input is not
 * a comment: GitLab would reject it and the round trip is wasted. Trimming follows Kotlin's
 * [String.trim] ([Char.isWhitespace]), under which U+00A0 NO-BREAK SPACE counts as content.
 */
internal fun isSubmittable(body: String): Boolean = body.trim().isNotEmpty()
