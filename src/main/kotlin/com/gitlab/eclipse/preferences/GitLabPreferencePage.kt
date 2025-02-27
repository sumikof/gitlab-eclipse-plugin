package com.gitlab.eclipse.preferences

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.authentication.GitLabOAuthService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.storage.SecretStorage
import com.gitlab.eclipse.preferences.storage.SecretStringFieldEditor
import org.eclipse.jface.preference.BooleanFieldEditor
import org.eclipse.jface.preference.FieldEditorPreferencePage
import org.eclipse.jface.preference.FileFieldEditor
import org.eclipse.jface.preference.StringFieldEditor
import org.eclipse.swt.SWT
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.widgets.Button
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPreferencePage
import org.eclipse.ui.preferences.ScopedPreferenceStore

@Suppress("ForbiddenComment", "LongMethod")
class GitLabPreferencePage(
  private val languageServiceConfigurationService: GitLabLanguageServerConfigurationService = service()
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

    if (BuildConfig.OAUTH_ENABLED) {
      val button = Button(fieldEditorParent, SWT.PUSH)
      button.text = "Login with OAuth"
      button.layoutData = GridData(GridData.FILL_HORIZONTAL)
      button.addListener(SWT.Selection) {
        val gitLabOAuthService = GitLabOAuthService()
        gitLabOAuthService.startOAuthFlow()
      }
    }

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
    preferenceStore = service<ScopedPreferenceStore>()
  }

  override fun performOk(): Boolean {
    super.performOk()
    languageServiceConfigurationService.sendConfiguration()
    return true // super.performOk() always returns true
  }
}
