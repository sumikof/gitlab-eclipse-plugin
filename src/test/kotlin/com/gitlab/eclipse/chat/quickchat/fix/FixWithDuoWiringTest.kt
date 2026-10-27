package com.gitlab.eclipse.chat.quickchat.fix

import com.gitlab.eclipse.chat.quickchat.fix.ui.FixWithDuoMarkerResolutionGenerator
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** Checks the plugin.xml wiring of Fix with GitLab Duo, which neither the compiler nor any other test sees. */
class FixWithDuoWiringTest : DescribeSpec({

  val root: Element = javaClass.classLoader.getResourceAsStream("plugin.xml")!!.use {
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
  }

  fun Element.descendants(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
  }

  it("registers the marker resolution generator for every marker type") {
    val generators = root.descendants("extension")
      .filter { it.getAttribute("point") == "org.eclipse.ui.ide.markerResolution" }
      .flatMap { it.descendants("markerResolutionGenerator") }
      .filter { it.getAttribute("class") == FixWithDuoMarkerResolutionGenerator::class.java.name }
    generators shouldHaveSize 1
    // A markerType would match that exact type only (markerResolution.exsd); hasResolutions narrows instead.
    generators.single().hasAttribute("markerType") shouldBe false
  }
})
