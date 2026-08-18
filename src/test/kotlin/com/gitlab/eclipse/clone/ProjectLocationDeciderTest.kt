package com.gitlab.eclipse.clone

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

class ProjectLocationDeciderTest : StringSpec({
  val workspace = File("/ws")

  "a destination outside the workspace always gets an explicit location" {
    ProjectLocationDecider.decide(File("/elsewhere/repo"), "repo", workspace) shouldBe
      ProjectLocationDecider.Decision.SetLocation(File("/elsewhere/repo"))
  }

  "an explicit location is still set when the names differ outside the workspace" {
    ProjectLocationDecider.decide(File("/elsewhere/bar"), "foo", workspace) shouldBe
      ProjectLocationDecider.Decision.SetLocation(File("/elsewhere/bar"))
  }

  "directly under the workspace root with a matching name uses the default location" {
    ProjectLocationDecider.decide(File("/ws/foo"), "foo", workspace) shouldBe
      ProjectLocationDecider.Decision.UseDefaultLocation
  }

  "directly under the workspace root with a different name is rejected" {
    ProjectLocationDecider.decide(File("/ws/bar"), "foo", workspace) shouldBe
      ProjectLocationDecider.Decision.Rejected
  }

  "a nested path inside the workspace is not the root case" {
    ProjectLocationDecider.decide(File("/ws/nested/bar"), "foo", workspace) shouldBe
      ProjectLocationDecider.Decision.SetLocation(File("/ws/nested/bar"))
  }
})
