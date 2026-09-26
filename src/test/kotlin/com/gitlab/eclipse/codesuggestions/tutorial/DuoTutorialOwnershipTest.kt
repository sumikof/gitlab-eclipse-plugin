package com.gitlab.eclipse.codesuggestions.tutorial

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.eclipse.core.resources.IProject
import org.eclipse.core.runtime.CoreException
import org.eclipse.core.runtime.QualifiedName
import org.eclipse.core.runtime.Status
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.io.IOException
import java.net.URI

/**
 * §9.2: ownership is "ID (persistent property) and location both match", read from an injected
 * preference store so no real workspace is needed (spec `DuoTutorialOwnershipTest`).
 */
class DuoTutorialOwnershipTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val propertyId = QualifiedName("com.gitlab.eclipse", "duoTutorialId")

  /** A preference store holding the two hidden keys in memory, with an injectable save failure. */
  fun fakeStore(id: String = "", location: String = "", failSave: Boolean = false): ScopedPreferenceStore {
    val store = mockk<ScopedPreferenceStore>(relaxed = true)
    var storedId = id
    var storedLocation = location
    every { store.getString(PreferenceConstants.DUO_TUTORIAL_PROJECT_ID) } answers { storedId }
    every { store.getString(PreferenceConstants.DUO_TUTORIAL_PROJECT_LOCATION) } answers { storedLocation }
    val capturedId = slot<String>()
    every {
      store.setValue(PreferenceConstants.DUO_TUTORIAL_PROJECT_ID, capture(capturedId))
    } answers { storedId = capturedId.captured }
    val capturedLocation = slot<String>()
    every {
      store.setValue(PreferenceConstants.DUO_TUTORIAL_PROJECT_LOCATION, capture(capturedLocation))
    } answers { storedLocation = capturedLocation.captured }
    every { store.save() } answers { if (failSave) throw IOException("disk full") else Unit }
    return store
  }

  fun fakeProject(open: Boolean, property: String?, locationUri: String?): IProject {
    val project = mockk<IProject>()
    every { project.isOpen } returns open
    every { project.getPersistentProperty(propertyId) } returns property
    every { project.locationURI } returns locationUri?.let(URI::create)
    return project
  }

  describe("record") {
    it("persists both keys and saves, returning true") {
      val store = fakeStore()
      val ownership = DuoTutorialOwnership(store)

      ownership.record("abc-123", "file:/workspace/state/duo-tutorial/abc-123/") shouldBe true

      store.getString(PreferenceConstants.DUO_TUTORIAL_PROJECT_ID) shouldBe "abc-123"
      store.getString(
        PreferenceConstants.DUO_TUTORIAL_PROJECT_LOCATION,
      ) shouldBe "file:/workspace/state/duo-tutorial/abc-123/"
    }

    it("restores the previous values and returns false when save fails") {
      val store = fakeStore(id = "old-id", location = "old-location", failSave = true)
      val ownership = DuoTutorialOwnership(store)

      ownership.record("new-id", "new-location") shouldBe false

      store.getString(PreferenceConstants.DUO_TUTORIAL_PROJECT_ID) shouldBe "old-id"
      store.getString(PreferenceConstants.DUO_TUTORIAL_PROJECT_LOCATION) shouldBe "old-location"
    }
  }

  describe("isOwned") {
    it("refuses to judge a closed project regardless of what is recorded") {
      val store = fakeStore(id = "abc-123", location = "file:/workspace/state/duo-tutorial/abc-123/")
      val ownership = DuoTutorialOwnership(store)
      val project = fakeProject(
        open = false,
        property = "abc-123",
        locationUri = "file:/workspace/state/duo-tutorial/abc-123/",
      )

      ownership.isOwned(project) shouldBe false
    }

    it("is owned when the property id and the location both match the record") {
      val store = fakeStore(id = "abc-123", location = "file:/workspace/state/duo-tutorial/abc-123/")
      val ownership = DuoTutorialOwnership(store)
      val project = fakeProject(
        open = true,
        property = "abc-123",
        locationUri = "file:/workspace/state/duo-tutorial/abc-123/",
      )

      ownership.isOwned(project) shouldBe true
    }

    it("is not owned when the property id matches but the location does not") {
      val store = fakeStore(id = "abc-123", location = "file:/workspace/state/duo-tutorial/abc-123/")
      val ownership = DuoTutorialOwnership(store)
      val project = fakeProject(
        open = true,
        property = "abc-123",
        locationUri = "file:/workspace/state/duo-tutorial/other/",
      )

      ownership.isOwned(project) shouldBe false
    }

    it("is not owned when the location matches but the property id does not") {
      val store = fakeStore(id = "abc-123", location = "file:/workspace/state/duo-tutorial/abc-123/")
      val ownership = DuoTutorialOwnership(store)
      val project = fakeProject(
        open = true,
        property = "different-id",
        locationUri = "file:/workspace/state/duo-tutorial/abc-123/",
      )

      ownership.isOwned(project) shouldBe false
    }

    it("is not owned when nothing has ever been recorded") {
      val ownership = DuoTutorialOwnership(fakeStore())
      val project = fakeProject(open = true, property = null, locationUri = null)

      ownership.isOwned(project) shouldBe false
    }

    it("is not owned when reading the persistent property throws") {
      val store = fakeStore(id = "abc-123", location = "file:/workspace/state/duo-tutorial/abc-123/")
      val ownership = DuoTutorialOwnership(store)
      val project = mockk<IProject>()
      every { project.isOpen } returns true
      every { project.getPersistentProperty(propertyId) } throws CoreException(Status.error("boom"))

      ownership.isOwned(project) shouldBe false
    }

    // Review Focus test 5: a workspace path containing non-ASCII characters (e.g. a Japanese
    // Windows/macOS username) must record and compare correctly — no character-set assumption.
    it("records and matches a location URI containing non-ASCII characters (Review Focus 5)") {
      val store = fakeStore()
      val ownership = DuoTutorialOwnership(store)
      val nonAsciiLocation = "file:/C:/Users/てすと/workspace/.metadata/" +
        ".plugins/com.gitlab.eclipse/duo-tutorial/abc-123/"

      ownership.record("abc-123", nonAsciiLocation) shouldBe true
      val project = fakeProject(open = true, property = "abc-123", locationUri = nonAsciiLocation)

      ownership.isOwned(project) shouldBe true
    }
  }
})
