package com.gitlab.eclipse.codesuggestions.status

import com.gitlab.eclipse.authentication.AuthState
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleState
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerPhase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.resources.IFile
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IEditorPart
import org.eclipse.ui.IFileEditorInput
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchWindow
import org.koin.core.error.NoDefinitionFoundException

/** Headless: windows, pages, editors and files are interface mocks; no SWT Display is touched. */
class DuoStatusInputsProviderTest : DescribeSpec({
  val running = LanguageServerLifecycleState(LanguageServerPhase.RUNNING, 3, null, null)

  fun windowWithInput(input: IEditorInput?): IWorkbenchWindow {
    val editor = mockk<IEditorPart> { every { editorInput } returns input }
    val page = mockk<IWorkbenchPage> { every { activeEditor } returns (if (input == null) null else editor) }
    return mockk { every { activePage } returns page }
  }

  fun fileInput(name: String, extension: String?): IFileEditorInput {
    val file = mockk<IFile> {
      every { this@mockk.name } returns name
      every { fileExtension } returns extension
    }
    return mockk { every { this@mockk.file } returns file }
  }

  fun provider(
    lifecycle: LanguageServerLifecycleState = running,
    enabledLanguages: Set<String> = setOf("python"),
    activeLanguageId: (IWorkbenchWindow) -> String? = ::activeEditorLanguageId,
  ) = DuoStatusInputsProvider(
    lifecycle = { lifecycle },
    authState = { AuthState.AUTHENTICATED },
    codeSuggestionsEnabled = { true },
    featureStateKnown = { true },
    primaryCheckId = { null },
    isLanguageEnabled = { it in enabledLanguages },
    activeLanguageId = activeLanguageId,
  )

  describe("inputs") {
    it("copies the global inputs from their sources") {
      val failedAt = LanguageServerLifecycleState(LanguageServerPhase.FAILED, 7, null, null)
      val p = DuoStatusInputsProvider(
        lifecycle = { failedAt },
        authState = { AuthState.SIGN_IN_REQUIRED },
        codeSuggestionsEnabled = { false },
        featureStateKnown = { false },
        primaryCheckId = { "code-suggestions-api-error" },
        isLanguageEnabled = { true },
      )
      p.inputs(null) shouldBe DuoStatusInputs(
        lifecycle = failedAt,
        auth = AuthState.SIGN_IN_REQUIRED,
        codeSuggestionsEnabled = false,
        activeLanguageEnabled = null,
        featureStateKnown = false,
        primaryCheckId = "code-suggestions-api-error",
      )
    }

    it("reads the active editor language of each window separately (two windows differ)") {
      val pythonWindow = windowWithInput(fileInput("main.py", "py"))
      val rubyWindow = windowWithInput(fileInput("main.rb", "rb"))
      val p = provider(enabledLanguages = setOf("python"))

      p.inputs(pythonWindow).activeLanguageEnabled shouldBe true
      p.inputs(rubyWindow).activeLanguageEnabled shouldBe false
    }

    it("is null without a window") {
      provider().inputs(null).activeLanguageEnabled.shouldBeNull()
    }

    it("is null when the window has no active page") {
      val window = mockk<IWorkbenchWindow> { every { activePage } returns null }
      provider().inputs(window).activeLanguageEnabled.shouldBeNull()
    }

    it("is null when the window has no active editor") {
      provider().inputs(windowWithInput(null)).activeLanguageEnabled.shouldBeNull()
    }

    it("is null when the active editor shows no workspace file") {
      provider().inputs(windowWithInput(mockk<IEditorInput>())).activeLanguageEnabled.shouldBeNull()
    }

    it("uses the injected language lookup for the given window") {
      val a = mockk<IWorkbenchWindow>()
      val b = mockk<IWorkbenchWindow>()
      val p = provider(enabledLanguages = setOf("go")) { if (it === a) "go" else "rust" }
      p.inputs(a).activeLanguageEnabled shouldBe true
      p.inputs(b).activeLanguageEnabled shouldBe false
    }

    it("treats a failing language lookup as no editor") {
      val p = provider { throw IllegalStateException() }
      p.inputs(mockk()).activeLanguageEnabled.shouldBeNull()
    }
  }

  describe("status (trim icon/tooltip and menu label from the same window)") {
    it("a window whose language is disabled shows the language row, another window shows enabled") {
      val pythonWindow = windowWithInput(fileInput("main.py", "py"))
      val rubyWindow = windowWithInput(fileInput("main.rb", "rb"))
      val p = provider(enabledLanguages = setOf("python"))

      val enabled = p.status(pythonWindow)
      enabled.icon shouldBe DuoStatusIcon.ENABLED
      enabled.icon.iconName shouldBe "duo_on_edit"
      enabled.tooltip shouldBe "Code suggestions are enabled"
      enabled.menuLabel shouldBe "Code Suggestions: Enabled"

      val disabled = p.status(rubyWindow)
      disabled.icon.iconName shouldBe "duo_off_edit"
      disabled.tooltip shouldBe "Code suggestions are disabled for this language"
      disabled.menuLabel shouldBe "Code Suggestions: Disabled (language)"
    }

    it("follows the lifecycle first") {
      val starting = LanguageServerLifecycleState(LanguageServerPhase.STARTING, 1, null, null)
      val s = provider(lifecycle = starting).status(null)
      s.icon.iconName shouldBe "duo_load_edit"
      s.menuLabel shouldBe "Code Suggestions: Starting..."
    }
  }

  describe("authStateOrUnknown (the module's authState source)") {
    it("passes the provider's state through") {
      authStateOrUnknown { AuthState.AUTHENTICATED } shouldBe AuthState.AUTHENTICATED
    }

    it("yields UNKNOWN when the lookup fails, including with a checked exception (a Koin failure)") {
      authStateOrUnknown { throw IllegalStateException() } shouldBe AuthState.UNKNOWN
      authStateOrUnknown { throw NoDefinitionFoundException("koin") } shouldBe AuthState.UNKNOWN
    }
  }
})
