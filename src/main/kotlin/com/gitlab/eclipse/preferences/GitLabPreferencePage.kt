package com.gitlab.eclipse.preferences

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.authentication.AuthenticationStateService
import com.gitlab.eclipse.authentication.GitLabOAuthService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.storage.SecretStorage
import com.gitlab.eclipse.preferences.storage.SecretStringFieldEditor
import org.eclipse.jface.preference.*
import org.eclipse.swt.SWT
import org.eclipse.swt.graphics.Font
import org.eclipse.swt.widgets.Label
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPreferencePage
import org.eclipse.ui.preferences.ScopedPreferenceStore

@Suppress("ForbiddenComment", "LongMethod")
class GitLabPreferencePage(
  private val languageServiceConfigurationService: GitLabLanguageServerConfigurationService = service(),
  private val authenticationStateService: AuthenticationStateService = service()
) : FieldEditorPreferencePage(GRID), IWorkbenchPreferencePage {
  public override fun createFieldEditors() {
    // Connection
    addLabel("Connection")

    addField(StringFieldEditor(PreferenceConstants.GITLAB_INSTANCE_URL, "URL to GitLab instance", fieldEditorParent))

    addField(
      BooleanFieldEditor(
        PreferenceConstants.IGNORE_CERTIFICATE_ERRORS,
        "Ignore Certificate Errors",
        fieldEditorParent
      )
    )

    addEmptyControls(EMPTY_CONTROLS_FULL_ROW)

    // Authentication
    addLabel("Authentication")

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
      addField(
        ButtonFieldEditor(
          "Login with OAuth",
          fieldEditorParent
        ) {
          service<GitLabOAuthService>().startOAuthFlow()
        }
      )
    }

    addEmptyControls(EMPTY_CONTROLS_FULL_ROW)

    // Language Server
    addLabel("Language Server")

    addField(
      ComboFieldEditor(
        PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL,
        "Language Server Log Level",
        LogLevel.toFieldEditorOptions(),
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
        PreferenceConstants.LANGUAGE_SERVER_STREAM_CODE_GENERATIONS,
        "Stream Code Generations",
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

  private fun addLabel(label: String) {
    Label(fieldEditorParent, SWT.WRAP).apply {
      text = label

      // make the font bold
      val currentFont = font
      val fontData = currentFont.fontData
      for (fd in fontData) {
        fd.style = fd.style or SWT.BOLD
      }
      val boldFont = Font(display, fontData)
      font = boldFont

      addDisposeListener { boldFont.dispose() }
    }

    addEmptyControls(EMPTY_CONTROLS_AFTER_LABEL)
  }

  /**
   * Adds empty Label controls to create vertical spacing in the preference page.
   *
   * This preference page uses a grid layout with 3 columns (determined by the FileFieldEditor
   * which requires 3 controls). To create proper vertical spacing:
   * - After a Label (1 column): add 2 empty controls to complete the row
   * - After a FieldEditor (3 columns): add 3 empty controls to create an empty row
   *
   * @param numControls The number of empty Label controls to add
   */
  private fun addEmptyControls(numControls: Int) {
    repeat(numControls) {
      Label(fieldEditorParent, SWT.NONE)
    }
  }

  override fun init(workbench: IWorkbench) {
    preferenceStore = service<ScopedPreferenceStore>()
  }

  override fun performOk(): Boolean {
    super.performOk()
    languageServiceConfigurationService.sendConfiguration()
    authenticationStateService.resetAuthenticatedState()
    return true // super.performOk() always returns true
  }

  private companion object {
    const val EMPTY_CONTROLS_AFTER_LABEL = 2
    const val EMPTY_CONTROLS_FULL_ROW = 3
  }
}
