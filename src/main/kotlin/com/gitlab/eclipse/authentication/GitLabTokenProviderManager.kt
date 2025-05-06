package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.inject.service

class GitLabTokenProviderManager {
  private val tokenProviders: Map<TokenProviderType, TokenProvider> = mapOf(
    Pair(TokenProviderType.OAUTH, service<OAuthTokenProvider>()),
    Pair(TokenProviderType.PAT, service<PatTokenProvider>())
  )

  fun getToken(tokenProviderType: TokenProviderType = TokenProviderType.PAT): String {
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
