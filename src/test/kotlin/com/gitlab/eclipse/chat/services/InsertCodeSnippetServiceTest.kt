package com.gitlab.eclipse.chat.services

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.utils.CodeFormatter
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.*
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextSelection
import org.eclipse.swt.SwtCallable

class InsertCodeSnippetServiceTest : DescribeSpec({
  val document = mockk<IDocument>()
  val selection = mockk<ITextSelection>()

  val platformUtils = mockk<PlatformUtils>()
  val codeFormatter = mockk<CodeFormatter>()

  val insertCodeSnippetService = InsertCodeSnippetService(platformUtils, codeFormatter)

  extensions(LoggingKotestExtension)

  beforeSpec {
    mockkStatic("com.gitlab.eclipse.utils.DisplayKt")
  }

  beforeEach {
    every { platformUtils.getActiveDocument() } returns document
    every { platformUtils.getActiveSelection() } returns selection

    every { codeFormatter.format(any()) } answers { firstArg() }

    every {
      currentDisplay.syncExec(any())
    } answers {
      firstArg<Runnable>().run()
    }

    every {
      currentDisplay.syncCall<ITextSelection?, Exception>(any())
    } answers {
      firstArg<SwtCallable<ITextSelection, Exception>>().call()
    }
  }

  afterEach { clearAllMocks() }

  afterSpec { unmockkAll() }

  it("should do nothing if there is no active document") {
    every { platformUtils.getActiveDocument() } returns null

    insertCodeSnippetService.insertCodeSnippet("print('Hello World')")

    verify(exactly = 0) { platformUtils.getActiveSelection() }
  }

  it("should replace selection and format code snippet") {
    val codeSnippet = "print('Hello World')"
    val formattedSnippet = "print('Hello World')"
    every { selection.text } returns "old code"
    every { selection.offset } returns 0
    every { selection.length } returns 8
    every { document.replace(0, 8, formattedSnippet) } returns Unit

    insertCodeSnippetService.insertCodeSnippet(codeSnippet)

    verify {
      platformUtils.getActiveDocument()
      platformUtils.getActiveSelection()
      codeFormatter.format(codeSnippet)
      document.replace(0, 8, formattedSnippet)
    }
  }

  it("should insert formatted code snippet at cursor") {
    val codeSnippet = "print('Hello World')"
    val formattedSnippet = "print('Hello World')"
    every { selection.text } returns ""
    every { selection.offset } returns 10
    every { selection.length } returns 0
    every { document.replace(10, 0, formattedSnippet) } returns Unit

    insertCodeSnippetService.insertCodeSnippet(codeSnippet)

    verify {
      platformUtils.getActiveDocument()
      platformUtils.getActiveSelection()
      codeFormatter.format(codeSnippet)
      document.replace(10, 0, formattedSnippet)
    }
  }
})
