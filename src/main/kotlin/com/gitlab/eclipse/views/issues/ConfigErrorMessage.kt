package com.gitlab.eclipse.views.issues

import com.gitlab.eclipse.api.GitLabConfigurationException

/** The user-safe message to display for a config error, or null to use the generic text. */
internal fun configErrorMessage(error: Throwable): String? =
  (error as? GitLabConfigurationException)?.message
