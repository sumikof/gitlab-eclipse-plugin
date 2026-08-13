package com.gitlab.eclipse.snippets

import com.gitlab.eclipse.api.ProjectDetailService
import com.gitlab.eclipse.api.model.GitLabProject
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class SnippetProjectIdResolverTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("resolve") {
    it("encodes the whole namespace path as ONE segment and returns the numeric id") {
      val projectDetail = mockk<ProjectDetailService>()
      val captured = slot<String>()
      every { projectDetail.getProject(capture(captured)) } returns GitLabProject(id = 4711L)

      val id = SnippetProjectIdResolver(projectDetail).resolve("group/sub/proj")

      id shouldBe 4711L
      // Slashes must be encoded: /projects/{id} takes the numeric id or a FULLY encoded path,
      // so `encodePath` (which preserves `/`) would address a different endpoint.
      captured.captured shouldBe "group%2Fsub%2Fproj"
      verify(exactly = 1) { projectDetail.getProject(any()) }
    }
  }
})
