import com.github.scribejava.core.builder.ServiceBuilder
import com.github.scribejava.core.model.OAuth2AccessToken
import com.github.scribejava.core.oauth.AccessTokenRequestParams
import com.github.scribejava.core.oauth.OAuth20Service
import fi.iki.elonen.NanoHTTPD
import java.awt.Desktop
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CompletableFuture

class GitLabOAuthService {
    private val clientId = "b5f152d05b136c3e70f1277144429a339a9b1011df3b34157e401e2a899cb163"
//    private val clientSecret = "your_client_secret"
    private val redirectUri = "http://127.0.0.1:63343/api/oauth/gitlab/authorization_code"
    private val authorizationEndpoint = "https://gitlab.com/oauth/authorize"
    private val tokenEndpoint = "https://gitlab.com/oauth/token"

    fun startOAuthFlow() {
        val service: OAuth20Service = ServiceBuilder(clientId)
//            .apiSecret(clientSecret)
            .callback(redirectUri)
            .defaultScope("api")
            .build(object : com.github.scribejava.core.builder.api.DefaultApi20() {
                override fun getAccessTokenEndpoint(): String = tokenEndpoint
                override fun getAuthorizationBaseUrl(): String = authorizationEndpoint
            })

        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        // Open in default browser
        val authUrl = "${service.authorizationUrl}&code_challenge=$codeChallenge&code_challenge_method=S256"

        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(authUrl))
        }



        // Start the local HTTP server to listen for the callback
        val future = CompletableFuture<String>()
        val server = OAuthCallbackServer(63343) { code ->
            println("Received authorization code: $code")
            // Exchange code for an access token

            // map is always null?? Cannot invoke "java.util.Map.putAll(java.util.Map)" because "this.extraParameters" is null
            val extraParams = HashMap<String, String>()
            extraParams["client_id"] = clientId

            //let map of extra parameter
            val tokenRequest = AccessTokenRequestParams(code)
                            .scope("api")
                            .pkceCodeVerifier(codeVerifier)
//                            .addExtraParameters(extraParams)

            println("params: ${tokenRequest.code}")
            println("params: ${tokenRequest.scope}")
            println("params: ${tokenRequest.pkceCodeVerifier}")
//            println("service client id: ${service.clientID}")


            val accessToken: OAuth2AccessToken = service.getAccessToken(tokenRequest)
            println("Access Token: ${accessToken.accessToken}")

            future.complete(code)
        }
        server.start()



    }

    // Utility function to generate a random string (code_verifier)
    fun generateCodeVerifier(): String {
        val randomBytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(randomBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
    }

    // Utility function to hash the code_verifier using SHA-256 (code_challenge)
    fun generateCodeChallenge(codeVerifier: String): String {
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
            newFixedLengthResponse(Response.Status.OK, "text/html", "<h1>Authorization Successful!</h1><p>You can close this window.</p>")
        } else {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/html", "<h1>Error: No authorization code received.</h1>")
        }
    }
}