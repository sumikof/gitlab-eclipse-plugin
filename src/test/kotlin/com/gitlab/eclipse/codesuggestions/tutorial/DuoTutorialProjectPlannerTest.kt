package com.gitlab.eclipse.codesuggestions.tutorial

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Every row of the design §9.2 decision table, one example each (spec `DuoTutorialProjectPlannerTest`).
 * The default-location folder is deliberately not part of [DuoTutorialState]: it is never read or
 * written by this feature (R9), so it cannot be an input to the decision.
 */
class DuoTutorialProjectPlannerTest : DescribeSpec({

  describe("no project named 'GitLab Duo Tutorial'") {
    it("plans the create sequence then opening the editor") {
      DuoTutorialProjectPlanner.plan(DuoTutorialState.NoProject) shouldBe DuoTutorialPlan.Actions(
        listOf(
          DuoTutorialAction.CreateProject,
          DuoTutorialAction.OpenProject,
          DuoTutorialAction.CreateFile,
          DuoTutorialAction.OpenEditor,
        ),
      )
    }
  }

  describe("project exists and is closed") {
    it("refuses without opening it, regardless of ownership") {
      DuoTutorialProjectPlanner.plan(DuoTutorialState.ProjectClosed) shouldBe
        DuoTutorialPlan.Refuse(RefuseReason.PROJECT_CLOSED)
    }
  }

  describe("project exists, open, not owned (or ownership unrecorded)") {
    it("refuses and adds no file") {
      val plan = DuoTutorialProjectPlanner.plan(
        DuoTutorialState.ProjectOpen(owned = false, fileExists = false),
      )

      plan shouldBe DuoTutorialPlan.Refuse(RefuseReason.NOT_OWNED)
    }

    it("refuses even when a same-named file already exists in the unowned project") {
      val plan = DuoTutorialProjectPlanner.plan(
        DuoTutorialState.ProjectOpen(owned = false, fileExists = true),
      )

      plan shouldBe DuoTutorialPlan.Refuse(RefuseReason.NOT_OWNED)
    }
  }

  describe("project exists, open, owned, file missing") {
    it("plans creating the file then opening the editor, without recreating the project") {
      val plan = DuoTutorialProjectPlanner.plan(
        DuoTutorialState.ProjectOpen(owned = true, fileExists = false),
      )

      plan shouldBe DuoTutorialPlan.Actions(
        listOf(DuoTutorialAction.CreateFile, DuoTutorialAction.OpenEditor),
      )
    }
  }

  describe("project exists, open, owned, file present") {
    it("plans opening the editor only, never recreating the file") {
      val plan = DuoTutorialProjectPlanner.plan(
        DuoTutorialState.ProjectOpen(owned = true, fileExists = true),
      )

      plan shouldBe DuoTutorialPlan.Actions(listOf(DuoTutorialAction.OpenEditor))
    }

    // Review Focus test 2: the user emptied the file's content and saved, then reran the command.
    // IFile.exists() is still true for an empty file, so the plan must still be OpenEditor only —
    // the empty content is not rewritten (§16 idempotency).
    it("plans opening the editor only when the existing file is empty (Review Focus 2)") {
      val plan = DuoTutorialProjectPlanner.plan(
        DuoTutorialState.ProjectOpen(owned = true, fileExists = true),
      )

      plan.shouldBe(DuoTutorialPlan.Actions(listOf(DuoTutorialAction.OpenEditor)))
      (plan as DuoTutorialPlan.Actions).steps shouldContainExactly listOf(DuoTutorialAction.OpenEditor)
    }
  }
})
