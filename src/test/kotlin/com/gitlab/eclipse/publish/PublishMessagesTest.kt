package com.gitlab.eclipse.publish

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PublishMessagesTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("of(PublishOutcome)") {
    it("gives the project url on success") {
      PublishMessages.of(PublishOutcome.Published("https://h/g/p")) shouldContain "https://h/g/p"
    }

    it("discloses the remote url when the push failed, which section 12 requires") {
      val message = PublishMessages.of(PublishOutcome.PushFailed("https://h/g/p", "git@h:g/p.git", true))

      message shouldContain "git@h:g/p.git"
      message shouldContain "https://h/g/p"
      // The user must know a retry will not create a second project.
      message shouldContain "NOT created a second time"
    }

    it("distinguishes a server rejection from a transport failure") {
      PublishMessages.of(PublishOutcome.PushFailed("w", "r", rejected = true)) shouldContain "rejected"
      PublishMessages.of(PublishOutcome.PushFailed("w", "r", rejected = false)) shouldNotContain "rejected"
    }

    it("never repeats the exception type back to the user") {
      val message = PublishMessages.of(PublishOutcome.Failed("java.net.UnknownHostException"))

      message shouldNotContain "UnknownHostException"
      message shouldBe "GitLab: Could not publish. See the Error Log."
    }

    it("says nothing was created when the record could not be persisted") {
      PublishMessages.of(PublishOutcome.RecordPersistenceFailed) shouldContain "nothing was created"
    }
  }

  describe("of(Preflight.Stop)") {
    it("gives every stop reason a distinct, non-empty line") {
      val messages = Preflight.Stop.entries.map { PublishMessages.of(it) }

      messages.toSet().size shouldBe Preflight.Stop.entries.size
      messages.all { it.startsWith("GitLab: ") } shouldBe true
    }

    it("tells the user what to do about a detached HEAD") {
      PublishMessages.of(Preflight.Stop.DETACHED_HEAD) shouldContain "Check out a branch"
    }
  }
})
