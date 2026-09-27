package com.gitlab.eclipse.mergerequests.review

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.charset.StandardCharsets

class NewThreadSnapshotStaleTest : DescribeSpec({
  val snapshot = LineSnapshot(File("/tmp/a.kt"), 1, 2, "one\ntwo\n", StandardCharsets.UTF_8)

  describe("newThreadSnapshotStale") {
    it("launches when the editor is clean and still holds the frozen text") {
      newThreadSnapshotStale(snapshot, dirty = false, liveText = String("one\ntwo\n".toCharArray())) shouldBe false
    }

    it("refuses when the live text differs from the frozen text") {
      newThreadSnapshotStale(snapshot, dirty = false, liveText = "zero\none\ntwo\n") shouldBe true
    }

    it("refuses when the editor is dirty even though its text equals the frozen text") {
      newThreadSnapshotStale(snapshot, dirty = true, liveText = "one\ntwo\n") shouldBe true
    }

    it("refuses when the editor has no document any more") {
      newThreadSnapshotStale(snapshot, dirty = false, liveText = null) shouldBe true
    }
  }
})
