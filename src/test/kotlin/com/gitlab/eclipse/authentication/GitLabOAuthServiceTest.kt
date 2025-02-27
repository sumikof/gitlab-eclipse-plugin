package com.gitlab.eclipse.authentication

import fi.iki.elonen.NanoHTTPD
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.*
import java.awt.Desktop
import java.net.URI

class GitLabOAuthServiceTest : DescribeSpec({
  describe("GitLabOAuthService") {
    val gitLabOAuthService = spyk(GitLabOAuthService())

    beforeTest {
      mockkStatic(Desktop::class)
    }

    afterTest {
      unmockkAll()
    }

    describe("startOAuthFlow") {
      it("should open the browser with the correct authorization URL") {
        val desktopMock = mockk<Desktop>()
        every { Desktop.isDesktopSupported() } returns true
        every { Desktop.getDesktop() } returns desktopMock
        every { desktopMock.browse(any()) } just Runs

        val serverMock = mockk<NanoHTTPD>()
        every { serverMock.start() } just Runs
        every { gitLabOAuthService.createServer(any(), any()) } returns serverMock

        gitLabOAuthService.startOAuthFlow()

        verify {
          desktopMock.browse(
            match<URI> { uri ->
              uri.toString().startsWith("https://gitlab.com/oauth/authorize") &&
                uri.toString().contains("client_id=") &&
                uri.toString().contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A63343%2Fapi%2Foauth%2Fgitlab%2Fauthorization") &&
                uri.toString().contains("code_challenge=") &&
                uri.toString().contains("code_challenge_method=S256")
            }
          )
          serverMock.start()
        }
      }

      it("should not attempt to open browser when desktop is not supported") {
        every { Desktop.isDesktopSupported() } returns false

        val serverMock = mockk<NanoHTTPD>()
        every { serverMock.start() } just Runs
        every { gitLabOAuthService.createServer(any(), any()) } returns serverMock

        gitLabOAuthService.startOAuthFlow()

        verify(exactly = 0) {
          Desktop.getDesktop()
        }
        verify {
          serverMock.start()
        }
      }
    }
  }
})
