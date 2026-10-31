package com.gitlab.eclipse

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * #144 D-4 / R-2 (TP §2.1): every way a production file can lead to the external browser, counted per file and label.
 * A new route, an extra occurrence or a stale row fails. Known blind spots (TP §2.1.7) are reviewed by hand.
 */
class BrowserRouteConventionTest : DescribeSpec({
  val xml: Element = checkNotNull(javaClass.classLoader.getResourceAsStream("plugin.xml")).use {
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
  }
  val sources = productionSources()
  val derivation = deriveBrowserCommands(handlersOf(xml), sources)
  val actual = sources.mapValues { (_, code) -> countRouteIdentifiers(code, derivation.browserCommands) }
    .filterValues { it.isNotEmpty() }

  describe("the route table") {
    it("has no unlisted route and no extra occurrence") {
      routeDiff(ROUTES, actual).unlisted.shouldBeEmpty()
    }

    it("has no stale row") {
      routeDiff(ROUTES, actual).stale.shouldBeEmpty()
    }

    it("derives exactly the expected browser commands, without errors, defaultHandler or child-element classes") {
      derivation.errors.shouldBeEmpty()
      defaultHandlerSites(xml).shouldBeEmpty()
      derivation.browserCommands shouldBe EXPECTED_BROWSER_COMMANDS
    }

    it("has no aliased import, typealias, nested-type import or id-prefix literal (TP §2.1.2)") {
      sources.filterValues { ROUTE_EVASION.containsMatchIn(it) }.keys.shouldBeEmpty()
    }
  }

  describe("the detector itself (TP §2.1.8)") {
    listOf(
      "import com.gitlab.eclipse.navigation.BrowserLauncher as BL",
      "import com.gitlab.eclipse.lsp.ShowDocumentLauncher as SDL",
      "import com.gitlab.eclipse.currentbranch.TrimAction as TA",
      "import com.gitlab.eclipse.views.sidebar.SidebarDoubleClick as SD",
      "typealias BL = BrowserLauncher",
      "typealias BL = com.gitlab.eclipse.navigation.BrowserLauncher",
      "typealias TA = com.gitlab.eclipse.currentbranch.TrimAction",
      "typealias Open = SidebarDoubleClick.OpenUrl",
      "import com.gitlab.eclipse.currentbranch.TrimAction.OpenUrl",
      "import com.gitlab.eclipse.views.sidebar.SidebarDoubleClick.*",
      "val id = \"com.gitlab.eclipse.commands.\" + name",
      "val id = \"gitlab-eclipse-plugin.commands\"",
      "val id = \"com.gitlab.eclipse.commands.\$name\"",
      "val id = \"gitlab-eclipse-plugin.commands.\${name}\"",
    ).forEach { code ->
      it("flags the evasion: $code") {
        ROUTE_EVASION.containsMatchIn(code) shouldBe true
      }
    }

    listOf(
      "import com.gitlab.eclipse.navigation.BrowserLauncher",
      "import com.gitlab.eclipse.currentbranch.TrimAction",
      "val a = TrimAction.OpenUrl(u)",
      "typealias Other = com.gitlab.eclipse.navigation.MyBrowserLauncherFactory",
      "import com.gitlab.eclipse.currentbranch.MyTrimAction as X",
      "val id = \"com.gitlab.eclipse.commands.OpenCreateNewMR\"",
      "val id = \"com.gitlab.eclipse.commands.SelectChatWebview.\$name\"",
    ).forEach { code ->
      it("allows: $code") {
        ROUTE_EVASION.containsMatchIn(code) shouldBe false
      }
    }

    it("reports an unlisted file, an extra occurrence and a stale row, and nothing when they agree") {
      val table = mapOf("a.kt" to mapOf("BrowserLauncher" to 2), "gone.kt" to mapOf("ShowDocumentLauncher" to 1))
      val diff = routeDiff(table, mapOf("a.kt" to mapOf("BrowserLauncher" to 1), "new.kt" to mapOf("cmd:x" to 1)))
      diff.unlisted shouldBe listOf("new.kt: cmd:x x1")
      diff.stale shouldBe listOf("a.kt: BrowserLauncher (table 2)", "gone.kt: ShowDocumentLauncher (table 1)")

      routeDiff(table, mapOf("a.kt" to mapOf("BrowserLauncher" to 3))).unlisted shouldBe
        listOf("a.kt: BrowserLauncher x3")
      routeDiff(table, table).unlisted.shouldBeEmpty()
      routeDiff(table, table).stale.shouldBeEmpty()
    }

    it("counts code, not comments; whole identifiers; spaced qualifiers; command literals") {
      val code = stripComments("// BrowserLauncher\nval b = BrowserLauncher(); val t = BrowserLauncherTest()")
      countRouteIdentifiers(code, emptySet()) shouldBe mapOf("BrowserLauncher" to 1)
      countRouteIdentifiers("TrimAction . OpenUrl(u); TrimAction.OpenUrl(v)", emptySet()) shouldBe
        mapOf("TrimAction.OpenUrl" to 2)
      countRouteIdentifiers("run(\"a.b.X\")", setOf("a.b.X")) shouldBe mapOf("cmd:a.b.X" to 1)
    }

    it("reaches a fixed point over two hops and seeds from ShowDocumentLauncher too") {
      val src = mapOf(
        "p/A.kt" to "class A { val b = BrowserLauncher() }",
        "p/B.kt" to "class B { fun go() = exec(\"cmd.a\") }",
        "p/C.kt" to "fun c() = exec(\"cmd.b\")",
        "p/D.kt" to "class D { val s = ShowDocumentLauncher() }",
      )
      val d = deriveBrowserCommands(
        listOf(
          HandlerDecl("cmd.a", "com.gitlab.eclipse.p.A"),
          HandlerDecl("cmd.b", "com.gitlab.eclipse.p.B"),
          HandlerDecl("cmd.d", "com.gitlab.eclipse.p.D"),
        ),
        src,
      )
      d.browserCommands shouldBe setOf("cmd.a", "cmd.b", "cmd.d")
      d.routeFiles shouldContainAll setOf("p/A.kt", "p/B.kt", "p/C.kt", "p/D.kt")
    }

    it("reports a handler without class, a missing file, a missing declaration, a nested or foreign class") {
      val d = deriveBrowserCommands(
        listOf(
          HandlerDecl("x1", null),
          HandlerDecl("x2", "com.gitlab.eclipse.p.Missing"),
          HandlerDecl("x3", "com.gitlab.eclipse.p.A"),
          HandlerDecl("x4", "com.gitlab.eclipse.p.A\$Inner"),
          HandlerDecl("x5", "org.example.Foreign"),
        ),
        mapOf("p/A.kt" to "val nothing = 1"),
      )
      d.errors.size shouldBe 5
    }
  }
})

private val LABELS = linkedMapOf(
  "BrowserLauncher" to Regex("""\bBrowserLauncher\b"""),
  "ShowDocumentLauncher" to Regex("""\bShowDocumentLauncher\b"""),
  "TrimAction.OpenUrl" to Regex("""\bTrimAction\s*\.\s*OpenUrl\b"""),
  "SidebarDoubleClick.OpenUrl" to Regex("""\bSidebarDoubleClick\s*\.\s*OpenUrl\b"""),
)

/**
 * TP §2.1.2: the spellings that would let a route slip past [LABELS] — an alias or typealias of any of the four types
 * (`TA.OpenUrl(u)` after `import …TrimAction as TA` matches no label), an import of the nested `OpenUrl`, and a
 * literal that is only a command-id prefix, closed (`"…commands."` + name) or continued by a template
 * (`"…commands.$x"`).
 */
internal val ROUTE_EVASION = Regex(
  """import\s+\S*\b(BrowserLauncher|ShowDocumentLauncher|TrimAction|SidebarDoubleClick)\s+as\b|""" +
    """typealias\s+\w+\s*=\s*[\w.]*\b(BrowserLauncher|ShowDocumentLauncher|TrimAction|SidebarDoubleClick)\b|""" +
    """import\s+\S*\b(TrimAction|SidebarDoubleClick)\.(OpenUrl|\*)|""" +
    """"(com\.gitlab\.eclipse|gitlab-eclipse-plugin)\.commands\.?(\$|")""",
)

/** [unlisted]: occurrences the table does not allow (`file: label xN`); [stale]: rows above the actual count. */
internal data class RouteDiff(val unlisted: List<String>, val stale: List<String>)

internal fun routeDiff(table: Map<String, Map<String, Int>>, actual: Map<String, Map<String, Int>>) = RouteDiff(
  unlisted = actual.flatMap { (file, labels) ->
    labels.filter { (label, n) -> n > (table[file]?.get(label) ?: 0) }.map { (label, n) -> "$file: $label x$n" }
  },
  stale = table.flatMap { (file, labels) ->
    labels.filter { (label, n) -> (actual[file]?.get(label) ?: 0) < n }.map { (label, n) -> "$file: $label (table $n)" }
  },
)

/** TP §2.1.3: the browser commands derived at `54593ea`. U6 removes `OpenCurrentMergeRequest` (17 → 16). */
private val EXPECTED_BROWSER_COMMANDS: Set<String> = setOf(
  "gitlab-eclipse-plugin.commands.showDocumentation", "gitlab-eclipse-plugin.commands.OpenActiveFile",
  "gitlab-eclipse-plugin.commands.OpenInGitLab", "gitlab-eclipse-plugin.commands.OpenCreateNewIssue",
  "gitlab-eclipse-plugin.commands.IssueSearch", "gitlab-eclipse-plugin.commands.MergeRequestSearch",
  "gitlab-eclipse-plugin.commands.AdvancedSearch", "gitlab-eclipse-plugin.commands.ShowDuoForum",
  "gitlab-eclipse-plugin.commands.ShowDuoDocumentation", "com.gitlab.eclipse.commands.ShowMergeRequestsAssignedToMe",
  "com.gitlab.eclipse.commands.OpenCurrentMergeRequest", "com.gitlab.eclipse.commands.CompareCurrentBranch",
  "com.gitlab.eclipse.commands.OpenCreateNewMR", "com.gitlab.eclipse.commands.DownloadArtifacts",
  "com.gitlab.eclipse.commands.CreateSnippet", "com.gitlab.eclipse.commands.CreateSnippetPatch",
  "com.gitlab.eclipse.commands.OpenSidebarItemInGitLab",
)

/**
 * TP §2.1.4: every browser route at `54593ea`, one row per file, each checked by hand against its source (why is there
 * a route here). Later tasks change only the rows their own change explains (U3 +MrOverviewEditor, U6 the Q6 rows).
 */
private val ROUTES: Map<String, Map<String, Int>> = mapOf(
  "authentication/GitLabOAuthService.kt" to mapOf("BrowserLauncher" to 3),
  "chat/ChatModule.kt" to mapOf("ShowDocumentLauncher" to 2),
  "chat/webview/ChatWebViewMessageHandlers.kt" to mapOf("BrowserLauncher" to 3),
  "ci/actions/DownloadArtifactsHandler.kt" to mapOf("BrowserLauncher" to 3),
  "codesuggestions/CodeSuggestionsModule.kt" to mapOf("ShowDocumentLauncher" to 2),
  "currentbranch/CurrentBranchModule.kt" to mapOf("BrowserLauncher" to 2),
  "currentbranch/CurrentBranchStatusTrim.kt" to
    mapOf("BrowserLauncher" to 2, "cmd:com.gitlab.eclipse.commands.OpenCreateNewMR" to 1),
  "currentbranch/StatusTrimController.kt" to mapOf("TrimAction.OpenUrl" to 1),
  "currentbranch/StatusTrimPresenter.kt" to mapOf("TrimAction.OpenUrl" to 2),
  "handlers/AdvancedSearchHandler.kt" to mapOf("BrowserLauncher" to 3),
  "handlers/IssueSearchHandler.kt" to mapOf("BrowserLauncher" to 3),
  "handlers/MergeRequestSearchHandler.kt" to mapOf("BrowserLauncher" to 3),
  "handlers/OpenActiveFileHandler.kt" to mapOf("BrowserLauncher" to 3),
  "handlers/OpenCreateNewIssueHandler.kt" to mapOf("BrowserLauncher" to 3),
  "handlers/OpenInGitLabHandler.kt" to mapOf("BrowserLauncher" to 3),
  "handlers/ShowDocumentation.kt" to mapOf("BrowserLauncher" to 3),
  "handlers/ShowDuoDocumentation.kt" to mapOf("ShowDocumentLauncher" to 3),
  "handlers/ShowDuoForum.kt" to mapOf("ShowDocumentLauncher" to 3),
  "lsp/GitLabLanguageServerClient.kt" to mapOf("ShowDocumentLauncher" to 1),
  "lsp/LanguageServerModule.kt" to mapOf("ShowDocumentLauncher" to 2),
  "lsp/OpenUrlHandler.kt" to mapOf("ShowDocumentLauncher" to 1),
  "lsp/ShowDocumentLauncher.kt" to mapOf("BrowserLauncher" to 3, "ShowDocumentLauncher" to 1),
  "mergerequests/CompareCurrentBranchHandler.kt" to mapOf("BrowserLauncher" to 3),
  "mergerequests/OpenCreateNewMrHandler.kt" to mapOf("BrowserLauncher" to 3),
  "mergerequests/OpenCurrentMergeRequestHandler.kt" to mapOf("BrowserLauncher" to 3),
  "mergerequests/ShowMergeRequestsAssignedToMeHandler.kt" to mapOf("BrowserLauncher" to 3),
  "navigation/BrowserLauncher.kt" to mapOf("BrowserLauncher" to 5),
  "preferences/GitLabPreferencePage.kt" to mapOf("BrowserLauncher" to 2),
  "snippets/handlers/CreateSnippetHandler.kt" to mapOf("BrowserLauncher" to 2),
  "snippets/handlers/CreateSnippetPatchHandler.kt" to mapOf("BrowserLauncher" to 2),
  "views/sidebar/GitLabSidebarView.kt" to mapOf("BrowserLauncher" to 2, "SidebarDoubleClick.OpenUrl" to 2),
  "views/sidebar/OpenSidebarItemInGitLabHandler.kt" to mapOf("BrowserLauncher" to 3),
  "views/webview/WebviewBrowserHost.kt" to mapOf("ShowDocumentLauncher" to 2),
)

internal fun countRouteIdentifiers(code: String, commandIds: Set<String>): Map<String, Int> {
  val counts = LABELS.mapValues { (_, regex) -> regex.findAll(code).count() }.toMutableMap()
  commandIds.forEach { id -> counts["cmd:$id"] = Regex(Regex.escape("\"$id\"")).findAll(code).count() }
  return counts.filterValues { it > 0 }
}

internal data class HandlerDecl(val commandId: String, val className: String?)

internal fun handlersOf(pluginXml: Element): List<HandlerDecl> {
  val nodes = pluginXml.getElementsByTagName("handler")
  return (0 until nodes.length).map { nodes.item(it) as Element }.map { h ->
    HandlerDecl(h.getAttribute("commandId"), h.getAttribute("class").takeIf { it.isNotBlank() })
  }
}

internal fun defaultHandlerSites(pluginXml: Element): List<String> {
  val all = pluginXml.getElementsByTagName("*")
  return (0 until all.length).map { all.item(it) as Element }
    .filter { it.tagName == "defaultHandler" || it.hasAttribute("defaultHandler") }
    .map { it.getAttribute("id").ifBlank { it.tagName } }
}

internal fun fileOfHandler(className: String, sources: Map<String, String>): String? {
  if (!className.startsWith("com.gitlab.eclipse.") || '$' in className) return null
  val relative = className.removePrefix("com.gitlab.eclipse.").replace('.', '/') + ".kt"
  val simple = className.substringAfterLast('.')
  val code = sources[relative] ?: return null
  return relative.takeIf { Regex("""\b(class|object)\s+$simple\b""").containsMatchIn(code) }
}

internal data class Derivation(val browserCommands: Set<String>, val routeFiles: Set<String>, val errors: List<String>)

internal fun deriveBrowserCommands(handlers: List<HandlerDecl>, sources: Map<String, String>): Derivation {
  val errors = mutableListOf<String>()
  // A list, not a map: two handlers of one command (different activeWhen) must both be looked at.
  val handlerFiles = handlers.map { h ->
    val file = h.className?.let { fileOfHandler(it, sources) }
    if (h.className == null) {
      errors += "handler ${h.commandId} has no class attribute"
    } else if (file == null) {
      errors += "handler ${h.commandId}: no source file declares ${h.className}"
    }
    h.commandId to file
  }
  val files = sources.filterValues { code -> LABELS.values.any { it.containsMatchIn(code) } }.keys.toMutableSet()
  val commands = mutableSetOf<String>()
  do {
    val before = files.size + commands.size
    handlerFiles.forEach { (id, file) -> if (file != null && file in files) commands += id }
    sources.forEach { (file, code) -> if (commands.any { "\"$it\"" in code }) files += file }
  } while (files.size + commands.size != before)
  return Derivation(commands, files, errors)
}
