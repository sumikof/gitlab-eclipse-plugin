package com.gitlab.eclipse.api.model

import com.google.gson.Gson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class GitLabJobTest : StringSpec({
  "reads started_at and erased_at" {
    val json = """{"id":1,"status":"running","started_at":"2024-01-01T00:00:00Z","erased_at":"2024-02-01T00:00:00Z"}"""

    val job = Gson().fromJson(json, GitLabJob::class.java)

    job.status shouldBe "running"
    job.startedAt shouldBe "2024-01-01T00:00:00Z"
    job.erasedAt shouldBe "2024-02-01T00:00:00Z"
  }

  "a null erased_at and missing keys give null" {
    val withNull = Gson().fromJson("""{"id":1,"started_at":"x","erased_at":null}""", GitLabJob::class.java)
    withNull.erasedAt.shouldBeNull()

    val bare = Gson().fromJson("""{"id":1}""", GitLabJob::class.java)
    bare.startedAt.shouldBeNull()
    bare.erasedAt.shouldBeNull()
  }
})
