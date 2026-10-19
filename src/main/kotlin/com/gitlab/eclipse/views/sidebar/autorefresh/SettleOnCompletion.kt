package com.gitlab.eclipse.views.sidebar.autorefresh

import kotlinx.coroutines.Job

/**
 * Reports the end of refresh [ticket] to the refresher once [job] completes, however it completes
 * (design §2.4, F3): `invokeOnCompletion` fires even for a job that never ran because its shared
 * scope was already cancelled, so the refresher can never wait forever on a lost ticket. [settle]
 * runs through [hop] (a `uiHop`: UI thread, dropped once the display is gone). A normally completed
 * job settles with what its fetch [recorded] (nothing recorded → Unknown); a failed or cancelled
 * one settles as [SettleOutcome.Unknown], which keeps the refresher's tracking as it was.
 */
fun settleOnCompletion(
  job: Job,
  ticket: Long,
  hop: (() -> Unit) -> Unit,
  recorded: () -> SettleOutcome?,
  settle: (Long, SettleOutcome) -> Unit,
) {
  job.invokeOnCompletion { cause ->
    val outcome = if (cause == null) recorded() ?: SettleOutcome.Unknown else SettleOutcome.Unknown
    hop { settle(ticket, outcome) }
  }
}

/** The settle outcome of a fetch that returned [head] (null: no repository resolved). */
fun settleOutcomeOf(head: TrackedHead?): SettleOutcome =
  if (head == null) SettleOutcome.NoRepository else SettleOutcome.Looked(head)
