package com.gitlab.eclipse.codesuggestions.status

import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.google.gson.JsonObject
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

private const val VERSION_ID = "code-suggestions-unsupported-gitlab-version"
private const val CREDITS_ID = "code-suggestions-no-credits"
private const val SUPPRESSED = "gitlab.codeSuggestions.versionWarning.suppressedInstances"
private const val DISMISSED = "gitlab.codeSuggestions.noCredits.dismissedDate"

private class FakePrefs : WarningPreferenceAccess {
  val values = mutableMapOf<String, String>()
  override fun get(key: String): String = values.getOrDefault(key, "")
  override fun set(key: String, value: String) {
    values[key] = value
  }
}

private fun versionCheck(baseUrl: String? = "https://gl.example.com", version: String? = "16.0.0"): FeatureStateChangeCheck {
  val context = JsonObject()
  if (baseUrl != null) context.addProperty("baseUrl", baseUrl)
  if (version != null) context.addProperty("version", version)
  return FeatureStateChangeCheck(VERSION_ID, true, context = context)
}

private val credits = FeatureStateChangeCheck(CREDITS_ID, true)

class CodeSuggestionsWarningPolicyTest : DescribeSpec({
  var today = LocalDate.of(2026, 10, 21)
  lateinit var prefs: FakePrefs
  lateinit var policy: CodeSuggestionsWarningPolicy
  val s1 = Any()

  beforeEach {
    today = LocalDate.of(2026, 10, 21)
    prefs = FakePrefs()
    policy = CodeSuggestionsWarningPolicy(prefs) { today }
  }

  describe("unsupported version") {
    it("shows once per session per URL") {
      policy.onFeatureState(s1, listOf(versionCheck())) shouldBe
        CodeSuggestionsWarning.UnsupportedVersion("https://gl.example.com", "16.0.0")
      policy.onFeatureState(s1, listOf(versionCheck())) shouldBe null
    }
    it("shows a second URL") {
      policy.onFeatureState(s1, listOf(versionCheck())) shouldBe
        CodeSuggestionsWarning.UnsupportedVersion("https://gl.example.com", "16.0.0")
      policy.onFeatureState(s1, listOf(versionCheck("https://other.example.com", "15.0"))) shouldBe
        CodeSuggestionsWarning.UnsupportedVersion("https://other.example.com", "15.0")
    }
    it("is not shown for a suppressed URL, with or without trailing slash") {
      policy.suppressVersionWarning("https://gl.example.com/")
      policy.onFeatureState(s1, listOf(versionCheck("https://gl.example.com"))) shouldBe null
      policy.onFeatureState(Any(), listOf(versionCheck("https://gl.example.com/"))) shouldBe null
    }
    it("returns null for missing or non-string context values") {
      policy.onFeatureState(s1, listOf(FeatureStateChangeCheck(VERSION_ID, true))) shouldBe null
      policy.onFeatureState(s1, listOf(versionCheck(baseUrl = null))) shouldBe null
      policy.onFeatureState(s1, listOf(versionCheck(version = null))) shouldBe null
      val context = JsonObject()
      context.addProperty("baseUrl", 5)
      context.add("version", JsonObject())
      val numeric = FeatureStateChangeCheck(VERSION_ID, true, context = context)
      policy.onFeatureState(s1, listOf(numeric)) shouldBe null
    }
    it("returns null when another check is engaged first") {
      val apiError = FeatureStateChangeCheck("code-suggestions-api-error", true)
      policy.onFeatureState(s1, listOf(apiError, versionCheck())) shouldBe null
    }
    it("ignores disengaged checks when picking the primary") {
      val off = FeatureStateChangeCheck("code-suggestions-api-error", false)
      policy.onFeatureState(s1, listOf(off, versionCheck())) shouldBe
        CodeSuggestionsWarning.UnsupportedVersion("https://gl.example.com", "16.0.0")
    }
    it("null or empty checks return null") {
      policy.onFeatureState(s1, null) shouldBe null
      policy.onFeatureState(s1, emptyList()) shouldBe null
    }
    it("shows again in a new session") {
      policy.onFeatureState(s1, listOf(versionCheck())) shouldBe
        CodeSuggestionsWarning.UnsupportedVersion("https://gl.example.com", "16.0.0")
      policy.onFeatureState(Any(), listOf(versionCheck())) shouldBe
        CodeSuggestionsWarning.UnsupportedVersion("https://gl.example.com", "16.0.0")
    }
    it("suppressVersionWarning appends without duplicates") {
      policy.suppressVersionWarning("https://a.example.com/")
      policy.suppressVersionWarning("https://b.example.com")
      policy.suppressVersionWarning("https://a.example.com")
      prefs.values[SUPPRESSED] shouldBe "https://a.example.com\nhttps://b.example.com"
    }
    it("a single suppression stores exactly the normalised URL") {
      policy.suppressVersionWarning("https://a.example.com/")
      prefs.values[SUPPRESSED] shouldBe "https://a.example.com"
    }
    it("trims surrounding whitespace when suppressing") {
      policy.suppressVersionWarning("  https://gl.example.com/ ")
      policy.onFeatureState(s1, listOf(versionCheck("https://gl.example.com"))) shouldBe null
    }
    it("tolerates a malformed persisted list") {
      prefs.values[SUPPRESSED] = "\n  \n\n"
      policy.onFeatureState(s1, listOf(versionCheck())) shouldBe
        CodeSuggestionsWarning.UnsupportedVersion("https://gl.example.com", "16.0.0")
    }
  }

  describe("no credits") {
    it("shows once per session, repeated events once") {
      policy.onFeatureState(s1, listOf(credits)) shouldBe CodeSuggestionsWarning.NoCredits
      policy.onFeatureState(s1, listOf(credits)) shouldBe null
    }
    it("shows again in a new session") {
      policy.onFeatureState(s1, listOf(credits)) shouldBe CodeSuggestionsWarning.NoCredits
      policy.onFeatureState(Any(), listOf(credits)) shouldBe CodeSuggestionsWarning.NoCredits
    }
    it("is not shown when dismissed today") {
      policy.dismissNoCreditsToday()
      policy.onFeatureState(Any(), listOf(credits)) shouldBe null
    }
    it("is shown when dismissed yesterday") {
      policy.dismissNoCreditsToday()
      today = today.plusDays(1)
      policy.onFeatureState(Any(), listOf(credits)) shouldBe CodeSuggestionsWarning.NoCredits
    }
    it("is shown with a malformed persisted date") {
      prefs.values[DISMISSED] = "yesterday-ish"
      policy.onFeatureState(s1, listOf(credits)) shouldBe CodeSuggestionsWarning.NoCredits
    }
    it("clear then re-raise in the same session is not shown again") {
      policy.onFeatureState(s1, listOf(credits)) shouldBe CodeSuggestionsWarning.NoCredits
      policy.onFeatureState(s1, listOf(FeatureStateChangeCheck(CREDITS_ID, false))) shouldBe null
      policy.onFeatureState(s1, listOf(credits)) shouldBe null
    }
    it("dismissNoCreditsToday writes the ISO date") {
      policy.dismissNoCreditsToday()
      prefs.values[DISMISSED] shouldBe "2026-10-21"
    }
  }
})
