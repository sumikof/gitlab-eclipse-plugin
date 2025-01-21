package com.gitlab.eclipse.preferences

import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.storage.SecretStorage
import com.gitlab.eclipse.preferences.storage.SecretStringFieldEditor
import org.eclipse.core.runtime.preferences.InstanceScope
import org.eclipse.jface.preference.BooleanFieldEditor
import org.eclipse.jface.preference.FieldEditorPreferencePage
import org.eclipse.jface.preference.FileFieldEditor
import org.eclipse.jface.preference.StringFieldEditor
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPreferencePage
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.osgi.framework.FrameworkUtil

@Suppress("ForbiddenComment")
class GitLabPreferencePage(
  private val languageServiceConfigurationService: GitLabLanguageServerConfigurationService =
    GitLabLanguageServerConfigurationService()
) : FieldEditorPreferencePage(GRID), IWorkbenchPreferencePage {
  init {
    description = "GitLab Duo plugin preferences"
  }

  public override fun createFieldEditors() {
    // Connection
    addField(StringFieldEditor(PreferenceConstants.GITLAB_INSTANCE_URL, "Connection URL", fieldEditorParent))

    // Authentication
    // TODO: Ensure first-time load succeeds given empty value does not break the entire page.
    addField(
      SecretStringFieldEditor(
        SecretStorage("gitlab.com"),
        "personal_access_token",
        "Personal Access Token",
        fieldEditorParent
      )
    )

    // Language Server
    addField(
      StringFieldEditor(
        PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL,
        "Language Server Log Level",
        fieldEditorParent
      )
    )
    addField(
      StringFieldEditor(
        PreferenceConstants.LANGUAGE_SERVER_HTTP_URL,
        "Language Server HTTP URL",
        fieldEditorParent
      )
    )
    addField(
      BooleanFieldEditor(
        PreferenceConstants.LANGUAGE_SERVER_STREAM_CODE_GENERATIONS,
        "Stream Code Generations",
        fieldEditorParent
      )
    )

    // Certificate
    addField(
      FileFieldEditor(
        PreferenceConstants.CA_CERTIFICATE,
        "CA certificate",
        true,
        StringFieldEditor.VALIDATE_ON_KEY_STROKE,
        fieldEditorParent
      ).apply {
        setFileExtensions(arrayOf("*.pem"))
        errorMessage = "Please select a .pem file."
      }
    )

    addField(
      BooleanFieldEditor(
        PreferenceConstants.IGNORE_CERTIFICATE_ERRORS,
        "Ignore Certificate Errors",
        fieldEditorParent
      )
    )

    addField(
      BooleanFieldEditor(
        PreferenceConstants.TELEMETRY_ENABLED,
        "Enable Telemetry",
        fieldEditorParent
      )
    )
  }

  override fun init(workbench: IWorkbench) {
    // TODO: Confirm intended storage bundle ID
    val bundleId = FrameworkUtil.getBundle(javaClass).bundleId.toString()
    val store = ScopedPreferenceStore(InstanceScope.INSTANCE, bundleId)
    preferenceStore = store

    PreferenceInitializer(store).initializeDefaultPreferences()
  }

  override fun performOk(): Boolean {
    super.performOk()
    languageServiceConfigurationService.sendConfiguration()
    return true // super.performOk() always returns true
  }
}
