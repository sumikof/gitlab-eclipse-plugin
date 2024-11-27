package com.gitlab.eclipse.preferences

import com.gitlab.eclipse.storage.SecretStorage
import org.eclipse.jface.preference.StringFieldEditor
import org.eclipse.swt.widgets.Composite
import java.util.*

class SecretStringFieldEditor(
    private val secretStorage: SecretStorage,
    secretName: String?,
    labelText: String?,
    parent: Composite?
) :
    StringFieldEditor(secretName, labelText, parent) {
    override fun doLoad() {
        val textField = textControl
        if (textField != null) {
            val value = Optional.ofNullable(secret())
                .map { s: String -> s.replace(".".toRegex(), "•") }
                .orElse("")
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
        val text = textField.text
        if (text.matches("^•+$".toRegex())) {
            // Avoid overwriting existing secrets with placeholder values.
            return
        }

        secretStorage.putSecret(preferenceName, text)

        doLoad()
    }

    override fun doCheckState(): Boolean {
        val text = textControl.text
        if (text.isEmpty()) {
            errorMessage = "$labelText must not be empty"
            return false
        }

        if (!text.matches("^[a-zA-Z0-9-]+$".toRegex()) && !text.matches("^•+$".toRegex())) {
            errorMessage = "$labelText must match pattern ^[a-zA-Z0-9-]+$"
            return false
        }

        return true
    }

    private fun secret(): String? {
        return secretStorage.getSecret(preferenceName)
    }
}
