package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.BridgeService
import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.JobService
import com.gitlab.eclipse.api.model.GitLabBridge
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.ci.actions.pinnedConnectionFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sole child of a downstream pipeline whose first load could not pin the connection its node was
 * built over (instance or account changed, or the connection could not be read): nothing was fetched.
 */
internal const val DOWNSTREAM_CONNECTION_UNCONFIRMED_MESSAGE = "Could not confirm the connection. Refresh the view."

/**
 * The REST side of [DownstreamLoader]. Called OFF the UI thread only: [pinConnection] may read the
 * credential (and refresh an expired OAuth token synchronously), and the two fetches block on HTTP.
 */
internal interface DownstreamSource {
  /** The snapshot to pin both fetches to, or null when the node's connection is no longer the current one. */
  fun pinConnection(instanceUrl: String, authFingerprint: String): ConnectionSnapshot?

  fun jobs(projectId: String, pipelineId: Long, isActive: () -> Boolean, connection: ConnectionSnapshot): List<GitLabJob>

  fun bridges(projectId: String, pipelineId: Long, isActive: () -> Boolean, connection: ConnectionSnapshot): List<GitLabBridge>
}

/**
 * Production [DownstreamSource]: the connection gate is the write handlers' own [pinnedConnectionFor]
 * (normalized instance URL compared inside the capture, before the credential is read; fingerprint
 * equality after), and jobs are requested with `include_retried=true` like the root pipeline's.
 */
internal class ServiceDownstreamSource(
  private val apiClient: GitLabApiClient,
  private val jobService: JobService,
  private val bridgeService: BridgeService,
) : DownstreamSource {
  override fun pinConnection(instanceUrl: String, authFingerprint: String): ConnectionSnapshot? =
    pinnedConnectionFor(apiClient, instanceUrl, authFingerprint)

  override fun jobs(projectId: String, pipelineId: Long, isActive: () -> Boolean, connection: ConnectionSnapshot) =
    jobService.getJobsForPipeline(projectId, pipelineId, isActive, connection, includeRetried = true)

  override fun bridges(projectId: String, pipelineId: Long, isActive: () -> Boolean, connection: ConnectionSnapshot) =
    bridgeService.getBridgesForPipeline(projectId, pipelineId, isActive, connection)
}

/** The view side of [DownstreamLoader]. Every call happens on the SWT UI thread. */
internal interface DownstreamTree {
  /** The viewer's current input. */
  fun roots(): List<SidebarNode>

  fun isDisposed(): Boolean

  fun isExpanded(node: SidebarNode): Boolean

  /**
   * Re-renders [node] after its children changed. With [oldChildren] (the sweep replaced them), the
   * expansion of the old children is carried onto the new ones by stable key ([remapInChildren]).
   */
  fun refreshSubtree(node: DownstreamPipelineNode, oldChildren: List<SidebarNode>?)
}

/**
 * Lazy, recursive, refresh-safe loading of [DownstreamPipelineNode] children (CI-06 §4.6). SWT-free:
 * the view supplies the UI hop ([uiDispatch], `asyncExec`), the shared IO scope ([launchOffUi]), the
 * REST side ([source]) and the viewer ([tree]); children are built by [buildChildren]
 * (`SidebarViewModel.buildDownstreamChildren`).
 *
 * Threading: every public function except [requestSweep] runs on the UI thread, and so does every
 * write to a node's load state (`loadState`, `loadedChildren`, `shownPayload`, `loadGeneration`) —
 * the result of an off-UI fetch is always applied inside a [uiDispatch] block. The off-UI side pins
 * the connection and fetches, nothing else; it reads only values captured on the UI thread. Each
 * launched coroutine lets [CancellationException] through and catches everything else, because the
 * shared scope is not a SupervisorJob.
 *
 * Gates (UI thread, at apply time): the view is not disposed, the node is still reachable from the
 * viewer's input by identity (a soft rebuild does not bump the refresh generation, so a replaced node
 * must be detected by walking the tree), and the node's load generation is the one the fetch was
 * started under. Any failing gate drops the result.
 *
 * Logs (through [warnLog]) carry the exception class and, for a [GitLabApiException], the HTTP status
 * — never a bridge or job name, a ref, a URL or a response body. Sweep failures are logged only at the
 * start of a failure streak.
 */
// TooManyFunctions: the first load, the sweep and the shared gates belong together — they share the
// in-flight map, the sweep sequence and the generation / attach checks, all UI-thread-confined state that
// splitting the class would have to hand across an object boundary. Suppressed, as on GitLabSidebarView.
@Suppress("TooManyFunctions")
internal class DownstreamLoader(
  private val launchOffUi: (suspend () -> Unit) -> Job,
  private val uiDispatch: (() -> Unit) -> Unit,
  private val source: DownstreamSource,
  private val tree: DownstreamTree,
  private val buildChildren: (DownstreamPipelineNode, DownstreamPayload) -> List<SidebarNode>,
  private val warnLog: (String) -> Unit,
) {
  // UI thread only: the job of each node's in-flight first load (identity-keyed; nodes do not override equals).
  private val inFlight = IdentityHashMap<DownstreamPipelineNode, Job>()

  // UI thread only: the running sweep, and a counter bumped whenever a sweep starts or is cancelled so
  // a result the cancelled sweep already queued for the UI thread is dropped too.
  private var sweepJob: Job? = null
  private var sweepSeq = 0L

  // Written from the sweep coroutine (IO); atomic because a cancelled sweep may still be finishing
  // while the next one starts.
  private val sweepFailing = AtomicBoolean(false)

  /**
   * UI thread. Starts the first load of an expanded [node] — from the expansion listener, or after a
   * programmatic expansion restore (which fires no event). Only an expandable NOT_LOADED node loads:
   * a LOADING one already has a fetch in flight, a LOADED one is kept fresh by the sweep, and a FAILED
   * one (connection not confirmed) waits for a refresh of the view.
   */
  fun load(node: DownstreamPipelineNode) {
    val request = node.requestOrNull() ?: return
    if (node.loadState != DownstreamLoadState.NOT_LOADED || tree.isDisposed()) return
    node.loadState = DownstreamLoadState.LOADING
    val generation = ++node.loadGeneration
    val job = launchOffUi {
      offUiGuarded {
        val connection = pin(request) { logFailure(it) }
        val payload = connection?.let { fetch(request, it) }
        payload?.failures()?.forEach(::logFailure)
        uiDispatch { applyLoad(node, generation, payload) }
      }
    }
    if (!job.isCompleted) inFlight[node] = job
  }

  /** UI thread. [load]s every [DownstreamPipelineNode] among [elements] (e.g. `viewer.expandedElements`). */
  fun loadAll(elements: Iterable<Any>) {
    elements.filterIsInstance<DownstreamPipelineNode>().forEach(::load)
  }

  /**
   * UI thread. A rebuild is about to replace [oldRoots] with [newRoots]: carries the LOADED state over
   * ([transferDownstreamState]) and cancels the loads it abandoned.
   */
  fun transfer(oldRoots: List<SidebarNode>, newRoots: List<SidebarNode>) {
    abandon(transferDownstreamState(oldRoots, newRoots))
  }

  /**
   * ANY thread (the refresh coroutine, right after its fetch returned). Schedules ONE sweep on the UI
   * thread; it runs only if the view is not disposed and [gate] (the refresh's own apply gate, read on
   * the UI thread) still holds. FIFO `asyncExec` puts it after that refresh's applies.
   */
  fun requestSweep(gate: () -> Boolean) {
    uiDispatch { if (!tree.isDisposed() && gate()) sweep() }
  }

  /** UI thread. Cancels the running sweep and drops whatever it already queued. */
  fun cancelSweep() {
    sweepSeq++
    sweepJob?.cancel()
    sweepJob = null
  }

  /**
   * UI thread (dispose, full refresh). Cancels the sweep and every in-flight first load; a node still
   * LOADING goes back to NOT_LOADED under a new generation, so a late result is dropped and a later
   * expansion loads it again.
   */
  fun cancelAll() {
    cancelSweep()
    inFlight.forEach { (node, job) ->
      job.cancel()
      if (node.loadState == DownstreamLoadState.LOADING) {
        node.loadGeneration++
        node.loadState = DownstreamLoadState.NOT_LOADED
      }
    }
    inFlight.clear()
  }

  // --- first load ---

  private fun applyLoad(node: DownstreamPipelineNode, generation: Long, payload: DownstreamPayload?) {
    if (node.loadGeneration == generation) inFlight.remove(node)
    if (node.loadState != DownstreamLoadState.LOADING || !isLive(node, generation)) return
    if (payload == null) {
      node.loadedChildren = listOf(MessageNode(DOWNSTREAM_CONNECTION_UNCONFIRMED_MESSAGE))
      node.loadState = DownstreamLoadState.FAILED
    } else {
      node.loadedChildren = buildChildren(node, payload)
      node.shownPayload = payload
      node.loadState = DownstreamLoadState.LOADED
    }
    tree.refreshSubtree(node, null)
  }

  // --- refresh sweep ---

  /**
   * UI thread. Walks the LOADED downstream nodes of the current tree: a collapsed one is reset to
   * NOT_LOADED (the next expansion re-fetches), an expanded one is re-fetched — sequentially, in one
   * coroutine, with one connection pin per sweep — and its children are replaced only when the payload
   * changed. LOADING nodes are skipped (their own load is in flight).
   */
  private fun sweep() {
    cancelSweep()
    val targets = mutableListOf<SweepTarget>()
    collectSweep(tree.roots(), targets)
    if (targets.isEmpty()) return
    val seq = sweepSeq
    sweepJob = launchOffUi { offUiGuarded { reloadAll(targets, seq) } }
  }

  /** Pre-order: an expanded LOADED node is a target and its children are searched; a collapsed one is reset. */
  private fun collectSweep(nodes: List<SidebarNode>, targets: MutableList<SweepTarget>) {
    for (node in nodes) {
      when {
        node !is DownstreamPipelineNode -> collectSweep(node.children, targets)
        node.loadState != DownstreamLoadState.LOADED -> Unit
        tree.isExpanded(node) -> {
          node.requestOrNull()?.let { targets += SweepTarget(node, node.loadGeneration, it) }
          collectSweep(node.children, targets)
        }
        else -> reset(node)
      }
    }
  }

  private fun reset(node: DownstreamPipelineNode) {
    // Loads still running somewhere below are detached by this reset: drop and cancel them.
    abandon(transferDownstreamState(node.loadedChildren.orEmpty(), emptyList()))
    node.loadGeneration++
    node.loadState = DownstreamLoadState.NOT_LOADED
    node.loadedChildren = null
    node.shownPayload = null
    tree.refreshSubtree(node, null)
  }

  /**
   * IO. Re-fetches each target in order; a target whose connection cannot be pinned is left as it is.
   * A sweep with no failure ends the failure streak.
   */
  private suspend fun reloadAll(targets: List<SweepTarget>, seq: Long) {
    val pinned = HashMap<Pair<String, String>, ConnectionSnapshot?>()
    var failed = false
    val onFailure: (Throwable) -> Unit = {
      failed = true
      logSweepFailure(it)
    }
    for (target in targets) {
      currentCoroutineContext().ensureActive()
      val request = target.request
      val key = request.instanceUrl to request.authFingerprint
      val connection = if (key in pinned) pinned[key] else pin(request, onFailure).also { pinned[key] = it }
      if (connection != null) {
        val payload = fetch(request, connection)
        payload.failures().forEach(onFailure)
        uiDispatch { applySweep(target, seq, payload) }
      }
    }
    if (!failed) sweepFailing.set(false)
  }

  private fun applySweep(target: SweepTarget, seq: Long, payload: DownstreamPayload) {
    val node = target.node
    val current = seq == sweepSeq && node.loadState == DownstreamLoadState.LOADED && isLive(node, target.generation)
    if (!current || payload == node.shownPayload) return
    val oldChildren = node.loadedChildren.orEmpty()
    val fresh = buildChildren(node, payload)
    // Before the re-render: the grandchildren keep their state, and loads the rebuild orphaned stop.
    abandon(transferDownstreamState(oldChildren, fresh))
    node.loadedChildren = fresh
    node.shownPayload = payload
    tree.refreshSubtree(node, oldChildren)
    // A rebuilt nested node whose expansion was restored by key starts its own first load.
    downstreamNodesIn(fresh).filter { tree.isExpanded(it) }.forEach(::load)
  }

  // --- shared ---

  /** UI thread. Not disposed, still in the viewer's tree (identity), and no newer load or abandonment. */
  private fun isLive(node: DownstreamPipelineNode, generation: Long): Boolean =
    !tree.isDisposed() && node.loadGeneration == generation && containsByIdentity(tree.roots(), node)

  private fun abandon(nodes: List<DownstreamPipelineNode>) {
    nodes.forEach { inFlight.remove(it)?.cancel() }
  }

  /** IO. The pinned connection, or null — a pin that throws is reported to [onFailure] and treated as not confirmed. */
  private fun pin(request: DownstreamRequest, onFailure: (Throwable) -> Unit): ConnectionSnapshot? =
    settle { source.pinConnection(request.instanceUrl, request.authFingerprint) }
      .onFailure(onFailure)
      .getOrNull()

  /** IO. Jobs, then bridges, over the same pinned connection, each settled on its own. */
  private suspend fun fetch(request: DownstreamRequest, connection: ConnectionSnapshot): DownstreamPayload {
    val job = currentCoroutineContext()[Job]
    val isActive = { job?.isActive != false }
    currentCoroutineContext().ensureActive()
    val jobs = settle { source.jobs(request.projectId, request.pipelineId, isActive, connection) }
    currentCoroutineContext().ensureActive()
    val bridges = settle { source.bridges(request.projectId, request.pipelineId, isActive, connection) }
    return DownstreamPayload(jobs, bridges)
  }

  private fun logFailure(error: Throwable) {
    warnLog(downstreamFailureMessage(error))
  }

  /** Logs [error] only when it starts a sweep failure streak. */
  private fun logSweepFailure(error: Throwable) {
    if (sweepFailing.compareAndSet(false, true)) logFailure(error)
  }

  private suspend fun offUiGuarded(block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logFailure(e)
    }
  }
}

/** What the off-UI side of a load needs, captured on the UI thread. */
private class DownstreamRequest(
  val instanceUrl: String,
  val authFingerprint: String,
  val projectId: String,
  val pipelineId: Long,
)

private class SweepTarget(val node: DownstreamPipelineNode, val generation: Long, val request: DownstreamRequest)

/** The request for an expandable node; null for a leaf (no project, a cycle, or too deep). */
private fun DownstreamPipelineNode.requestOrNull(): DownstreamRequest? {
  val project = projectId
  return if (expandable && project != null) {
    DownstreamRequest(sourceInstanceUrl, sourceAuthFingerprint, project.toString(), pipelineId)
  } else {
    null
  }
}

private fun DownstreamPayload.failures(): List<Throwable> =
  listOfNotNull(jobsResult.exceptionOrNull(), bridgesResult.exceptionOrNull())

/** The one log line format (§4.7): the exception class, plus the HTTP status of a [GitLabApiException]. */
internal fun downstreamFailureMessage(error: Throwable): String {
  val status = (error as? GitLabApiException)?.let { " (HTTP ${it.statusCode})" }.orEmpty()
  return "Downstream pipeline load failed: ${error.javaClass.name}$status"
}

/** Whether [node] is [roots] or under them, by identity. Reading children never triggers a fetch. */
internal fun containsByIdentity(roots: List<SidebarNode>, node: SidebarNode): Boolean =
  roots.any { it === node || containsByIdentity(it.children, node) }

/**
 * Runs [block], folding any failure into the [Result] — except [CancellationException], which always
 * propagates so a cancelled load paints and logs nothing.
 */
private inline fun <T> settle(block: () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (e: CancellationException) {
    throw e
  } catch (e: Throwable) {
    Result.failure(e)
  }
