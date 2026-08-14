package com.gitlab.eclipse.assignments

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.io.IOException

class SelectedProjectStoreTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun fakeStore(initial: String = "", failSave: Boolean = false): ScopedPreferenceStore {
    val store = mockk<ScopedPreferenceStore>(relaxed = true)
    var value = initial
    every { store.getString(PreferenceConstants.SELECTED_PROJECTS) } answers { value }
    val written = slot<String>()
    every {
      store.setValue(PreferenceConstants.SELECTED_PROJECTS, capture(written))
    } answers { value = written.captured }
    every { store.save() } answers { if (failSave) throw IOException("disk full") else Unit }
    return store
  }

  val assignment = ProjectAssignment(
    repositoryRootPath = "/repo",
    remoteUrl = "https://gitlab.com/g/p.git",
    instanceUrl = "https://gitlab.com",
    namespaceWithPath = "g/p",
    projectId = 42,
  )

  describe("put and find") {
    it("round trips an assignment") {
      val store = SelectedProjectStore(fakeStore())

      store.put(assignment) shouldBe true

      store.find("/repo") shouldBe assignment
    }

    it("keeps both assignments when two repositories are assigned in turn (A13)") {
      val store = SelectedProjectStore(fakeStore())
      val other = assignment.copy(repositoryRootPath = "/other", namespaceWithPath = "g/other")

      store.put(assignment)
      store.put(other)

      store.find("/repo") shouldBe assignment
      store.find("/other") shouldBe other
    }

    it("replaces an existing assignment for the same repository") {
      val store = SelectedProjectStore(fakeStore())
      store.put(assignment)

      store.put(assignment.copy(namespaceWithPath = "g/changed", projectId = 7))

      store.find("/repo")!!.namespaceWithPath shouldBe "g/changed"
      store.find("/repo")!!.projectId shouldBe 7L
    }

    it("returns null for an unassigned repository") {
      SelectedProjectStore(fakeStore()).find("/nope") shouldBe null
    }

    it("treats a corrupt value as empty instead of throwing") {
      SelectedProjectStore(fakeStore("not json")).find("/repo") shouldBe null
    }

    it("drops an entry with no instance url, which cannot be validated") {
      val raw = """[{"repository":"/repo","remoteUrl":"r","namespaceWithPath":"g/p","projectId":1}]"""

      SelectedProjectStore(fakeStore(raw)).find("/repo") shouldBe null
    }
  }

  describe("isEmpty") {
    it("is true before anything is assigned, which is what keeps A8 cheap") {
      SelectedProjectStore(fakeStore()).isEmpty() shouldBe true
    }

    it("is false once an assignment exists") {
      val store = SelectedProjectStore(fakeStore())
      store.put(assignment)

      store.isEmpty() shouldBe false
    }
  }

  describe("persistence failure") {
    it("reports failure and puts the stored value back to the old JSON (A17)") {
      val backing = fakeStore(failSave = true)
      val store = SelectedProjectStore(backing)

      store.put(assignment) shouldBe false

      // Otherwise the shutdown flush would persist an assignment reported as failed —
      // "works this session, gone after a restart", which contradicts A7.
      backing.getString(PreferenceConstants.SELECTED_PROJECTS) shouldBe ""
      store.find("/repo") shouldBe null
    }
  }

  describe("remove") {
    it("drops the assignment") {
      val store = SelectedProjectStore(fakeStore())
      store.put(assignment)

      store.remove("/repo") shouldBe true

      store.find("/repo") shouldBe null
    }
  }
})
