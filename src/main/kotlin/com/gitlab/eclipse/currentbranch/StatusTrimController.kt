package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.views.sidebar.autorefresh.Registration
import com.gitlab.eclipse.views.sidebar.autorefresh.SettleOutcome

/** The three status-bar items, in display order. */
enum class TrimSlot { PIPELINE, MR, ISSUE }

/** What one re-render touches: the [changed] items, and whether the trim must be laid out again (R1). */
data class TrimDiff(val changed: Set<TrimSlot>, val relayout: Boolean) {
  val isEmpty: Boolean
    get() = changed.isEmpty()
}

/**
 * Diffed re-render (status-bar design §4.5): an item is re-rendered only when its model changed, and the trim is laid
 * out again only when an item appeared, disappeared or changed its text — an icon or tooltip change keeps the width,
 * so a 30 s tick that changes nothing (or only an icon) causes no layout jitter.
 */
object TrimDiffer {
  fun diff(old: TrimModel?, new: TrimModel): TrimDiff {
    if (old == null) return TrimDiff(TrimSlot.entries.toSet(), relayout = true)
    val changed = TrimSlot.entries.filter { old.item(it) != new.item(it) }.toSet()
    val relayout = changed.any { slot ->
      val before = old.item(slot)
      val after = new.item(slot)
      (before == null) != (after == null) || before?.text != after?.text
    }
    return TrimDiff(changed, relayout)
  }
}

/** The item model shown in [slot]. */
fun TrimModel.item(slot: TrimSlot): TrimItemModel? =
  when (slot) {
    TrimSlot.PIPELINE -> pipeline
    TrimSlot.MR -> mr
    TrimSlot.ISSUE -> issue
  }

/** The SWT side of one window's trim control, as the [StatusTrimController] drives it. UI thread only. */
interface StatusTrimView {
  val isDisposed: Boolean

  /** Applies [model] to the items [diff] names, and runs the width recipe when [TrimDiff.relayout]. */
  fun render(model: TrimModel, diff: TrimDiff)

  /** Opens the CI actions pulldown under the pipeline item. */
  fun showPipelineMenu(items: List<PipelineMenuItem>)
}

/**
 * Drives one window's status trim from its [WindowCurrentBranchState] (status-bar design §4.5). SWT-free.
 *
 * Re-presents on every state callback — the refresh's start and finish as well as its applies: the head kind and the
 * `refreshing` flag can change without an apply — through the pure [StatusTrimPresenter] (which keeps the previous
 * model over placeholders, R11), and hands the [view] only what changed ([TrimDiffer]). Clicks dispatch on the
 * rendered item's action; the pipeline menu is built by [menuItems] from the window's last settled snapshot.
 *
 * A state of null (no service, closing window) renders everything hidden. [dispose] (the control's DisposeListener,
 * R2) unsubscribes and is idempotent; a view found disposed at the next event disposes the controller too. UI thread
 * only.
 */
class StatusTrimController(
  private val view: StatusTrimView,
  private val menuItems: (CurrentBranchSnapshot) -> List<PipelineMenuItem>,
  private val openUrl: (String) -> Unit,
  private val createMergeRequest: () -> Unit,
  private val log: (String) -> Unit,
) : CurrentBranchListener {
  private var state: WindowCurrentBranchState? = null
  private var subscription: Registration? = null
  private var rendered: TrimModel? = null
  private var disposed = false

  /** Subscribes to [state] (or shows nothing without one) and renders its current snapshot. */
  fun attach(state: WindowCurrentBranchState?) {
    if (disposed) return
    this.state = state
    subscription = state?.addListener(this)
    update()
  }

  override fun onRefreshStarted(id: Long, soft: Boolean) = update()

  override fun onApplied(id: Long, snapshot: CurrentBranchSnapshot) = update()

  override fun onRefreshFinished(id: Long, outcome: SettleOutcome?) = update()

  fun onClick(slot: TrimSlot) {
    if (disposed) return
    when (val action = rendered?.item(slot)?.action) {
      TrimAction.ShowPipelineMenu -> state?.let { view.showPipelineMenu(menuItems(it.settledSnapshot ?: it.snapshot)) }
      is TrimAction.OpenUrl -> openUrl(action.url)
      TrimAction.CreateMergeRequest -> createMergeRequest()
      TrimAction.None, null -> Unit
    }
  }

  fun dispose() {
    if (disposed) return
    disposed = true
    try {
      subscription?.unregister()
    } catch (e: Exception) {
      runCatching { log("Current-branch trim unsubscribe failed: ${e.javaClass.name}") }
    }
    subscription = null
    state = null
  }

  private fun update() {
    if (disposed) return
    if (view.isDisposed) {
      dispose()
      return
    }
    val model = state?.let { StatusTrimPresenter.present(it.snapshot, rendered) } ?: TrimModel.HIDDEN
    val diff = TrimDiffer.diff(rendered, model)
    if (diff.isEmpty) return
    rendered = model
    view.render(model, diff)
  }
}

/** The tool item holding the trim control, as far as the width recipe needs it. */
interface TrimWidthItem {
  var width: Int
}

/** The control's place in the status trim (production: the SWT wrapper / ToolBar / ToolItem chain). */
interface TrimWidthHost {
  val isDisposed: Boolean

  /** The tool item whose control is the control's wrapper; null when the parent is not a tool bar or has none. */
  fun ownItem(): TrimWidthItem?

  fun preferredWidth(): Int

  /** Asks the trim bar to tile again (`requestLayout` on the tool bar's parent). */
  fun relayoutTrim()
}

/**
 * The R1 width recipe (status-bar design §4.5): the platform sizes the separator tool item that holds a trim
 * `<control>` once, so after any visibility or text change the item takes the control's new preferred width and the
 * trim bar re-tiles. Every step is guarded: a disposed control is skipped silently; a missing item, or a host that
 * throws, is skipped and logged once (class name only).
 */
class TrimWidth(private val log: (String) -> Unit) {
  private var logged = false

  fun apply(host: TrimWidthHost): Boolean =
    try {
      when {
        host.isDisposed -> false
        else -> resize(host)
      }
    } catch (e: Exception) {
      logOnce("Current-branch trim resize failed: ${e.javaClass.name}")
      false
    }

  private fun resize(host: TrimWidthHost): Boolean {
    val item = host.ownItem()
    if (item == null) {
      logOnce("Current-branch trim: no tool item to resize")
      return false
    }
    item.width = host.preferredWidth()
    host.relayoutTrim()
    return true
  }

  private fun logOnce(message: String) {
    if (logged) return
    logged = true
    runCatching { log(message) }
  }
}

/** R19: the themed icon (`icons/{light,dark}/<name>.png`, + `@2x`) of each trim icon. */
fun trimIconName(icon: TrimIcon): String = TRIM_ICON_NAMES.getValue(icon)

private val TRIM_ICON_NAMES = mapOf(
  TrimIcon.SUCCESS to "pipeline_success",
  TrimIcon.FAILED to "pipeline_failed",
  TrimIcon.RUNNING to "pipeline_running",
  TrimIcon.PENDING to "pipeline_pending",
  TrimIcon.CANCELED to "pipeline_canceled",
  TrimIcon.SKIPPED to "pipeline_skipped",
  TrimIcon.MANUAL to "pipeline_manual",
  TrimIcon.UNKNOWN to "pipeline_unknown",
  TrimIcon.INFO_LOADING to "pipeline_loading",
  TrimIcon.NO_PIPELINE to "pipeline_none",
  TrimIcon.MR_OPEN to "mr_open",
  TrimIcon.MR_DRAFT to "mr_draft",
  TrimIcon.MR_CREATE to "mr_create",
  TrimIcon.ISSUE to "issue_open",
)
