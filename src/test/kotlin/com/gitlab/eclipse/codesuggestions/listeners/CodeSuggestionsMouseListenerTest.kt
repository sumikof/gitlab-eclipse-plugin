package com.gitlab.eclipse.codesuggestions.listeners

import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.MouseEvent
import org.junit.jupiter.api.assertDoesNotThrow

class CodeSuggestionsMouseListenerTest : DescribeSpec({
  val textWidget = mockk<StyledText>(relaxUnitFun = true)
  val session = mockk<CodeSuggestionsSession>(relaxUnitFun = true)

  var listener = CodeSuggestionsMouseListener(textWidget, session)

  extensions(LoggingKotestExtension)

  beforeEach {
    listener = CodeSuggestionsMouseListener(textWidget, session)
  }

  afterEach {
    clearAllMocks()
  }

  it("should register itself as a mouse listener") {
    verify { textWidget.addMouseListener(listener) }
  }

  it("should cancel code suggestions when mouse is clicked") {
    val mouseEvent = mockk<MouseEvent>()
    listener.mouseDown(mouseEvent)

    verify { session.cancelCodeSuggestion() }
  }

  it("should unregister itself as a mouse listener") {
    listener.dispose()

    verify { textWidget.removeMouseListener(listener) }
  }

  it("should not fail if unregistering the listener throws an exception") {
    every { textWidget.removeMouseListener(listener) } throws Exception("Test")

    assertDoesNotThrow { listener.dispose() }
  }
})
