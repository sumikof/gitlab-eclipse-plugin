package com.gitlab.eclipse.handlers

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs

/** The per-shell pulldown menu bookkeeping of [ShowPluginStatusMenu] (design §8.3, I5), without SWT. */
class OwnerScopedCacheTest : DescribeSpec({
  class Value(val name: String) {
    var alive = true
  }

  class Fixture {
    val released = mutableListOf<String>()
    var releaseFailure: RuntimeException? = null
    val cache = OwnerScopedCache<String, Value>(
      isAlive = { it.alive },
      release = {
        released += it.name
        releaseFailure?.let { e -> throw e }
      },
    )
  }

  it("keeps one value per owner, so a second window does not reuse the first window's menu") {
    val f = Fixture()
    val a = f.cache.getOrCreate("shellA") { Value("a") }
    val b = f.cache.getOrCreate("shellB") { Value("b") }

    a shouldNotBeSameInstanceAs b
    f.cache.getOrCreate("shellA") { error("must reuse") } shouldBeSameInstanceAs a
    f.cache.size shouldBe 2
  }

  it("release on owner dispose releases the value once and forgets it") {
    val f = Fixture()
    f.cache.getOrCreate("shellA") { Value("a") }

    f.cache.remove("shellA")
    f.cache.remove("shellA")

    f.released shouldContainExactly listOf("a")
    f.cache.size shouldBe 0
    f.cache.getOrCreate("shellA") { Value("a2") }!!.name shouldBe "a2"
  }

  it("a dead value is released and replaced") {
    val f = Fixture()
    val first = f.cache.getOrCreate("shellA") { Value("a") }!!
    first.alive = false

    val second = f.cache.getOrCreate("shellA") { Value("a2") }

    second!!.name shouldBe "a2"
    f.released shouldContainExactly listOf("a")
  }

  it("caches nothing when creation yields null") {
    val f = Fixture()
    f.cache.getOrCreate("shellA") { null }.shouldBeNull()
    f.cache.size shouldBe 0
    f.released.shouldBeEmpty()
  }

  it("contains a failing release") {
    val f = Fixture()
    f.cache.getOrCreate("shellA") { Value("a") }
    f.releaseFailure = IllegalStateException()

    shouldNotThrowAny { f.cache.remove("shellA") }
    f.cache.size shouldBe 0
  }
})
