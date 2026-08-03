package com.gitlab.eclipse.mergerequests.discussions

import com.gitlab.eclipse.ci.actions.WriteKey
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class DiscussionWriteKeyTest : DescribeSpec({
  describe("DiscussionWriteKey") {
    it("gives forNote the same key for an edit and a delete on one note") {
      val a = DiscussionWriteKey.forNote("https://gl.example.com", "fp-1", "gid://gitlab/Note/1")
      val b = DiscussionWriteKey.forNote("https://gl.example.com", "fp-1", "gid://gitlab/Note/1")
      a shouldBe b
    }

    it("gives forDiscussion the same key for a reply and a resolve on one thread") {
      val a = DiscussionWriteKey.forDiscussion("https://gl.example.com", "fp-1", "reply-1")
      val b = DiscussionWriteKey.forDiscussion("https://gl.example.com", "fp-1", "reply-1")
      a shouldBe b
    }

    it("forMergeRequest sets targetKind and targetId correctly") {
      val key = DiscussionWriteKey.forMergeRequest(
        "https://gl.example.com",
        "fp-1",
        "gid://gitlab/MergeRequest/42",
      )
      key.targetKind shouldBe "mergeRequest"
      key.targetId shouldBe "gid://gitlab/MergeRequest/42"
    }

    it("does not split the key on a trailing slash in the instance url") {
      val withSlash = DiscussionWriteKey.forNote("https://gl.example.com/", "fp-1", "x")
      val withoutSlash = DiscussionWriteKey.forNote("https://gl.example.com", "fp-1", "x")
      withSlash shouldBe withoutSlash
    }

    it("does not equate keys with different authFingerprint on the same url") {
      val a = DiscussionWriteKey.forNote("https://gl.example.com", "fp-1", "x")
      val b = DiscussionWriteKey.forNote("https://gl.example.com", "fp-2", "x")
      a shouldNotBe b
    }

    it("does not equate a note key and a discussion key with the same id") {
      val note = DiscussionWriteKey.forNote("https://gl.example.com", "fp-1", "x")
      val discussion = DiscussionWriteKey.forDiscussion("https://gl.example.com", "fp-1", "x")
      note shouldNotBe discussion
    }

    it("is never equal to a ci actions WriteKey, in both directions") {
      val ci: Any = WriteKey("https://gl.example.com", "job", 7L)
      val discussion: Any = DiscussionWriteKey.forNote("https://gl.example.com", "fp-1", "7")
      ci shouldNotBe discussion
      discussion shouldNotBe ci
    }
  }
})
