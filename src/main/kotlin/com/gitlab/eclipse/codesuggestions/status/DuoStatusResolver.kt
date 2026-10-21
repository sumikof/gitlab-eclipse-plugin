package com.gitlab.eclipse.codesuggestions.status

import com.gitlab.eclipse.authentication.AuthState
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerFailureKind
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleState
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerPhase

/** Snapshot of everything the Duo status depends on (design §8.1). */
data class DuoStatusInputs(
  val lifecycle: LanguageServerLifecycleState,
  val auth: AuthState,
  val codeSuggestionsEnabled: Boolean,
  val activeLanguageEnabled: Boolean?,
  val featureStateKnown: Boolean,
  val primaryCheckId: String?,
)

enum class DuoStatusIcon(val iconName: String) {
  ENABLED("duo_on_edit"),
  DISABLED("duo_off_edit"),
  ERROR("duo_error_edit"),
  LOADING("duo_load_edit"),
  UNAVAILABLE("duo_unav_edit"),
}

data class DuoStatus(val icon: DuoStatusIcon, val tooltip: String, val menuLabel: String)

/** Pure priority resolver for the Duo trim item and menu (design §8.2, first match wins). */
object DuoStatusResolver {
  private const val PREFIX = "Code Suggestions: "

  private fun disabled(suffix: String) = "${PREFIX}Disabled ($suffix)"

  private fun status(icon: DuoStatusIcon, tooltip: String, label: String) = DuoStatus(icon, tooltip, label)

  private fun off(tooltip: String, suffix: String) = status(DuoStatusIcon.DISABLED, tooltip, disabled(suffix))

  fun resolve(inputs: DuoStatusInputs): DuoStatus =
    lifecycleStatus(inputs.lifecycle)
      ?: settingsStatus(inputs)
      ?: checkStatus(inputs)

  private fun lifecycleStatus(lifecycle: LanguageServerLifecycleState): DuoStatus? =
    when (lifecycle.phase) {
      LanguageServerPhase.FAILED ->
        if (lifecycle.failure == LanguageServerFailureKind.CRASHED) {
          status(
            DuoStatusIcon.ERROR,
            "GitLab Duo is disabled: Language Server stopped unexpectedly.",
            "${PREFIX}Unavailable (Language Server stopped)",
          )
        } else {
          status(
            DuoStatusIcon.ERROR,
            "GitLab Duo is disabled: Language Server failed to start.",
            "${PREFIX}Unavailable (Language Server failed to start)",
          )
        }
      LanguageServerPhase.STARTING ->
        status(DuoStatusIcon.LOADING, "GitLab Duo: Starting Language Server...", "${PREFIX}Starting...")
      LanguageServerPhase.STOPPED ->
        status(
          DuoStatusIcon.UNAVAILABLE,
          "GitLab Duo: Language Server is not running.",
          "${PREFIX}Unavailable (Language Server not running)",
        )
      LanguageServerPhase.RUNNING -> null
    }

  private fun settingsStatus(inputs: DuoStatusInputs): DuoStatus? =
    when {
      !inputs.codeSuggestionsEnabled ->
        status(DuoStatusIcon.DISABLED, "Code suggestions are disabled", "${PREFIX}Disabled")
      inputs.activeLanguageEnabled == false ->
        off("Code suggestions are disabled for this language", "language")
      !inputs.featureStateKnown ->
        status(
          DuoStatusIcon.LOADING,
          "GitLab Duo: Checking Code Suggestions status...",
          "${PREFIX}Checking...",
        )
      else -> null
    }

  private fun checkStatus(inputs: DuoStatusInputs): DuoStatus {
    val id = inputs.primaryCheckId
    if (inputs.auth == AuthState.SIGN_IN_REQUIRED || id == "authentication-required") {
      return off("Authentication required for Code Suggestions", "Authentication Required")
    }
    return when (id) {
      null -> status(DuoStatusIcon.ENABLED, "Code suggestions are enabled", "${PREFIX}Enabled")
      "code-suggestions-disabled-by-user" ->
        off("Code suggestions are disabled per user request", "Disabled by User")
      "code-suggestions-api-error" ->
        status(
          DuoStatusIcon.ERROR,
          "Code Suggestion requests to API are failing",
          "${PREFIX}Unavailable (API Error)",
        )
      "code-suggestions-unsupported-gitlab-version" ->
        off("GitLab Duo Code Suggestions requires GitLab version 16.8 or later.", "Unsupported GitLab Version")
      "code-suggestions-no-license" ->
        off(
          "A GitLab Duo license is required to use this feature. Contact your GitLab administrator to request access.",
          "Invalid License",
        )
      "code-suggestions-no-default-namespace" ->
        off(
          "Code Suggestions cannot detect a namespace for this project. To continue, please set a default " +
            "GitLab Duo namespace in your user preferences.",
          "No Default Namespace Selected",
        )
      "code-suggestions-no-credits" ->
        off(
          "No GitLab Credits remain for this billing period. To continue using Code Suggestions, contact your " +
            "administrator. When you have more credits, restart the GitLab Language Server.",
          "Out of GitLab Credits",
        )
      "duo-disabled-for-project" ->
        off("Code suggestions are disabled for this project", "GitLab Duo Disabled for Project")
      "code-suggestions-file-excluded" ->
        off("File excluded from GitLab Duo context by project settings", "File Excluded")
      else -> off("Code suggestions unavailable (unknown state: $id)", id)
    }
  }
}
