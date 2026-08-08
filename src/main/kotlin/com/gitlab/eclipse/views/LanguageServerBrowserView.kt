@file:Suppress("MagicNumber", "UseOrEmpty")

package com.gitlab.eclipse.views

import com.gitlab.eclipse.chat.ChatAvailability
import com.gitlab.eclipse.chat.ChatAvailabilityService
import com.gitlab.eclipse.chat.ChatSelectionResolver
import com.gitlab.eclipse.chat.webview.AgenticChatWebViewClient
import com.gitlab.eclipse.chat.webview.ChatIntentAction
import com.gitlab.eclipse.chat.webview.ChatIntentRouter
import com.gitlab.eclipse.chat.webview.ChatWebviewCatalog
import com.gitlab.eclipse.chat.webview.ChatWebviewEntry
import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewClient
import com.gitlab.eclipse.chat.webview.PendingChatIntents
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.NewPromptRequest
import com.gitlab.eclipse.lsp.WebviewInfo
import com.gitlab.eclipse.lsp.webview.ThemeProvider
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.system.SystemUtils
import org.eclipse.swt.SWT
import org.eclipse.swt.browser.Browser
import org.eclipse.swt.custom.StackLayout
import org.eclipse.swt.widgets.Composite
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.keys.IBindingService
import org.eclipse.ui.part.ViewPart
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.util.concurrent.TimeUnit

/**
 * Hosts the chat webviews advertised by the Language Server (classic `duo-chat-v2` and
 * `agentic-duo-chat`) on a [StackLayout], one [Browser] per webview id, plus a loading page and a
 * message page (LS not ready / no candidates / all disabled).
 *
 * Threading contract: all public methods ([refresh], [selectWebview], [requestFocus],
 * [requestClassicPrompt], [requestAgenticView], [setFocus]) must be called on the UI thread. The
 * Language Server metadata future is consumed off the UI thread (`whenComplete`) and its result is
 * applied via `asyncExec` guarded by a generation counter (latest-wins) and a dispose check.
 */
@Suppress("TooManyFunctions")
class LanguageServerBrowserView : ViewPart() {
  companion object {
    private const val CLASSIC_WEBVIEW_ID = ChatWebviewCatalog.CLASSIC_WEBVIEW_ID
    private const val AGENTIC_WEBVIEW_ID = ChatWebviewCatalog.AGENTIC_WEBVIEW_ID
    private const val METADATA_TIMEOUT_SECONDS = 10L
  }

  private val logger = logger<LanguageServerBrowserView>()

  private val languageServerWrapper by lazyService<GitLabLanguageServerWrapper>()
  private val chatAvailabilityService by lazyService<ChatAvailabilityService>()
  private val preferenceStore by lazyService<ScopedPreferenceStore>()
  private val classicWebViewClient by lazyService<GitLabDuoChatWebViewClient>()
  private val agenticWebViewClient by lazyService<AgenticChatWebViewClient>()

  private val stackLayout = StackLayout()
  private var container: Composite? = null
  private var loadingPage: Browser? = null
  private var messagePage: Browser? = null

  /** One live [Browser] per chat webview id (kept alive across selection switches). */
  private val pages = mutableMapOf<String, Browser>()

  /** The URI each Browser in [pages] currently has loaded, to detect URI changes per id. */
  private val pageUris = mutableMapOf<String, String>()

  /** Refresh generation counter (UI thread only): stale metadata results are dropped. */
  private var generation = 0
  private var selectedId: String? = null

  /** Transient intents flushed after the next selection resolution (see §17.1/§17.1a). */
  private var focusRequested = false
  private var pendingClassicPrompt: NewPromptRequest? = null
  private var pendingAgenticView: String? = null

  override fun createPartControl(parent: Composite?) {
    val root = Composite(parent, SWT.NONE)
    root.layout = stackLayout
    container = root

    loadingPage = newBrowser(root)
    messagePage = newBrowser(root)

    refresh()
    appendKeybindingToTitle()
  }

  /**
   * Re-resolves candidates and selection from the Language Server without blocking the UI thread.
   * Safe to call repeatedly; only the latest invocation's result is applied.
   */
  fun refresh() {
    val root = container ?: return
    if (root.isDisposed) return

    generation += 1
    val gen = generation
    showLoadingUnlessChatVisible()

    val future = languageServerWrapper.languageServer?.webviewMetadata()
    if (future == null) {
      // P1-G: no LS / no future — a failed fetch, same as timeout/error below: keep any live
      // chat Browsers untouched; the message page is shown unless an enabled chat is visible.
      // A later refresh (feature-state transition or LS-ready hook) recovers this state.
      showMessagePageUnlessChatVisible(
        "GitLab Duo Chat is not ready yet: waiting for the language server to start."
      )
      return
    }

    future
      .orTimeout(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .whenComplete { metadata, error ->
        if (error != null) {
          logger.error("Failed to fetch webview metadata", error)
        }
        currentDisplay.asyncExec {
          val current = container
          if (current == null || current.isDisposed || gen != generation) return@asyncExec
          if (error != null || metadata == null) {
            // Failed fetch (timeout or exceptional completion) — distinct from a successful
            // response that is genuinely empty. Do NOT sync/dispose Browsers: the live chat
            // (and its conversation) must survive a transient metadata failure. Park on the
            // message page unless an ENABLED chat is currently visible — a chat whose feature
            // was just disabled is hidden even though the fetch failed; recovery comes from a
            // later refresh (feature-state transition or LS-ready hook).
            showMessagePageUnlessChatVisible(
              "GitLab Duo Chat is currently unavailable: could not reach the language server."
            )
          } else {
            applyMetadata(metadata)
          }
        }
      }
  }

  /**
   * Switches the visible chat webview to [id] and persists the selection. Existing Browsers are
   * kept alive (chat state is preserved). If no Browser exists yet for [id], only the selection
   * is persisted; the next [refresh] resolution honors it.
   *
   * Keyboard focus moves into the shown Browser here — and only here — because [selectWebview]
   * runs exclusively on user actions (toolbar selector, classic-prompt commands). The passive
   * resolution path ([showResolvedSelection], reached by every [refresh]) must never call
   * `setFocus()`: it also fires on background refreshes (feature-state changes, LS-ready hook)
   * and would steal focus from whatever widget the user is typing in.
   */
  fun selectWebview(id: String) {
    selectedId = id
    preferenceStore.setValue(PreferenceConstants.DUO_CHAT_SELECTED_WEBVIEW, id)

    val page = pages[id] ?: return
    stackLayout.topControl = page
    container?.layout()
    page.setFocus()
  }

  /**
   * Requests that, once the selection resolves to the classic webview, a `focusChat` prompt is
   * sent to the classic client. If the resolution shows anything else, or no chat at all, nothing
   * is sent (no classic queue stranding).
   */
  fun requestFocus() {
    focusRequested = true
    refresh()
  }

  /**
   * Forces the selection to the classic webview and sends [payload] to the classic client once
   * classic is resolved and shown. If the resolution shows anything else, or no chat at all, the
   * intent is dropped.
   */
  fun requestClassicPrompt(payload: NewPromptRequest) {
    selectWebview(CLASSIC_WEBVIEW_ID)
    pendingClassicPrompt = payload
    refresh()
  }

  /**
   * Forces the selection to the agentic webview and asks the agentic client to switch it to [view]
   * once agentic is resolved and shown. If the resolution shows anything else, or no chat at all,
   * the intent is dropped.
   *
   * This is gate A of design §7.4; gate B — the webview app having reported itself ready — is the
   * agentic client's own.
   */
  fun requestAgenticView(view: String) {
    selectWebview(AGENTIC_WEBVIEW_ID)
    pendingAgenticView = view
    refresh()
  }

  override fun setFocus() {
    stackLayout.topControl?.setFocus()
  }

  private fun applyMetadata(metadata: List<WebviewInfo?>?) {
    val candidates = ChatWebviewCatalog.extract(metadata)
    val availability = candidates.associate { it.id to chatAvailabilityService.availabilityFor(it.id) }
    syncBrowsers(candidates, availability)

    val saved = preferenceStore
      .getString(PreferenceConstants.DUO_CHAT_SELECTED_WEBVIEW)
      .takeIf { it.isNotBlank() }
    val resolved = ChatSelectionResolver.resolve(candidates, availability, saved)

    // §12: if the saved selection's id disappeared from the candidates, re-point the preference.
    if (resolved != null && saved != null && candidates.none { it.id == saved }) {
      preferenceStore.setValue(PreferenceConstants.DUO_CHAT_SELECTED_WEBVIEW, resolved)
    }
    selectedId = resolved

    val shownId = showResolvedSelection(resolved, availability)
    flushPendingIntents(shownId)
  }

  /** Applies §12: dispose Browsers of vanished ids, create/reload Browsers of enabled candidates. */
  private fun syncBrowsers(candidates: List<ChatWebviewEntry>, availability: Map<String, ChatAvailability>) {
    val root = container ?: return
    val advertisedIds = candidates.map { it.id }.toSet()

    (pages.keys - advertisedIds).forEach { id ->
      pages.remove(id)?.dispose()
      pageUris.remove(id)
    }

    candidates
      .filter { availability[it.id]?.enabled == true }
      .forEach { entry ->
        val existing = pages[entry.id]
        when {
          existing == null -> {
            val browser = newBrowser(root)
            browser.setUrl(entry.uri)
            pages[entry.id] = browser
            pageUris[entry.id] = entry.uri
            closeAgenticLatch(entry.id)
          }
          pageUris[entry.id] != entry.uri -> {
            existing.setUrl(entry.uri)
            pageUris[entry.id] = entry.uri
            closeAgenticLatch(entry.id)
          }
          // else: id continues with unchanged URI — reuse as-is, chat state preserved.
        }
      }
  }

  /**
   * Closes the agentic client's readiness latch when [id] is the webview that was just given a
   * Browser or pointed at a new URI. The latch stands for the page that reported itself ready, and
   * that page has just been replaced (design §5.3 / §7.4).
   */
  private fun closeAgenticLatch(id: String) {
    if (id == AGENTIC_WEBVIEW_ID) agenticWebViewClient.markNotReady()
  }

  /** Sets the top control for the resolved selection; returns the webview id now shown, if any. */
  private fun showResolvedSelection(resolved: String?, availability: Map<String, ChatAvailability>): String? {
    if (resolved == null) {
      logger.warn("No chat webview advertised by the language server")
      showMessagePage("GitLab Duo Chat is currently unavailable: no chat webview is available.")
      return null
    }

    if (availability[resolved]?.enabled != true) {
      val reason = availability[resolved]?.disabledReason
      val details = if (reason != null) ": $reason" else "."
      showMessagePage("GitLab Duo Chat is currently disabled$details")
      return null
    }

    val page = pages[resolved]
    if (page == null || page.isDisposed) {
      logger.error("No browser exists for enabled webview '$resolved'")
      showMessagePage("GitLab Duo Chat is currently unavailable: no chat webview is available.")
      return null
    }

    stackLayout.topControl = page
    container?.layout()
    return resolved
  }

  /**
   * §17.1/§17.1a: intents are transient — every intent is dropped here whether or not it is routed,
   * so nothing survives into a later resolution.
   *
   * Which of them reach a client is design §7.4's routing rule, and it lives in [ChatIntentRouter]
   * rather than here so that it can be tested without SWT.
   */
  private fun flushPendingIntents(shownId: String?) {
    val intents = PendingChatIntents(focusRequested, pendingClassicPrompt, pendingAgenticView)
    focusRequested = false
    pendingClassicPrompt = null
    pendingAgenticView = null

    ChatIntentRouter.route(shownId, intents).forEach { action ->
      when (action) {
        is ChatIntentAction.SendClassicPrompt -> classicWebViewClient.notify("newPrompt", action.payload)
        is ChatIntentAction.SwitchAgenticView -> agenticWebViewClient.switchView(action.view)
      }
    }
  }

  /**
   * Shows the loading page unless an ENABLED chat Browser is currently visible: an in-place
   * re-resolution keeps a healthy live chat on screen instead of flashing a loading page
   * (latest-wins still applies). A shown chat whose feature was just disabled (the feature-state
   * transition that triggered this refresh) is hidden immediately rather than staying interactive
   * for the duration of the pending metadata fetch.
   */
  private fun showLoadingUnlessChatVisible() {
    if (isEnabledChatVisible()) return

    val page = loadingPage ?: return
    page.setText(themedHtml("Loading GitLab Duo Chat..."))
    stackLayout.topControl = page
    container?.layout()
  }

  /**
   * Failure path (timeout / error / no LS): shows the message page unless an ENABLED chat Browser
   * is currently visible. A healthy on-screen conversation survives a transient fetch failure,
   * but a chat whose feature was disabled (logout, entitlement revocation) is hidden even when
   * the ensuing metadata fetch also fails: feature state via [ChatAvailabilityService] is
   * authoritative and was already applied before [refresh] ran.
   */
  private fun showMessagePageUnlessChatVisible(message: String) {
    if (isEnabledChatVisible()) return
    showMessagePage(message)
  }

  /** True when the top control is a live chat Browser whose feature is still enabled. */
  private fun isEnabledChatVisible(): Boolean {
    val shownId = shownChatId() ?: return false
    return chatAvailabilityService.availabilityFor(shownId).enabled
  }

  /** The webview id of the chat Browser currently on top, or null when no chat is shown. */
  private fun shownChatId(): String? {
    val top = stackLayout.topControl
    if (top == null || top.isDisposed) return null
    return pages.entries.firstOrNull { it.value == top }?.key
  }

  private fun showMessagePage(message: String) {
    val page = messagePage ?: return
    page.setText(themedHtml(message))
    stackLayout.topControl = page
    container?.layout()
  }

  private fun newBrowser(parent: Composite): Browser {
    val browserStyle = if (SystemUtils.isWindows()) SWT.EDGE else SWT.WEBKIT
    return Browser(parent, browserStyle)
  }

  private fun themedHtml(message: String): String {
    val colors = ThemeProvider.currentTheme()

    return """
      <!doctype html>
      <html lang="en">
      <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>GitLab</title>
      </head>
      <style>
        * {
          background: ${colors.styles["--editor-background-alternative"]};
          color: ${colors.styles["--editor-foreground"]};

          font-family: ${colors.styles["--editor-font-family"]};
          font-size: ${colors.styles["--editor-font-size"]};
          font-weight: ${colors.styles["--editor-font-style"]};
        }
      </style>
      <body>
          <p>$message</p>
      </body>
      </html>
    """.trimIndent()
  }

  private fun appendKeybindingToTitle() {
    try {
      PlatformUI
        .getWorkbench()
        .getService(IBindingService::class.java)
        .getActiveBindingsFor("gitlab-eclipse-plugin.commands.OpenDuoChat")
        .firstOrNull()
        ?.let {
          partName += " (${it.format()})"
        }
    } catch (e: Exception) {
      logger.error("Failed to update title with keybinding", e)
    }
  }
}
