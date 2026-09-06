package com.gitlab.eclipse.clone

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/** A fragment unique to the NAME_TAKEN wording; absent from [CloneMessages.orphanCleanupInstructions]. */
private const val NAME_TAKEN_FRAGMENT = "既にワークスペースにあるため"

class CloneNotificationsTest : StringSpec({
  val destination = File("/tmp/dest")

  "declined-consent leftover (NAME_TAKEN): the cleanup instructions stand alone" {
    RepositorySource.entries.forEach { source ->
      val outcome = CloneOutcome.ImportSkipped(
        destination,
        ImportSkipReason.NAME_TAKEN,
        source,
        projectName = "proj",
        leftoverProjectName = "orphan",
      )
      val message = CloneNotifications.importNotification(outcome)
      message shouldBe CloneMessages.orphanCleanupInstructions("orphan")
      // A future change that appends the reason's own wording must fail here.
      message shouldNotContain NAME_TAKEN_FRAGMENT
    }
  }

  "the NAME_TAKEN fragment the alone-rule is pinned against really is in that wording" {
    RepositorySource.entries.forEach { source ->
      CloneMessages.importSkipped(ImportSkipReason.NAME_TAKEN, source, "proj") shouldContain
        NAME_TAKEN_FRAGMENT
    }
  }

  "with a leftover, every reason except NAME_TAKEN composes both facts into ONE string" {
    ImportSkipReason.entries.filter { it != ImportSkipReason.NAME_TAKEN }.forEach { reason ->
      RepositorySource.entries.forEach { source ->
        val outcome = CloneOutcome.ImportSkipped(
          destination,
          reason,
          source,
          projectName = "proj",
          leftoverProjectName = "orphan",
        )
        val message = CloneNotifications.importNotification(outcome)
        message shouldContain CloneMessages.importSkipped(reason, source, "proj")
        message shouldContain CloneMessages.orphanCleanupInstructions("orphan")
      }
    }
  }

  "no leftover: the reason's own wording, for every reason and both sources" {
    ImportSkipReason.entries.forEach { reason ->
      RepositorySource.entries.forEach { source ->
        val outcome = CloneOutcome.ImportSkipped(destination, reason, source, projectName = "proj")
        CloneNotifications.importNotification(outcome) shouldBe
          CloneMessages.importSkipped(reason, source, "proj")
      }
    }
  }

  "an import renders the imported wording for its source" {
    RepositorySource.entries.forEach { source ->
      val outcome = CloneOutcome.Imported(destination, "proj", source)
      CloneNotifications.importNotification(outcome) shouldBe
        CloneMessages.imported(source, "proj")
    }
  }
})
