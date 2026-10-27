package com.gitlab.eclipse.chat.quickchat.fix.ui

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IMarker
import org.eclipse.core.resources.IProject
import org.eclipse.core.runtime.CoreException
import org.eclipse.ui.IMarkerResolution

class FixWithDuoMarkerResolutionTest : DescribeSpec({

  fun marker(
    exists: Boolean = true,
    severity: Int = IMarker.SEVERITY_ERROR,
    onFile: Boolean = true,
  ): IMarker = mockk {
    every { exists() } returns exists
    every { isSubtypeOf(IMarker.PROBLEM) } returns true
    every { getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO) } returns severity
    every { resource } returns if (onFile) mockk<IFile>() else mockk<IProject>()
  }

  describe("FixWithDuoMarkerResolutionGenerator") {
    val offered = mockk<IMarkerResolution>()

    fun generator(chatEnabled: Boolean = true) =
      FixWithDuoMarkerResolutionGenerator(chatEnabled = { chatEnabled }, resolution = { offered })

    it("offers one resolution for an error or a warning on a file") {
      listOf(IMarker.SEVERITY_ERROR, IMarker.SEVERITY_WARNING).forEach { severity ->
        val m = marker(severity = severity)
        generator().hasResolutions(m) shouldBe true
        generator().getResolutions(m).toList() shouldContainExactly listOf(offered)
      }
    }

    it("offers nothing for an info marker") {
      val m = marker(severity = IMarker.SEVERITY_INFO)
      generator().hasResolutions(m) shouldBe false
      generator().getResolutions(m).toList().shouldBeEmpty()
    }

    it("offers nothing for a marker that is not on a file") {
      generator().hasResolutions(marker(onFile = false)) shouldBe false
    }

    it("offers nothing while Duo Chat is disabled, without reading the marker") {
      val m = mockk<IMarker>()
      generator(chatEnabled = false).hasResolutions(m) shouldBe false
      generator(chatEnabled = false).getResolutions(m).toList().shouldBeEmpty()
      verify(exactly = 0) { m.exists() }
    }

    it("reads no more of a marker that no longer exists") {
      val m = marker(exists = false)
      generator().hasResolutions(m) shouldBe false
      verify(exactly = 0) { m.isSubtypeOf(any()) }
      verify(exactly = 0) { m.getAttribute(any<String>(), any<Int>()) }
    }

    it("offers nothing when the marker throws") {
      val m = mockk<IMarker> { every { exists() } throws IllegalStateException("gone") }
      generator().hasResolutions(m) shouldBe false
      generator().getResolutions(m).toList().shouldBeEmpty()
      val broken = marker().also { every { it.isSubtypeOf(any()) } throws CoreException(mockk(relaxed = true)) }
      generator().hasResolutions(broken) shouldBe false
    }
  }

  describe("FixWithDuoMarkerResolution") {
    it("is labelled like VS Code") {
      FixWithDuoMarkerResolution(schedule = {}, fix = {}).label shouldBe "Fix with GitLab Duo"
    }

    it("never outranks the language's own fixes") {
      (FixWithDuoMarkerResolution(schedule = {}, fix = {}).relevanceForResolution <= 0) shouldBe true
    }

    it("only schedules the fix: nothing opens inside run") {
      val scheduled = mutableListOf<Runnable>()
      val fixed = mutableListOf<IMarker>()
      val m = mockk<IMarker>()
      FixWithDuoMarkerResolution(schedule = { scheduled += it }, fix = { fixed += it }).run(m)

      fixed.shouldBeEmpty()
      scheduled.size shouldBe 1
      scheduled.single().run()
      fixed shouldContainExactly listOf(m)
    }
  }
})
