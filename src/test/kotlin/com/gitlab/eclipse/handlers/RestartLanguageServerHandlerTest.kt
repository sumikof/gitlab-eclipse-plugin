package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServerProcessProvider
import com.gitlab.eclipse.lsp.RestartOutcome
import com.gitlab.eclipse.preferences.storage.CapturedLog
import com.gitlab.eclipse.utils.NotificationUtils
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.osgi.framework.Bundle
import org.osgi.framework.FrameworkUtil

class RestartLanguageServerHandlerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val provider = mockk<GitLabLanguageServerProcessProvider>()
  val bundle = mockk<Bundle>()

  beforeSpec {
    startKoin {
      modules(
        module {
          single<GitLabLanguageServerProcessProvider> { provider }
          // Unconfined so the handler's background launch runs inline and
          // the test can verify synchronously.
          single<CoroutineScope> { CoroutineScope(Dispatchers.Unconfined) }
        }
      )
    }
    mockkStatic(FrameworkUtil::class)
    mockkObject(NotificationUtils)
  }

  beforeEach {
    every { FrameworkUtil.getBundle(GitLabLanguageServerProcessProvider::class.java) } returns bundle
    every { NotificationUtils.show(any()) } just runs
  }

  afterEach { clearAllMocks() }

  afterSpec { stopKoin() }

  describe("execute") {
    it("restarts the language server and notifies on success") {
      every { provider.restartForOutcome(bundle) } returns RestartOutcome.RESTARTED

      RestartLanguageServerHandler().execute(mockk())

      verify { provider.restartForOutcome(bundle) }
      verify { NotificationUtils.show("GitLab Language Server restarted.") }
      verify { NotificationUtils.show("Restarting the GitLab Language Server...") }
    }

    it("notifies that the restart failed and can be retried") {
      every { provider.restartForOutcome(bundle) } returns RestartOutcome.FAILED

      RestartLanguageServerHandler().execute(mockk())

      verify { provider.restartForOutcome(bundle) }
      verify {
        NotificationUtils.show(
          "GitLab Language Server restart failed. Check the error log and run the command again to retry."
        )
      }
    }

    it("notifies failure when the restart throws unexpectedly") {
      every { provider.restartForOutcome(bundle) } throws IllegalStateException("boom")

      RestartLanguageServerHandler().execute(mockk())

      verify {
        NotificationUtils.show(
          "GitLab Language Server restart failed. Check the error log and run the command again to retry."
        )
      }
    }

    it("stays quiet when the restart is abandoned because the plugin is stopping") {
      every { provider.restartForOutcome(bundle) } returns RestartOutcome.ABANDONED
      val log = CapturedLog()
      log.install()

      RestartLanguageServerHandler().execute(mockk())

      log.lines.filter { it.contains("restart was abandoned") } shouldHaveSize 1

      verify { provider.restartForOutcome(bundle) }
      verify(exactly = 0) {
        NotificationUtils.show(
          "GitLab Language Server restart failed. Check the error log and run the command again to retry."
        )
      }
      verify(exactly = 0) { NotificationUtils.show("GitLab Language Server restarted.") }
      verify(exactly = 1) { NotificationUtils.show("Restarting the GitLab Language Server...") }
    }
  }
})
