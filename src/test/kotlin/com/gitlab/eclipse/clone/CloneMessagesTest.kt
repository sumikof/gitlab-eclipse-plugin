package com.gitlab.eclipse.clone

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class CloneMessagesTest : StringSpec({
  "every skip reason has wording for both paths and the two differ" {
    // 全称規定: 理由を追加したら 2 経路分書くまでが追加である。
    ImportSkipReason.entries.forEach { reason ->
      val cloned = CloneMessages.importSkipped(reason, RepositorySource.CLONED_NOW, "proj")
      val adopted = CloneMessages.importSkipped(reason, RepositorySource.ADOPTED_EXISTING, "proj")
      cloned.isNotBlank() shouldBe true
      adopted.isNotBlank() shouldBe true
      cloned shouldNotBe adopted
    }
  }

  "no adopted-path wording claims the clone finished (C31)" {
    val adopted = ImportSkipReason.entries.map {
      CloneMessages.importSkipped(it, RepositorySource.ADOPTED_EXISTING, "proj")
    } + CloneMessages.imported(RepositorySource.ADOPTED_EXISTING, "proj")

    adopted.forEach { message ->
      message shouldNotContain "clone は完了"
      message shouldNotContain "clone しました"
    }
  }

  "the cloned path does say the clone finished" {
    CloneMessages.imported(RepositorySource.CLONED_NOW, "proj") shouldContain "clone しました"
  }

  "the generic clone failure never blames a non-empty destination (C16)" {
    // 非空の宛先は入口判定で (a) / (a0) に分岐するのでこの経路に入らない。
    CloneMessages.cloneFailed shouldNotContain "空"
  }

  "the occupied wording offers no deletion" {
    CloneMessages.occupied shouldContain "空ではない"
    CloneMessages.occupied shouldNotContain "削除"
  }

  "leftover wording neither promises nor denies that anything remains" {
    val message = CloneMessages.cloneIncompleteLeftovers(File("/tmp/dest"))
    message shouldContain "何も削除していません"
    message shouldNotContain "必ず"
  }

  "only the leftover wording carries the destination path" {
    CloneMessages.cloneIncompleteLeftovers(File("/tmp/dest")) shouldContain "/tmp/dest"
    CloneMessages.cloneIncompleteNothingLeft shouldNotContain "/"
    CloneMessages.occupied shouldNotContain "/"
  }

  "project-named wording interpolates the name" {
    CloneMessages.orphanConsent("proj") shouldContain "proj"
    CloneMessages.orphanCleanupInstructions("proj") shouldContain "proj"
    CloneMessages.importSkipped(ImportSkipReason.NAME_TAKEN, RepositorySource.CLONED_NOW, "proj") shouldContain "proj"
  }

  "the cleanup instructions warn about the checkbox" {
    CloneMessages.orphanCleanupInstructions("proj") shouldContain "Delete project contents on disk"
    CloneMessages.orphanCleanupInstructions("proj") shouldContain "チェックを入れると"
  }
})
