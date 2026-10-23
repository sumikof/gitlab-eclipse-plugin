package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.ci.CiStatusIcon
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldNotBeEmpty
import org.eclipse.swt.SWT
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Design A D2/D3: the theme colors and icon assets the sidebar resolves by name at runtime. Neither is seen by the
 * compiler, and a mismatch only shows on real hardware as a row without its color or icon (no exception, no log).
 */
class SidebarDecorationAssetsTest : StringSpec({
  val root: Element = checkNotNull(javaClass.classLoader.getResourceAsStream("plugin.xml")).use {
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
  }

  fun Element.children(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
  }

  val sidebarColors = root.children("colorDefinition").filter { it.getAttribute("id").startsWith(CHANGE_COLOR_PREFIX) }

  "every change color id, light and dark, is declared in plugin.xml, and nothing else under the prefix" {
    val expected = ChangeColorRole.entries.flatMap { role -> listOf(false, true).map { changeColorId(role, it) } }

    sidebarColors.map { it.getAttribute("id") } shouldContainExactlyInAnyOrder expected
  }

  "every sidebar color sits in a declared theme category" {
    val categories = root.children("themeElementCategory").map { it.getAttribute("id") }.toSet()

    sidebarColors shouldHaveSize ChangeColorRole.entries.size * 2
    sidebarColors.filter { it.getAttribute("categoryId") !in categories }.map { it.getAttribute("id") }.shouldBeEmpty()
  }

  "every sidebar color declares a value that names a real SWT color constant" {
    sidebarColors.forEach { it.getAttribute("value").shouldNotBeEmpty() }
    sidebarColors.map { it.getAttribute("value") }.filter {
      !it.startsWith("COLOR_") || runCatching { SWT::class.java.getField(it) }.isFailure
    }.shouldBeEmpty()
  }

  "every CI status icon exists for both tones (light/dark folders), at 1x and @2x" {
    val missing = CiStatusIcon.entries.flatMap { icon ->
      listOf("light", "dark").flatMap { folder ->
        listOf("", "@2x").map { scale -> "icons/$folder/${icon.iconName}$scale.png" }
      }
    }.filter { javaClass.classLoader.getResource(it) == null }

    missing.shouldBeEmpty()
  }
})
