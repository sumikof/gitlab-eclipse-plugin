package com.gitlab.eclipse.lsp.diagnostics

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IMarker
import org.eclipse.core.resources.IResource
import org.eclipse.core.resources.IWorkspace
import org.eclipse.core.resources.IWorkspaceRoot
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.CoreException
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Status
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

private const val GENERATION = DiagnosticMarkerAttributes.ATTR_GENERATION
private const val EPOCH = DiagnosticMarkerAttributes.ATTR_EPOCH
private const val SOURCE = DiagnosticMarkerAttributes.ATTR_SOURCE

private fun coreFailure() = CoreException(Status(IStatus.ERROR, "test", "boom"))

private fun diagnostic(message: String = "boom") =
  Diagnostic(Range(Position(0, 0), Position(0, 1)), message)

/** A marker whose attributes are fixed up front, as the workspace would return them. */
private fun existingMarker(vararg attributes: Pair<String, String>): IMarker {
  val values = attributes.toMap()
  val marker = mockk<IMarker>(relaxUnitFun = true)
  every { marker.getAttribute(any<String>(), any<String>()) } answers {
    values[firstArg<String>()] ?: secondArg()
  }
  return marker
}

/**
 * An [IFile] backed by an in-memory marker store. Created markers really remember the attributes
 * they were given and deleted markers really disappear, so the tests observe the resulting marker
 * set rather than a sequence of mock calls.
 */
private class FakeFile(var failCreateOnCall: Int = 0) {
  val markers = mutableListOf<IMarker>()
  val file = mockk<IFile>(relaxUnitFun = true)
  private var creates = 0

  init {
    every { file.createMarker(DiagnosticMarkerAttributes.TYPE) } answers {
      creates += 1
      if (creates == failCreateOnCall) throw coreFailure()
      newMarker()
    }
    every {
      file.findMarkers(DiagnosticMarkerAttributes.TYPE, false, IResource.DEPTH_ZERO)
    } answers { markers.toTypedArray() }
  }

  fun addExisting(vararg attributes: Pair<String, String>): IMarker {
    val marker = existingMarker(*attributes)
    every { marker.delete() } answers {
      markers.remove(marker)
      Unit
    }
    markers.add(marker)
    return marker
  }

  fun generations(): List<String> = markers.map { it.getAttribute(GENERATION, "") }

  private fun newMarker(): IMarker {
    val values = mutableMapOf<String, Any>()
    val marker = mockk<IMarker>(relaxUnitFun = true)
    every { marker.setAttributes(any<Map<String, Any>>()) } answers {
      values.putAll(firstArg<Map<String, Any>>())
    }
    every { marker.getAttribute(any<String>(), any<String>()) } answers {
      values[firstArg<String>()] as? String ?: secondArg()
    }
    every { marker.delete() } answers {
      markers.remove(marker)
      Unit
    }
    markers.add(marker)
    return marker
  }
}

class DiagnosticMarkerServiceTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val service = DiagnosticMarkerService()

  beforeEach { DiagnosticGenerationRegistry.resetForTest() }
  afterEach { DiagnosticGenerationRegistry.resetForTest() }

  describe("replaceIn") {
    it("creates a marker per diagnostic and drops the previous generation") {
      val fake = FakeFile()
      fake.addExisting(GENERATION to "1", EPOCH to "0")

      service.replaceIn(fake.file, listOf(diagnostic("a"), diagnostic("b")), generation = 2, epoch = 0)

      fake.generations() shouldContainExactly listOf("2", "2")
    }

    // Invariant 1: a half-written generation must never be observable, so the markers created
    // before the failure are removed again and the previous generation is left untouched.
    it("rolls the whole generation back when a marker cannot be created") {
      val fake = FakeFile(failCreateOnCall = 2)
      val old = fake.addExisting(GENERATION to "1", EPOCH to "0")

      service.replaceIn(fake.file, listOf(diagnostic("a"), diagnostic("b")), generation = 2, epoch = 0)

      fake.markers shouldContainExactly listOf(old)
    }

    // Invariant 3: an empty batch is the server saying "this file is clean now".
    it("removes the previous generation when the batch is empty") {
      val fake = FakeFile()
      fake.addExisting(GENERATION to "1", EPOCH to "0")

      service.replaceIn(fake.file, emptyList(), generation = 2, epoch = 0)

      fake.markers.shouldBeEmpty()
    }

    // Invariant 4: repeated publishes for the same file replace, never accumulate.
    it("does not accumulate markers across consecutive generations") {
      val fake = FakeFile()

      service.replaceIn(fake.file, listOf(diagnostic("a")), generation = 1, epoch = 0)
      service.replaceIn(fake.file, listOf(diagnostic("a")), generation = 2, epoch = 0)

      fake.generations() shouldContainExactly listOf("2")
    }

    it("treats a marker with an unreadable generation as stale") {
      val fake = FakeFile()
      fake.addExisting(EPOCH to "0")

      service.replaceIn(fake.file, listOf(diagnostic("a")), generation = 7, epoch = 0)

      fake.generations() shouldContainExactly listOf("7")
    }

    it("does not propagate a failure raised while deleting the previous generation") {
      val file = mockk<IFile>(relaxUnitFun = true)
      every { file.createMarker(DiagnosticMarkerAttributes.TYPE) } returns mockk(relaxUnitFun = true)
      every { file.findMarkers(any(), any(), any()) } throws coreFailure()

      service.replaceIn(file, listOf(diagnostic("a")), generation = 1, epoch = 0)
    }
  }

  describe("applyNow") {
    it("writes the markers while the generation is still the newest one for the file") {
      val fake = FakeFile()
      val generation = DiagnosticGenerationRegistry.nextGeneration("/p/a.kt", 0)!!

      service.applyNow("/p/a.kt", listOf(fake.file), listOf(diagnostic("a")), generation, 0)

      fake.generations() shouldContainExactly listOf(generation.toString())
    }

    it("writes nothing once a newer generation has been registered for the file") {
      val fake = FakeFile()
      val superseded = DiagnosticGenerationRegistry.nextGeneration("/p/a.kt", 0)!!
      DiagnosticGenerationRegistry.nextGeneration("/p/a.kt", 0)

      service.applyNow("/p/a.kt", listOf(fake.file), listOf(diagnostic("a")), superseded, 0)

      fake.markers.shouldBeEmpty()
    }
  }

  describe("workspace wide clean up") {
    lateinit var root: IWorkspaceRoot

    beforeEach {
      root = mockk(relaxUnitFun = true)
      val workspace = mockk<IWorkspace>()
      mockkStatic(ResourcesPlugin::class)
      every { ResourcesPlugin.getWorkspace() } returns workspace
      every { workspace.root } returns root
    }

    afterEach { unmockkStatic(ResourcesPlugin::class) }

    fun stubMarkers(vararg markers: IMarker) {
      every {
        root.findMarkers(DiagnosticMarkerAttributes.TYPE, false, IResource.DEPTH_INFINITE)
      } returns arrayOf(*markers)
    }

    describe("deleteBySource") {
      // The watermark is what makes a late clean up safe: markers published after the source was
      // switched off again belong to a newer decision and must survive.
      it("deletes only markers of that source that are not newer than the watermark") {
        val stale = existingMarker(SOURCE to "sast", GENERATION to "2")
        val newer = existingMarker(SOURCE to "sast", GENERATION to "9")
        val otherSource = existingMarker(SOURCE to "secret_detection", GENERATION to "1")
        stubMarkers(stale, newer, otherSource)

        service.deleteBySource("sast", watermark = 5)

        verify { stale.delete() }
        verify(exactly = 0) { newer.delete() }
        verify(exactly = 0) { otherSource.delete() }
      }

      it("deletes markers of that source whose generation cannot be read") {
        val broken = existingMarker(SOURCE to "sast")
        stubMarkers(broken)

        service.deleteBySource("sast", watermark = 5)

        verify { broken.delete() }
      }
    }

    describe("deleteNotInEpoch") {
      it("keeps the markers of the current epoch and deletes every other one") {
        val current = existingMarker(EPOCH to "3")
        val previous = existingMarker(EPOCH to "2")
        stubMarkers(current, previous)

        service.deleteNotInEpoch(3)

        verify { previous.delete() }
        verify(exactly = 0) { current.delete() }
      }
    }

    describe("deleteAll") {
      it("deletes every gitlab diagnostic marker in the workspace") {
        service.deleteAll()

        verify {
          root.deleteMarkers(DiagnosticMarkerAttributes.TYPE, false, IResource.DEPTH_INFINITE)
        }
      }
    }

    it("does not propagate a failure raised while cleaning up") {
      every { root.findMarkers(any(), any(), any()) } throws coreFailure()

      service.deleteBySource("sast", watermark = 5)
      service.deleteNotInEpoch(3)
    }

    it("schedules nothing when the file is not part of the workspace") {
      every { root.findFilesForLocationURI(any()) } returns emptyArray()

      service.apply("/p/a.kt", listOf(diagnostic("a")), generation = 1, epoch = 0)

      verify(exactly = 0) { root.findMarkers(any(), any(), any()) }
    }
  }
})
