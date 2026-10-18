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
   * The token for [instanceUrl] (#115): the selected provider's, else the first other provider's, all asked for
   * that same instance. No fallback while OAuth is selected for its issuer and its first load is in flight or has
   * failed (see [skipFallback]). Callers read [instanceUrl] once and send the token there, never elsewhere.
   */
  fun getToken(instanceUrl: String): String {
    val tokenProviderType = selectedType()

    val tokenByType = tokenProviders[tokenProviderType]?.getToken(instanceUrl)
    if (tokenByType?.isNotEmpty() == true) {
      return tokenByType
    }
    if (skipFallback(tokenProviderType, instanceUrl)) return ""

    for (provider in tokenProviders) {
      val currentToken = provider.value.getToken(instanceUrl)
      if (currentToken.isNotEmpty()) {
        return currentToken
      }
    }
    return ""
  }

  /** Temporary: [getToken] for the configured instance, until its callers pass the URL they send to. */
  fun getToken(): String = getToken(configuredInstanceUrl())

  /**
   * Whether any provider has a token for [instanceUrl], checked in the same order as [getToken]. Never calls
   * [getToken], which may refresh the OAuth token over the network.
   */
  fun hasToken(instanceUrl: String): Boolean {
    val tokenProviderType = selectedType()

    if (tokenProviders[tokenProviderType]?.hasToken(instanceUrl) == true) {
      return true
    }
    if (skipFallback(tokenProviderType, instanceUrl)) return false

    return tokenProviders.values.any { it.hasToken(instanceUrl) }
  }

  /** [hasToken] for the configured instance: a gate only, for callers that do not send the token themselves. */
  fun hasToken(): Boolean = hasToken(configuredInstanceUrl())

  private fun selectedType(): TokenProviderType =
    TokenProviderType.valueOf(preferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE))

  private fun configuredInstanceUrl(): String = preferenceStore.getString(PreferenceConstants.GITLAB_INSTANCE_URL)

  /**
   * Whether to answer "no token" instead of falling back to PAT: OAuth is selected, [instanceUrl] is its issuer,
   * and its first load is in flight or has failed. The PAT read goes to the same secure storage, so it would prompt
   * for the master password again or block the UI thread on its lock. For another instance OAuth never loads, so
   * that instance's PAT is used. Checked after the OAuth call, so it sees that call's outcome.
   */
  private fun skipFallback(selected: TokenProviderType, instanceUrl: String): Boolean =
    selected == TokenProviderType.OAUTH && oAuthTokenProvider.serves(instanceUrl) &&
      oAuthTokenProvider.isLoadPendingOrFailed()
}

enum class TokenProviderType {
  PAT,
  OAUTH
}
