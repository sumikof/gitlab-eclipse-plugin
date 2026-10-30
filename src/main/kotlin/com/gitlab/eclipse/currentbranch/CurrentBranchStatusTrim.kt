package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.PipelineActionService
import com.gitlab.eclipse.ci.actions.launchPinnedPipelineWrite
import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.theming.ThemeUtils
import com.gitlab.eclipse.views.sidebar.autorefresh.RefreshReason
import kotlinx.coroutines.CoroutineScope
import org.eclipse.core.commands.common.NotDefinedException
import org.eclipse.jface.action.Action
import org.eclipse.jface.action.IMenuManager
import org.eclipse.jface.action.MenuManager
import org.eclipse.jface.resource.JFaceResources
import org.eclipse.jface.resource.LocalResourceManager
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.CLabel
import org.eclipse.swt.layout.RowData
import org.eclipse.swt.layout.RowLayout
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Control
import org.eclipse.swt.widgets.Menu
import org.eclipse.swt.widgets.ToolBar
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.handlers.IHandlerService
import org.eclipse.ui.menus.WorkbenchWindowControlContribution
import org.koin.core.context.GlobalContext

private const val CREATE_PIPELINE_COMMAND = "com.gitlab.eclipse.commands.CreatePipeline"
private const val CREATE_MR_COMMAND = "com.gitlab.eclipse.commands.OpenCreateNewMR"
private const val TRIM_MARGIN = 2
private const val TRIM_SPACING = 8

/**
 * The current branch's pipeline, MR and closing issue in a window's status trim (status-bar design §4.5, D2: items
 * that do not apply are hidden, which command items cannot do). One instance per window's trim; the platform may
 * dispose and recreate it (perspective reset, trim rebuild), and each instance subscribes on its own.
 *
 * - [createControl] resolves this window's shared state with `ensureWindow` (the control can be created before the
 *   plugin-start activation ran, R3). Total: without Koin or a state the control is created empty and nothing shows.
 * - Rendering is diffed by [StatusTrimController]; after a visible width change the R1 recipe ([TrimWidth]) resizes
 *   the separator tool item the platform put the control in and re-tiles the trim.
 * - R2: the subscription, the pulldown menu and the images ([LocalResourceManager] bound to the control) are released
 *   from a DisposeListener on the control, and [dispose] does the same; both are idempotent.
 * - Clicks (left button): the pipeline item opens the CI actions pulldown ([PipelineMenuActions], built from the
 *   window's last settled snapshot); the MR / issue items open their URL; "Create MR" runs the existing command.
 *   No keyboard access (as the VS Code status bar) — a known limit.
 *
 * UI thread only. Nothing logs a URL, path, branch or title: failures are logged by exception class.
 */
class CurrentBranchStatusTrim : WorkbenchWindowControlContribution() {
  private val log = logger<CurrentBranchStatusTrim>()
  private val browser = BrowserLauncher()
  private var controller: StatusTrimController? = null
  private var menuManager: MenuManager? = null
  private var menu: Menu? = null
  private var released = false

  override fun createControl(parent: Composite): Control {
    val root = Composite(parent, SWT.NONE)
    root.layout = RowLayout(SWT.HORIZONTAL).apply {
      marginTop = 0
      marginBottom = 0
      marginLeft = TRIM_MARGIN
      marginRight = TRIM_MARGIN
      spacing = TRIM_SPACING
      center = true
      wrap = false
    }
    try {
      wire(root)
    } catch (e: Exception) {
      log.warn("Current-branch status trim unavailable: ${e.javaClass.name}")
    }
    return root
  }

  override fun dispose() {
    release()
    super.dispose()
  }

  private fun wire(root: Composite) {
    val window: IWorkbenchWindow? = workbenchWindow
    val service = GlobalContext.getOrNull()?.getOrNull<CurrentBranchStateService>()
    val state = window?.let { service?.ensureWindow(it) }
    val view = SwtStatusTrimView(root, { message -> log.warn(message) }) { showMenu(root, it) }
    // Captured now, while the control is alive: a POST may complete after it was disposed (T5b).
    val display = root.display
    val uiHop = uiHopUnlessDisposed({ display.isDisposed }, display::asyncExec)
    val actions = PipelineMenuActions(
      openUrl = browser::openChecked,
      writePipeline = { write, onSuccess ->
        launchPinnedPipelineWrite(
          write,
          // Off the UI thread: hop back, then refresh this window only (R15 also covers a closed sidebar).
          onSuccess = { uiHop(onSuccess) },
          scope = GlobalContext.get().get<CoroutineScope>(),
          log = logger<PipelineActionService>(),
          apiClient = GlobalContext.get().get<GitLabApiClient>(),
          actionService = PipelineActionService(),
        )
      },
      createPipeline = { executeCommand(window, CREATE_PIPELINE_COMMAND) },
      refresh = { state?.refreshFull() },
      requestRefresh = { if (window != null) service?.requestRefresh(window, RefreshReason.MANUAL) },
      notify = { NotificationUtils.show(it) },
    )
    val trimController = StatusTrimController(
      view = view,
      menuItems = actions::itemsFor,
      openUrl = { browser.open(it) },
      createMergeRequest = { executeCommand(window, CREATE_MR_COMMAND) },
      log = { message -> log.warn(message) },
    )
    controller = trimController
    view.onClick = trimController::onClick
    root.addDisposeListener { release() }
    trimController.attach(state)
  }

  /** R2: idempotent; from the control's DisposeListener and from [dispose]. */
  private fun release() {
    if (released) return
    released = true
    controller?.dispose()
    controller = null
    menu?.takeUnless { it.isDisposed }?.dispose()
    menu = null
    menuManager?.dispose()
    menuManager = null
  }

  private fun showMenu(root: Composite, items: List<PipelineMenuItem>) {
    if (root.isDisposed) return
    val manager = menuManager ?: MenuManager().also { menuManager = it }
    manager.removeAll()
    items.forEach { addItem(manager, it) }
    val shown = menu?.takeUnless { it.isDisposed } ?: manager.createContextMenu(root).also { menu = it }
    manager.update(true)
    shown.setLocation(root.toDisplay(0, 0))
    shown.isVisible = true
  }

  private fun addItem(manager: IMenuManager, item: PipelineMenuItem) {
    if (item.children.isNotEmpty()) {
      val submenu = MenuManager(item.label)
      item.children.forEach { addItem(submenu, it) }
      manager.add(submenu)
      return
    }
    manager.add(
      object : Action(item.label) {
        init {
          isEnabled = item.enabled
        }

        override fun run() = item.run()
      },
    )
  }

  /** Runs an existing command in [window]'s context (it keeps its own dialogs and refresh). */
  private fun executeCommand(window: IWorkbenchWindow?, commandId: String) {
    try {
      val handlers = window?.getService(IHandlerService::class.java) ?: return
      handlers.executeCommand(commandId, null)
    } catch (e: NotDefinedException) {
      log.warn("Current-branch trim command is not defined: ${e.javaClass.name}")
    } catch (e: Exception) {
      log.warn("Current-branch trim command failed: ${e.javaClass.name}")
    }
  }
}

/**
 * The SWT widgets of one trim: three [CLabel]s in a [RowLayout], hidden with `exclude` (D2), images from a
 * [LocalResourceManager] bound to the root (disposed with it, R2). Applies a [TrimDiff] and, when it changes the width,
 * the R1 recipe through [TrimWidth].
 */
private class SwtStatusTrimView(
  private val root: Composite,
  log: (String) -> Unit,
  private val menu: (List<PipelineMenuItem>) -> Unit,
) : StatusTrimView {
  var onClick: (TrimSlot) -> Unit = {}

  private val resources = LocalResourceManager(JFaceResources.getResources(), root)
  private val width = TrimWidth(log)
  private val labels = TrimSlot.entries.associateWith { slot -> label(slot) }

  override val isDisposed: Boolean
    get() = root.isDisposed

  override fun render(model: TrimModel, diff: TrimDiff) {
    if (root.isDisposed) return
    diff.changed.forEach { slot -> show(checkNotNull(labels[slot]), model.item(slot)) }
    // Visibility (RowData.exclude) and text take effect with a layout, the first paint included; the tool item is
    // resized only for a width change after JFace created it (never on the first render, Fable SHOULD-FIX 1).
    root.layout(true)
    if (diff.relayout) width.apply(SwtWidthHost(root))
  }

  override fun showPipelineMenu(items: List<PipelineMenuItem>) = menu(items)

  private fun label(slot: TrimSlot): CLabel =
    CLabel(root, SWT.NONE).apply {
      layoutData = RowData().apply { exclude = true }
      isVisible = false
      addListener(SWT.MouseUp) { event -> if (event.button == 1) onClick(slot) }
    }

  private fun show(label: CLabel, item: TrimItemModel?) {
    val data = label.layoutData as RowData
    if (item == null) {
      label.isVisible = false
      data.exclude = true
      return
    }
    label.text = item.text
    label.toolTipText = item.tooltip
    label.image = ThemeUtils.getThemedIcon(trimIconName(item.icon))?.let { resources.create(it) }
    label.cursor = if (item.action == TrimAction.None) null else root.display.getSystemCursor(SWT.CURSOR_HAND)
    data.exclude = false
    label.isVisible = true
  }
}

/**
 * R1 over the real widgets: the control sits in a FillLayout wrapper composite, which is the control of a separator
 * [org.eclipse.swt.widgets.ToolItem] of a [ToolBar] (JFace `ToolControlContribution`).
 */
private class SwtWidthHost(private val control: Control) : TrimWidthHost {
  override val isDisposed: Boolean
    get() = control.isDisposed

  override fun ownItem(): TrimWidthItem? {
    val wrapper = control.parent ?: return null
    val toolBar = wrapper.parent as? ToolBar ?: return null
    val item = toolBar.items.firstOrNull { it.control === wrapper } ?: return null
    return object : TrimWidthItem {
      override var width: Int
        get() = item.width
        set(value) {
          item.width = value
        }
    }
  }

  override fun preferredWidth(): Int = control.parent.computeSize(SWT.DEFAULT, SWT.DEFAULT, true).x

  override fun relayoutTrim() {
    (control.parent?.parent as? ToolBar)?.parent?.requestLayout()
  }
}
