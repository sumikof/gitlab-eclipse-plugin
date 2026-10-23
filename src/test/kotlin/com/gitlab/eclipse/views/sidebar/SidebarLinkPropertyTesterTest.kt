package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabPipeline
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SidebarLinkPropertyTesterTest : StringSpec({
  val tester = SidebarLinkPropertyTester()

  fun test(receiver: Any?, property: String) = tester.test(receiver, property, emptyArray<Any?>(), null)

  fun pipeline(url: String?) = PipelineNode(
    GitLabPipeline(id = 1, status = "success", webUrl = url),
    children = emptyList(),
    canRetry = false,
    canCancel = false,
    sourceInstanceUrl = "https://gitlab.example.com",
    sourceAuthFingerprint = "fp0",
  )

  "node with an openable web URL -> hasWebUrl true" {
    test(pipeline("https://gitlab.example.com/g/p/-/pipelines/1"), "hasWebUrl") shouldBe true
  }
  "node without a web URL -> hasWebUrl false" {
    test(pipeline(null), "hasWebUrl") shouldBe false
    test(pipeline("file:///x"), "hasWebUrl") shouldBe false
    test(MessageNode("hi"), "hasWebUrl") shouldBe false
  }
  "unknown property -> false even for a node with a URL" {
    test(pipeline("https://h/x"), "other") shouldBe false
    tester.test(pipeline("https://h/x"), null, emptyArray<Any?>(), null) shouldBe false
  }
  "receiver that is not a SidebarNode -> false" {
    test("https://h/x", "hasWebUrl") shouldBe false
    test(null, "hasWebUrl") shouldBe false
  }
})
