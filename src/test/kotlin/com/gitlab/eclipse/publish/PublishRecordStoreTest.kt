package com.gitlab.eclipse.publish

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.io.IOException
import java.time.Instant

class PublishRecordStoreTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  /** A preference store holding one string in memory, with an injectable save failure. */
  fun fakeStore(initial: String = "", failSave: Boolean = false): ScopedPreferenceStore {
    val store = mockk<ScopedPreferenceStore>(relaxed = true)
    var value = initial
    every { store.getString(PreferenceConstants.PUBLISH_RECORDS) } answers { value }
    val written = slot<String>()
    every {
      store.setValue(PreferenceConstants.PUBLISH_RECORDS, capture(written))
    } answers { value = written.captured }
    every { store.save() } answers { if (failSave) throw IOException("disk full") else Unit }
    return store
  }

  val intent = PublishRecord.Intent(
    repositoryRootPath = "/repo",
    instanceUrl = "https://gitlab.com",
    namespacePath = "group",
    projectPath = "thing",
    recordedAt = Instant.ofEpochMilli(1_700_000_000_000),
  )
  val state = PublishRecord.State(
    repositoryRootPath = "/repo",
    instanceUrl = "https://gitlab.com",
    namespacePath = "group",
    projectPath = "thing",
    projectId = 42,
    normalizedRemoteUrl = "https://gitlab.com/group/thing",
    remoteName = "origin",
    projectWebUrl = "https://gitlab.com/group/thing",
  )

  describe("put and find") {
    it("round trips an intent including its recorded time") {
      val store = PublishRecordStore(fakeStore())

      store.put(intent) shouldBe true

      store.find("/repo") shouldBe intent
    }

    it("round trips a state") {
      val store = PublishRecordStore(fakeStore())
      store.put(state)

      store.find("/repo") shouldBe state
    }

    it("replaces the intent with the state, leaving one record for the repository") {
      val backing = fakeStore()
      val store = PublishRecordStore(backing)
      store.put(intent)

      store.put(state)

      store.find("/repo").shouldBeInstanceOf<PublishRecord.State>()
      // One record per repository: the intent is gone, not kept alongside. A two-write
      // transition would be recoverable-into-nothing if it crashed in between (§11 R3-4).
      backing.getString(PreferenceConstants.PUBLISH_RECORDS).count { it == '{' } shouldBe 1
    }

    it("keeps records for other repositories untouched") {
      val store = PublishRecordStore(fakeStore())
      store.put(intent)
      store.put(state.copy(repositoryRootPath = "/other"))

      store.find("/repo") shouldBe intent
      store.find("/other")!!.repositoryRootPath shouldBe "/other"
    }

    it("returns null for an unknown repository") {
      PublishRecordStore(fakeStore()).find("/nope") shouldBe null
    }

    it("treats a corrupt value as empty instead of throwing") {
      PublishRecordStore(fakeStore("not json at all")).find("/repo") shouldBe null
    }

    it("treats an empty value as empty") {
      PublishRecordStore(fakeStore("")).find("/repo") shouldBe null
    }
  }

  describe("persistence failure") {
    it("reports failure and puts the stored value back to the old JSON") {
      val backing = fakeStore(failSave = true)
      val store = PublishRecordStore(backing)

      store.put(intent) shouldBe false

      // Not merely "the snapshot is stale": the store's own value has to be back to the old one,
      // or a later save or the shutdown flush would persist the record we reported as failed
      // (§15.1 R3-9).
      backing.getString(PreferenceConstants.PUBLISH_RECORDS) shouldBe ""
      store.find("/repo") shouldBe null
    }
  }

  describe("remove") {
    it("drops the record and reports success") {
      val store = PublishRecordStore(fakeStore())
      store.put(state)

      store.remove("/repo") shouldBe true

      store.find("/repo") shouldBe null
    }

    it("is a no-op for a repository with no record") {
      PublishRecordStore(fakeStore()).remove("/nope") shouldBe true
    }
  }
})
