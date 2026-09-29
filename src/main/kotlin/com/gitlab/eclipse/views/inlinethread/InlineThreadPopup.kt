package com.gitlab.eclipse.views.inlinethread

import com.gitlab.eclipse.utils.logger
import org.eclipse.jface.resource.JFaceResources
import org.eclipse.jface.text.ITextViewer
import org.eclipse.jface.text.JFaceTextUtil
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.ScrolledComposite
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.ControlAdapter
import org.eclipse.swt.events.ControlEvent
import org.eclipse.swt.events.KeyAdapter
import org.eclipse.swt.events.KeyEvent
import org.eclipse.swt.events.SelectionAdapter
import org.eclipse.swt.events.SelectionEvent
import org.eclipse.swt.events.ShellAdapter
import org.eclipse.swt.events.ShellEvent
import org.eclipse.swt.events.TraverseEvent
import org.eclipse.swt.graphics.Rectangle
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.layout.GridLayout
import org.eclipse.swt.layout.RowLayout
import org.eclipse.swt.widgets.Button
import org.eclipse.swt.widgets.Combo
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Control
import org.eclipse.swt.widgets.Label
import org.eclipse.swt.widgets.Shell
import org.eclipse.swt.widgets.Text
import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.texteditor.ITextEditor

/**
 * What a host may do with its popup once it is open, SWT-free so hosts are testable: the state
 * machine, whether the shell is still there, a re-sync of the widgets from the state, a swap of
 * the display model, and a close (which preserves unsent drafts, see [InlineThreadPopup.close]).
 * UI thread only.
 */
interface InlineThreadSurface {
  val state: InlineThreadState
  val isOpen: Boolean
  fun refresh()

  /** Swaps the display model (drafts and busy are kept by the state); a no-op once closed. */
  fun update(model: InlineThreadModel)
  fun close()
}

/**
 * The user of an [InlineThreadPopup] (design §11.3): the MR layer today, Quick Chat later. Every
 * callback runs on the UI thread. The ticket flow of §29 #20–#22 is what made the callbacks take
 * the [InlineThreadSurface] and a frozen [SubmitTicket] rather than the plain text of §11.3's
 * sketch: the host must release the popup's busy through the same state that set it.
 */
interface InlineThreadHost {
  /** Send pressed: [ticket] is frozen and the state is busy. The host must end busy through the state on every path. */
  fun onSubmit(surface: InlineThreadSurface, ticket: SubmitTicket)

  /** A non-text action button (RESOLVE / UNRESOLVE) of the selected thread was pressed. */
  fun onAction(surface: InlineThreadSurface, action: InlineThreadAction)

  /** The popup is about to close with these submittable, unsent drafts (§29 #22): offer to copy them. */
  fun preserveDrafts(drafts: List<String>)

  /** The shell is gone (after any [preserveDrafts]). */
  fun onClosed()

  /**
   * A Copy / Insert button of a rendered code block ([InlineThreadEntry.codeBlocks]) was pressed
   * with that block's [code]. Hosts without code-block entries (the MR layer) never see it.
   */
  fun onCodeAction(surface: InlineThreadSurface, action: CodeBlockAction, code: String) {}
}

/**
 * The drafts to offer for copying when the popup closes (§29 #22): every submittable draft except
 * the one an in-flight [SubmitTicket] is sending — the launcher's terminal already preserves that
 * body — and only while that thread is still unedited since the ticket (an edited draft is new text).
 *
 * [keepInFlight] is for a host with no such terminal (Quick Chat: closing ends the send and drops
 * its result): the in-flight body is offered too. It is still the thread's draft while unedited, and
 * an edit replaced it, so every draft appears once either way.
 */
fun InlineThreadState.draftsToPreserve(inFlight: SubmitTicket?, keepInFlight: Boolean = false): List<String> {
  val drafts = unsentDrafts().toMutableList()
  if (keepInFlight) return drafts
  if (inFlight != null && busy && editGeneration(inFlight.threadId) == inFlight.generation) drafts.remove(inFlight.body)
  return drafts
}

/** Appended to a thread's entries when the server holds more than were fetched (design §9.1.1, A19). */
const val MORE_ENTRIES_NOTE = "(more replies — open in GitLab)"

private const val POPUP_WIDTH = 520
private const val POPUP_HEIGHT = 380
private const val INPUT_HEIGHT = 72
private const val MARGIN = 8
private const val ENTRY_SPACING = 6

/**
 * The non-modal, line-anchored thread popup (design §8.2, §9.2, §11.3, FR-5, E7, E8): a
 * `Shell(workbenchShell, TOOL | RESIZE | TITLE | CLOSE)` — never a `PopupDialog`, which closes
 * itself when focus returns to the editor (E7). It owns one [InlineThreadState]; the widgets are a
 * view of it (drafts and edit generations per thread, busy, selection). Placed under the line by
 * [PopupAnchor] when opened, and not moved afterwards (no scroll following).
 *
 * **UI thread only.** Closing through Esc, the title-bar close button, the editor's close or input
 * change (own [EditorGoneListener], so it holds for editors no session ever tracked) or [close]
 * first hands the unsent drafts to [InlineThreadHost.preserveDrafts] (§29 #22), except the body of
 * the ticket in flight, which the launcher's terminal keeps (offered too with [preserveInFlightDraft]).
 * [discard] closes without that (bundle stop), and so does a dispose of the shell by anything else
 * (its parent window closing, design §9.5).
 *
 * The defaults of [title], [submitOnModEnter] and [preserveInFlightDraft], and entries without
 * [InlineThreadEntry.codeBlocks], keep the MR popup exactly as it was before Quick Chat (A18).
 *
 * `@Suppress("TooManyFunctions")`: a widget class — the §11.3 API plus one builder per control
 * and the E8 placement steps; splitting it would scatter one shell's lifecycle.
 */
@Suppress("TooManyFunctions")
class InlineThreadPopup(
  private val editor: ITextEditor,
  val oneBasedLine: Int,
  private val host: InlineThreadHost,
  private val title: String = "Merge Request Thread — line $oneBasedLine",
  /** `M1+Enter` in the input submits like the button (see [isSubmitChord]). */
  private val submitOnModEnter: Boolean = false,
  /** [close] offers the in-flight body too, for a host that does not keep it ([draftsToPreserve]'s `keepInFlight`). */
  private val preserveInFlightDraft: Boolean = false,
) : InlineThreadSurface {
  private val logger by lazy { logger<InlineThreadPopup>() }

  private var shell: Shell? = null
  private var stateOrNull: InlineThreadState? = null
  override val state: InlineThreadState
    get() = checkNotNull(stateOrNull) { "the popup has not been opened" }

  /** The popup's own copy of the ticket in flight, to leave its body out of the close-time prompt. */
  private var inFlight: SubmitTicket? = null
  private var externalBusy = false

  private var selector: Combo? = null
  private var content: Composite? = null
  private var input: Text? = null
  private var submitButton: Button? = null

  /** The entries scroller of the rich rendering ([InlineThreadEntry.codeBlocks]); null for plain text. */
  private var entriesScroller: ScrolledComposite? = null
  private val actionButtons = HashMap<InlineThreadAction, Button>()
  private var syncingInput = false

  private var page: IWorkbenchPage? = null

  /** Design §9.6 / FR-11: the editor closed or shows another input → the popup closes (drafts preserved). */
  private var partListener: IPartListener2? = null

  override val isOpen: Boolean
    get() = shell?.isDisposed == false

  /** Creates and shows the shell for [model]. Once per popup. */
  fun open(model: InlineThreadModel) {
    check(shell == null) { "the popup was already opened" }
    stateOrNull = InlineThreadState(model)
    val parent = editor.site.workbenchWindow.shell
    val newShell = Shell(parent, SWT.TOOL or SWT.RESIZE or SWT.TITLE or SWT.CLOSE)
    shell = newShell
    newShell.text = title
    newShell.layout = GridLayout(1, false).apply {
      marginWidth = MARGIN
      marginHeight = MARGIN
    }
    newShell.addShellListener(object : ShellAdapter() {
      override fun shellClosed(e: ShellEvent) {
        // The title-bar close button: route through close() so drafts are preserved first.
        e.doit = false
        close()
      }
    })
    newShell.addTraverseListener(::onTraverse)
    // Disposed without discard() (the parent window closed): still release the listeners and tell
    // the host, but there is no shell left to prompt from, so drafts are not offered (§9.5).
    newShell.addDisposeListener { if (shell === newShell) release(newShell, disposeShell = false) }
    selector = Combo(newShell, SWT.DROP_DOWN or SWT.READ_ONLY).apply {
      layoutData = GridData(SWT.FILL, SWT.CENTER, true, false)
      addSelectionListener(object : SelectionAdapter() {
        override fun widgetSelected(e: SelectionEvent) = onThreadSelected()
      })
    }
    content = Composite(newShell, SWT.NONE).apply {
      layoutData = GridData(SWT.FILL, SWT.FILL, true, true)
      layout = GridLayout(1, false).apply {
        marginWidth = 0
        marginHeight = 0
      }
    }
    render()
    val bounds = anchoredBounds(newShell)
    newShell.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
    // The first render ran before the shell had a size: scroll the rich entries again now (I6).
    entriesScroller?.let {
      newShell.layout(true, true)
      scrollToBottom(it)
    }
    editor.site.page.let { editorPage ->
      page = editorPage
      val listener = EditorGoneListener(editor, editorPage.getReference(editor), ::close)
      partListener = listener
      editorPage.addPartListener(listener)
    }
    newShell.open()
    input?.setFocus()
  }

  /** Swaps the display model (a reload landed); drafts and busy are kept by the state. */
  override fun update(model: InlineThreadModel) {
    if (!isOpen) return
    state.replaceModel(model)
    render()
  }

  /** An extra busy flag from the host (§11.3): disables the input and every button while set. */
  fun setBusy(busy: Boolean) {
    externalBusy = busy
    refresh()
  }

  /**
   * Brings the shell to the front (a second request for the same line) and puts the focus in the
   * input when there is one to type in (Quick Chat FR-2); `setActive` alone does not promise that.
   */
  fun activate() {
    val current = shell?.takeUnless { it.isDisposed } ?: return
    current.setActive()
    input?.takeIf { !it.isDisposed && it.isEnabled }?.setFocus()
  }

  /**
   * Re-syncs the widgets from the state: enablement from busy / canSubmit, and the input text from
   * the **selected** thread's draft (never cleared blindly — a success may have cleared another
   * thread's draft). Safe after the shell is gone.
   */
  override fun refresh() {
    if (!state.busy) inFlight = null
    if (!isOpen) return
    val busy = state.busy || externalBusy
    selector?.isEnabled = !busy
    input?.let { widget ->
      val draft = state.draft(state.selectedThreadId)
      if (widget.text != draft) {
        syncingInput = true
        try {
          widget.text = draft
          widget.setSelection(draft.length)
        } finally {
          syncingInput = false
        }
      }
      widget.isEnabled = !busy
    }
    submitButton?.isEnabled = !externalBusy && state.canSubmit()
    actionButtons.values.forEach { it.isEnabled = !busy }
  }

  /** Preserves the unsent drafts through the host (§29 #22), then disposes the shell. */
  override fun close() {
    val current = shell ?: return
    if (current.isDisposed) return
    val drafts = state.draftsToPreserve(inFlight, keepInFlight = preserveInFlightDraft)
    if (drafts.isNotEmpty()) {
      try {
        host.preserveDrafts(drafts)
      } catch (e: Exception) {
        logger.error("inlineThreadPopup preserveDrafts failed: exceptionType=${e.javaClass.name}")
      }
    }
    discard()
  }

  /**
   * Disposes the shell without offering the drafts (bundle stop, §29 #22's known limitation).
   * Idempotent: [InlineThreadHost.onClosed] runs once, whether this or the shell's dispose came first.
   */
  fun discard() {
    release(shell ?: return, disposeShell = true)
  }

  /**
   * The one teardown path. [disposeShell] is false when called from the shell's own dispose
   * listener: re-entering `dispose()` from inside the Dispose event is not safe.
   */
  private fun release(current: Shell, disposeShell: Boolean) {
    // Cleared before dispose() so the dispose listener sees the popup already released.
    shell = null
    try {
      partListener?.let { page?.removePartListener(it) }
    } catch (e: Exception) {
      logger.warn("inlineThreadPopup part listener removal failed: exceptionType=${e.javaClass.name}")
    }
    page = null
    partListener = null
    if (disposeShell && !current.isDisposed) current.dispose()
    try {
      host.onClosed()
    } catch (e: Exception) {
      logger.error("inlineThreadPopup onClosed failed: exceptionType=${e.javaClass.name}")
    }
  }

  // ---- widgets -------------------------------------------------------------------------------

  /** Rebuilds the thread area for the selected item; the selector stays. */
  private fun render() {
    val current = shell ?: return
    val area = content ?: return
    val items = state.model.items
    selector?.let { combo ->
      combo.removeAll()
      items.forEach { combo.add(it.title) }
      combo.select(items.indexOfFirst { it.threadId == state.selectedThreadId }.coerceAtLeast(0))
      (combo.layoutData as GridData).exclude = items.size <= 1
      combo.isVisible = items.size > 1
    }
    val inputHadFocus = input?.isFocusControl == true
    area.children.forEach(Control::dispose)
    input = null
    submitButton = null
    actionButtons.clear()
    val item = items.first { it.threadId == state.selectedThreadId }
    createHeader(area, item)
    entriesScroller = null
    if (item.entries.isNotEmpty() || item.moreEntriesOnServer) {
      if (item.entries.any { it.codeBlocks }) {
        entriesScroller = createRichEntries(area, item)
      } else {
        createEntries(area, item)
      }
    }
    if (item.inputPlaceholder != null) createInput(area, item.inputPlaceholder)
    createButtons(area, item)
    current.layout(true, true)
    entriesScroller?.let(::scrollToBottom)
    refresh()
    if (inputHadFocus) input?.setFocus()
  }

  private fun createHeader(parent: Composite, item: InlineThreadItem) {
    val status = when (item.resolved) {
      true -> " · resolved"
      false -> " · unresolved"
      null -> ""
    }
    Label(parent, SWT.NONE).apply {
      text = item.title + status
      layoutData = GridData(SWT.FILL, SWT.CENTER, true, false)
    }
  }

  /** Read-only, wrapped text: the Markdown source as fetched, one entry after another (FR-5). */
  private fun createEntries(parent: Composite, item: InlineThreadItem) {
    val text = buildString {
      item.entries.forEachIndexed { index, entry ->
        if (index > 0) append("\n\n")
        entryHeader(entry)?.let { append(it).append('\n') }
        append(entry.body)
      }
      if (item.moreEntriesOnServer) {
        if (isNotEmpty()) append("\n\n")
        append(MORE_ENTRIES_NOTE)
      }
    }
    Text(parent, SWT.MULTI or SWT.READ_ONLY or SWT.WRAP or SWT.V_SCROLL or SWT.BORDER).apply {
      this.text = text
      layoutData = GridData(SWT.FILL, SWT.FILL, true, true)
      addTraverseListener(::onTraverse)
    }
  }

  /**
   * The entries as one widget per part inside a vertical scroller (design §9.7): a header label,
   * then the body as plain text, or — for [InlineThreadEntry.codeBlocks] — its prose and fenced
   * code blocks, each actionable block with Copy / Insert buttons above a monospace view.
   */
  private fun createRichEntries(parent: Composite, item: InlineThreadItem): ScrolledComposite {
    val scroller = ScrolledComposite(parent, SWT.V_SCROLL or SWT.BORDER).apply {
      layoutData = GridData(SWT.FILL, SWT.FILL, true, true)
      expandHorizontal = true
      expandVertical = true
    }
    val column = Composite(scroller, SWT.NONE).apply {
      layout = GridLayout(1, false).apply { verticalSpacing = ENTRY_SPACING }
    }
    scroller.content = column
    item.entries.forEach { entry ->
      entryHeader(entry)?.let { header ->
        Label(column, SWT.NONE).apply {
          text = header
          layoutData = GridData(SWT.FILL, SWT.CENTER, true, false)
        }
      }
      if (entry.codeBlocks) createSegments(column, entry.body) else createProse(column, entry.body)
    }
    if (item.moreEntriesOnServer) createProse(column, MORE_ENTRIES_NOTE)
    // Wrapped text only knows its height for a given width: recompute on every resize.
    scroller.addControlListener(object : ControlAdapter() {
      override fun controlResized(e: ControlEvent) = fitScrolledContent(scroller)
    })
    fitScrolledContent(scroller)
    return scroller
  }

  private fun createSegments(parent: Composite, body: String) {
    MarkdownCodeBlocks.split(body).forEach { segment ->
      when (segment) {
        is Segment.Prose -> createProse(parent, segment.text)
        is Segment.Code -> createCode(parent, segment)
      }
    }
  }

  private fun createProse(parent: Composite, body: String) {
    Text(parent, SWT.MULTI or SWT.READ_ONLY or SWT.WRAP).apply {
      text = body
      layoutData = GridData(SWT.FILL, SWT.TOP, true, false).apply { widthHint = 1 }
      addTraverseListener(::onTraverse)
    }
  }

  private fun createCode(parent: Composite, segment: Segment.Code) {
    if (segment.actionable) {
      val bar = Composite(parent, SWT.NONE).apply {
        layoutData = GridData(SWT.END, SWT.CENTER, true, false)
        layout = RowLayout(SWT.HORIZONTAL).apply {
          marginWidth = 0
          marginHeight = 0
        }
      }
      button(bar, "Copy") { host.onCodeAction(this, CodeBlockAction.COPY, segment.code) }
      button(bar, "Insert") { host.onCodeAction(this, CodeBlockAction.INSERT, segment.code) }
    }
    StyledText(parent, SWT.MULTI or SWT.READ_ONLY or SWT.H_SCROLL or SWT.BORDER).apply {
      text = segment.code
      font = JFaceResources.getTextFont()
      layoutData = GridData(SWT.FILL, SWT.TOP, true, false).apply { widthHint = 1 }
      addTraverseListener(::onTraverse)
    }
  }

  /** Sizes the scrolled column to the scroller's width, so wrapped text gets its real height. */
  private fun fitScrolledContent(scroller: ScrolledComposite) {
    if (scroller.isDisposed) return
    val column = scroller.content ?: return
    val width = scroller.clientArea.width
    scroller.setMinSize(column.computeSize(if (width > 0) width else SWT.DEFAULT, SWT.DEFAULT))
  }

  /** I6: after a render the newest entry (an answer or failure) is in view. Best effort. */
  private fun scrollToBottom(scroller: ScrolledComposite) {
    fitScrolledContent(scroller)
    val column = scroller.content ?: return
    scroller.setOrigin(0, (column.size.y - scroller.clientArea.height).coerceAtLeast(0))
  }

  private fun createInput(parent: Composite, placeholder: String) {
    input = Text(parent, SWT.MULTI or SWT.WRAP or SWT.V_SCROLL or SWT.BORDER).apply {
      message = placeholder
      layoutData = GridData(SWT.FILL, SWT.FILL, true, false).apply { heightHint = INPUT_HEIGHT }
      addModifyListener {
        if (!syncingInput) {
          state.onEdit(state.selectedThreadId, text)
          submitButton?.isEnabled = !externalBusy && state.canSubmit()
        }
      }
      addTraverseListener(::onTraverse)
      if (submitOnModEnter) addKeyListener(SubmitChordListener())
    }
  }

  /** `M1+Enter` in the input: no newline, and the button's submit when the button would be enabled. */
  private inner class SubmitChordListener : KeyAdapter() {
    override fun keyPressed(e: KeyEvent) {
      if (!isSubmitChord(e.stateMask, e.keyCode)) return
      e.doit = false
      if (!externalBusy && state.canSubmit()) submit()
    }
  }

  private fun createButtons(parent: Composite, item: InlineThreadItem) {
    val bar = Composite(parent, SWT.NONE).apply {
      layoutData = GridData(SWT.END, SWT.CENTER, true, false)
      layout = RowLayout(SWT.HORIZONTAL).apply {
        marginWidth = 0
        marginHeight = 0
        pack = false
      }
    }
    val submitLabel = submitLabelOf(item)
    val labels = listOf(InlineThreadAction.RESOLVE to "Resolve", InlineThreadAction.UNRESOLVE to "Unresolve")
    for ((action, label) in labels) {
      if (action in item.actions) actionButtons[action] = button(bar, label) { host.onAction(this, action) }
    }
    if (submitLabel != null && input != null) submitButton = button(bar, submitLabel, ::submit)
  }

  private fun button(parent: Composite, label: String, onPress: () -> Unit) = Button(parent, SWT.PUSH).apply {
    text = label
    addSelectionListener(object : SelectionAdapter() {
      override fun widgetSelected(e: SelectionEvent) {
        try {
          onPress()
        } catch (ex: Exception) {
          logger.error("inlineThreadPopup button failed: exceptionType=${ex.javaClass.name}")
        }
      }
    })
  }

  private fun submit() {
    val ticket = state.beginSubmit() ?: return
    inFlight = ticket
    refresh()
    try {
      host.onSubmit(this, ticket)
    } catch (e: Exception) {
      // Nothing was launched: do not leave the popup stuck busy.
      logger.error("inlineThreadPopup submit failed: exceptionType=${e.javaClass.name}")
      state.onLaunchRejected(ticket)
      refresh()
    }
  }

  private fun onThreadSelected() {
    val combo = selector ?: return
    val items = state.model.items
    val index = combo.selectionIndex
    if (index !in items.indices) return
    // `select` is false while busy (the selector is disabled then anyway): just show the current one again.
    if (state.select(items[index].threadId)) {
      render()
    } else {
      combo.select(items.indexOfFirst { it.threadId == state.selectedThreadId }.coerceAtLeast(0))
    }
  }

  private fun onTraverse(e: TraverseEvent) {
    if (e.detail == SWT.TRAVERSE_ESCAPE) {
      e.doit = false
      close()
    }
  }

  // ---- placement (E8) ------------------------------------------------------------------------

  /**
   * Under the anchored line when visible; a hidden or scrolled-away line is revealed once and
   * recomputed; still nothing → top center of the editor. Clamped to the monitor of the editor.
   */
  private fun anchoredBounds(newShell: Shell): ScreenRect {
    val viewer = editor.getAdapter(ITextViewer::class.java)
    val widget = viewer?.textWidget?.takeUnless { it.isDisposed }
    val modelLine = oneBasedLine - 1
    var line = lineBoxOf(viewer, widget, modelLine)
    if (line == null && viewer != null) {
      revealLine(viewer, modelLine)
      line = lineBoxOf(viewer, widget, modelLine)
    }
    val display = newShell.display
    val editorArea = widget?.let { display.map(it.parent, null, it.bounds).toScreenRect() }
      ?: newShell.parent.bounds.toScreenRect()
    val monitor = (widget ?: newShell.parent).monitor.clientArea.toScreenRect()
    return PopupAnchor.place(line, editorArea, POPUP_WIDTH, POPUP_HEIGHT, monitor)
  }

  private fun lineBoxOf(viewer: ITextViewer?, widget: StyledText?, modelLine: Int): LineBox? {
    if (viewer == null || widget == null) return null
    val widgetLine = JFaceTextUtil.modelLineToWidgetLine(viewer, modelLine)
    return PopupAnchor.lineBox(
      widgetLine = widgetLine,
      lineHeight = widget.lineHeight,
      linePixel = widget::getLinePixel,
      toDisplay = { x, y -> widget.toDisplay(x, y).let { ScreenPoint(it.x, it.y) } },
      viewportHeight = widget.clientArea.height,
    )
  }

  private fun revealLine(viewer: ITextViewer, modelLine: Int) {
    val document = viewer.document ?: return
    if (modelLine !in 0 until document.numberOfLines) return
    try {
      viewer.revealRange(document.getLineOffset(modelLine), 0)
    } catch (e: org.eclipse.jface.text.BadLocationException) {
      logger.warn("inlineThreadPopup reveal failed: exceptionType=${e.javaClass.name}")
    }
  }

  private fun Rectangle.toScreenRect() = ScreenRect(x, y, width, height)
}
