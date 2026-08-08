package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.utils.openDuoChatWindowWithAgenticView
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify

class StartNewAgenticConversationHandlerTest : DescribeSpec({
  beforeSpec { mockkStatic("com.gitlab.eclipse.chat.utils.DuoChatWindowKt") }

  beforeEach { every { openDuoChatWindowWithAgenticView(any()) } returns Unit }

  afterEach { clearAllMocks() }
  afterSpec { unmockkAll() }

  describe("execute") {
    // The literal is the whole contribution of this handler, so it is compared against a literal
    // and not against a constant the production code also reads.
    it("asks the Duo Chat view for the agentic new-conversation view") {
      StartNewAgenticConversationHandler().execute(mockk())

      verify(exactly = 1) { openDuoChatWindowWithAgenticView("newConversation") }
    }
  }
})
