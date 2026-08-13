package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.utils.PlatformUtils
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.texteditor.ITextEditor

/**
 * Kept apart from [CodeSuggestionsManagerTest] on purpose: that spec mocks `StyledText`, which
 * needs the native SWT library and therefore cannot run headless. These cases need no SWT, so
 * they stay runnable in CI and in the devcontainer.
 */
class CodeSuggestionsManagerSessionLookupTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun managerWith(onCreate: (ITextEditor) -> CodeSuggestionsSession): CodeSuggestionsManager {
    val platformUtils = mockk<PlatformUtils>(relaxed = true)
    val workbench = mockk<IWorkbench>(relaxed = true)
    val window = mockk<IWorkbenchWindow>(relaxed = true)
    val page = mockk<IWorkbenchPage>(relaxed = true)
    every { platformUtils.getWorkbench() } returns workbench
    every { workbench.workbenchWindows } returns arrayOf(window)
    every { window.pages } returns arrayOf(page)
    return CodeSuggestionsManager(platformUtils = platformUtils, createCodeSuggestionsSession = onCreate)
  }

  describe("isSuggestionDisplayed (issue #74)") {
    // isEnabled must never throw: the platform calls it from BindingManager.computeBindings, and
    // an exception there aborts the whole binding computation.
    it("reports not displayed instead of throwing when the session fails") {
      val editor = mockk<ITextEditor>(relaxed = true)
      val session = mockk<CodeSuggestionsSession>(relaxed = true)
      every { session.isCodeSuggestionDisplayed() } throws RuntimeException("boom")
      val manager = managerWith { session }
      manager.getOrCreateSession(editor)

      manager.isSuggestionDisplayed(editor) shouldBe false
    }

    it("reports not displayed when there is no session at all") {
      val editor = mockk<ITextEditor>(relaxed = true)

      managerWith { mockk(relaxed = true) }.isSuggestionDisplayed(editor) shouldBe false
    }
  }

  describe("getSession (issue #74)") {
    // Enablement checks must not create sessions. The platform runs them while recomputing key
    // bindings, so a construction failure there is thrown out of BindingManager.computeBindings,
    // which aborts the computation and leaves commands stuck disabled.
    it("returns null and creates nothing when the editor has no session") {
      val editor = mockk<ITextEditor>(relaxed = true)
      var created = 0
      val manager = managerWith {
        created++
        mockk(relaxed = true)
      }

      manager.getSession(editor) shouldBe null
      created shouldBe 0
    }

    it("returns the existing session without creating another") {
      val editor = mockk<ITextEditor>(relaxed = true)
      val session = mockk<CodeSuggestionsSession>(relaxed = true)
      var created = 0
      val manager = managerWith {
        created++
        session
      }
      manager.getOrCreateSession(editor)

      manager.getSession(editor) shouldBe session
      created shouldBe 1
    }
  }
})
