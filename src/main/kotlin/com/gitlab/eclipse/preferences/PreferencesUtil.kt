package com.gitlab.eclipse.preferences

import org.eclipse.ui.dialogs.PreferencesUtil

fun openGitLabPreferences() {
  PreferencesUtil.createPreferenceDialogOn(
    null,
    "com.gitlab.eclipse.preferences.GitLabPreferencePage",
    emptyArray(),
    null
  ).open()
}
