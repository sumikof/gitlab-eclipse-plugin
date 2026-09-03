package com.gitlab.eclipse.snippets

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PatchApplyMessagesTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("of") {
    it("reports how many files were applied") {
      PatchApplyMessages.of(PatchApplyOutcome.Applied(3, 0)) shouldBe
        "GitLab: Applied the patch to 3 file(s)."
    }

    it("discloses pruned backups only when some were deleted") {
      PatchApplyMessages.of(PatchApplyOutcome.Applied(1, 2)) shouldContain "Removed 2 expired patch backup(s)."
      PatchApplyMessages.of(PatchApplyOutcome.Applied(1, 0)) shouldNotContain "expired"
    }

    it("names the backup area only when something was left unrestored") {
      PatchApplyMessages.of(PatchApplyOutcome.RolledBack(2, 1)) shouldContain "1 file(s) were changed outside"
      PatchApplyMessages.of(PatchApplyOutcome.RolledBack(2, 0)) shouldNotContain "outside Eclipse"
    }

    it("counts staged paths and conflicts") {
      PatchApplyMessages.of(PatchApplyOutcome.StagedChanges(2)) shouldContain "2 file(s) have staged changes"
      PatchApplyMessages.of(PatchApplyOutcome.PatchRejected(4)) shouldContain "4 conflict(s)"
      PatchApplyMessages.of(PatchApplyOutcome.DirtyWorkTree(3)) shouldContain "3 file(s) have uncommitted"
    }

    it("never repeats the exception type back to the user") {
      val message = PatchApplyMessages.of(PatchApplyOutcome.Failed("java.io.IOException"))

      message shouldNotContain "IOException"
      message shouldBe "GitLab: Could not apply the patch. See the Error Log."
    }

    it("gives every outcome a distinct, non-empty line") {
      val outcomes = listOf(
        PatchApplyOutcome.Busy,
        PatchApplyOutcome.NoHead,
        PatchApplyOutcome.Empty,
        PatchApplyOutcome.BinaryNotSupported,
        PatchApplyOutcome.GitlinkNotSupported,
        PatchApplyOutcome.TooLarge,
        PatchApplyOutcome.BackupUnavailable,
        PatchApplyOutcome.IndexConflicted,
      )

      val messages = outcomes.map { PatchApplyMessages.of(it) }

      messages.toSet().size shouldBe outcomes.size
      messages.all { it.startsWith("GitLab: ") } shouldBe true
    }
  }
})
