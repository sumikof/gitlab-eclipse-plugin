package com.gitlab.eclipse.preferences

import org.eclipse.jface.preference.FieldEditor
import org.eclipse.swt.SWT
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.widgets.Button
import org.eclipse.swt.widgets.Composite

@Suppress("EmptyFunctionBlock", "MagicNumber")
class ButtonFieldEditor(
  private val labelText: String,
  parent: Composite,
  private val clickListener: () -> Unit
) : FieldEditor() {

  private lateinit var button: Button

  init {
    createControl(parent)
  }

  override fun getNumberOfControls(): Int = 1

  override fun doFillIntoGrid(parent: Composite, numColumns: Int) {
    button = Button(parent, SWT.PUSH)
    button.text = labelText
    button.addListener(SWT.Selection) { clickListener() }

    val gd = GridData(GridData.FILL_HORIZONTAL)
    gd.horizontalSpan = numColumns
    gd.horizontalAlignment = GridData.BEGINNING
    // Set a fixed width of the button
    gd.widthHint = 150
    button.layoutData = gd
  }

  override fun doLoad() {}

  override fun doLoadDefault() {}

  override fun doStore() {}

  override fun getPreferenceName(): String? = null

  override fun adjustForNumColumns(numColumns: Int) {
    (button.layoutData as? GridData)?.horizontalSpan = numColumns
  }

  override fun setEnabled(enabled: Boolean, parent: Composite) {
    super.setEnabled(enabled, parent)
    button.isEnabled = enabled
  }

  fun setButtonText(text: String) {
    button.text = text
  }
}
