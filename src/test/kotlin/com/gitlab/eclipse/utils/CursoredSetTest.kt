package com.gitlab.eclipse.utils

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class CursoredSetTest : DescribeSpec({

  describe("CursoredSet") {
    lateinit var cursoredSet: CursoredSet<String>

    beforeTest {
      cursoredSet = CursoredSet()
    }

    describe("when empty") {
      it("has size zero") {
        cursoredSet.size shouldBe 0
      }

      it("returns null when getting current item") {
        cursoredSet.getCurrent().shouldBeNull()
      }

      it("returns null when getting next item") {
        cursoredSet.getNext().shouldBeNull()
      }

      it("returns null when getting previous item") {
        cursoredSet.getPrevious().shouldBeNull()
      }
    }

    describe("adding items") {
      it("increases size and sets current when adding first item") {
        cursoredSet.addAll(listOf("Item1")).shouldBeTrue()
        cursoredSet.size shouldBe 1
        cursoredSet.getCurrent() shouldBe "Item1"
      }

      it("doesn't add duplicate items") {
        cursoredSet.addAll(listOf("Item1"))
        cursoredSet.addAll(listOf("Item1")).shouldBeFalse()
        cursoredSet.size shouldBe 1
      }

      it("correctly adds multiple items") {
        cursoredSet.addAll(listOf("Item1", "Item2", "Item3")).shouldBeTrue()
        cursoredSet.size shouldBe 3
        cursoredSet.getCurrent() shouldBe "Item1"
      }

      it("returns true when adding a mix of new and existing items") {
        cursoredSet.addAll(listOf("Item1", "Item2"))
        cursoredSet.addAll(listOf("Item2", "Item3")).shouldBeTrue()
        cursoredSet.size shouldBe 3
      }

      it("returns false when adding all existing items") {
        cursoredSet.addAll(listOf("Item1", "Item2"))
        cursoredSet.addAll(listOf("Item1", "Item2")).shouldBeFalse()
        cursoredSet.size shouldBe 2
      }

      it("preserves cursor position when adding after navigation") {
        cursoredSet.addAll(listOf("Item1", "Item2"))
        cursoredSet.getNext()

        cursoredSet.addAll(listOf("Item3")).shouldBeTrue()
        cursoredSet.getCurrent() shouldBe "Item2"
        cursoredSet.getNext() shouldBe "Item3"
      }
    }

    describe("navigation") {
      it("advances cursor and returns correct items with getNext") {
        cursoredSet.addAll(listOf("Item1", "Item2", "Item3"))

        cursoredSet.getCurrent() shouldBe "Item1"
        cursoredSet.getNext() shouldBe "Item2"
        cursoredSet.getCurrent() shouldBe "Item2"
        cursoredSet.getNext() shouldBe "Item3"
        cursoredSet.getCurrent() shouldBe "Item3"
      }

      it("wraps around at the end with getNext") {
        cursoredSet.addAll(listOf("Item1", "Item2", "Item3"))

        cursoredSet.getNext()
        cursoredSet.getNext()

        cursoredSet.getNext() shouldBe "Item1"
        cursoredSet.getCurrent() shouldBe "Item1"
      }

      it("moves cursor backward and returns correct items with getPrevious") {
        cursoredSet.addAll(listOf("Item1", "Item2", "Item3"))

        cursoredSet.getCurrent() shouldBe "Item1"
        cursoredSet.getPrevious() shouldBe "Item3"
        cursoredSet.getCurrent() shouldBe "Item3"
        cursoredSet.getPrevious() shouldBe "Item2"
        cursoredSet.getCurrent() shouldBe "Item2"
      }

      it("circles back to the same item in a single item set") {
        cursoredSet.addAll(listOf("Item1"))

        cursoredSet.getCurrent() shouldBe "Item1"
        cursoredSet.getNext() shouldBe "Item1"
        cursoredSet.getPrevious() shouldBe "Item1"
      }
    }

    describe("clear") {
      it("resets size and cursor") {
        cursoredSet.addAll(listOf("Item1", "Item2", "Item3"))
        cursoredSet.clear()

        cursoredSet.size shouldBe 0
        cursoredSet.getCurrent().shouldBeNull()
      }

      it("allows navigation after clear and re-adding items") {
        cursoredSet.addAll(listOf("Item1", "Item2"))
        cursoredSet.clear()
        cursoredSet.addAll(listOf("NewItem1", "NewItem2"))

        cursoredSet.getCurrent() shouldBe "NewItem1"
        cursoredSet.getNext() shouldBe "NewItem2"
      }
    }

    describe("insertion order") {
      it("preserves order of added items") {
        cursoredSet.addAll(listOf("Item3", "Item1", "Item2"))

        cursoredSet.getCurrent() shouldBe "Item3"
        cursoredSet.getNext() shouldBe "Item1"
        cursoredSet.getNext() shouldBe "Item2"
      }
    }

    describe("current index") {
      it("returns -1 when empty") {
        cursoredSet.currentIndex shouldBe -1
      }

      it("returns 0 after adding first item") {
        cursoredSet.add("Item1")
        cursoredSet.currentIndex shouldBe 0
      }

      it("updates index when navigating") {
        cursoredSet.addAll(listOf("Item1", "Item2", "Item3"))

        cursoredSet.currentIndex shouldBe 0
        cursoredSet.getNext()
        cursoredSet.currentIndex shouldBe 1
        cursoredSet.getNext()
        cursoredSet.currentIndex shouldBe 2
        cursoredSet.getPrevious()
        cursoredSet.currentIndex shouldBe 1
      }

      it("resets to -1 after clearing") {
        cursoredSet.add("Item1")
        cursoredSet.currentIndex shouldBe 0
        cursoredSet.clear()
        cursoredSet.currentIndex shouldBe -1
      }
    }
  }
})
