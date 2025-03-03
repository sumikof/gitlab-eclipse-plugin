package com.gitlab.eclipse.authentication

import com.github.scribejava.core.model.OAuth2AccessToken

class OAuthTokenProvider : TokenProvider {
  private var currentToken: OAuth2AccessToken? = null

  override fun getToken(): String {
    return currentToken?.accessToken.orEmpty()
  }

  fun updateToken(newToken: OAuth2AccessToken) {
    this.currentToken = newToken
  }
}
