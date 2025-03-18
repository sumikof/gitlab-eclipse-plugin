package com.gitlab.eclipse.codesuggestions.annotation

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.utils.ruler.DuoAnnotationsRulerColumn
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.jface.text.Position
import org.eclipse.jface.text.source.CompositeRuler
import org.eclipse.jface.text.source.IVerticalRulerInfo
import org.eclipse.ui.texteditor.ITextEditor
import org.junit.jupiter.api.assertDoesNotThrow

class CodeSuggestionsSessionAnnotationManagerTest : DescribeSpec({
  val textEditor = mockk<ITextEditor>(relaxed = true)
  val compositeRuler = mockk<CompositeRuler>(relaxed = true)
  val duoRuler = mockk<DuoAnnotationsRulerColumn>(relaxUnitFun = true)

  val manager = CodeSuggestionsSessionAnnotationManager(textEditor)

  extensions(LoggingKotestExtension)

  beforeEach {
    every { textEditor.getAdapter(IVerticalRulerInfo::class.java) } returns compositeRuler
    every { compositeRuler.decoratorIterator } returns listOf(duoRuler).iterator()
  }

  afterEach {
    manager.hide()
    clearAllMocks()
  }

  it("should add a new annotation if none are displayed") {
    manager.display(CodeSuggestionAnnotationType.LOADING, 10)

    val annotation = slot<CodeSuggestionAnnotation>()
    val position = slot<Position>()
    verify { duoRuler.add(capture(annotation), capture(position)) }
    annotation.captured.type shouldBe CodeSuggestionAnnotationType.LOADING
    position.captured.offset shouldBe 10
  }

  it("should replace an existing annotation if already displayed") {
    manager.display(CodeSuggestionAnnotationType.LOADING, 10)
    manager.display(CodeSuggestionAnnotationType.READY, 10)

    val previousAnnotation = slot<CodeSuggestionAnnotation>()
    val annotation = slot<CodeSuggestionAnnotation>()
    val position = slot<Position>()
    verify { duoRuler.replace(capture(previousAnnotation), capture(annotation), capture(position)) }
    previousAnnotation.captured.type shouldBe CodeSuggestionAnnotationType.LOADING
    annotation.captured.type shouldBe CodeSuggestionAnnotationType.READY
    position.captured.offset shouldBe 10
  }

  it("should hide the displayed annotation") {
    manager.display(CodeSuggestionAnnotationType.LOADING, 10)
    manager.hide()

    val annotation = slot<CodeSuggestionAnnotation>()
    verify { duoRuler.remove(capture(annotation)) }
    annotation.captured.type shouldBe CodeSuggestionAnnotationType.LOADING
  }

  it("should handle exceptions when hiding annotations") {
    every { duoRuler.remove(any()) } throws RuntimeException("Test exception")
    manager.display(CodeSuggestionAnnotationType.LOADING, 10)

    assertDoesNotThrow { manager.hide() }
  }
})
