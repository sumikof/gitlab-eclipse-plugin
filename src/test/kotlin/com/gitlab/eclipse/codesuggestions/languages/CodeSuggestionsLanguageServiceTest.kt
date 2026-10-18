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

  beforeEach {
    every { preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES) } returns ""
    every { preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES) } returns ""
  }

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

  describe("normalizing stored languages") {
    it("drops leading dots and duplicates from additional languages, as the language server does") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES)
      } returns " .ZIG,zig, Gleam,.gleam"

      codeSuggestionsLanguageService.getAdditionalLanguages() shouldBe listOf("zig", "gleam")
    }

    it("drops leading dots and duplicates from disabled languages") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES)
      } returns "python,Python, .rust"

      codeSuggestionsLanguageService.getDisabledLanguages() shouldBe listOf("python", "rust")
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

    it("should normalize the language id it is asked about") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES)
      } returns "rust"

      codeSuggestionsLanguageService.isEnabled(" Python ") shouldBe true
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

    // Their id never matches one of their extensions, which is how the toggle used to tell supported languages apart
    listOf("python", "kotlin", "typescript", "javascript", "ruby", "rust", "csharp").forEach { languageId ->
      it("should add $languageId to disabled list when disabling it") {
        codeSuggestionsLanguageService.toggleLanguage(languageId)

        verify {
          preferenceStore.putValue(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES, languageId)
        }
        verify(exactly = 0) {
          preferenceStore.putValue(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES, any())
        }
      }
    }

    it("should normalize the language id before toggling it") {
      codeSuggestionsLanguageService.toggleLanguage(" .Python ")

      verify {
        preferenceStore.putValue(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES, "python")
      }
    }

    it("should re-enable a disabled language without leaving a duplicate behind") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES)
      } returns "Python,rust,python"

      codeSuggestionsLanguageService.toggleLanguage("python")

      verify {
        preferenceStore.putValue(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES, "rust")
      }
    }

    it("should not add a duplicate when disabling a language that is stored with a leading dot") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES)
      } returns ".zig,gleam"

      codeSuggestionsLanguageService.toggleLanguage("zig")

      verify {
        preferenceStore.putValue(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES, "gleam")
      }
    }

    it("should drop a supported language from additional list where the old toggle used to put it") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES)
      } returns "zig,Python"

      codeSuggestionsLanguageService.toggleLanguage("python")

      verify {
        preferenceStore.putValue(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES, "python")
        preferenceStore.putValue(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES, "zig")
      }
    }

    it("should leave additional list untouched once it no longer holds the supported language") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES)
      } returns "zig"

      codeSuggestionsLanguageService.toggleLanguage("python")

      verify(exactly = 0) {
        preferenceStore.putValue(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES, any())
      }
    }

    it("should do nothing for a blank language id") {
      codeSuggestionsLanguageService.toggleLanguage(" . ")

      verify(exactly = 0) {
        preferenceStore.putValue(any(), any())
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
