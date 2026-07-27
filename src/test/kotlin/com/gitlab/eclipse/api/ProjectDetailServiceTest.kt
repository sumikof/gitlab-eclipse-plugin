package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabProject
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

class ProjectDetailServiceTest : DescribeSpec({
  val apiClient = mockk<GitLabApiClient>()
  val service = ProjectDetailService(apiClient)

  describe("getProject") {
    it("fetches the project by encoded id") {
      val project = GitLabProject(42, "main")
      val path = slot<String>()
      every { apiClient.fetchObject(capture(path), type = GitLabProject::class.java) } returns project

      val result = service.getProject("g%2Fp")

      result shouldBe project
      path.captured shouldBe "/projects/g%2Fp"
    }
  }
})
