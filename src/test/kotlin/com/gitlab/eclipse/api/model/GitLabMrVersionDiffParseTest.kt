package com.gitlab.eclipse.api.model

import com.google.gson.Gson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [GitLabMrVersion.Diff]'s task-2 additions (design §8.3, §12.2.1): `diff`,
 * `tooLarge` (wire key `too_large`), and `collapsed`. Split into its own file rather than added to
 * [GitLabMrVersionTest] because that file predates these fields and already covers the rest of the
 * envelope; this file focuses solely on the three new, nullable fields.
 */
class GitLabMrVersionDiffParseTest : StringSpec({
  val gson = Gson()

  fun versionJson(diffEntryJson: String) = """{"id":1,"diffs":[$diffEntryJson]}"""

  "parses diff, too_large, and collapsed when all three are present" {
    val json = versionJson(
      """{"old_path":"a.txt","new_path":"a.txt","new_file":false,""" +
        """"deleted_file":false,"renamed_file":false,""" +
        """"diff":"@@ -1,2 +1,2 @@\n-a\n+b\n","too_large":true,"collapsed":true}""",
    )

    val version = gson.fromJson(json, GitLabMrVersion::class.java)
    val entry = version.diffs[0]

    entry.diff shouldBe "@@ -1,2 +1,2 @@\n-a\n+b\n"
    entry.tooLarge shouldBe true
    entry.collapsed shouldBe true
  }

  "leaves diff, tooLarge, and collapsed null when all three are absent from the JSON" {
    val json = versionJson(
      """{"old_path":"a.txt","new_path":"a.txt","new_file":false,""" +
        """"deleted_file":false,"renamed_file":false}""",
    )

    val version = gson.fromJson(json, GitLabMrVersion::class.java)
    val entry = version.diffs[0]

    entry.diff.shouldBeNull()
    entry.tooLarge.shouldBeNull()
    entry.collapsed.shouldBeNull()
  }

  "parses too_large and collapsed as explicit false, and diff as empty string, both distinct from absent (null)" {
    val json = versionJson(
      """{"old_path":"a.txt","new_path":"a.txt","new_file":false,""" +
        """"deleted_file":false,"renamed_file":false,""" +
        """"diff":"","too_large":false,"collapsed":false}""",
    )

    val version = gson.fromJson(json, GitLabMrVersion::class.java)
    val entry = version.diffs[0]

    entry.diff shouldBe ""
    entry.tooLarge shouldBe false
    entry.collapsed shouldBe false
  }
})
