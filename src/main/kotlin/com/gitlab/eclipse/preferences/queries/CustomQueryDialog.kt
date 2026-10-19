package com.gitlab.eclipse.preferences.queries

import com.gitlab.eclipse.views.sidebar.queries.CustomQuery
import com.gitlab.eclipse.views.sidebar.queries.ProblemSeverity
import org.eclipse.jface.dialogs.IDialogConstants
import org.eclipse.jface.dialogs.IMessageProvider
import org.eclipse.jface.dialogs.TitleAreaDialog
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.ScrolledComposite
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.layout.GridLayout
import org.eclipse.swt.widgets.Combo
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Control
import org.eclipse.swt.widgets.Label
import org.eclipse.swt.widgets.Shell
import org.eclipse.swt.widgets.Text

/**
 * Adds or edits one sidebar query (design §10). Thin SWT layer over [CustomQueryFormModel]: every control writes its
 * text into the form on change, then enablement and messages are re-read from the form. Opened on the UI thread.
 *
 * @param query the row to edit, or null to add a new query.
 * @param otherNames the names of the other rows, for the duplicate-name warning.
 */
class CustomQueryDialog(
  shell: Shell?,
  query: CustomQuery?,
  private val otherNames: List<String?>,
) : TitleAreaDialog(shell) {
  private val form = CustomQueryFormModel(query)
  private val adding = query == null
  private val controls = linkedMapOf<String, Control>()
  private val labels = mutableMapOf<String, Label>()

  /** The entered query (id left blank: the list model keeps or assigns it); null until OK is pressed. */
  var result: CustomQuery? = null
    private set

  override fun configureShell(newShell: Shell) {
    super.configureShell(newShell)
    newShell.text = if (adding) "Add Sidebar Query" else "Edit Sidebar Query"
  }

  override fun createDialogArea(parent: Composite): Control {
    val area = super.createDialogArea(parent) as Composite
    setTitle(if (adding) "Add Sidebar Query" else "Edit Sidebar Query")
    val scrolled = ScrolledComposite(area, SWT.V_SCROLL or SWT.H_SCROLL)
    scrolled.layoutData = GridData(SWT.FILL, SWT.FILL, true, true).apply {
      heightHint = convertHeightInCharsToPixels(HEIGHT_IN_LINES)
      widthHint = convertWidthInCharsToPixels(WIDTH_IN_CHARS)
    }
    val fields = Composite(scrolled, SWT.NONE)
    fields.layout = GridLayout(2, false)
    CustomQueryFormModel.FIELDS.forEach { createField(fields, it) }
    scrolled.content = fields
    scrolled.expandHorizontal = true
    scrolled.expandVertical = true
    scrolled.setMinSize(fields.computeSize(SWT.DEFAULT, SWT.DEFAULT))
    return area
  }

  override fun createButtonsForButtonBar(parent: Composite) {
    super.createButtonsForButtonBar(parent)
    refreshState()
  }

  override fun okPressed() {
    // Read before super.okPressed(): it closes the dialog and disposes the controls.
    result = form.toQuery("")
    super.okPressed()
  }

  override fun isResizable(): Boolean = true

  private fun createField(parent: Composite, field: String) {
    labels[field] = Label(parent, SWT.NONE).apply { text = CustomQueryFieldSpecs.label(field) }
    val combo = CustomQueryFieldSpecs.combo(field)
    val control = if (combo != null) createCombo(parent, field, combo) else createText(parent, field)
    control.layoutData = GridData(SWT.FILL, SWT.CENTER, true, false)
    controls[field] = control
  }

  private fun createText(parent: Composite, field: String): Text =
    Text(parent, SWT.SINGLE or SWT.BORDER).apply {
      text = form.get(field)
      addModifyListener {
        form.set(field, text)
        refreshState()
      }
    }

  private fun createCombo(parent: Composite, field: String, spec: ComboField): Combo =
    Combo(parent, SWT.READ_ONLY or SWT.DROP_DOWN).apply {
      val initial = form.get(field)
      spec.items(initial).forEach { item -> add(item) }
      select(spec.selectedIndex(initial))
      addListener(SWT.Selection) {
        spec.textFor(selectionIndex, initial, form.get(field))?.let { value -> form.set(field, value) }
        refreshState()
      }
    }

  /** Re-reads enablement (it depends on the type) and the first problem from the form. */
  private fun refreshState() {
    controls.forEach { (field, control) ->
      val enabled = form.isEnabled(field)
      control.isEnabled = enabled
      labels[field]?.isEnabled = enabled
    }
    val problems = form.problems(otherNames + form.toQuery("").name)
    val error = problems.firstOrNull { it.severity == ProblemSeverity.ERROR }
    val warning = problems.firstOrNull { it.severity == ProblemSeverity.WARNING }
    getButton(IDialogConstants.OK_ID)?.isEnabled = error == null
    setErrorMessage(error?.message)
    when {
      error != null -> Unit
      warning != null -> setMessage(warning.message, IMessageProvider.WARNING)
      else -> setMessage("Choose what this query lists in the GitLab sidebar.", IMessageProvider.NONE)
    }
  }

  private companion object {
    const val HEIGHT_IN_LINES = 24
    const val WIDTH_IN_CHARS = 70
  }
}
