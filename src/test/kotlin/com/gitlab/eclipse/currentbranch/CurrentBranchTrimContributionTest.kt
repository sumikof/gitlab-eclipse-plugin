package com.gitlab.eclipse.currentbranch

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Status bar §4.5: the plugin.xml wiring and the icon assets the trim resolves at runtime — neither is seen by the
 * compiler, and a missing one only shows on real hardware (no trim, or an item without an icon).
 */
class CurrentBranchTrimContributionTest : StringSpec({
  val root: Element = checkNotNull(javaClass.classLoader.getResourceAsStream("plugin.xml")).use {
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
  }

  fun Element.children(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
  }

  "the status trim contributes one toolbar holding the CurrentBranchStatusTrim control" {
    val contributions = root.children("menuContribution").filter {
      it.getAttribute("locationURI") == "toolbar:org.eclipse.ui.trim.status"
    }
    val controls = contributions.flatMap { it.children("control") }

    controls shouldHaveSize 1
    controls.single().getAttribute("class") shouldBe CurrentBranchStatusTrim::class.java.name
    (controls.single().parentNode as Element).getAttribute("id") shouldBe "com.gitlab.eclipse.menus.currentBranchTrim"
  }

  "R19: every trim icon exists for both tones (light/dark folders), at 1x and @2x" {
    val missing = TrimIcon.entries.flatMap { icon ->
      listOf("light", "dark").flatMap { folder ->
        listOf("", "@2x").map { scale -> "icons/$folder/${trimIconName(icon)}$scale.png" }
      }
    }.filter { javaClass.classLoader.getResource(it) == null }

    missing.shouldBeEmpty()
  }
})
