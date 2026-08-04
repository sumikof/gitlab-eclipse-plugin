package com.gitlab.eclipse.preferences

import com.gitlab.eclipse.authentication.TokenProviderType
import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer
import org.eclipse.ui.preferences.ScopedPreferenceStore

class PreferenceInitializer(private val store: ScopedPreferenceStore) : AbstractPreferenceInitializer() {
  override fun initializeDefaultPreferences() {
    store.setDefault(PreferenceConstants.GITLAB_INSTANCE_URL, "https://gitlab.com")
    store.setDefault(PreferenceConstants.TELEMETRY_ENABLED, true)
    store.setDefault(PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL, "info")
    store.setDefault(PreferenceConstants.LANGUAGE_SERVER_STREAM_CODE_GENERATIONS, true)
    store.setDefault(PreferenceConstants.IGNORE_CERTIFICATE_ERRORS, false)
    store.setDefault(PreferenceConstants.CA_CERTIFICATE, "")
    store.setDefault(PreferenceConstants.AUTHENTICATION_TYPE, TokenProviderType.PAT.name)
    store.setDefault(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES, "")
    store.setDefault(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES, "")
    store.setDefault(PreferenceConstants.CLIENT_CERTIFICATE, "")
    store.setDefault(PreferenceConstants.CLIENT_CERTIFICATE_KEY, "")
    store.setDefault(PreferenceConstants.CODE_SUGGESTIONS_ENABLED, true)
    store.setDefault(PreferenceConstants.DUO_CHAT_ENABLED, true)
    store.setDefault(PreferenceConstants.DUO_ENABLED_WITHOUT_GITLAB_PROJECT, true)
    store.setDefault(PreferenceConstants.DUO_CHAT_SELECTED_WEBVIEW, "")
    store.setDefault(PreferenceConstants.SECURITY_SCAN_ENABLED, false)
    store.setDefault(PreferenceConstants.SECURITY_SCAN_ON_SAVE, true)
  }
}
