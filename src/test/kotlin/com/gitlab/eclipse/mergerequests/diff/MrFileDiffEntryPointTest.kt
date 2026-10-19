package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.views.sidebar.ChangeType
import com.gitlab.eclipse.views.sidebar.ChangedFileNode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class MrFileDiffEntryPointTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  afterEach { MrFileDiffRegistry.resetForTest() }

  describe("diffRequestOf") {
    val source = MrDiffSource(
      instanceUrl = "https://gl.example",
      authFingerprint = "fp",
      projectId = 42L,
      mrIid = 7L,
      namespaceWithPath = "g/p",
      baseSha = FIXTURE_BASE,
      startSha = null,
      headSha = FIXTURE_HEAD,
    )

    it("builds the request from the node, old path to old path and new path to new path") {
      val node = ChangedFileNode(
        oldPath = "src/Old.kt",
        newPath = "src/New.kt",
        changeType = ChangeType.RENAMED,
        diffHeadSha = FIXTURE_HEAD,
        diffSource = source,
        aMode = "100644",
        bMode = "100755",
      )

      val request = diffRequestOf(node).shouldBeInstanceOf<MrFileDiffRequest.Validated.Ok>().request

      request.oldPath shouldBe "src/Old.kt"
      request.newPath shouldBe "src/New.kt"
      request.changeType shouldBe ChangeType.RENAMED
      request.baseSha shouldBe FIXTURE_BASE
      request.headSha shouldBe FIXTURE_HEAD
      request.aMode shouldBe "100644"
      request.bMode shouldBe "100755"
    }

    it("a node without a diff source is invalid") {
      val node = ChangedFileNode("a", "a", ChangeType.MODIFIED, diffHeadSha = null)

      diffRequestOf(node) shouldBe MrFileDiffRequest.Validated.Invalid(MrFileDiffRequest.InvalidReason.NO_SOURCE)
    }
  }

  describe("lifecycle") {
    it("stop deactivates the registry and the controller, then cancels the diff jobs, then closes the diff editors") {
      val calls = mutableListOf<String>()

      MrFileDiffEntryPoint.onStop(
        deactivateController = { calls += "controller (active=${MrFileDiffRegistry.active})" },
        cancelJobs = { calls += "cancel jobs (active=${MrFileDiffRegistry.active})" },
        closeEditors = { calls += "close editors" },
      )

      calls shouldContainExactly listOf("controller (active=false)", "cancel jobs (active=false)", "close editors")
      MrFileDiffRegistry.active shouldBe false
    }

    it("stop still closes the editors when cancelling the jobs throws, and never throws itself") {
      var closed = false

      MrFileDiffEntryPoint.onStop(
        cancelJobs = { throw IllegalStateException("job manager gone") },
        closeEditors = { closed = true },
      )

      closed shouldBe true
    }

    it("stop does not throw when closing the editors throws") {
      MrFileDiffEntryPoint.onStop(cancelJobs = {}, closeEditors = { throw IllegalStateException("no workbench") })

      MrFileDiffRegistry.active shouldBe false
    }

    it("the production stop never builds a controller that was not used yet (R2-1)") {
      MrFileDiffEntryPoint.controllerCreatedForTest() shouldBe false
      var built = 0
      val original = MrFileDiffEntryPoint.controllerFactory
      MrFileDiffEntryPoint.controllerFactory = {
        built++
        error("stop must not build the controller")
      }
      try {
        MrFileDiffEntryPoint.onStop(cancelJobs = {}, closeEditors = {})
      } finally {
        MrFileDiffEntryPoint.controllerFactory = original
      }

      built shouldBe 0
      MrFileDiffEntryPoint.controllerCreatedForTest() shouldBe false
    }

    it("the production stop is safe headless and start re-activates the registry") {
      MrFileDiffEntryPoint.onStop()
      MrFileDiffRegistry.active shouldBe false

      MrFileDiffEntryPoint.onStart()

      MrFileDiffRegistry.active shouldBe true
    }
  }
})
