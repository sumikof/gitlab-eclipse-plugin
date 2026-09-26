package com.gitlab.eclipse.codesuggestions.tutorial

/**
 * The observable facts [DuoTutorialProjectPlanner] decides from (design §9.2's decision table).
 *
 * The default-location folder (`<workspace>/GitLab Duo Tutorial`) is deliberately absent: this
 * feature never reads or writes it (R9), so it cannot be an input to the decision. A closed
 * project carries no ownership fields because a closed project's persistent property cannot be
 * read safely, and §9.2 refuses it without trying.
 */
sealed interface DuoTutorialState {
  /** No project named [DuoTutorialContent.PROJECT_NAME] exists in the workspace. */
  data object NoProject : DuoTutorialState

  /** A same-named project exists but is closed. Ownership is not read (persistent property). */
  data object ProjectClosed : DuoTutorialState

  /**
   * A same-named project exists and is open.
   *
   * @param owned whether the project's ID (persistent property) and its location both match the
   *   ownership record this feature wrote (§9.2, `DuoTutorialOwnership.isOwned`).
   * @param fileExists whether [DuoTutorialContent.FILE_NAME] already exists directly under the
   *   project. True for an empty file too — `IFile.exists()` does not look at content. Ignored by
   *   the planner when [owned] is false, but still accepted so a caller does not need a separate
   *   shape for that combination.
   */
  data class ProjectOpen(val owned: Boolean, val fileExists: Boolean) : DuoTutorialState
}

/** Why [DuoTutorialProjectPlanner] refused to act. Both leave the workspace untouched. */
enum class RefuseReason {
  /** Same-named project exists but is closed; opening it to check is not attempted (R6). */
  PROJECT_CLOSED,

  /** Same-named project is open but its ownership record does not match, or none was ever made. */
  NOT_OWNED,
}

/** One step [DuoTutorialWorkspaceWriter] executes, in the order planned. */
sealed interface DuoTutorialAction {
  /** Reserve the state-directory location, create the project there, and record ownership. */
  data object CreateProject : DuoTutorialAction

  /** Open the just-created project. */
  data object OpenProject : DuoTutorialAction

  /** Create [DuoTutorialContent.FILE_NAME] with [DuoTutorialContent.TEXT]. */
  data object CreateFile : DuoTutorialAction

  /**
   * Hand the file off to be opened in an editor. The writer itself never opens an editor (that
   * needs the UI thread); it turns this step into `WriterOutcome.Ready(file)` instead (§9.2).
   */
  data object OpenEditor : DuoTutorialAction
}

/** What [DuoTutorialProjectPlanner.plan] decided. */
sealed interface DuoTutorialPlan {
  /** Do nothing to the workspace; a caller reports [reason] and stops. */
  data class Refuse(val reason: RefuseReason) : DuoTutorialPlan

  /** Execute these steps in order. */
  data class Actions(val steps: List<DuoTutorialAction>) : DuoTutorialPlan
}

/**
 * Pure decision logic for design §9.2's branch table: given the workspace facts in
 * [DuoTutorialState], what should happen — nothing else touches `IProject` or `IFile` here.
 *
 * Split out for the same reason as `ChatIntentRouter`: the decision is what needs exhaustive
 * testing, and it must not need a live workspace (or SWT) to run.
 */
object DuoTutorialProjectPlanner {
  fun plan(state: DuoTutorialState): DuoTutorialPlan = when (state) {
    is DuoTutorialState.NoProject -> DuoTutorialPlan.Actions(
      listOf(
        DuoTutorialAction.CreateProject,
        DuoTutorialAction.OpenProject,
        DuoTutorialAction.CreateFile,
        DuoTutorialAction.OpenEditor,
      ),
    )

    is DuoTutorialState.ProjectClosed -> DuoTutorialPlan.Refuse(RefuseReason.PROJECT_CLOSED)

    is DuoTutorialState.ProjectOpen -> when {
      !state.owned -> DuoTutorialPlan.Refuse(RefuseReason.NOT_OWNED)
      state.fileExists -> DuoTutorialPlan.Actions(listOf(DuoTutorialAction.OpenEditor))
      else -> DuoTutorialPlan.Actions(listOf(DuoTutorialAction.CreateFile, DuoTutorialAction.OpenEditor))
    }
  }
}
