package com.gitlab.eclipse.views.sidebar.autorefresh

import com.gitlab.eclipse.mergerequests.CurrentBranchGitReader
import com.gitlab.eclipse.views.sidebar.headKeyOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The production [HeadProbe] (CI-04 design §2.2): reads HEAD with [reader] on [scope] (IO, never
 * the UI thread) and answers through [hop] (a `uiHop`, which drops the answer once the display is
 * gone). The answer is sent from the launched job's completion handler, so it goes out exactly
 * once whatever happens to the job — including a scope cancelled before the read ever ran — and is
 * [HeadKey.Unavailable] unless the read completed.
 *
 * A git dir that is no longer a directory answers Unavailable without reading: the reader's
 * `findGitDir` would otherwise walk up the parents and could report an enclosing repository's HEAD.
 */
class GitHeadProbe(
  private val scope: CoroutineScope,
  private val reader: CurrentBranchGitReader,
  private val hop: (() -> Unit) -> Unit,
) : HeadProbe {
  override fun probe(gitDir: String, onResult: (HeadKey) -> Unit) {
    val answer = AtomicReference<HeadKey>(HeadKey.Unavailable)
    val job = scope.launch {
      // Shared scope with a plain Job: nothing may escape. The reader never throws by contract;
      // this catch only keeps a broken contract from cancelling every other user of the scope.
      try {
        val dir = File(gitDir)
        if (dir.isDirectory) answer.set(headKeyOf(reader.read(dir)))
      } catch (@Suppress("SwallowedException") e: Exception) {
        // Answered as Unavailable below; the reader already logs its own failures by class name.
      }
    }
    job.invokeOnCompletion { hop { onResult(answer.get()) } }
  }
}
