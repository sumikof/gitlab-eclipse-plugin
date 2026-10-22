package com.gitlab.eclipse

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * AC-G1 (design §10): the Eclipse log of the files this cycle touched never receives an LS message,
 * a payload, a request result, an exception message, a path, or a throwable (whose stack trace
 * carries the message). A static scan of every `logger.info/warn/error/debug(...)` and
 * `logger.debug { ... }` call in those files; the exception's class name stays allowed.
 *
 * `languageServerLogger` (the LS's own stderr, written to `language_server.log`) is out of scope by
 * design and does not match the `logger` receiver pattern.
 */
class LogHygieneAuditTest : DescribeSpec({
  describe("log hygiene of the files touched by LS-02 / LS-08 / CS-08 / QCHAT-07") {
    it("scans files that exist and contain log calls") {
      val missing = AUDITED_FILES.filterNot { File(it).isFile }
      missing.shouldBeEmpty()
      AUDITED_FILES.flatMap { logCalls(File(it).readText()) }.shouldNotBeEmpty()
    }

    it("no log call carries a message, payload, result, path or throwable") {
      val violations = AUDITED_FILES.flatMap { path ->
        logCalls(File(path).readText()).filter(::isForbidden).map { "$path: $it" }
      }

      violations.shouldBeEmpty()
    }

    // CI-05/CI-06 §4.7: the current-branch fetcher handles refs, tag names and pipeline URLs, so it logs ONLY
    // through its injected errorLog/warnLog lambdas, whose stage + class-name messages its own tests pin. The
    // generic scan above does not know `$ref`/`$tag`, so any direct logger call there is rejected outright.
    it("the current-branch fetcher never calls a logger directly") {
      val fetcher = File("src/main/kotlin/com/gitlab/eclipse/views/sidebar/CurrentBranchSectionFetcher.kt").readText()
      logCalls(fetcher).shouldBeEmpty()
      Regex("""\blogger\b""").containsMatchIn(fetcher) shouldBe false
    }

    // CI-06 §4.7 (Task 5): the downstream loader logs ONLY through its injected warnLog, whose one
    // message format (exception class + HTTP status) DownstreamLoaderTest pins; the transfer never logs.
    it("the downstream loader and transfer never call a logger directly") {
      listOf("DownstreamLoader.kt", "DownstreamTransfer.kt").forEach { name ->
        val source = File("src/main/kotlin/com/gitlab/eclipse/views/sidebar/$name").readText()
        logCalls(source).shouldBeEmpty()
        Regex("""\blogger\b""").containsMatchIn(source) shouldBe false
      }
    }

    describe("the detector itself") {
      listOf(
        "logger.info(\"showMessage: \$message\")",
        "logger.warn(\"request: \${params.message}\")",
        "logger.info(\"Initialized: \$result\")",
        "logger.error(\"Failed: \${e.message}\")",
        "logger.error(\"Failed to start\", e)",
        "logger<Foo>().warn(\"Failed\", err)",
        "logger.info(\"Logs saved to: \${logFile.absolutePath}.\")",
        "logger.debug { \"params=\$params\" }",
        "logger.warn(\n  \"multi line \${cause.message}\",\n)",
        "logger.info(\"type ok \${message.type} but text \${message.message}\")",
        "logger.error(\n  \"Failed\",\n  e,\n)",
        "logger.warn(\"Failed: \$e\")",
        "logger.warn(\"Failed: \${e}\")",
        "logger.warn(\"Failed: \$e (retrying)\")",
        "logger.warn(\"Failed\", exception)",
        "logger.warn(\"Failed: \${e.localizedMessage}\")",
        "logger.info(\"telemetryEvent: \$event\")",
        "logger.info(\"telemetryEvent: \${event.payload}\")",
        "logger.warn(\"Failed: \" + e.message)",
        "logger.warn(\"Failed: \${e.message ?: \"?\"}\")",
      ).forEach { call ->
        it("flags: $call") {
          logCalls(call).filter(::isForbidden).size shouldBe 1
        }
      }

      listOf(
        "logger.warn(\"Failed (\${e.javaClass.simpleName}).\")",
        "logger.info(\"Exit code \$exitCode.\")",
        "logger.debug { \"showMessage received (type=\${typeName(type)})\" }",
        "languageServerLogger.info(stderrLine)",
        "logger.debug { \"logMessage received (type=\${message.type?.name})\" }",
        "logger.debug { \"experiment features: enabled=\${params?.enabled}\" }",
        "logger.warn(\"Skipped (\${e::class.simpleName}).\")",
        "logger.warn(\"Exited (exit code \$exitCode).\")",
        "logger.debug { \"telemetryEvent received\" }",
      ).forEach { call ->
        it("allows: $call") {
          logCalls(call).filter(::isForbidden).shouldBeEmpty()
        }
      }
    }
  }
})

/** Production files changed on this branch (`git diff --name-only fba65a1f -- src/main`, Kotlin only). */
private val AUDITED_FILES = listOf(
  "codesuggestions/CodeSuggestionsModule.kt",
  "codesuggestions/handlers/CodeSuggestionsStatusHandler.kt",
  "codesuggestions/handlers/ToggleCodeSuggestionForLanguageHandler.kt",
  "codesuggestions/handlers/ToggleCodeSuggestionsHandler.kt",
  "codesuggestions/status/CodeSuggestionsStateService.kt",
  "codesuggestions/status/CodeSuggestionsWarningNotifier.kt",
  "codesuggestions/status/CodeSuggestionsWarningPolicy.kt",
  "codesuggestions/status/DuoStatusInputsProvider.kt",
  "codesuggestions/status/DuoStatusRefresher.kt",
  "codesuggestions/status/DuoStatusResolver.kt",
  "handlers/OwnerScopedCache.kt",
  "handlers/ShowPluginStatusMenu.kt",
  "lsp/FeatureStateChange.kt",
  "lsp/GitLabLanguageServerClient.kt",
  "lsp/GitLabLanguageServerProcessProvider.kt",
  "lsp/LanguageServerInstaller.kt",
  "lsp/LanguageServerModule.kt",
  "lsp/lifecycle/LanguageServerFailureNotifier.kt",
  "lsp/lifecycle/LanguageServerLifecycleState.kt",
  "lsp/lifecycle/LanguageServerLifecycleStore.kt",
  "lsp/messages/LanguageServerMessageDialog.kt",
  "lsp/messages/LanguageServerMessagePresenter.kt",
  "lsp/messages/ShowMessageDeduplicator.kt",
  "mergerequests/HeadTagReader.kt",
  "preferences/PreferenceConstants.kt",
  "preferences/PreferenceInitializer.kt",
  "utils/ActionNotificationPopup.kt",
  "views/sidebar/CurrentBranchSectionFetcher.kt",
  "views/sidebar/DownstreamLoader.kt",
  "views/sidebar/DownstreamTransfer.kt",
  "GitLabEclipseStartup.kt",
).map { "src/main/kotlin/com/gitlab/eclipse/$it" }

/** `logger.x(` / `logger.x {` / `logger<T>().x(` — not `languageServerLogger`, whose output is the LS's own file. */
private val LOG_CALL = Regex("""(?<![A-Za-z0-9_])logger(?:<[^>]*>\(\))?\s*\.\s*(?:info|warn|error|debug)\s*([({])""")

private val FORBIDDEN_TEXT = listOf(
  "\$message",
  "\${message",
  "\$params",
  "\${params",
  "\$result",
  ".message}",
  "e.message",
  "localizedMessage",
  "absolutePath",
)

/** Names a caught throwable usually has. */
private const val THROWABLE_NAMES = "e|err|ex|exception|t|it|cause|throwable|failure"

/**
 * A throwable handed to the log as the last argument (its stack trace prints the message), also as
 * the house-style multi-line call with a trailing comma.
 */
private val THROWABLE_ARGUMENT = Regex(""",\s*(?:$THROWABLE_NAMES)\s*,?\s*\)\s*$""")

/**
 * A throwable or an LS event interpolated whole — `$e`, `${e}`, `$event`, `${event.payload}` — whose
 * text is its message or payload. `${e.javaClass…}` / `${e::class…}` stay allowed; `$exitCode` is
 * another identifier. `failure` is excluded here: in these files it names a class-name string.
 */
private val INTERPOLATED = Regex(
  listOf(
    """\$(?:e|err|ex|exception|throwable|cause|event)\b""",
    """\$\{\s*(?:e|err|ex|exception|throwable|cause)\s*}""",
    """\$\{\s*event\b""",
  ).joinToString("|"),
)

/**
 * Fields of a message or params object that are not its text: a message type, an on/off flag. Removed
 * before the scan, so `${message.type}` passes while `$message` and `${params.message}` do not.
 */
private val ALLOWED_FIELDS = Regex("""\$\{(?:message|params|result)\??\.(?:type|enabled)\b""")

private fun isForbidden(call: String): Boolean {
  val scanned = ALLOWED_FIELDS.replace(call, "")
  return FORBIDDEN_TEXT.any { it in scanned } ||
    THROWABLE_ARGUMENT.containsMatchIn(scanned) ||
    INTERPOLATED.containsMatchIn(scanned)
}

/** Every log call in [source], from the receiver to its balanced closing bracket. */
private fun logCalls(source: String): List<String> =
  LOG_CALL.findAll(source).map { match ->
    val open = match.groups[1]!!.range.first
    val close = if (source[open] == '(') ')' else '}'
    var depth = 0
    var end = source.length - 1
    for (i in open until source.length) {
      when (source[i]) {
        source[open] -> depth++
        close -> if (--depth == 0) {
          end = i
          break
        }
      }
    }
    source.substring(match.range.first, end + 1)
  }.toList()
