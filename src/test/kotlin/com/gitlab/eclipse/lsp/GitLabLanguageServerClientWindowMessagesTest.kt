package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.messages.LanguageServerMessagePresenter
import com.gitlab.eclipse.lsp.plugins.PluginMessageService
import com.gitlab.eclipse.utils.DebugLogging
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Platform
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.osgi.framework.Bundle
import java.util.concurrent.CompletableFuture

/** LS-08: the client's window/showMessage, window/showMessageRequest and window/logMessage. */
class GitLabLanguageServerClientWindowMessagesTest : DescribeSpec({
  val pluginMessageService = mockk<PluginMessageService>()
  val messagePresenter = mockk<LanguageServerMessagePresenter>(relaxUnitFun = true)

  extensions(LoggingKotestExtension)

  beforeSpec {
    startKoin { modules(module { single<LanguageServerMessagePresenter> { messagePresenter } }) }
  }

  afterEach { clearAllMocks() }

  afterSpec { stopKoin() }

  it("hands window/showMessage to the presenter with this connection's session") {
    val client = GitLabLanguageServerClient(pluginMessageService)
    val params = MessageParams(MessageType.Error, "hello")

    client.showMessage(params)

    verify(exactly = 1) { messagePresenter.show(client.session, params) }
  }

  it("answers window/showMessageRequest with the presenter's future for this connection's session") {
    val client = GitLabLanguageServerClient(pluginMessageService)
    val params = ShowMessageRequestParams(listOf(MessageActionItem("Go")))
    val answer = CompletableFuture<MessageActionItem?>()
    every { messagePresenter.request(client.session, params) } returns answer

    client.showMessageRequest(params) shouldBeSameInstanceAs answer
  }

  it("logs only the type of window messages, never their payload") {
    val log = mockk<ILog>(relaxed = true)
    val statuses = mutableListOf<IStatus>()
    every { log.log(capture(statuses)) } returns Unit
    every { log.bundle.symbolicName } returns "test.bundle"
    every { Platform.getLog(any<Bundle>()) } returns log
    every { messagePresenter.request(any(), any()) } returns CompletableFuture()
    val previous = DebugLogging.isEnabled
    DebugLogging.isEnabled = { true }
    try {
      val client = GitLabLanguageServerClient(pluginMessageService)
      client.showMessage(MessageParams(MessageType.Error, "SENTINEL-SHOW"))
      client.showMessageRequest(
        ShowMessageRequestParams(listOf(MessageActionItem("SENTINEL-TITLE"))).apply { message = "SENTINEL-ASK" },
      )
      client.logMessage(MessageParams(MessageType.Info, "SENTINEL-LOG"))
    } finally {
      DebugLogging.isEnabled = previous
    }

    statuses.map { it.message } shouldContain "[debug] logMessage received (type=INFO)"
    statuses.forEach { it.message shouldNotContain "SENTINEL" }
    verify(exactly = 0) { log.info(match<String> { it.contains("SENTINEL") }) }
    verify(exactly = 0) { log.warn(match<String> { it.contains("SENTINEL") }) }
    verify(exactly = 0) { log.error(match<String> { it.contains("SENTINEL") }) }
  }
})
