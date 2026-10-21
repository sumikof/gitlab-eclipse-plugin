package com.gitlab.eclipse.codesuggestions.status

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.google.gson.JsonObject
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.eclipse.jface.preference.IPersistentPreferenceStore
import org.eclipse.swt.SWT
import java.io.IOException
import java.time.LocalDate

private const val VERSION_ID = "code-suggestions-unsupported-gitlab-version"
private const val CREDITS_ID = "code-suggestions-no-credits"

private class MapPrefs : WarningPreferenceAccess {
  val values = mutableMapOf<String, String>()
  override fun get(key: String): String = values.getOrDefault(key, "")
  override fun set(key: String, value: String) {
    values[key] = value
  }
}

private fun versionCheck(baseUrl: String = "https://gl.example.com", version: String = "16.0.0") =
  FeatureStateChangeCheck(
    VERSION_ID,
    engaged = true,
    context = JsonObject().apply {
      addProperty("baseUrl", baseUrl)
      addProperty("version", version)
    },
  )

private val credits = FeatureStateChangeCheck(CREDITS_ID, engaged = true)

class CodeSuggestionsWarningNotifierTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  class Fixture {
    val prefs = MapPrefs()
    val policy = CodeSuggestionsWarningPolicy(prefs) { LocalDate.of(2026, 10, 21) }
    var current: LanguageServerSession? = LanguageServerSession()
    var closed = false
    var upgradeDocsOpened = 0
    val queued = mutableListOf<Runnable>()
    val presented = mutableListOf<WarningPresentation>()
    val notifier = CodeSuggestionsWarningNotifier(
      policy = policy,
      currentSession = { current },
      isClosed = { closed },
      openUpgradeDocs = { upgradeDocsOpened++ },
      uiDispatch = { queued += it },
      present = { presented += it },
    )

    fun runUi() {
      while (queued.isNotEmpty()) queued.removeAt(0).run()
    }
  }

  describe("unsupported GitLab version (W1)") {
    it("shows the warning with the design text, warning image, no auto-close and two links") {
      val f = Fixture()

      f.notifier.onFeatureState(f.current!!, listOf(versionCheck()))
      f.runUi()

      f.presented shouldHaveSize 1
      val shown = f.presented.single()
      shown.title shouldBe "GitLab Duo"
      shown.message shouldBe "GitLab Duo Code Suggestions requires GitLab version 16.8.0 or later. " +
        "GitLab instance located at: https://gl.example.com is currently using 16.0.0."
      shown.severity shouldBe SWT.ICON_WARNING
      shown.autoCloseMillis shouldBe 0L
      shown.links.map { it.first } shouldContainExactly listOf("Learn about upgrading", "Do not show again")
    }

    it("'Learn about upgrading' opens the upgrade documentation") {
      val f = Fixture()
      f.notifier.onFeatureState(f.current!!, listOf(versionCheck()))
      f.runUi()

      f.presented.single().links.first { it.first == "Learn about upgrading" }.second()

      f.upgradeDocsOpened shouldBe 1
      f.prefs.values[PreferenceConstants.CODE_SUGGESTIONS_VERSION_WARNING_SUPPRESSED] shouldBe null
    }

    it("'Do not show again' persists the instance through the policy, so a new session stays quiet") {
      val f = Fixture()
      f.notifier.onFeatureState(f.current!!, listOf(versionCheck("https://gl.example.com/")))
      f.runUi()

      f.presented.single().links.first { it.first == "Do not show again" }.second()

      f.prefs.values[PreferenceConstants.CODE_SUGGESTIONS_VERSION_WARNING_SUPPRESSED] shouldBe "https://gl.example.com"
      f.current = LanguageServerSession()
      f.notifier.onFeatureState(f.current!!, listOf(versionCheck()))
      f.runUi()
      f.presented shouldHaveSize 1
    }
  }

  describe("no credits (W2)") {
    it("shows the information with the design text, 10 s auto-close and a Dismiss link") {
      val f = Fixture()

      f.notifier.onFeatureState(f.current!!, listOf(credits))
      f.runUi()

      val shown = f.presented.single()
      shown.title shouldBe "GitLab Duo"
      shown.message shouldBe "No GitLab Credits remain for this billing period. To continue using Code Suggestions, " +
        "contact your administrator. When you have more credits, restart the GitLab Language Server."
      shown.severity shouldBe SWT.ICON_INFORMATION
      shown.autoCloseMillis shouldBe 10_000L
      shown.links.map { it.first } shouldContainExactly listOf("Dismiss")
    }

    it("'Dismiss' persists today's date through the policy") {
      val f = Fixture()
      f.notifier.onFeatureState(f.current!!, listOf(credits))
      f.runUi()

      f.presented.single().links.single().second()

      f.prefs.values[PreferenceConstants.CODE_SUGGESTIONS_NO_CREDITS_DISMISSED_DATE] shouldBe "2026-10-21"
    }
  }

  describe("session and store gating (W6)") {
    it("drops an event of a stale session before the policy, so the policy keeps the current session's state") {
      val f = Fixture()
      val live = f.current!!
      f.notifier.onFeatureState(live, listOf(credits))
      f.runUi()

      f.notifier.onFeatureState(LanguageServerSession(), listOf(credits))
      f.notifier.onFeatureState(live, listOf(credits))
      f.runUi()

      // Had the stale event reached the policy, its session state would have flipped twice and the
      // live session's warning would show a second time.
      f.presented shouldHaveSize 1
      f.queued.shouldBeEmpty()
    }

    it("shows nothing when there is no current session") {
      val f = Fixture()
      val session = f.current!!
      f.current = null

      f.notifier.onFeatureState(session, listOf(credits))
      f.runUi()

      f.presented.shouldBeEmpty()
    }

    it("shows nothing when the session changed before the UI runnable ran") {
      val f = Fixture()
      f.notifier.onFeatureState(f.current!!, listOf(versionCheck()))
      f.current = LanguageServerSession()

      f.runUi()

      f.presented.shouldBeEmpty()
    }

    it("shows nothing when the lifecycle store is closed on arrival") {
      val f = Fixture()
      f.closed = true

      f.notifier.onFeatureState(f.current!!, listOf(credits))
      f.runUi()

      f.presented.shouldBeEmpty()
      f.queued.shouldBeEmpty()
    }

    it("shows nothing when the lifecycle store closed before the UI runnable ran") {
      val f = Fixture()
      f.notifier.onFeatureState(f.current!!, listOf(credits))
      f.closed = true

      f.runUi()

      f.presented.shouldBeEmpty()
    }

    it("shows nothing for another primary check") {
      val f = Fixture()

      val apiError = FeatureStateChangeCheck("code-suggestions-api-error", true)
      f.notifier.onFeatureState(f.current!!, listOf(apiError, credits))
      f.runUi()

      f.presented.shouldBeEmpty()
      f.queued.shouldBeEmpty()
    }
  }

  describe("containment") {
    it("never throws to the dispatching thread when the UI thread cannot be reached") {
      val f = Fixture()
      val notifier = CodeSuggestionsWarningNotifier(
        policy = f.policy,
        currentSession = { f.current },
        isClosed = { false },
        openUpgradeDocs = {},
        uiDispatch = { throw IllegalStateException("workbench torn down") },
        present = {},
      )

      shouldNotThrowAny { notifier.onFeatureState(f.current!!, listOf(credits)) }
    }

    it("never throws when the policy fails") {
      val failing = object : WarningPreferenceAccess {
        override fun get(key: String): String = error("store gone")
        override fun set(key: String, value: String) = Unit
      }
      val session = LanguageServerSession()
      val notifier = CodeSuggestionsWarningNotifier(
        policy = CodeSuggestionsWarningPolicy(failing) { LocalDate.of(2026, 10, 21) },
        currentSession = { session },
        isClosed = { false },
        openUpgradeDocs = {},
        uiDispatch = { it.run() },
        present = {},
      )

      shouldNotThrowAny { notifier.onFeatureState(session, listOf(credits)) }
    }
  }

  describe("ScopedPreferenceWarningAccess") {
    it("reads through getString") {
      val store = mockk<IPersistentPreferenceStore>(relaxUnitFun = true)
      every { store.getString("k") } returns "v"

      ScopedPreferenceWarningAccess(store).get("k") shouldBe "v"
    }

    it("writes with setValue, then saves") {
      val store = mockk<IPersistentPreferenceStore>(relaxUnitFun = true)

      ScopedPreferenceWarningAccess(store).set("k", "v")

      verifyOrder {
        store.setValue("k", "v")
        store.save()
      }
    }

    it("contains a failing save") {
      val store = mockk<IPersistentPreferenceStore>(relaxUnitFun = true)
      every { store.save() } throws IOException("disk full at /some/path")

      shouldNotThrowAny { ScopedPreferenceWarningAccess(store).set("k", "v") }
    }
  }
})
