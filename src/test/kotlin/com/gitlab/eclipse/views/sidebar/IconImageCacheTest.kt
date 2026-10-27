package com.gitlab.eclipse.views.sidebar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTError

class IconImageCacheTest : StringSpec({
  "creates once per name" {
    var calls = 0
    val cache = IconImageCache<String>({
      calls++
      "img-$it"
    }, { _, _ -> })
    cache.get("a") shouldBe "img-a"
    cache.get("a") shouldBe "img-a"
    calls shouldBe 1
    cache.get("b") shouldBe "img-b"
    calls shouldBe 2
  }
  "remembers a null result" {
    var calls = 0
    val cache = IconImageCache<String>({
      calls++
      null
    }, { _, _ -> })
    cache.get("a") shouldBe null
    cache.get("a") shouldBe null
    calls shouldBe 1
  }
  "a throwing create reports once and is remembered as null" {
    var calls = 0
    val errors = mutableListOf<String>()
    val cache = IconImageCache<String>({
      calls++
      error("boom")
    }, { name, _ -> errors += name })
    cache.get("a") shouldBe null
    cache.get("a") shouldBe null
    calls shouldBe 1
    errors shouldBe listOf("a")
  }
  "an SWTError from create (no more handles) reports once and is remembered as null" {
    var calls = 0
    val errors = mutableListOf<Throwable>()
    val cache = IconImageCache<String>({
      calls++
      throw SWTError(SWT.ERROR_NO_HANDLES)
    }, { _, e -> errors += e })
    cache.get("a") shouldBe null
    cache.get("a") shouldBe null
    calls shouldBe 1
    errors.map { it.javaClass } shouldBe listOf(SWTError::class.java)
  }
  "an Error is not swallowed" {
    val cache = IconImageCache<String>({ throw OutOfMemoryError("x") }, { _, _ -> })
    val thrown = runCatching { cache.get("a") }.exceptionOrNull()
    (thrown is OutOfMemoryError) shouldBe true
  }
  "clear makes the next get create again" {
    var calls = 0
    val cache = IconImageCache<String>({
      calls++
      "i"
    }, { _, _ -> })
    cache.get("a")
    cache.clear()
    cache.get("a")
    calls shouldBe 2
  }
})
