package com.gitlab.eclipse.preferences

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.api.ConnectionConfigGeneration
import com.gitlab.eclipse.authentication.AuthenticationStateService
import com.gitlab.eclipse.authentication.GitLabOAuthService
import com.gitlab.eclipse.codesuggestions.dismissActiveCodeSuggestion
import com.gitlab.eclipse.codesuggestions.languages.refreshCodeSuggestionsLanguageToggle
import com.gitlab.eclipse.codesuggestions.refreshCodeSuggestionsToggle
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.utils.LanguageServerLanguage
import com.gitlab.eclipse.preferences.healthcheck.ConfigurationValidationRequest
import com.gitlab.eclipse.preferences.healthcheck.ConfigurationValidationService
import com.gitlab.eclipse.preferences.healthcheck.HealthCheckFieldEditor
import com.gitlab.eclipse.preferences.storage.SecretStorage
import com.gitlab.eclipse.preferences.storage.SecretStringWithButtonFieldEditor
import com.gitlab.eclipse.security.SecurityScanSettings
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.makeBoldFont
import com.gitlab.eclipse.utils.makeHintFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.jface.preference.*
import org.eclipse.swt.SWT
import org.eclipse.swt.program.Program
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

    val urlField =
      StringFieldEditor(PreferenceConstants.GITLAB_INSTANCE_URL, "URL to GitLab instance", fieldEditorParent)
        .also(::addField)

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

    val tokenField = SecretStringWithButtonFieldEditor(
      SecretStorage("gitlab.com"),
      "personal_access_token",
      "Personal Access Token",
      "Generate token",
      fieldEditorParent
    ) {
      val gitLabUrl = preferenceStore.getString(PreferenceConstants.GITLAB_INSTANCE_URL)
      val tokenUrl =
        "$gitLabUrl/-/user_settings/personal_access_tokens?name=GitLab%20Duo%20For%20Eclipse&scopes=api"

      Program.launch(tokenUrl)
    }.also(::addField)

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

    // Health Checks
    var verifySetupFieldEditor: ButtonFieldEditor? = null
    var healthCheckFieldEditor: HealthCheckFieldEditor? = null
    val verifySetupText = "Verify Setup"

    verifySetupFieldEditor = ButtonFieldEditor(
      verifySetupText,
      fieldEditorParent
    ) {
      healthCheckFieldEditor?.resetStatus()
      verifySetupFieldEditor?.setButtonText("Verifying...")
      verifySetupFieldEditor?.setEnabled(false, fieldEditorParent)

      service<CoroutineScope>().launch {
        val request = currentDisplay.syncCall<ConfigurationValidationRequest, Exception> {
          ConfigurationValidationRequest(
            baseUrl = urlField.stringValue,
            token = tokenField.stringValue
          )
        }

        val results = ConfigurationValidationService().validateConfiguration(request)

        currentDisplay.asyncExec {
          healthCheckFieldEditor?.updateStatus(results)
          verifySetupFieldEditor?.setButtonText(verifySetupText)
          verifySetupFieldEditor?.setEnabled(true, fieldEditorParent)
        }
      }
    }.also(::addField)

    healthCheckFieldEditor = HealthCheckFieldEditor(fieldEditorParent).also(::addField)

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
      FileFieldEditor(
        PreferenceConstants.CLIENT_CERTIFICATE,
        "Client certificate",
        true,
        StringFieldEditor.VALIDATE_ON_KEY_STROKE,
        fieldEditorParent
      ).apply {
        setFileExtensions(arrayOf("*.pem", "*.crt"))
        errorMessage = "Please select a .pem or .crt file."
      }
    )

    addField(
      FileFieldEditor(
        PreferenceConstants.CLIENT_CERTIFICATE_KEY,
        "Client certificate key",
        true,
        StringFieldEditor.VALIDATE_ON_KEY_STROKE,
        fieldEditorParent
      ).apply {
        setFileExtensions(arrayOf("*.key", "*.pem"))
        errorMessage = "Please select a .key or .pem file."
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

    addEmptyControls(EMPTY_CONTROLS_FULL_ROW)

    addField(
      BooleanFieldEditor(
        PreferenceConstants.DUO_CHAT_ENABLED,
        "Enable Duo Chat",
        fieldEditorParent
      )
    )

    addField(
      BooleanFieldEditor(
        PreferenceConstants.DUO_ENABLED_WITHOUT_GITLAB_PROJECT,
        "Enable Duo features when no GitLab project is detected",
        fieldEditorParent
      )
    )

    addField(
      BooleanFieldEditor(
        PreferenceConstants.CODE_SUGGESTIONS_ENABLED,
        "Enable Code Suggestions",
        fieldEditorParent
      )
    )

    addLabel("Code Suggestions Enabled Languages")

    addField(
      CheckGroupFieldEditor(
        PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES,
        "Supported Languages",
        LanguageServerLanguage.Language.entries.map {
          it.humanReadableName to it.id
        }.toTypedArray(),
        fieldEditorParent
      )
    )

    addField(
      StringFieldEditor(
        PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES,
        "Additional Languages",
        fieldEditorParent
      )
    )

    addHint("Use , to separate file extensions (ex: md, vue, sh).")

    addEmptyControls(EMPTY_CONTROLS_FULL_ROW)

    addField(
      BooleanFieldEditor(
        PreferenceConstants.SECURITY_SCAN_ENABLED,
        "Enable real-time SAST scan (experimental). Sends the contents of the open file to your GitLab instance.",
        fieldEditorParent
      )
    )

    addField(
      BooleanFieldEditor(
        PreferenceConstants.SECURITY_SCAN_ON_SAVE,
        "Scan file on save",
        fieldEditorParent
      )
    )
  }

  private fun addLabel(label: String) {
    Label(fieldEditorParent, SWT.WRAP).apply {
      text = label
      font = makeBoldFont(font)
    }

    addEmptyControls(EMPTY_CONTROLS_AFTER_LABEL)
  }

  private fun addHint(hint: String) = Label(fieldEditorParent, SWT.WRAP).apply {
    text = hint
    font = makeHintFont(font)
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
    // Read BEFORE super.performOk() writes the field editors into the store: the transition is the
    // difference between the two reads, and once the new value is stored there is nothing left to
    // compare it against.
    val securityScanWasEnabled = preferenceStore.getBoolean(PreferenceConstants.SECURITY_SCAN_ENABLED)
    // super.performOk() stores the URL field editor (preference store) AND the token field editor
    // (secure storage) as separate writes; bracketing them marks the whole window as
    // update-in-progress so GitLabApiClient.captureConnection never accepts the torn intermediate.
    ConnectionConfigGeneration.beginUpdate()
    try {
      super.performOk()
    } finally {
      ConnectionConfigGeneration.endUpdate()
    }
    languageServiceConfigurationService.sendConfiguration()
    authenticationStateService.resetAuthenticatedState()

    refreshCodeSuggestionsLanguageToggle()

    refreshCodeSuggestionsToggle()
    if (!preferenceStore.getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED)) {
      dismissActiveCodeSuggestion()
    }

    val securityScanEnabled = preferenceStore.getBoolean(PreferenceConstants.SECURITY_SCAN_ENABLED)
    if (securityScanEnabled != securityScanWasEnabled) {
      // Only a real transition is applied. Pressing OK without touching this setting must leave the
      // suspended parity, the pending scans and the published markers exactly as they were.
      //
      // The sequence number that orders two rapid transitions against each other is taken inside
      // this call, synchronously on the UI thread, in the same turn the user pressed OK; the work
      // itself is handed to the plugin's scope, because it has to take the outbound lock and the UI
      // thread may not block on it.
      service<SecurityScanSettings>().onSettingChanged(securityScanEnabled)
    }

    return true // super.performOk() always returns true
  }

  private companion object {
    const val EMPTY_CONTROLS_AFTER_LABEL = 2
    const val EMPTY_CONTROLS_FULL_ROW = 3
  }
}
