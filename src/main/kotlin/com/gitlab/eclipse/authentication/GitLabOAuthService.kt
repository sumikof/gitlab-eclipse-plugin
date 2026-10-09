package com.gitlab.eclipse.authentication

import com.github.scribejava.core.builder.ServiceBuilder
import com.github.scribejava.core.builder.api.DefaultApi20
import com.github.scribejava.core.httpclient.HttpClientConfig
import com.github.scribejava.core.httpclient.jdk.JDKHttpClientConfig
import com.github.scribejava.core.model.OAuth2AccessTokenErrorResponse
import com.github.scribejava.core.oauth.AccessTokenRequestParams
import com.github.scribejava.core.oauth.OAuth20Service
import com.github.scribejava.core.oauth2.OAuth2Error
import com.github.scribejava.core.oauth2.clientauthentication.ClientAuthentication
import com.github.scribejava.core.oauth2.clientauthentication.RequestBodyAuthenticationScheme
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.logger
import com.google.gson.GsonBuilder
import fi.iki.elonen.NanoHTTPD
import java.awt.Desktop
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*

/**
 * @param tokenEndpoint test seam (design §7.1): the OAuth token endpoint. Defaults to GitLab.com.
 * @param httpClientConfig test seam: connect/read timeouts for the single HTTP round trip. Defaults
 *   bound the refresh call so it can never hang indefinitely (design §10, C1).
 */
class GitLabOAuthService(
  tokenEndpoint: String = TOKEN_ENDPOINT,
  httpClientConfig: HttpClientConfig = JDKHttpClientConfig.defaultConfig()
    .withConnectTimeout(CONNECT_TIMEOUT_MS)
    .withReadTimeout(READ_TIMEOUT_MS),
) {
  companion object {
    private const val CLIENT_ID = "ee276bb6507af1f6a7eb086d1a07c5cd1bc3c192b631a214d9f8bba35fb9178a"
    private const val CALLBACK_PORT = 63343
    private const val REDIRECT_URI = "http://127.0.0.1:$CALLBACK_PORT/api/oauth/gitlab/authorization"
    private const val AUTHORIZATION_ENDPOINT = "https://gitlab.com/oauth/authorize"
    private const val TOKEN_ENDPOINT = "https://gitlab.com/oauth/token"
    private const val SCOPE = "api"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Design §9: the only OAuth error codes that mean the refresh token itself is unusable. */
    private val REJECTED_ERRORS =
      setOf(OAuth2Error.INVALID_GRANT, OAuth2Error.INVALID_CLIENT, OAuth2Error.UNAUTHORIZED_CLIENT)
    private val REJECTED_STATUS_RANGE = 400..499
  }

  private val logger by lazy { logger<GitLabOAuthService>() }

  private val oauthService: OAuth20Service = ServiceBuilder(CLIENT_ID)
    .callback(REDIRECT_URI)
    .httpClientConfig(httpClientConfig)
    .build(object : DefaultApi20() {
      override fun getAccessTokenEndpoint(): String = tokenEndpoint
      override fun getAuthorizationBaseUrl(): String = AUTHORIZATION_ENDPOINT
      override fun getRefreshTokenEndpoint(): String = tokenEndpoint
      override fun getClientAuthentication(): ClientAuthentication = RequestBodyAuthenticationScheme.instance()
    })

  private val gson = GsonBuilder()
    .registerTypeAdapter(GitLabAuthorizationToken::class.java, GitLabAuthorizationTokenDeserializer())
    .create()

  private var oauthCallbackServer: OAuthCallbackServer? = null

  fun startOAuthFlow() {
    oauthCallbackServer?.stop()

    val codeVerifier = generateCodeVerifier()
    val codeChallenge = generateCodeChallenge(codeVerifier)

    // Open in default browser
    if (Desktop.isDesktopSupported()) {
      val authUrl = "${oauthService.authorizationUrl}&code_challenge=$codeChallenge&code_challenge_method=S256"
      Desktop.getDesktop().browse(URI(authUrl))
    } else {
      logger.info("Desktop is not supported, cannot open the browser.")
      return
    }

    // Start the local HTTP server to listen for the callback
    try {
      oauthCallbackServer = createServer(codeVerifier)
      oauthCallbackServer?.start()
      logger.info("OAuth Callback server started.")
    } catch (e: java.net.BindException) {
      logger.error("Port $CALLBACK_PORT is already in use. Cannot start the OAuth callback server.", e)
    } catch (e: Exception) {
      logger.error("Failed to start the OAuth callback server.", e)
    }
  }

  fun refreshToken(currentToken: String): RefreshOutcome =
    try {
      val newToken = oauthService.refreshAccessToken(currentToken, SCOPE)
      parseRefreshedToken(newToken.rawResponse)
    } catch (errorResponse: OAuth2AccessTokenErrorResponse) {
      classifyErrorResponse(errorResponse)
    } catch (exception: Exception) {
      classifyFailure(exception)
    }

  private fun parseRefreshedToken(rawResponse: String): RefreshOutcome =
    try {
      val gitlabToken = gson.fromJson(rawResponse, GitLabAuthorizationToken::class.java)
      logger.info("OAuth token refresh: Refreshed")
      RefreshOutcome.Refreshed(gitlabToken)
    } catch (exception: Exception) {
      classifyFailure(exception)
    }

  /** Design §9: allow-list the status/error combinations that mean the refresh token is dead. */
  private fun classifyErrorResponse(errorResponse: OAuth2AccessTokenErrorResponse): RefreshOutcome {
    val status = errorResponse.response.code
    val errorCode: OAuth2Error? = errorResponse.error
    return if (status in REJECTED_STATUS_RANGE && errorCode != null && errorCode in REJECTED_ERRORS) {
      val code = errorCode.errorString
      logger.info("OAuth token refresh: Rejected($code)")
      RefreshOutcome.Rejected(code)
    } else {
      val reason = errorCode?.errorString ?: "http $status"
      logger.info("OAuth token refresh: Transient($reason)")
      RefreshOutcome.Transient(reason)
    }
  }

  /**
   * Classifies any other failure (I/O, timeout, interrupt, an unparsable body, …) as
   * [RefreshOutcome.Transient]. Only the exception's type name is used — never its message, which
   * for ScribeJava's response exceptions embeds the response body (design §9/§12, C14).
   *
   * Internal so the interrupt-flag restoration can be pinned by a unit test with an injected
   * [InterruptedException], instead of a real socket interrupt: a blocking read on a plain socket
   * does not respond to [Thread.interrupt].
   */
  internal fun classifyFailure(exception: Exception): RefreshOutcome {
    if (exception is InterruptedException) {
      Thread.currentThread().interrupt()
    }
    val reason = exception::class.simpleName ?: "Exception"
    logger.info("OAuth token refresh: Transient($reason)")
    return RefreshOutcome.Transient(reason)
  }

  internal fun createServer(codeVerifier: String): OAuthCallbackServer {
    return OAuthCallbackServer(CALLBACK_PORT) { code ->
      val tokenRequest = AccessTokenRequestParams(code)
        .scope(SCOPE)
        .pkceCodeVerifier(codeVerifier)

      val token = oauthService.getAccessToken(tokenRequest)
      val gitlabToken = gson.fromJson(token.rawResponse, GitLabAuthorizationToken::class.java)

      service<OAuthTokenProvider>().updateToken(gitlabToken)
      service<OAuthTokenProvider>().startTokenRefreshTimer(gitlabToken.expiresIn)
    }
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

class OAuthCallbackServer(port: Int, private val onCodeReceived: (String) -> Unit) :
  NanoHTTPD(port) {
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
