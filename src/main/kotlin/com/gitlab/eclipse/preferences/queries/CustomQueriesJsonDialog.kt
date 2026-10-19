package com.gitlab.eclipse.preferences.queries

import com.gitlab.eclipse.views.sidebar.queries.CustomQuery
import org.eclipse.jface.dialogs.Dialog
import org.eclipse.jface.dialogs.IDialogConstants
import org.eclipse.jface.resource.JFaceResources
import org.eclipse.swt.SWT
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Control
import org.eclipse.swt.widgets.Label
import org.eclipse.swt.widgets.Shell
import org.eclipse.swt.widgets.Text

/**
 * "Edit as JSON…" (design §10): the whole query list as text, for pasting a VS Code `gitlab.customQueries` array and
 * for repairing an unreadable stored value. OK stays disabled until the text parses ([CustomQueriesJsonText.parse]).
 *
 * @param initialText the formatted rows, or the raw unreadable stored text.
 */
class CustomQueriesJsonDialog(shell: Shell?, private val initialText: String) : Dialog(shell) {
  /** The parsed queries; null until OK is pressed. */
  var result: List<CustomQuery>? = null
    private set

  private var textWidget: Text? = null
  private var errorLabel: Label? = null

  override fun configureShell(newShell: Shell) {
    super.configureShell(newShell)
    newShell.text = "Edit Sidebar Queries as JSON"
  }

  override fun createDialogArea(parent: Composite): Control {
    val container = super.createDialogArea(parent) as Composite
    Label(container, SWT.WRAP).apply {
      text = "Enter {\"version\": 1, \"queries\": [...]} or paste a VS Code gitlab.customQueries array."
      layoutData = wideRow()
    }
    textWidget = Text(container, SWT.MULTI or SWT.BORDER or SWT.V_SCROLL or SWT.H_SCROLL).apply {
      font = JFaceResources.getTextFont()
      text = initialText
      layoutData = GridData(SWT.FILL, SWT.FILL, true, true).apply {
        heightHint = convertHeightInCharsToPixels(HEIGHT_IN_LINES)
        widthHint = convertWidthInCharsToPixels(WIDTH_IN_CHARS)
      }
      addModifyListener { validate() }
    }
    errorLabel = Label(container, SWT.WRAP).apply {
      foreground = container.display.getSystemColor(SWT.COLOR_RED)
      layoutData = wideRow()
    }
    return container
  }

  override fun createButtonsForButtonBar(parent: Composite) {
    super.createButtonsForButtonBar(parent)
    validate()
  }

  override fun okPressed() {
    // Parse before super.okPressed(): it closes the dialog and disposes the text widget.
    result = CustomQueriesJsonText.parse(textWidget?.text ?: initialText).getOrNull() ?: return
    super.okPressed()
  }

  override fun isResizable(): Boolean = true

  private fun validate() {
    val error = CustomQueriesJsonText.parse(textWidget?.text ?: initialText).exceptionOrNull()?.message
    errorLabel?.text = error.orEmpty()
    errorLabel?.parent?.layout(true)
    getButton(IDialogConstants.OK_ID)?.isEnabled = error == null
  }

  private fun wideRow(): GridData = GridData(SWT.FILL, SWT.CENTER, true, false).apply {
    widthHint = convertWidthInCharsToPixels(WIDTH_IN_CHARS)
  }

  private companion object {
    const val HEIGHT_IN_LINES = 20
    const val WIDTH_IN_CHARS = 90
  }
}
