package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class QuickChatAvailabilityTest : DescribeSpec({
  val state = mockk<DuoChatStateService>()

  it("is null while Duo Chat is enabled") {
    every { state.isEnabled } returns true
    duoChatAvailability(state)().shouldBeNull()
  }

  it("is the engaged check's details, or the default text") {
    every { state.isEnabled } returns false
    every { state.getFirstEngagedCheck() } returns FeatureStateChangeCheck("duo-disabled", true, "Duo is off here")
    duoChatAvailability(state)() shouldBe "Duo is off here"
    every { state.getFirstEngagedCheck() } returns null
    duoChatAvailability(state)() shouldBe QuickChatTexts.UNAVAILABLE_DEFAULT
  }
})
