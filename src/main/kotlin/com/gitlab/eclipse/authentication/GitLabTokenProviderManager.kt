package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.PreferenceConstants
import org.eclipse.ui.preferences.ScopedPreferenceStore

class GitLabTokenProviderManager(private val preferenceStore: ScopedPreferenceStore = service()) {
  private val oAuthTokenProvider: OAuthTokenProvider = service()
  private val tokenProviders: Map<TokenProviderType, TokenProvider> = mapOf(
    Pair(TokenProviderType.OAUTH, oAuthTokenProvider),
    Pair(TokenProviderType.PAT, service<PatTokenProvider>())
  )

  /**
   * The selected provider's token, else the first other provider's. No fallback while OAuth is selected and its
   * first load is in flight or has failed (see [skipFallback]).
   */
  fun getToken(): String {
    val authenticationType = preferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE)
    val tokenProviderType = TokenProviderType.valueOf(authenticationType)

    val tokenByType = tokenProviders[tokenProviderType]?.getToken()
    if (tokenByType?.isNotEmpty() == true) {
      return tokenByType
    }
    if (skipFallback(tokenProviderType)) return ""

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
    if (skipFallback(tokenProviderType)) return false

    return tokenProviders.values.any { it.hasToken() }
  }

  /**
   * Whether to answer "no token" instead of falling back to PAT: OAuth is selected and its first load is in
   * flight or has failed. The PAT read goes to the same secure storage, so it would prompt for the master
   * password again or block the UI thread on its lock. Checked after the OAuth call, so it sees that call's
   * outcome.
   */
  private fun skipFallback(selected: TokenProviderType): Boolean =
    selected == TokenProviderType.OAUTH && oAuthTokenProvider.isLoadPendingOrFailed()
}

enum class TokenProviderType {
  PAT,
  OAUTH
}
