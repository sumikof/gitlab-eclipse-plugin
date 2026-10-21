package com.gitlab.eclipse.codesuggestions.status

import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.google.gson.JsonElement
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Minimal string preference access so the policy stays free of Eclipse preference types. */
interface WarningPreferenceAccess {
  fun get(key: String): String
  fun set(key: String, value: String)
}

sealed interface CodeSuggestionsWarning {
  data class UnsupportedVersion(val baseUrl: String, val version: String) : CodeSuggestionsWarning
  object NoCredits : CodeSuggestionsWarning
}

/**
 * Decides which CS-08 Code Suggestions warning (if any) a feature-state event warrants (design §7 W1-W5).
 * The notifier displays; this class only decides. The in-memory session state resets when [onFeatureState]
 * is given a different `session` object (compared by reference), mirroring a VS Code policy recreated
 * on language server restart.
 */
class CodeSuggestionsWarningPolicy(
  private val prefs: WarningPreferenceAccess,
  private val today: () -> LocalDate,
) {
  private var session: Any? = null
  private val shownVersionUrls = mutableSetOf<String>()
  private var creditsShown = false

  @Synchronized
  fun onFeatureState(session: Any, checks: List<FeatureStateChangeCheck>?): CodeSuggestionsWarning? {
    if (this.session !== session) {
      this.session = session
      shownVersionUrls.clear()
      creditsShown = false
    }
    val primary = checks?.firstOrNull { it.engaged } ?: return null
    return when (primary.checkId) {
      VERSION_CHECK_ID -> versionWarning(primary)
      NO_CREDITS_CHECK_ID -> creditsWarning()
      else -> null
    }
  }

  @Synchronized
  fun suppressVersionWarning(baseUrl: String) {
    val normalised = normalise(baseUrl)
    if (normalised.isEmpty()) return
    val current = suppressedUrls()
    if (normalised in current) return
    val joined = (current + normalised).joinToString("\n")
    prefs.set(PreferenceConstants.CODE_SUGGESTIONS_VERSION_WARNING_SUPPRESSED, joined)
  }

  @Synchronized
  fun dismissNoCreditsToday() {
    prefs.set(PreferenceConstants.CODE_SUGGESTIONS_NO_CREDITS_DISMISSED_DATE, today().toString())
  }

  private fun versionWarning(check: FeatureStateChangeCheck): CodeSuggestionsWarning? {
    val baseUrl = check.context?.get("baseUrl").asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val version = check.context?.get("version").asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val key = normalise(baseUrl)
    if (key in shownVersionUrls || key in suppressedUrls()) return null
    shownVersionUrls.add(key)
    return CodeSuggestionsWarning.UnsupportedVersion(baseUrl, version)
  }

  private fun creditsWarning(): CodeSuggestionsWarning? {
    if (creditsShown || dismissedToday()) return null
    creditsShown = true
    return CodeSuggestionsWarning.NoCredits
  }

  private fun dismissedToday(): Boolean {
    val persisted = prefs.get(PreferenceConstants.CODE_SUGGESTIONS_NO_CREDITS_DISMISSED_DATE).trim()
    return try {
      LocalDate.parse(persisted) == today()
    } catch (_: DateTimeParseException) {
      false
    }
  }

  private fun suppressedUrls(): List<String> =
    prefs.get(PreferenceConstants.CODE_SUGGESTIONS_VERSION_WARNING_SUPPRESSED)
      .lineSequence().map { normalise(it) }.filter { it.isNotEmpty() }.distinct().toList()

  private fun normalise(url: String): String = url.trim().trimEnd('/')

  private fun JsonElement?.asStringOrNull(): String? =
    if (this != null && isJsonPrimitive && asJsonPrimitive.isString) asString else null

  private companion object {
    const val VERSION_CHECK_ID = "code-suggestions-unsupported-gitlab-version"
    const val NO_CREDITS_CHECK_ID = "code-suggestions-no-credits"
  }
}
