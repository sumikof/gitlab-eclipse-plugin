package com.gitlab.eclipse.codesuggestions.listeners

import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.KeyEvent
import org.junit.jupiter.api.assertDoesNotThrow

class CodeSuggestionsKeyListenerTest : DescribeSpec({
  val textWidget = mockk<StyledText>(relaxUnitFun = true)
  val session = mockk<CodeSuggestionsSession>(relaxUnitFun = true)

  var listener = CodeSuggestionsKeyListener(textWidget, session)

  extensions(LoggingKotestExtension)

  beforeEach {
    listener = CodeSuggestionsKeyListener(textWidget, session)
  }

  afterEach {
    clearAllMocks()
  }

  it("should register itself as a key listener") {
    verify { textWidget.addKeyListener(listener) }
  }

  it("should cancel suggestions when arrow keys are pressed") {
    listOf(SWT.ARROW_RIGHT, SWT.ARROW_LEFT, SWT.ARROW_UP, SWT.ARROW_DOWN).forEach { keyCode ->
      val keyEvent = mockk<KeyEvent>()
      keyEvent.keyCode = keyCode
      listener.keyPressed(keyEvent)
    }

    verify(exactly = 4) { session.cancelCodeSuggestion() }
  }

  it("should not cancel suggestions on other key presses") {
    val keyEvent = mockk<KeyEvent>()
    keyEvent.character = SWT.TAB

    listener.keyPressed(keyEvent)

    verify(exactly = 0) { session.cancelCodeSuggestion() }
  }

  it("should unregister itself as a key listener") {
    listener.dispose()

    verify { textWidget.removeKeyListener(listener) }
  }

  it("should not fail if unregistering the listener throws an exception") {
    every { textWidget.removeKeyListener(listener) } throws Exception("Test")

    assertDoesNotThrow { listener.dispose() }
  }
})
