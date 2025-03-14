package com.gitlab.eclipse.authentication

import com.github.scribejava.core.builder.ServiceBuilder
import com.github.scribejava.core.builder.api.DefaultApi20
import com.github.scribejava.core.oauth.AccessTokenRequestParams
import com.github.scribejava.core.oauth.OAuth20Service
import com.github.scribejava.core.oauth2.clientauthentication.ClientAuthentication
import com.github.scribejava.core.oauth2.clientauthentication.RequestBodyAuthenticationScheme
import com.gitlab.eclipse.inject.service
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.awt.Desktop
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CompletableFuture

class GitLabOAuthService {
  companion object {
    private const val CLIENT_ID = "ee276bb6507af1f6a7eb086d1a07c5cd1bc3c192b631a214d9f8bba35fb9178a"
    private const val REDIRECT_URI = "http://127.0.0.1:63343/api/oauth/gitlab/authorization"
    private const val AUTHORIZATION_ENDPOINT = "https://gitlab.com/oauth/authorize"
    private const val TOKEN_ENDPOINT = "https://gitlab.com/oauth/token"
    private const val CALLBACK_PORT = 63343
    private const val SCOPE = "api"
  }

  private val oauthService: OAuth20Service = ServiceBuilder(CLIENT_ID)
    .debug()
    .callback(REDIRECT_URI)
    .build(object : DefaultApi20() {
      override fun getAccessTokenEndpoint(): String = TOKEN_ENDPOINT
      override fun getAuthorizationBaseUrl(): String = AUTHORIZATION_ENDPOINT
      override fun getRefreshTokenEndpoint(): String = TOKEN_ENDPOINT
      override fun getClientAuthentication(): ClientAuthentication = RequestBodyAuthenticationScheme.instance()
    })

  private val gson = Gson()

  fun startOAuthFlow() {
    val codeVerifier = generateCodeVerifier()
    val codeChallenge = generateCodeChallenge(codeVerifier)

    // Open in default browser
    if (Desktop.isDesktopSupported()) {
      val authUrl = "${oauthService.authorizationUrl}&code_challenge=$codeChallenge&code_challenge_method=S256"
      Desktop.getDesktop().browse(URI(authUrl))
    }

    // Start the local HTTP server to listen for the callback
    val future = CompletableFuture<String>()
    val server = createServer(CALLBACK_PORT) { code ->
      // Exchange code for an access token
      val tokenRequest = AccessTokenRequestParams(code)
        .scope(SCOPE)
        .pkceCodeVerifier(codeVerifier)

      val token = oauthService.getAccessToken(tokenRequest)
      val gitlabToken = gson.fromJson(token.rawResponse, GitLabAuthorizationToken::class.java)

      service<OAuthTokenProvider>().updateToken(gitlabToken)

      future.complete(code)
    }
    server.start()
  }

  @Suppress("SwallowedException")
  fun refreshToken(currentToken: String): GitLabAuthorizationToken? {
    try {
      val newToken = oauthService.refreshAccessToken(currentToken, SCOPE)
      val gitlabToken = gson.fromJson(newToken.rawResponse, GitLabAuthorizationToken::class.java)
      return gitlabToken
    } catch (exception: Exception) {
      return null
    }
  }

  internal fun createServer(port: Int, onCodeReceived: (String) -> Unit): NanoHTTPD {
    return OAuthCallbackServer(port, onCodeReceived)
  }

  @Suppress("MagicNumber")
  private fun generateCodeVerifier(): String {
    val randomBytes = ByteArray(32)
    java.security.SecureRandom().nextBytes(randomBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
  }

  // Utility function to hash the code_verifier using SHA-256 (code_challenge)
  private fun generateCodeChallenge(codeVerifier: String): String {
    val bytes = codeVerifier.toByteArray(StandardCharsets.US_ASCII)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
  }
}

class OAuthCallbackServer(port: Int, private val onCodeReceived: (String) -> Unit) : NanoHTTPD(port) {
  override fun serve(session: IHTTPSession): Response {
    val parameters = session.parameters
    val code = parameters["code"]?.firstOrNull()

    return if (code != null) {
      onCodeReceived(code)
      newFixedLengthResponse(
        Response.Status.OK,
        "text/html",
        "<h1>Authorization Successful!</h1><p>You can close this window.</p>"
      )
    } else {
      newFixedLengthResponse(
        Response.Status.BAD_REQUEST,
        "text/html",
        "<h1>Error: No authorization code received.</h1>"
      )
    }
  }
}
