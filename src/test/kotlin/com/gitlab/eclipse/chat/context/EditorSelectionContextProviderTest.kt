package com.gitlab.eclipse.chat.context

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.messages.EditorSelectionContext
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.relativePath
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.eclipse.core.resources.IFile
import org.eclipse.core.runtime.Path
import org.eclipse.jface.text.ITextSelection
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.editors.text.TextEditor

class EditorSelectionContextProviderTest : DescribeSpec({
  val platformUtils = mockk<PlatformUtils>()
  val textEditor = mockk<TextEditor>()
  val editorInput = mockk<IEditorInput>()
  val file = mockk<IFile>()
  val selection = mockk<ITextSelection>()

  fun provider(
    onUiThread: (Runnable) -> Unit = { it.run() },
    timeoutMillis: Long = 2_000L
  ) = EditorSelectionContextProvider(platformUtils, onUiThread, timeoutMillis)

  extensions(LoggingKotestExtension)

  beforeSpec { mockkStatic("com.gitlab.eclipse.utils.FileKt") }

  beforeEach {
    every { platformUtils.getActiveTextEditor() } returns textEditor
    every { textEditor.editorInput } returns editorInput
    every { editorInput.getAdapter(IFile::class.java) } returns file
    every { textEditor.selectionProvider.selection } returns selection
    every { file.relativePath } returns Path.fromPortableString("a/main.kt")
    every { selection.text } returns "def"
  }

  afterEach { clearAllMocks() }
  afterSpec { unmockkAll() }

  describe("provide") {
    it("returns the file name and the selected text") {
      provider().provide().get() shouldBe EditorSelectionContext("a/main.kt", "def")
    }

    it("returns null when no text editor is active") {
      every { platformUtils.getActiveTextEditor() } returns null

      provider().provide().get() shouldBe null
    }

    it("returns null when the editor input does not adapt to IFile") {
      every { editorInput.getAdapter(IFile::class.java) } returns null

      provider().provide().get() shouldBe null
    }

    it("returns null when the selection is empty") {
      every { selection.text } returns ""

      provider().provide().get() shouldBe null
    }

    it("returns null when the selection is not a text selection") {
      every { textEditor.selectionProvider.selection } returns null

      provider().provide().get() shouldBe null
    }

    it("returns null when the UI thread does not respond within the deadline") {
      val neverRuns: (Runnable) -> Unit = { }

      provider(onUiThread = neverRuns, timeoutMillis = 50L).provide().get() shouldBe null
    }

    it("discards the result of a UI task that completes after the deadline") {
      val pending = mutableListOf<Runnable>()
      val future = provider(onUiThread = { pending += it }, timeoutMillis = 50L).provide()

      future.get() shouldBe null

      pending.single().run()

      future.get() shouldBe null
    }

    it("returns null when scheduling onto the UI thread throws") {
      val throwing: (Runnable) -> Unit = { error("no display") }

      provider(onUiThread = throwing).provide().get() shouldBe null
    }
  }
})
