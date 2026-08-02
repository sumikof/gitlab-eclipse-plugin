package com.gitlab.eclipse.ci.lint

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.resources.IFile
import org.eclipse.core.runtime.Path
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IFileEditorInput
import org.eclipse.ui.IURIEditorInput
import java.net.URI

class ActiveEditorContentTest : DescribeSpec({
  fun fileInput(fullPath: String): IFileEditorInput {
    val file = mockk<IFile>()
    every { file.fullPath } returns Path(fullPath)
    val input = mockk<IFileEditorInput>()
    every { input.file } returns file
    return input
  }

  describe("sourceIdOf") {
    it("IFileEditorInput -> workspace-relative full path") {
      sourceIdOf(fileInput("/proj/.gitlab-ci.yml")) shouldBe "/proj/.gitlab-ci.yml"
    }

    it("same basename under different paths -> different sourceIds") {
      val a = sourceIdOf(fileInput("/projA/.gitlab-ci.yml"))
      val b = sourceIdOf(fileInput("/projB/.gitlab-ci.yml"))
      a shouldBe "/projA/.gitlab-ci.yml"
      b shouldBe "/projB/.gitlab-ci.yml"
      a shouldNotBe b
    }

    it("IURIEditorInput -> absolute URI string") {
      val input = mockk<IURIEditorInput>()
      every { input.uri } returns URI("file:///tmp/x.yml")
      sourceIdOf(input) shouldBe "file:///tmp/x.yml"
    }

    it("other input -> name#identityHashCode, stable for the same instance") {
      val input = mockk<IEditorInput>()
      every { input.name } returns "Untitled"
      val first = sourceIdOf(input)
      val second = sourceIdOf(input)
      first shouldBe "Untitled#${System.identityHashCode(input)}"
      second shouldBe first
    }

    it("other input -> different instances get different sourceIds") {
      val one = mockk<IEditorInput>()
      every { one.name } returns "Untitled"
      val two = mockk<IEditorInput>()
      every { two.name } returns "Untitled"
      sourceIdOf(one) shouldNotBe sourceIdOf(two)
    }

    it("input implementing both IFileEditorInput and IURIEditorInput -> file branch wins") {
      val file = mockk<IFile>()
      every { file.fullPath } returns Path("/proj/.gitlab-ci.yml")
      val input = mockk<IFileEditorInput>(moreInterfaces = arrayOf(IURIEditorInput::class))
      every { input.file } returns file
      every { (input as IURIEditorInput).uri } returns URI("file:///should/not/be/used.yml")
      sourceIdOf(input) shouldBe "/proj/.gitlab-ci.yml"
    }
  }
})
