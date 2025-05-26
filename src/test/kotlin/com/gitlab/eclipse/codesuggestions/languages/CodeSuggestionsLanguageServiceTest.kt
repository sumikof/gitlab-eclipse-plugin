package com.gitlab.eclipse.codesuggestions.languages

import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.ui.preferences.ScopedPreferenceStore

class CodeSuggestionsLanguageServiceTest : DescribeSpec({
  val preferenceStore = mockk<ScopedPreferenceStore>(relaxUnitFun = true)

  val codeSuggestionsLanguageService = CodeSuggestionsLanguageService(preferenceStore)

  afterEach {
    clearAllMocks(answers = false)
  }

  describe("getAdditionalLanguages") {
    it("returns empty list when preference is blank") {
      every { preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES) } returns ""

      val result = codeSuggestionsLanguageService.getAdditionalLanguages()

      result shouldBe emptyList()
    }

    it("returns empty list when preference is only whitespace") {
      every { preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES) } returns "   "

      val result = codeSuggestionsLanguageService.getAdditionalLanguages()

      result shouldBe emptyList()
    }

    it("splits comma-separated languages and trims whitespace") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES)
      } returns "JaVa, kotlin,  PYTHON "

      val result = codeSuggestionsLanguageService.getAdditionalLanguages()

      result shouldBe listOf("java", "kotlin", "python")
    }

    it("ignores empty entries") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES)
      } returns "java,,kotlin, ,python"

      val result = codeSuggestionsLanguageService.getAdditionalLanguages()

      result shouldBe listOf("java", "kotlin", "python")
    }
  }

  describe("getDisabledLanguages") {
    it("returns empty list when preference is blank") {
      every { preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES) } returns ""

      val result = codeSuggestionsLanguageService.getDisabledLanguages()

      result shouldBe emptyList()
    }

    it("returns empty list when preference is only whitespace") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES)
      } returns "   "

      val result = codeSuggestionsLanguageService.getDisabledLanguages()

      result shouldBe emptyList()
    }

    it("splits comma-separated languages and trims whitespace") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES)
      } returns "java, kotlin,  python "

      val result = codeSuggestionsLanguageService.getDisabledLanguages()

      result shouldBe listOf("java", "kotlin", "python")
    }

    it("ignores empty entries") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES)
      } returns "java,,kotlin, ,python"

      val result = codeSuggestionsLanguageService.getDisabledLanguages()

      result shouldBe listOf("java", "kotlin", "python")
    }
  }

  describe("isEnabled") {
    it("should be enabled if supported language that is not disabled") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES)
      } returns "python,javascript"

      val result = codeSuggestionsLanguageService.isEnabled("java")

      result shouldBe true
    }

    it("should be disabled if supported language that is disabled") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES)
      } returns "java,python"

      val result = codeSuggestionsLanguageService.isEnabled("java")

      result shouldBe false
    }

    it("should be enabled if additional language that is explicitly added") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES)
      } returns "ZiG,gleam"

      val result = codeSuggestionsLanguageService.isEnabled("zig")

      result shouldBe true
    }

    it("should be disabled if additional language that is not explicitly added") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES)
      } returns "zig,gleam"

      val result = codeSuggestionsLanguageService.isEnabled("elm")

      result shouldBe false
    }
  }

  describe("toggleLanguage") {
    it("should add language to disabled list when disabling a supported language") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES)
      } returns "python,javascript"

      codeSuggestionsLanguageService.toggleLanguage("java")

      verify {
        preferenceStore.putValue(
          PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES,
          "python,javascript,java"
        )
      }
    }

    it("should remove language from disabled list when enabling a supported language") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES)
      } returns "java,python,javascript"

      codeSuggestionsLanguageService.toggleLanguage("java")

      verify {
        preferenceStore.putValue(
          PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES,
          "python,javascript"
        )
      }
    }

    it("should add language to additional list when enabling an unsupported language") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES)
      } returns "zig,gleam"

      codeSuggestionsLanguageService.toggleLanguage("ElM")

      verify {
        preferenceStore.putValue(
          PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES,
          "zig,gleam,elm"
        )
      }
    }

    it("should remove language from additional list when disabling an unsupported language") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES)
      } returns "zig,gleam,ELM"

      codeSuggestionsLanguageService.toggleLanguage("elm")

      verify {
        preferenceStore.putValue(
          PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES,
          "zig,gleam"
        )
      }
    }
  }
})
