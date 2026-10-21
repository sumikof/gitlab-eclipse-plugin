package com.gitlab.eclipse.codesuggestions.status

import com.gitlab.eclipse.authentication.AuthState
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerFailureKind
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleState
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerPhase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

class DuoStatusResolverTest : DescribeSpec({
  fun life(phase: LanguageServerPhase, failure: LanguageServerFailureKind? = null) =
    LanguageServerLifecycleState(phase, 1, null, failure)

  val running = life(LanguageServerPhase.RUNNING)

  fun inputs(
    lifecycle: LanguageServerLifecycleState = running,
    auth: AuthState = AuthState.AUTHENTICATED,
    enabled: Boolean = true,
    language: Boolean? = true,
    known: Boolean = true,
    primary: String? = null,
  ) = DuoStatusInputs(lifecycle, auth, enabled, language, known, primary)

  fun check(i: DuoStatusInputs, icon: DuoStatusIcon, tooltip: String, label: String) {
    val s = DuoStatusResolver.resolve(i)
    s.icon shouldBe icon
    s.tooltip shouldBe tooltip
    s.menuLabel shouldBe label
  }

  describe("priority table rows") {
    it("1 failed to start") {
      for (k in LanguageServerFailureKind.values().filter { it != LanguageServerFailureKind.CRASHED }) {
        check(
          inputs(lifecycle = life(LanguageServerPhase.FAILED, k)),
          DuoStatusIcon.ERROR,
          "GitLab Duo is disabled: Language Server failed to start.",
          "Code Suggestions: Unavailable (Language Server failed to start)",
        )
      }
      check(
        inputs(lifecycle = life(LanguageServerPhase.FAILED, null)),
        DuoStatusIcon.ERROR,
        "GitLab Duo is disabled: Language Server failed to start.",
        "Code Suggestions: Unavailable (Language Server failed to start)",
      )
    }
    it("1' crashed") {
      check(
        inputs(lifecycle = life(LanguageServerPhase.FAILED, LanguageServerFailureKind.CRASHED)),
        DuoStatusIcon.ERROR,
        "GitLab Duo is disabled: Language Server stopped unexpectedly.",
        "Code Suggestions: Unavailable (Language Server stopped)",
      )
    }
    it("2 starting") {
      check(
        inputs(lifecycle = life(LanguageServerPhase.STARTING)),
        DuoStatusIcon.LOADING,
        "GitLab Duo: Starting Language Server...",
        "Code Suggestions: Starting...",
      )
    }
    it("3 stopped") {
      check(
        inputs(lifecycle = life(LanguageServerPhase.STOPPED)),
        DuoStatusIcon.UNAVAILABLE,
        "GitLab Duo: Language Server is not running.",
        "Code Suggestions: Unavailable (Language Server not running)",
      )
    }
    it("4 setting disabled") {
      check(
        inputs(enabled = false),
        DuoStatusIcon.DISABLED,
        "Code suggestions are disabled",
        "Code Suggestions: Disabled",
      )
    }
    it("6 language disabled") {
      check(
        inputs(language = false),
        DuoStatusIcon.DISABLED,
        "Code suggestions are disabled for this language",
        "Code Suggestions: Disabled (language)",
      )
    }
    it("null language does not disable") {
      check(
        inputs(language = null),
        DuoStatusIcon.ENABLED,
        "Code suggestions are enabled",
        "Code Suggestions: Enabled",
      )
    }
    it("7 checking") {
      check(
        inputs(known = false),
        DuoStatusIcon.LOADING,
        "GitLab Duo: Checking Code Suggestions status...",
        "Code Suggestions: Checking...",
      )
    }
    it("8 auth required via auth state") {
      check(
        inputs(auth = AuthState.SIGN_IN_REQUIRED),
        DuoStatusIcon.DISABLED,
        "Authentication required for Code Suggestions",
        "Code Suggestions: Disabled (Authentication Required)",
      )
    }
    it("8 auth required via primary check") {
      check(
        inputs(primary = "authentication-required"),
        DuoStatusIcon.DISABLED,
        "Authentication required for Code Suggestions",
        "Code Suggestions: Disabled (Authentication Required)",
      )
    }
    it("UNKNOWN auth alone does not trigger row 8") {
      check(
        inputs(auth = AuthState.UNKNOWN),
        DuoStatusIcon.ENABLED,
        "Code suggestions are enabled",
        "Code Suggestions: Enabled",
      )
    }
    it("9 disabled by user") {
      check(
        inputs(primary = "code-suggestions-disabled-by-user"),
        DuoStatusIcon.DISABLED,
        "Code suggestions are disabled per user request",
        "Code Suggestions: Disabled (Disabled by User)",
      )
    }
    it("10 api error") {
      check(
        inputs(primary = "code-suggestions-api-error"),
        DuoStatusIcon.ERROR,
        "Code Suggestion requests to API are failing",
        "Code Suggestions: Unavailable (API Error)",
      )
    }
    it("11 unsupported version") {
      check(
        inputs(primary = "code-suggestions-unsupported-gitlab-version"),
        DuoStatusIcon.DISABLED,
        "GitLab Duo Code Suggestions requires GitLab version 16.8 or later.",
        "Code Suggestions: Disabled (Unsupported GitLab Version)",
      )
    }
    it("12 no license") {
      check(
        inputs(primary = "code-suggestions-no-license"),
        DuoStatusIcon.DISABLED,
        "A GitLab Duo license is required to use this feature. Contact your GitLab administrator to request access.",
        "Code Suggestions: Disabled (Invalid License)",
      )
    }
    it("13 no default namespace") {
      check(
        inputs(primary = "code-suggestions-no-default-namespace"),
        DuoStatusIcon.DISABLED,
        "Code Suggestions cannot detect a namespace for this project. To continue, please set a default GitLab Duo " +
          "namespace in your user preferences.",
        "Code Suggestions: Disabled (No Default Namespace Selected)",
      )
    }
    it("14 no credits") {
      check(
        inputs(primary = "code-suggestions-no-credits"),
        DuoStatusIcon.DISABLED,
        "No GitLab Credits remain for this billing period. To continue using Code Suggestions, contact your administrator. " +
          "When you have more credits, restart the GitLab Language Server.",
        "Code Suggestions: Disabled (Out of GitLab Credits)",
      )
    }
    it("15 duo disabled for project") {
      check(
        inputs(primary = "duo-disabled-for-project"),
        DuoStatusIcon.DISABLED,
        "Code suggestions are disabled for this project",
        "Code Suggestions: Disabled (GitLab Duo Disabled for Project)",
      )
    }
    it("16 file excluded") {
      check(
        inputs(primary = "code-suggestions-file-excluded"),
        DuoStatusIcon.DISABLED,
        "File excluded from GitLab Duo context by project settings",
        "Code Suggestions: Disabled (File Excluded)",
      )
    }
    it("17 unknown id") {
      check(
        inputs(primary = "some-new-check"),
        DuoStatusIcon.DISABLED,
        "Code suggestions unavailable (unknown state: some-new-check)",
        "Code Suggestions: Disabled (some-new-check)",
      )
    }
    it("18 enabled") {
      check(
        inputs(),
        DuoStatusIcon.ENABLED,
        "Code suggestions are enabled",
        "Code Suggestions: Enabled",
      )
    }
  }

  describe("precedence") {
    fun label(i: DuoStatusInputs) = DuoStatusResolver.resolve(i).menuLabel

    val failed = life(LanguageServerPhase.FAILED, LanguageServerFailureKind.START_FAILED)
    val starting = life(LanguageServerPhase.STARTING)
    val signIn = AuthState.SIGN_IN_REQUIRED

    it("FAILED beats disabled setting") {
      label(inputs(lifecycle = failed, enabled = false)) shouldBe "Code Suggestions: Unavailable (Language Server failed to start)"
    }
    it("STARTING beats auth") {
      label(inputs(lifecycle = starting, auth = signIn)) shouldBe "Code Suggestions: Starting..."
    }
    it("setting disabled beats language") {
      label(inputs(enabled = false, language = false)) shouldBe "Code Suggestions: Disabled"
    }
    it("language beats checking") {
      label(inputs(language = false, known = false)) shouldBe "Code Suggestions: Disabled (language)"
    }
    it("checking beats auth") {
      label(inputs(known = false, auth = signIn)) shouldBe "Code Suggestions: Checking..."
    }
    it("auth beats api-error") {
      label(inputs(auth = signIn, primary = "code-suggestions-api-error")) shouldBe
        "Code Suggestions: Disabled (Authentication Required)"
    }
    describe("lifecycle beats settings and feature-state checks") {
      val settingVariants = mapOf(
        "setting disabled" to { l: LanguageServerLifecycleState -> inputs(lifecycle = l, enabled = false) },
        "language disabled" to { l: LanguageServerLifecycleState -> inputs(lifecycle = l, language = false) },
        "feature state unknown" to { l: LanguageServerLifecycleState -> inputs(lifecycle = l, known = false) },
      )
      val expectations = mapOf(
        "STARTING" to Triple(
          starting,
          "GitLab Duo: Starting Language Server...",
          "Code Suggestions: Starting...",
        ),
        "STOPPED" to Triple(
          life(LanguageServerPhase.STOPPED),
          "GitLab Duo: Language Server is not running.",
          "Code Suggestions: Unavailable (Language Server not running)",
        ),
        "FAILED" to Triple(
          failed,
          "GitLab Duo is disabled: Language Server failed to start.",
          "Code Suggestions: Unavailable (Language Server failed to start)",
        ),
      )
      for ((phase, exp) in expectations) {
        for ((variant, build) in settingVariants) {
          it("$phase beats $variant") {
            val s = DuoStatusResolver.resolve(build(exp.first))
            s.tooltip shouldBe exp.second
            s.menuLabel shouldBe exp.third
          }
        }
      }
    }
    it("api-error beats unsupported version") {
      label(inputs(primary = "code-suggestions-api-error")) shouldBe "Code Suggestions: Unavailable (API Error)"
    }
  }

  describe("consistency") {
    it("every row has non-blank text and only the enabled row uses the ENABLED icon") {
      val ids = listOf(
        "code-suggestions-disabled-by-user",
        "code-suggestions-api-error",
        "code-suggestions-unsupported-gitlab-version",
        "code-suggestions-no-license",
        "code-suggestions-no-default-namespace",
        "code-suggestions-no-credits",
        "duo-disabled-for-project",
        "code-suggestions-file-excluded",
        "x",
      )
      val cases = listOf(
        inputs(lifecycle = life(LanguageServerPhase.FAILED, LanguageServerFailureKind.CRASHED)),
        inputs(lifecycle = life(LanguageServerPhase.FAILED, LanguageServerFailureKind.START_FAILED)),
        inputs(lifecycle = life(LanguageServerPhase.STARTING)),
        inputs(lifecycle = life(LanguageServerPhase.STOPPED)),
        inputs(enabled = false),
        inputs(language = false),
        inputs(known = false),
        inputs(auth = AuthState.SIGN_IN_REQUIRED),
        inputs(primary = "authentication-required"),
        inputs(),
      ) + ids.map { inputs(primary = it) }
      for (c in cases) {
        val s = DuoStatusResolver.resolve(c)
        s.tooltip.shouldNotBeBlank()
        s.menuLabel.shouldNotBeBlank()
        (s.icon == DuoStatusIcon.ENABLED) shouldBe (s.menuLabel == "Code Suggestions: Enabled")
      }
    }
    it("icon names") {
      DuoStatusIcon.values().map { it.iconName } shouldBe
        listOf("duo_on_edit", "duo_off_edit", "duo_error_edit", "duo_load_edit", "duo_unav_edit")
    }
  }
})
