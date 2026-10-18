package com.gitlab.eclipse.preferences.storage

import org.eclipse.jface.preference.StringButtonFieldEditor
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Text

/**
 * A secret text field bound to the instance URL another field is editing ([instanceUrl]): it shows and saves the
 * secret of that instance only (#115). The page calls [syncWithUrl] whenever that URL may have changed.
 */
class SecretStringWithButtonFieldEditor(
  private val secret: BoundSecret,
  private val instanceUrl: () -> String?,
  secretName: String,
  labelText: String,
  buttonText: String,
  parent: Composite?,
  private val buttonAction: () -> Unit
) : StringButtonFieldEditor(secretName, labelText, parent) {

  /** Set while [syncWithUrl] runs, on the UI thread. */
  private var syncing = false

  init {
    setChangeButtonText(buttonText)
  }

  public override fun doLoad() {
    val textField = textControl
    if (textField != null) {
      val value = secret.load(instanceUrl())
      textField.text = value
      oldValue = value
    }
  }

  /**
   * Shows the secret of [url]'s instance if it names another instance than the one shown. A text typed but not
   * saved is dropped then, so it is never saved under, or sent to, an instance it was not typed for.
   */
  fun syncWithUrl(url: String?) {
    // Re-entered when the read's master-password dialog moves the focus off the URL field (a nested VALUE event).
    if (syncing) return
    syncing = true
    try {
      if (secret.needsReload(url)) doLoad()
    } finally {
      syncing = false
    }
  }

  override fun doLoadDefault() {
    val textField = textControl
    if (textField != null) {
      textField.text = ""
    }

    // Keeps isDefaultPresented false, so OK still calls doStore (which then removes the secret).
    valueChanged()
  }

  override fun doStore() {
    val textField = textControl
    if (textField != null) {
      secret.store(instanceUrl(), textField.text)
    }

    doLoad()
  }

  override fun createTextWidget(parent: Composite): Text {
    return Text(parent, SWT.SINGLE or SWT.BORDER or SWT.PASSWORD)
  }

  override fun changePressed(): String? {
    buttonAction()
    return null
  }
}
