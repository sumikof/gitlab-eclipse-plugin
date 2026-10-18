package com.gitlab.eclipse.lsp.messages

/**
 * Parameters of the language server's `$/gitlab/experimentFeaturesState` notification:
 * `{ enabled: boolean, reason?: string }`, sent since LS 9.x whenever the configuration changes.
 *
 * Both fields are nullable on purpose, as in [OpenUrlParams]: Gson builds this without the Kotlin
 * constructor, so a missing field arrives as `null` whatever the declared type says.
 */
data class ExperimentFeaturesStateParams(val enabled: Boolean? = null, val reason: String? = null)
