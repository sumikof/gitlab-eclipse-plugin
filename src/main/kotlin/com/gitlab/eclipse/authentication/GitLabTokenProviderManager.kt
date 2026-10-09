package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.PreferenceConstants
import org.eclipse.ui.preferences.ScopedPreferenceStore

class GitLabTokenProviderManager(private val preferenceStore: ScopedPreferenceStore = service()) {
  private val tokenProviders: Map<TokenProviderType, TokenProvider> = mapOf(
    Pair(TokenProviderType.OAUTH, service<OAuthTokenProvider>()),
    Pair(TokenProviderType.PAT, service<PatTokenProvider>())
  )

  fun getToken(): String {
    val authenticationType = preferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE)
    val tokenProviderType = TokenProviderType.valueOf(authenticationType)

    val tokenByType = tokenProviders[tokenProviderType]?.getToken()
    if (tokenByType?.isNotEmpty() == true) {
      return tokenByType
    }

    for (provider in tokenProviders) {
      val currentToken = provider.value.getToken()
      if (currentToken.isNotEmpty()) {
        return currentToken
      }
    }
    return ""
  }

  /**
   * Whether any provider has a token, checked in the same order as [getToken]. Never calls [getToken],
   * which may refresh the OAuth token over the network.
   */
  fun hasToken(): Boolean {
    val authenticationType = preferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE)
    val tokenProviderType = TokenProviderType.valueOf(authenticationType)

    if (tokenProviders[tokenProviderType]?.hasToken() == true) {
      return true
    }

    return tokenProviders.values.any { it.hasToken() }
  }
}

enum class TokenProviderType {
  PAT,
  OAUTH
}
