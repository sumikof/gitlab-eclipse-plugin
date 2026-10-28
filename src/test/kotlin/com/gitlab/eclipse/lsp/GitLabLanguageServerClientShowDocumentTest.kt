package com.gitlab.eclipse.lsp

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.lsp4j.ShowDocumentParams
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.CompletableFuture

/**
 * `window/showDocument` hands the server's URI to [ShowDocumentLauncher] exactly as sent: the gate, not the
 * client, decides what to do with surrounding whitespace. Kept apart from [GitLabLanguageServerClientTest],
 * which is already at the size limit.
 */
class GitLabLanguageServerClientShowDocumentTest : DescribeSpec({
  val showDocumentLauncher = mockk<ShowDocumentLauncher>()
  val client = GitLabLanguageServerClient(mockk())

  beforeSpec {
    startKoin { modules(module { single<ShowDocumentLauncher> { showDocumentLauncher } }) }
  }

  afterSpec { stopKoin() }

  describe("window/showDocument URI fidelity") {
    it("hands a whitespace-padded URI to the launcher untouched") {
      val padded = " https://gitlab.com/x "
      every { showDocumentLauncher.show(padded) } returns CompletableFuture.completedFuture(false)

      client.showDocument(ShowDocumentParams(padded)).get().isSuccess shouldBe false

      verify(exactly = 1) { showDocumentLauncher.show(padded) }
    }
  }
})
