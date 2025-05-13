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
}

enum class TokenProviderType {
  PAT,
  OAUTH
}
