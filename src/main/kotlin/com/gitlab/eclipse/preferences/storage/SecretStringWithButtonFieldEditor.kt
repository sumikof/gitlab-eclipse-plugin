package com.gitlab.eclipse.preferences.storage

import org.eclipse.jface.preference.StringButtonFieldEditor
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Text

class SecretStringWithButtonFieldEditor(
  private val secretStorage: SecretStorage,
  secretName: String,
  labelText: String,
  buttonText: String,
  parent: Composite?,
  private val buttonAction: () -> Unit
) : StringButtonFieldEditor(secretName, labelText, parent) {

  init {
    setChangeButtonText(buttonText)
  }

  public override fun doLoad() {
    val textField = textControl
    if (textField != null) {
      val value = secretStorage.getSecret(preferenceName).orEmpty()
      textField.text = value
      oldValue = value
    }
  }

  override fun doLoadDefault() {
    val textField = textControl
    if (textField != null) {
      textField.text = ""
    }

    valueChanged()
  }

  override fun doStore() {
    val textField = textControl
    if (textField != null) {
      secretStorage.putSecret(preferenceName, textField.text)
    }

    doLoad()
  }

  override fun doCheckState(): Boolean {
    val text = textControl.text
    if (text.isEmpty()) {
      errorMessage = "$labelText must not be empty"
      return false
    }

    return true
  }

  override fun createTextWidget(parent: Composite): Text {
    return Text(parent, SWT.SINGLE or SWT.BORDER or SWT.PASSWORD)
  }

  override fun changePressed(): String? {
    buttonAction()
    return null
  }
}
