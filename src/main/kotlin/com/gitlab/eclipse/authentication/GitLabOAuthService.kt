import com.github.scribejava.core.builder.ServiceBuilder
import com.github.scribejava.core.model.OAuth2AccessToken
import com.github.scribejava.core.oauth.OAuth20Service
import fi.iki.elonen.NanoHTTPD
import java.awt.Desktop
import java.net.URI
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
            .defaultScope("api") // Optional scope
            .build(object : com.github.scribejava.core.builder.api.DefaultApi20() {
                override fun getAccessTokenEndpoint(): String = tokenEndpoint
                override fun getAuthorizationBaseUrl(): String = authorizationEndpoint
            })

        // Step 1: Start the local HTTP server to listen for the callback
        val future = CompletableFuture<String>()
        val server = OAuthCallbackServer(63343) { code ->
            println("Received authorization code: $code")

            // Step 4: Exchange code for an access token
            val accessToken: OAuth2AccessToken = service.getAccessToken(code)
            println("Access Token: ${accessToken.accessToken}")

            future.complete(code)
        }
        server.start()

        // Open in default browser
        val authUrl = service.authorizationUrl
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(authUrl))
        }
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