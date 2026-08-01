package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.runtime.CoreException
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IEditorPart
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IWindowListener
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchPartReference
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PartInitException
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.texteditor.ITextEditor
import java.lang.ref.WeakReference

/**
 * Opens/refreshes the in-memory read-only job-log editor and releases its memory when the
 * last editor for a job closes.
 *
 * A singleton `object` (not a Koin service) because `GitLabEclipseStartup.start` must not be
 * modified (no start-time wiring): one instance per bundle classloader gives the per-activation
 * isolation the activation guard relies on. All mutable state is UI-thread-only.
 */
object JobLogEditorOpener {
  const val DEFAULT_TEXT_EDITOR_ID = "org.eclipse.ui.DefaultTextEditor"

  private val logger by lazy { logger<JobLogEditorOpener>() }

  /**
   * Weak VALUES so that once every editor for a key is closed (dropping the strong reference
   * held by their inputs) the content becomes GC-eligible. UI-thread only.
   */
  private val contentRegistry = HashMap<JobLogKey, WeakReference<JobLogContent>>()
  private var partListener: IPartListener2? = null
  private var windowListener: IWindowListener? = null

  /**
   * Updates the shared content for [key] to [text], refreshes every already-open editor for
   * [key] across all windows/pages, and makes sure the result is visible in the active page.
   * UI thread only. Generation/latest gating is the caller's responsibility.
   *
   * @throws PartInitException if the active page cannot open a new editor (the caller's
   *   catch surfaces it as an audit entry + latest-gated notification).
   * @throws CoreException if resetting the document of an editor on the ACTIVE page fails
   *   (same surfacing path); background-page reset failures are logged and swallowed.
   */
  fun openOrReload(key: JobLogKey, text: String) {
    ensureListenersRegistered()

    // Any already-open editor for [key] strong-references this same JobLogContent via its
    // input, so the weak ref is still live and returns the SAME instance - updating its text
    // makes every open editor's getStorage() see the new text on reset. A new key creates
    // a fresh content.
    val content = contentRegistry[key]?.get()
      ?: JobLogContent(text).also { contentRegistry[key] = WeakReference(it) }
    content.text = text

    val input = JobLogEditorInput(key, content)

    // findEditors returns ALL matching editors per page (including clones/splits of the same
    // input), collected across ALL windows/pages so every open tab gets refreshed.
    val matches = mutableListOf<Pair<IWorkbenchPage, IEditorPart>>()
    PlatformUI.getWorkbench().workbenchWindows.forEach { window ->
      window.pages.forEach { page -> matches += matchingEditorsOn(page, input) }
    }

    // The invoking page whose editor the user actually sees; used both to surface an
    // active-page reload failure below and for the visibility step.
    val activePage = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage

    // Reset each matched editor's document so the new text shows (not just focus): reset
    // re-reads from the input's storage, which now reflects the updated shared content.
    // Background-page failures are best-effort (logged); a failure on the ACTIVE page
    // propagates so reflectLatest surfaces it instead of leaving the user looking at a
    // stale log that appears refreshed.
    matches.forEach { (page, editor) ->
      val textEditor = editor as? ITextEditor ?: return@forEach
      try {
        textEditor.documentProvider?.resetDocument(textEditor.editorInput)
      } catch (e: CoreException) {
        logger.error("Failed to reset job-log document for ${input.name}.", e)
        if (page == activePage) throw e
      }
    }

    // Guarantee visibility in the invoking (active) page: bring an existing editor to front,
    // or open a fresh editor referencing the SHARED content. Duplicate tabs across pages are
    // acceptable - they all share one content.
    if (activePage == null) return
    val editorOnActivePage = matches.firstOrNull { (page, _) -> page == activePage }?.second
    if (editorOnActivePage != null) {
      activePage.activate(editorOnActivePage)
    } else {
      // Let a terminal open failure propagate: reflectLatest's catch audits + shows a
      // latest-gated notification, so a GET-succeeded-but-cannot-display case isn't silent.
      activePage.openEditor(input, DEFAULT_TEXT_EDITOR_ID)
    }
  }

  /**
   * All materialized editors on [page] whose input matches [input] by MATCH_INPUT (so cloned/
   * split editors of the same job log are included, unlike findEditor which returns only one).
   * getEditor(true) restores not-yet-materialized parts; a null (failed restore) is skipped.
   */
  private fun matchingEditorsOn(
    page: IWorkbenchPage,
    input: JobLogEditorInput,
  ): List<Pair<IWorkbenchPage, IEditorPart>> =
    page.findEditors(input, null, IWorkbenchPage.MATCH_INPUT)
      .mapNotNull { ref -> ref.getEditor(true) }
      .map { page to it }

  /**
   * Shutdown path: closes every open job-log editor, removes the part/window listeners, and
   * clears the content registry (wiping trace text). UI thread only (the caller marshals via
   * syncExec).
   */
  fun disposeAtShutdown() {
    val workbench = PlatformUI.getWorkbench()
    // If the workbench is tearing down, editors die with it - skip closing them.
    if (!workbench.isClosing) {
      workbench.workbenchWindows.forEach { window ->
        window.pages.forEach { page -> closeJobLogEditors(page) }
      }
    }
    removeListeners()
    contentRegistry.values.forEach { it.get()?.text = "" }
    contentRegistry.clear()
  }

  private fun closeJobLogEditors(page: IWorkbenchPage) {
    val jobLogEditors = page.editorReferences
      .filter { safeInput(it) is JobLogEditorInput }
      .toTypedArray()
    if (jobLogEditors.isNotEmpty()) {
      page.closeEditors(jobLogEditors, false) // false = don't save (read-only)
    }
  }

  /**
   * Idempotent: registers ONE part listener on the part service of every current workbench
   * window, plus a window listener so windows opened later also get it (and closed windows
   * have it removed).
   */
  private fun ensureListenersRegistered() {
    if (partListener != null) return
    val workbench = PlatformUI.getWorkbench()
    // All IPartListener2 methods are default on this platform (verified on the compile
    // classpath, org.eclipse.ui.workbench 3.133.0), so only partClosed needs an override.
    val pl = object : IPartListener2 {
      override fun partClosed(partRef: IWorkbenchPartReference) = onPartClosed(partRef)
    }
    partListener = pl
    workbench.workbenchWindows.forEach { it.partService.addPartListener(pl) }
    val wl = object : IWindowListener {
      override fun windowOpened(window: IWorkbenchWindow) {
        partListener?.let { window.partService.addPartListener(it) }
      }

      override fun windowClosed(window: IWorkbenchWindow) {
        partListener?.let { window.partService.removePartListener(it) }
      }

      override fun windowActivated(window: IWorkbenchWindow) { /* no-op */ }

      override fun windowDeactivated(window: IWorkbenchWindow) { /* no-op */ }
    }
    windowListener = wl
    workbench.addWindowListener(wl)
  }

  /** When the LAST editor for a key closes (any window/page), wipe and drop its content. */
  private fun onPartClosed(ref: IWorkbenchPartReference) {
    val editorRef = ref as? IEditorReference ?: return
    val closedKey = (safeInput(editorRef) as? JobLogEditorInput)?.key ?: return
    val stillOpen = PlatformUI.getWorkbench().workbenchWindows
      .flatMap { it.pages.toList() }
      .flatMap { it.editorReferences.toList() }
      .filter { it != editorRef }
      .any { (safeInput(it) as? JobLogEditorInput)?.key == closedKey }
    if (!stillOpen) {
      // Wipe trace text immediately rather than waiting for GC.
      contentRegistry.remove(closedKey)?.get()?.text = ""
    }
  }

  private fun removeListeners() {
    val workbench = PlatformUI.getWorkbench()
    partListener?.let { pl ->
      workbench.workbenchWindows.forEach { it.partService.removePartListener(pl) }
    }
    windowListener?.let { workbench.removeWindowListener(it) }
    partListener = null
    windowListener = null
  }

  private fun safeInput(ref: IEditorReference): IEditorInput? =
    try {
      ref.editorInput
    } catch (ignored: PartInitException) {
      null
    }
}
