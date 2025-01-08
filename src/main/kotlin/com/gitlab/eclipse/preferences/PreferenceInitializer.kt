package com.gitlab.eclipse.preferences

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer
import org.eclipse.ui.preferences.ScopedPreferenceStore

class PreferenceInitializer(private val store: ScopedPreferenceStore) :
  AbstractPreferenceInitializer() {

  override fun initializeDefaultPreferences() {
    store.setDefault(PreferenceConstants.GITLAB_INSTANCE_URL, "https://gitlab.com")
    store.setDefault(PreferenceConstants.IGNORE_CERTIFICATE_ERRORS, false)
    store.setDefault(PreferenceConstants.TELEMETRY_ENABLED, true)
    store.setDefault(PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL, "info")
    store.setDefault(PreferenceConstants.LANGUAGE_SERVER_STREAM_CODE_GENERATIONS, true)
  }
}
