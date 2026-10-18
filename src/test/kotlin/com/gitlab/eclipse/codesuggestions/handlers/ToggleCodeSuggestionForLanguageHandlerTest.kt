package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.languages.CodeSuggestionsLanguageService
import com.gitlab.eclipse.codesuggestions.languages.refreshCodeSuggestionsLanguageToggle
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.utils.PlatformUtils
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.IFileEditorInput
import org.eclipse.ui.menus.UIElement
import org.eclipse.ui.texteditor.ITextEditor
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class ToggleCodeSuggestionForLanguageHandlerTest : DescribeSpec({
  val uiElement = mockk<UIElement>(relaxUnitFun = true)
  val executionEvent = mockk<ExecutionEvent>()

  val platformUtils = mockk<PlatformUtils>()

  val activeTextEditor = mockk<ITextEditor>()
  val fileEditorInput = mockk<IFileEditorInput>()

  val codeSuggestionsLanguageService = mockk<CodeSuggestionsLanguageService>(relaxUnitFun = true)
  val gitLabLanguageServerConfigurationService = mockk<GitLabLanguageServerConfigurationService>(relaxUnitFun = true)

  val handler = ToggleCodeSuggestionForLanguageHandler()

  beforeSpec {
    mockkStatic("com.gitlab.eclipse.codesuggestions.languages.CodeSuggestionsLanguageStatusKt")

    startKoin {
      modules(
        module {
          single { platformUtils }
          single { codeSuggestionsLanguageService }
          single { gitLabLanguageServerConfigurationService }
        }
      )
    }
  }

  beforeEach {
    every { platformUtils.getActiveTextEditor() } returns activeTextEditor
    every { activeTextEditor.editorInput } returns fileEditorInput

    every { refreshCodeSuggestionsLanguageToggle() } returns Unit
  }

  afterEach {
    clearAllMocks()
  }

  afterSpec {
    stopKoin()
    unmockkAll()
  }

  describe("execute") {
    it("does nothing when there is no file editor input") {
      every { platformUtils.getActiveTextEditor() } returns activeTextEditor
      every { activeTextEditor.editorInput } returns mockk() // Not a file editor input

      handler.execute(executionEvent)

      verify(exactly = 0) {
        codeSuggestionsLanguageService.toggleLanguage(any())
        gitLabLanguageServerConfigurationService.sendConfiguration()
        refreshCodeSuggestionsLanguageToggle()
      }
    }

    it("should toggle language based on the file extension if present") {
      every { fileEditorInput.file.fileExtension } returns "kt"
      every { fileEditorInput.file.name } returns "Main.kt"

      handler.execute(executionEvent)

      verify {
        codeSuggestionsLanguageService.toggleLanguage("kotlin")
        gitLabLanguageServerConfigurationService.sendConfiguration()
        refreshCodeSuggestionsLanguageToggle()
      }
    }

    it("should toggle the language id of a supported language whose id is not its extension") {
      every { fileEditorInput.file.fileExtension } returns "py"
      every { fileEditorInput.file.name } returns "main.py"

      handler.execute(executionEvent)

      verify { codeSuggestionsLanguageService.toggleLanguage("python") }
    }

    it("should toggle csharp for a C# file") {
      every { fileEditorInput.file.fileExtension } returns "cs"
      every { fileEditorInput.file.name } returns "Program.cs"

      handler.execute(executionEvent)

      verify { codeSuggestionsLanguageService.toggleLanguage("csharp") }
    }

    it("should toggle the same lowercased language id that is sent to the language server for an unknown extension") {
      every { fileEditorInput.file.fileExtension } returns "ZIG"
      every { fileEditorInput.file.name } returns "build.ZIG"

      handler.execute(executionEvent)

      verify { codeSuggestionsLanguageService.toggleLanguage("zig") }
    }

    it("should toggle language based on the file name if its extension is empty") {
      every { fileEditorInput.file.fileExtension } returns ""
      every { fileEditorInput.file.name } returns "Notes."

      handler.execute(executionEvent)

      verify { codeSuggestionsLanguageService.toggleLanguage("notes.") }
    }

    it("should toggle language based on the file name if it has no extension") {
      every { fileEditorInput.file.fileExtension } returns null
      every { fileEditorInput.file.name } returns "Makefile"

      handler.execute(executionEvent)

      verify {
        codeSuggestionsLanguageService.toggleLanguage("makefile")
        gitLabLanguageServerConfigurationService.sendConfiguration()
        refreshCodeSuggestionsLanguageToggle()
      }
    }
  }

  describe("isEnabled") {
    it("should be enabled when there is an active text editor with file input") {
      every { platformUtils.getActiveTextEditor() } returns activeTextEditor
      every { activeTextEditor.editorInput } returns fileEditorInput

      handler.isEnabled shouldBe true
    }

    it("should be disabled when there is no active text editor") {
      every { platformUtils.getActiveTextEditor() } returns null

      handler.isEnabled shouldBe false
    }

    it("should be disabled when the active editor input is not a file editor input") {
      every { platformUtils.getActiveTextEditor() } returns activeTextEditor
      every { activeTextEditor.editorInput } returns mockk() // Not a file editor input

      handler.isEnabled shouldBe false
    }
  }

  describe("updateElement") {
    it("should update the UI element text based on the current file name if the extension is null") {
      every { fileEditorInput.file.fileExtension } returns null
      every { fileEditorInput.file.name } returns "Makefile"
      every { codeSuggestionsLanguageService.isEnabled("makefile") } returns true

      handler.updateElement(uiElement, emptyMap<Any, Any>())

      verify {
        uiElement.setText("Disable Code Suggestions for Makefile")
      }
    }

    it("should update the UI element text based on the current file language") {
      every { fileEditorInput.file.fileExtension } returns "kt"
      every { fileEditorInput.file.name } returns "Main.kt"
      every { codeSuggestionsLanguageService.isEnabled("kotlin") } returns true

      handler.updateElement(uiElement, emptyMap<Any, Any>())

      verify {
        uiElement.setText("Disable Code Suggestions for Kotlin")
      }
    }

    it("should name C# in the UI element text for a C# file") {
      every { fileEditorInput.file.fileExtension } returns "cs"
      every { fileEditorInput.file.name } returns "Program.cs"
      every { codeSuggestionsLanguageService.isEnabled("csharp") } returns true

      handler.updateElement(uiElement, emptyMap<Any, Any>())

      verify {
        uiElement.setText("Disable Code Suggestions for C#")
      }
    }

    it("should name the file in the UI element text if its extension is empty") {
      every { fileEditorInput.file.fileExtension } returns ""
      every { fileEditorInput.file.name } returns "Notes."
      every { codeSuggestionsLanguageService.isEnabled("notes.") } returns false

      handler.updateElement(uiElement, emptyMap<Any, Any>())

      verify {
        uiElement.setText("Enable Code Suggestions for Notes.")
      }
    }

    it("should update the UI element text when code suggestions are disabled") {
      every { fileEditorInput.file.fileExtension } returns "zig"
      every { fileEditorInput.file.name } returns "build.zig"
      every { codeSuggestionsLanguageService.isEnabled("zig") } returns false

      handler.updateElement(uiElement, emptyMap<Any, Any>())

      verify {
        uiElement.setText("Enable Code Suggestions for zig")
      }
    }

    it("should display no action available when there is no file editor input") {
      every { platformUtils.getActiveTextEditor() } returns activeTextEditor
      every { activeTextEditor.editorInput } returns mockk() // Not a file editor input

      handler.updateElement(uiElement, emptyMap<Any, Any>())

      verify {
        uiElement.setText("No code suggestions action available.")
      }
    }

    it("should display no action available when file has no extension or name") {
      every { fileEditorInput.file.fileExtension } returns null
      every { fileEditorInput.file.name } returns null

      handler.updateElement(uiElement, emptyMap<Any, Any>())

      verify {
        uiElement.setText("No code suggestions action available.")
      }
    }
  }
})
