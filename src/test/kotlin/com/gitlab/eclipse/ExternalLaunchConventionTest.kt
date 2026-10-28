package com.gitlab.eclipse

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * #142 D8a / AC1: `BrowserLauncher.kt` is the one place this plugin hands a link to the external browser. A static scan
 * of every production `.kt` file for the other ways to launch something outside the IDE — the workbench browser
 * support, SWT `Program`, AWT `Desktop` — so a new direct call fails the regression run instead of slipping past the
 * gate's scheme and userinfo policy.
 *
 * Comments are ignored (a KDoc may name `openURL`); string contents are not (a `"xdg-open"` literal is a launch).
 * The ALLOWED table below is also checked from the other side: every pattern it allows must still occur in its file,
 * so the table cannot go stale. Each entry is a count: a second occurrence of an allowed pattern is a violation too.
 */
class ExternalLaunchConventionTest : DescribeSpec({
  describe("external launch convention") {
    it("scans the production sources and the allowed files exist") {
      productionFiles().shouldNotBeEmpty()
      ALLOWED.keys.filterNot { File("$SOURCE_ROOT/$it").isFile }.shouldBeEmpty()
    }

    it("opens nothing outside BrowserLauncher except the allowed mailto launch") {
      val violations = productionFiles().flatMap { (relative, source) ->
        findLaunchPatterns(source)
          .groupBy { (_, label) -> label }
          .flatMap { (label, found) ->
            found.drop(ALLOWED[relative]?.get(label) ?: 0).map { (line, _) -> "$relative:$line: $label" }
          }
      }

      violations.shouldBeEmpty()
    }

    it("every allowed pattern still occurs in its file") {
      val sources = productionFiles().toMap()
      val stale = ALLOWED.flatMap { (relative, labels) ->
        val present = findLaunchPatterns(sources[relative].orEmpty()).groupingBy { it.second }.eachCount()
        labels.filter { (label, allowed) -> (present[label] ?: 0) < allowed }
          .map { (label, allowed) -> "$relative: $label (allowed $allowed, found ${present[label] ?: 0})" }
      }

      stale.shouldBeEmpty()
    }

    describe("the detector itself") {
      listOf(
        "PlatformUI.getWorkbench().browserSupport.externalBrowser.openURL(u)",
        "Program.launch(x)",
        "Desktop.getDesktop().browse(u)",
        "import java.awt.Desktop",
        "Program.findProgram(\"html\")",
        "Program.getPrograms()",
        "org.eclipse.swt.program.Program.launch(x)",
        "import org.eclipse.swt.program.Program as P",
        "import org.eclipse.swt.program.*",
        "val b: org.eclipse.ui.browser.IWebBrowser",
        "val s = \"https://x\"; Program.launch(s)",
        "val s = \"a // b\"; Program.launch(s)",
        "val s = \"\"\"https://x\"\"\"; Program.launch(s)",
        "val s = \"\${\"https://x\"}\"; Program.launch(s)",
        "val c = '\"'; Program.launch(s)",
        "/* note */ Program.launch(x)",
        "ProcessBuilder(\"xdg-open\", url)",
      ).forEach { code ->
        it("flags: $code") {
          findLaunchPatterns(code).shouldNotBeEmpty()
        }
      }

      listOf(
        "browser.open(url)",
        "browser.openChecked(url)",
        "/** Opens it through the gate instead of `openURL`. */ val x = 1",
        "// Program.launch(x)",
        "val x = 1 // externalBrowser.openURL(u)",
        "/* Desktop.getDesktop().browse(u)\n   still a comment */ val x = 1",
        "/* outer /* nested */ Program.launch(x) */ val x = 1",
        "ProcessBuilder(path, \"--stdio\")",
        "val c = '\"'; val d = \"x\"",
        "val c = '\\''; val d = 1",
      ).forEach { code ->
        it("allows: $code") {
          findLaunchPatterns(code).shouldBeEmpty()
        }
      }

      it("counts each occurrence, and an import or a qualified use only once") {
        findLaunchPatterns("Program.launch(a); Program.launch(b)").size shouldBe 2
        findLaunchPatterns("import org.eclipse.swt.program.Program").map { it.second } shouldBe
          listOf("import org.eclipse.swt.program.")
        findLaunchPatterns("org.eclipse.swt.program.Program.launch(x)").map { it.second } shouldBe
          listOf("Program.launch(", "org.eclipse.swt.program. (qualified)")
      }

      it("reports the line number and the pattern") {
        findLaunchPatterns("val a = 1\n/* c\n*/\nval b = Program.launch(x)\n") shouldBe
          listOf(4 to "Program.launch(")
      }
    }
  }
})

private const val SOURCE_ROOT = "src/main/kotlin/com/gitlab/eclipse"

/**
 * The only direct launches. `BrowserLauncher` is the gate itself. `OpenUrlHandler` opens a `mailto:` link in the mail
 * client: `Program.launch` is how SWT hands a mailto to the OS, the browser gate refuses non-http(s) on purpose, and
 * `VulnerabilityLinkPolicy` has already admitted only a non-empty `mailto:` before it gets there (design §3.5). Each
 * value is how many times the file may contain that pattern, exactly.
 */
private val ALLOWED: Map<String, Map<String, Int>> = mapOf(
  "navigation/BrowserLauncher.kt" to mapOf("browserSupport" to 1, "externalBrowser" to 1, ".openURL(" to 1),
  "lsp/OpenUrlHandler.kt" to mapOf("import org.eclipse.swt.program." to 1, "Program.launch(" to 1),
)

/** Label (what the failure message prints and ALLOWED names) to the regex that finds it in comment-free code. */
private val FORBIDDEN: List<Pair<String, Regex>> = listOf(
  ".openURL(" to Regex("""\.openURL\("""),
  "externalBrowser" to Regex("""externalBrowser"""),
  "getExternalBrowser(" to Regex("""getExternalBrowser\("""),
  "browserSupport" to Regex("""browserSupport"""),
  "getBrowserSupport(" to Regex("""getBrowserSupport\("""),
  "IWorkbenchBrowserSupport" to Regex("""IWorkbenchBrowserSupport"""),
  "IWebBrowser" to Regex("""IWebBrowser"""),
  "Program.launch(" to Regex("""\bProgram\.launch\("""),
  "Program.findProgram(" to Regex("""\bProgram\.findProgram\("""),
  "Program.getPrograms(" to Regex("""\bProgram\.getPrograms\("""),
  "Desktop.getDesktop(" to Regex("""\bDesktop\.getDesktop\("""),
  ".browse(" to Regex("""\.browse\("""),
  "\"xdg-open\"" to Regex(""""xdg-open""""),
  "\"rundll32\"" to Regex(""""rundll32""""),
  // An import line and a fully qualified use are separate labels that never match the same line.
  "import org.eclipse.swt.program." to Regex("""import\s+org\.eclipse\.swt\.program\."""),
  "org.eclipse.swt.program. (qualified)" to Regex("""^(?!\s*import\b).*org\.eclipse\.swt\.program\."""),
  "import java.awt.Desktop" to Regex("""import\s+java\.awt\.Desktop"""),
  "java.awt.Desktop (qualified)" to Regex("""^(?!\s*import\b).*java\.awt\.Desktop"""),
  "org.eclipse.ui.browser." to Regex("""org\.eclipse\.ui\.browser\."""),
)

private fun productionFiles(): List<Pair<String, String>> =
  File(SOURCE_ROOT).walkTopDown()
    .filter { it.isFile && it.extension == "kt" }
    .map { it.toRelativeString(File(SOURCE_ROOT)).replace(File.separatorChar, '/') to it.readText() }
    .toList()

/** `(line, label)` for every forbidden pattern in [source], comments ignored; one entry per match. */
private fun findLaunchPatterns(source: String): List<Pair<Int, String>> =
  stripComments(source).lines().flatMapIndexed { index, line ->
    FORBIDDEN.flatMap { (label, regex) -> regex.findAll(line).map { index + 1 to label }.toList() }
  }

/**
 * [source] with every KDoc, block comment (nesting included) and `//` comment blanked out and every newline kept, so
 * line numbers survive. String and char literals are walked, not searched: a `//` inside `"https://x"` is not a
 * comment, so a call later on that line is still seen, and a `"` inside `'"'` does not open a string. A `${...}`
 * template inside a string is code again, nesting strings of its own.
 */
private fun stripComments(source: String): String = CommentStripper(source).run()

private class CommentStripper(private val source: String) {
  private val out = StringBuilder()
  private val templateDepths = ArrayDeque<Int>()
  private val templateRaw = ArrayDeque<Boolean>()
  private var inString = false
  private var raw = false
  private var i = 0

  fun run(): String {
    while (i < source.length) {
      if (inString) stepInString() else stepInCode()
    }
    return out.toString()
  }

  private fun startsWith(text: String) = source.startsWith(text, i)

  private fun take(count: Int) {
    val end = minOf(i + count, source.length)
    out.append(source, i, end)
    i = end
  }

  private fun stepInString() {
    val c = source[i]
    when {
      raw && startsWith("\"\"\"") -> {
        take(3)
        inString = false
      }
      !raw && c == '\\' -> take(2)
      !raw && (c == '"' || c == '\n') -> {
        take(1)
        inString = false
      }
      startsWith("\${") -> {
        take(2)
        templateDepths.addLast(0)
        templateRaw.addLast(raw)
        inString = false
      }
      else -> take(1)
    }
  }

  @Suppress("CyclomaticComplexMethod")
  private fun stepInCode() {
    val c = source[i]
    when {
      templateDepths.isNotEmpty() && c == '}' && templateDepths.last() == 0 -> {
        take(1)
        templateDepths.removeLast()
        raw = templateRaw.removeLast()
        inString = true
      }
      templateDepths.isNotEmpty() && (c == '{' || c == '}') -> {
        templateDepths[templateDepths.lastIndex] += if (c == '{') 1 else -1
        take(1)
      }
      startsWith("//") -> while (i < source.length && source[i] != '\n') i++
      startsWith("/*") -> skipBlockComment()
      startsWith("\"\"\"") -> {
        take(3)
        inString = true
        raw = true
      }
      c == '"' -> {
        take(1)
        inString = true
        raw = false
      }
      c == '\'' -> takeCharLiteral()
      else -> take(1)
    }
  }

  /** Skips a block comment, nested ones too, keeping its newlines. */
  private fun skipBlockComment() {
    var depth = 0
    do {
      when {
        startsWith("/*") -> {
          depth++
          i += 2
        }
        startsWith("*/") -> {
          depth--
          i += 2
        }
        else -> {
          if (source[i] == '\n') out.append('\n')
          i++
        }
      }
    } while (depth > 0 && i < source.length)
  }

  /** Copies a char literal whole, so `'"'` and `'\\''` do not open a string. A lone `'` is copied as itself. */
  private fun takeCharLiteral() {
    val closing = if (source.getOrNull(i + 1) == '\\') source.indexOf('\'', i + 3) else i + 2
    val closed = closing in (i + 2) until source.length && source[closing] == '\''
    take(if (closed) closing + 1 - i else 1)
  }
}
