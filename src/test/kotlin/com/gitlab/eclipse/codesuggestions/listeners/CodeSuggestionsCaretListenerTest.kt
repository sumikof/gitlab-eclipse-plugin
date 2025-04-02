package com.gitlab.eclipse.codesuggestions.listeners

import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.swt.custom.CaretEvent
import org.eclipse.swt.custom.StyledText
import org.junit.jupiter.api.assertDoesNotThrow

class CodeSuggestionsCaretListenerTest : DescribeSpec({
  val caretEvent = mockk<CaretEvent>()

  val textWidget = mockk<StyledText>(relaxUnitFun = true)
  val session = mockk<CodeSuggestionsSession>(relaxUnitFun = true)

  val listener = CodeSuggestionsCaretListener(textWidget, session)

  extensions(LoggingKotestExtension)

  beforeEach {
    caretEvent.mockCaretOffset(42)
  }

  afterEach { clearAllMocks() }

  it("should register the caret listener as caret listener") {
    verify { textWidget.addCaretListener(listener) }
  }

  it("should cancel code suggestions when the caret is moved for an unknown reason") {
    listener.setCaretMovementReason(CaretMovementReason.UNKNOWN)

    listener.caretMoved(caretEvent)

    verify { session.cancelCodeSuggestion() }
  }

  it("should not cancel code suggestions when the caret is moved for a user typed reason") {
    listener.setCaretMovementReason(CaretMovementReason.USER_TYPED)

    listener.caretMoved(caretEvent)

    verify(exactly = 0) { session.cancelCodeSuggestion() }
  }

  it("should not cancel code suggestions when the caret is moved for a suggestion accepted reason") {
    listener.setCaretMovementReason(CaretMovementReason.SUGGESTION_ACCEPTED)

    listener.caretMoved(caretEvent)

    verify(exactly = 0) { session.cancelCodeSuggestion() }
  }

  it("should reset the caret movement reason after the caret is moved") {
    listener.setCaretMovementReason(CaretMovementReason.USER_TYPED)

    caretEvent.mockCaretOffset(20)
    listener.caretMoved(caretEvent)
    verify(exactly = 0) { session.cancelCodeSuggestion() }

    caretEvent.mockCaretOffset(21)
    listener.caretMoved(caretEvent)
    verify(exactly = 1) { session.cancelCodeSuggestion() }
  }

  it("should ignore caret events at the offset 0") {
    caretEvent.mockCaretOffset(0)

    listener.caretMoved(caretEvent)

    verify(exactly = 0) { session.cancelCodeSuggestion() }
  }

  it("should not cancel code suggestions when the caret is moved to the same offset") {
    caretEvent.mockCaretOffset(10)

    listener.caretMoved(caretEvent)
    listener.caretMoved(caretEvent)

    verify(exactly = 1) { session.cancelCodeSuggestion() }
  }

  it("should dispose the caret listener") {
    listener.dispose()

    verify { textWidget.removeCaretListener(listener) }
  }

  it("should not throw when removing the listener errors") {
    every { textWidget.removeCaretListener(listener) } throws Exception()

    assertDoesNotThrow { listener.dispose() }
  }
})

// Since caretOffset is a Java field without a getter, mockk is unable to mock it.
private fun CaretEvent.mockCaretOffset(offset: Int) {
  val caretOffsetField = CaretEvent::class.java.getField("caretOffset")
  caretOffsetField.set(this, offset)
}
