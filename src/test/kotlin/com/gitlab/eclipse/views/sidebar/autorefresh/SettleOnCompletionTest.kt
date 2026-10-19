package com.gitlab.eclipse.views.sidebar.autorefresh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val HEAD = TrackedHead("/repo/.git", HeadKey.Branch("main"))

/**
 * Review focus 1 / F3: whatever happens to a refresh's job — completed, failed, cancelled, or never
 * started because the shared scope was already cancelled — the refresher hears exactly one settle
 * for its ticket, through the hop, so auto refresh can never stall on a lost ticket.
 */
class SettleOnCompletionTest : StringSpec({
  fun recorder(): Pair<MutableList<Pair<Long, SettleOutcome>>, (Long, SettleOutcome) -> Unit> {
    val settled = mutableListOf<Pair<Long, SettleOutcome>>()
    return settled to { ticket, outcome -> settled += ticket to outcome }
  }

  val hops = mutableListOf<String>()
  val hop: (() -> Unit) -> Unit = { block ->
    hops += "hop"
    block()
  }

  "a job whose scope was already cancelled settles once as Unknown, through the hop" {
    hops.clear()
    val (settled, settle) = recorder()
    var ran = false
    val job = immediateScope(Job().apply { cancel() }).launch { ran = true }

    settleOnCompletion(job, 7, hop, { SettleOutcome.Looked(HEAD) }, settle)

    ran shouldBe false
    settled shouldContainExactly listOf(7L to SettleOutcome.Unknown)
    hops shouldBe listOf("hop")
  }

  "a completed job settles with the outcome its fetch recorded" {
    val (settled, settle) = recorder()
    val job = immediateScope().launch { }
    settleOnCompletion(job, 3, hop, { SettleOutcome.NoRepository }, settle)
    settled shouldContainExactly listOf(3L to SettleOutcome.NoRepository)
  }

  "a completed job whose fetch recorded nothing settles as Unknown" {
    val (settled, settle) = recorder()
    val job = immediateScope().launch { }
    settleOnCompletion(job, 4, hop, { null }, settle)
    settled shouldContainExactly listOf(4L to SettleOutcome.Unknown)
  }

  "a failed job settles as Unknown, even if a head was recorded" {
    val (settled, settle) = recorder()
    val quiet = CoroutineExceptionHandler { _, _ -> } // the failure is the point; keep it off stderr
    val scope = CoroutineScope(immediateScope().coroutineContext + quiet)
    val job = scope.launch(start = CoroutineStart.LAZY) { error("bug") }
    settleOnCompletion(job, 5, hop, { SettleOutcome.Looked(HEAD) }, settle)
    job.start()
    settled shouldContainExactly listOf(5L to SettleOutcome.Unknown)
  }

  "a job cancelled while running settles once as Unknown" {
    val (settled, settle) = recorder()
    val job = immediateScope().launch(start = CoroutineStart.LAZY) { }
    settleOnCompletion(job, 6, hop, { SettleOutcome.Looked(HEAD) }, settle)
    job.cancel()
    settled shouldContainExactly listOf(6L to SettleOutcome.Unknown)
  }
})
