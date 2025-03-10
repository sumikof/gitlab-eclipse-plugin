package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.NewPromptRequest
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.exactly
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class GitLabDuoChatWebViewClientTest : DescribeSpec({
  val languageService = mockk<GitLabLanguageServer>(relaxUnitFun = true)
  val wrapper: GitLabLanguageServerWrapper = mockk<GitLabLanguageServerWrapper>()

  var client = GitLabDuoChatWebViewClient(wrapper)

  beforeEach {
    every { wrapper.languageServer } returns languageService
    client = GitLabDuoChatWebViewClient(wrapper)
  }

  afterEach {
    clearAllMocks()
  }

  it("should send all held messages when chat is focused") {
    client.notify("newPrompt", NewPromptRequest("message-1"))
    client.notify("newPrompt", NewPromptRequest("message-2"))
    verify(exactly = 0) { languageService.pluginNotification(any()) }

    client.updateFocus(true)

    verify(exactly = 1) {
      languageService.pluginNotification(match { it.payload == NewPromptRequest("message-1") })
      languageService.pluginNotification(match { it.payload == NewPromptRequest("message-2") })
    }
  }

  it("should directly send notification when chat is focused") {
    client.updateFocus(true)

    client.notify("newPrompt", NewPromptRequest("message-1"))

    verify(exactly = 1) { languageService.pluginNotification(match { it.payload == NewPromptRequest("message-1") }) }
  }

  it("should start holding notification when chat loses focus") {
    client.updateFocus(true)
    client.notify("newPrompt", NewPromptRequest("message-1"))
    verify(exactly = 1) { languageService.pluginNotification(match { it.payload == NewPromptRequest("message-1") }) }

    client.updateFocus(false)
    client.notify("newPrompt", NewPromptRequest("message-2"))
    verify(exactly = 1) { languageService.pluginNotification(any()) }

    client.updateFocus(true)
    verify(exactly = 1) { languageService.pluginNotification(match { it.payload == NewPromptRequest("message-2") }) }
  }
})
