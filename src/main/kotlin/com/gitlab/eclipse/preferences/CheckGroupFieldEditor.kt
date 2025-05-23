package com.gitlab.eclipse.preferences

import org.eclipse.jface.preference.FieldEditor
import org.eclipse.swt.SWT
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.layout.GridLayout
import org.eclipse.swt.widgets.Button
import org.eclipse.swt.widgets.Composite

class CheckGroupFieldEditor(
  name: String,
  text: String,
  private val labelsAndValues: Array<Pair<String, String>>,
  parent: Composite,
) : FieldEditor() {
  private var checkBox: Composite? = null
  private var checkBoxes: List<Button> = mutableListOf()

  init {
    this.preferenceName = name
    this.labelText = text

    createControl(parent)
  }

  override fun adjustForNumColumns(numColumns: Int) {
    (labelControl?.layoutData as? GridData)?.horizontalSpan = numColumns
    (checkBox?.layoutData as? GridData)?.horizontalSpan = numColumns
  }

  override fun doFillIntoGrid(parent: Composite, numColumns: Int) {
    getLabelControl(parent).layoutData = GridData().apply {
      horizontalSpan = numColumns
    }

    getCheckBoxControl(parent).layoutData = GridData().apply {
      horizontalSpan = numColumns
      horizontalIndent = HORIZONTAL_GAP
    }
  }

  private fun getCheckBoxControl(parent: Composite): Composite {
    val currentCheckbox = checkBox
    if (currentCheckbox != null) {
      checkParent(currentCheckbox, parent)
      return currentCheckbox
    }

    val parentFont = parent.font
    val checkBoxComposite = Composite(parent, SWT.NONE).apply {
      layout = GridLayout().apply {
        marginWidth = 0
        marginHeight = 0
        horizontalSpacing = HORIZONTAL_GAP
        numColumns = 1
      }
      font = parentFont
    }.also { checkBox = it }

    checkBoxes = labelsAndValues.map { (label, value) ->
      Button(checkBoxComposite, SWT.CHECK or SWT.LEFT).apply {
        text = label
        data = value
        font = parentFont
      }
    }

    checkBoxComposite.addDisposeListener {
      checkBoxes.forEach { box -> box.dispose() }

      checkBox = null
      checkBoxes = emptyList()
    }

    return checkBoxComposite
  }

  override fun doLoad() {
    updateCheckboxes(preferenceStore.getString(preferenceName))
  }

  override fun doLoadDefault() {
    updateCheckboxes(preferenceStore.getDefaultString(preferenceName))
  }

  private fun updateCheckboxes(value: String) {
    val values = value.split(",").map { it.trim() }

    checkBoxes.forEach { checkbox ->
      val checkboxValue = checkbox.data as String
      checkbox.selection = !values.contains(checkboxValue)
    }
  }

  override fun doStore() {
    val selectedValues = checkBoxes
      .filter { !it.selection }
      .joinToString(",") { it.data as String }

    preferenceStore.setValue(preferenceName, selectedValues)
  }

  override fun getNumberOfControls() = 1
}
