package com.gitlab.eclipse.lsp.messages

/**
 * Parameters of the language server's `$/gitlab/experimentFeaturesState` notification:
 * `{ enabled: boolean, reason?: string }`. LS 9.30.0 sends it after the API configuration becomes
 * valid, only for users with a default Duo namespace; `reason` is `"disabled"` or `"unsupported"`
 * when `enabled` is false.
 *
 * Both fields are nullable on purpose, as in [OpenUrlParams]: Gson builds this without the Kotlin
 * constructor, so a missing field arrives as `null` whatever the declared type says.
 */
data class ExperimentFeaturesStateParams(val enabled: Boolean? = null, val reason: String? = null)
