package com.gitlab.eclipse.lsp.lifecycle

enum class LanguageServerPhase { STOPPED, STARTING, RUNNING, FAILED }

enum class LanguageServerFailureKind {
  INSTALL_FAILED,
  START_FAILED,
  INITIALIZE_FAILED,
  INITIALIZE_TIMEOUT,
  EXITED_DURING_STARTUP,
  CRASHED,
}

enum class StartOrigin { STARTUP, RESTART }

data class LanguageServerLifecycleState(
  val phase: LanguageServerPhase,
  val generation: Long,
  val origin: StartOrigin?,
  val failure: LanguageServerFailureKind?,
) {
  companion object {
    val INITIAL = LanguageServerLifecycleState(LanguageServerPhase.STOPPED, 0, null, null)
  }
}

fun interface LanguageServerLifecycleListener {
  fun onTransition(old: LanguageServerLifecycleState, new: LanguageServerLifecycleState)
}
