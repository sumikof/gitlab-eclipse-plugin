package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.utils.logger

/**
 * The platform seams of [MrFileDiffController]: Jobs, the UI thread, the Compare editor and
 * notifications. Production wires them to Eclipse; tests use a synchronous fake.
 */
interface MrDiffPorts {
  /** UI thread. Activates an already-open diff editor for [key]; `true` if there was one. */
  fun findOpen(key: MrFileDiffKey): Boolean

  /**
   * Runs [work] in the background under [name]. [onCancelHandle] receives, on the calling (UI)
   * thread, a function that cancels this work; `isCancelled` reflects it.
   */
  fun schedule(name: String, work: (isCancelled: () -> Boolean) -> Unit, onCancelHandle: (() -> Unit) -> Unit)

  /** Runs [block] on the UI thread later, if the display is still alive. */
  fun uiHop(block: () -> Unit)

  /** UI thread. Opens the diff editor for [content]. */
  fun open(content: MrFileDiffContent)

  /** Shows one user notification. */
  fun notify(message: String)
}

/**
 * Opens MR file diffs (REVIEW-01 design §5.1, §7): reuse an open editor, otherwise load in the
 * background and open on the UI thread — but only if this is still the latest request for the file
 * and the bundle is still active. A newer snapshot of the same file cancels the older load; the same
 * snapshot already loading is not loaded twice; different files are independent. Generation checks
 * happen only on the UI thread ([MrFileDiffRegistry] is UI-confined); the background side sees only
 * its own cancellation and [MrFileDiffRegistry.active].
 *
 * UI thread only, including [cancelHandles].
 */
class MrFileDiffController(private val loader: MrFileDiffLoader, private val ports: MrDiffPorts) {
  private val cancelHandles = HashMap<MrFileIdentity, Pair<Long, () -> Unit>>()

  /** UI thread. Opens the diff for [validated], or explains why it cannot. */
  fun open(validated: MrFileDiffRequest.Validated) {
    val request =
      when (validated) {
        is MrFileDiffRequest.Validated.Ok -> validated.request
        is MrFileDiffRequest.Validated.Invalid -> {
          logInvalid(validated.reason)
          ports.notify(MrDiffMessages.message(DiffFailure.INVALID))
          return
        }
      }
    val key = request.key
    if (ports.findOpen(key)) return
    val generation = MrFileDiffRegistry.begin(key) ?: return
    val identity = request.fileIdentity
    cancelHandles.remove(identity)?.second?.invoke()
    ports.schedule(
      JOB_NAME,
      { isCancelled ->
        val outcome = loader.load(request) { isCancelled() || !MrFileDiffRegistry.active }
        ports.uiHop { deliver(request, generation, outcome) }
      },
    ) { cancel ->
      if (MrFileDiffRegistry.isCurrent(key, generation)) cancelHandles[identity] = generation to cancel
    }
  }

  private fun deliver(request: MrFileDiffRequest, generation: Long, outcome: LoadOutcome) {
    val key = request.key
    try {
      if (MrFileDiffRegistry.isCurrent(key, generation)) {
        when (outcome) {
          is LoadOutcome.Ready -> ports.open(outcome.content)
          is LoadOutcome.Failed -> ports.notify(MrDiffMessages.message(outcome.kind))
          LoadOutcome.Cancelled -> Unit
        }
      }
    } finally {
      MrFileDiffRegistry.finish(key, generation)
      if (cancelHandles[request.fileIdentity]?.first == generation) cancelHandles.remove(request.fileIdentity)
    }
  }

  /** The reason code only — never a path, SHA or URL. */
  private fun logInvalid(reason: MrFileDiffRequest.InvalidReason) {
    runCatching { logger<MrFileDiffController>().warn("$INVALID_MESSAGE outcome=INVALID reason=$reason") }
  }

  companion object {
    /** The background Job's name, shown in the Progress view. */
    const val JOB_NAME = "Loading merge request diff…"
    private const val INVALID_MESSAGE = "MR file diff refused:"
  }
}
