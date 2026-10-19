package com.gitlab.eclipse.preferences.queries

import com.gitlab.eclipse.views.sidebar.queries.CustomQuery
import com.gitlab.eclipse.views.sidebar.queries.CustomQueryCodec
import com.gitlab.eclipse.views.sidebar.queries.CustomQueryDefaults
import com.gitlab.eclipse.views.sidebar.queries.QueryConfig
import com.gitlab.eclipse.views.sidebar.queries.UnreadableReason
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs

class CustomQueryListModelTest : StringSpec({
  fun query(id: String, name: String): CustomQuery =
    CustomQuery(id, JsonObject().apply { addProperty("name", name) })

  fun custom(vararg names: String): QueryConfig.Custom =
    QueryConfig.Custom(names.map { query("id-$it", it) })

  fun ids(): () -> String {
    var next = 0
    return { "new-${++next}" }
  }

  fun CustomQueryListModel.names() = rows.map { it.name }

  fun SaveAction.written(): String = shouldBeInstanceOf<SaveAction.Write>().value

  val unreadable = QueryConfig.Unreadable(UnreadableReason.MALFORMED_JSON, "{broken")

  "a defaults config lists the built-in defaults and saves nothing without edits" {
    val model = CustomQueryListModel(QueryConfig.Defaults)
    model.rows.map { it.id } shouldBe CustomQueryDefaults.queries.map { it.id }
    model.isReadOnly shouldBe false
    model.unreadableText.shouldBeNull()
    model.isDirty shouldBe false
    model.saveAction() shouldBeSameInstanceAs SaveAction.Nothing
  }

  "a custom config without edits saves nothing" {
    val model = CustomQueryListModel(custom("A", "B"))
    model.names() shouldBe listOf("A", "B")
    model.saveAction() shouldBeSameInstanceAs SaveAction.Nothing
  }

  "an edit writes the encoded rows" {
    val model = CustomQueryListModel(custom("A", "B"))
    model.remove(0)
    model.isDirty shouldBe true
    model.saveAction().written() shouldBe CustomQueryCodec.encode(listOf(query("id-B", "B")))
  }

  "edits that end equal to the original save nothing" {
    val model = CustomQueryListModel(custom("A", "B"))
    model.moveDown(0)
    model.moveUp(1)
    model.replace(0, query("ignored", "A"))
    model.saveAction() shouldBeSameInstanceAs SaveAction.Nothing
  }

  "edits of a defaults config that end equal to the defaults save nothing" {
    val model = CustomQueryListModel(QueryConfig.Defaults)
    model.moveDown(0)
    model.moveUp(1)
    model.saveAction() shouldBeSameInstanceAs SaveAction.Nothing
  }

  "an edit of a defaults config writes the full list" {
    val model = CustomQueryListModel(QueryConfig.Defaults)
    model.remove(5)
    model.saveAction().written() shouldBe CustomQueryCodec.encode(CustomQueryDefaults.queries.take(5))
  }

  "an unreadable config is read-only, empty and saves nothing" {
    val model = CustomQueryListModel(unreadable)
    model.rows.shouldBeEmpty()
    model.isReadOnly shouldBe true
    model.unreadableText shouldBe "{broken"
    model.isDirty shouldBe false
    model.saveAction() shouldBeSameInstanceAs SaveAction.Nothing
  }

  "list edits are ignored while the config is read-only" {
    val model = CustomQueryListModel(unreadable)
    model.add(query("x", "X"))
    model.rows.shouldBeEmpty()
    model.saveAction() shouldBeSameInstanceAs SaveAction.Nothing
  }

  "restoring defaults from an unreadable config sets the default" {
    val model = CustomQueryListModel(unreadable)
    model.restoreDefaults()
    model.isReadOnly shouldBe false
    model.unreadableText.shouldBeNull()
    model.rows.map { it.id } shouldBe CustomQueryDefaults.queries.map { it.id }
    model.isDirty shouldBe true
    model.saveAction() shouldBeSameInstanceAs SaveAction.SetToDefault
  }

  "replacing an unreadable config from JSON writes the new list" {
    val model = CustomQueryListModel(unreadable)
    model.replaceAllFromJson(listOf(query("a", "A")))
    model.isReadOnly shouldBe false
    model.names() shouldBe listOf("A")
    model.saveAction().written() shouldBe CustomQueryCodec.encode(listOf(query("a", "A")))
  }

  "replacing an unreadable config with an empty list still writes" {
    val model = CustomQueryListModel(unreadable)
    model.replaceAllFromJson(emptyList())
    model.saveAction().written() shouldBe CustomQueryCodec.encode(emptyList())
  }

  "restoring defaults on a custom config sets the default" {
    val model = CustomQueryListModel(custom("A"))
    model.restoreDefaults()
    model.saveAction() shouldBeSameInstanceAs SaveAction.SetToDefault
  }

  "restoring defaults on a defaults config saves nothing" {
    val model = CustomQueryListModel(QueryConfig.Defaults)
    model.remove(0)
    model.restoreDefaults()
    model.saveAction() shouldBeSameInstanceAs SaveAction.Nothing
  }

  "restoring defaults sets the default even when the custom list equals the defaults" {
    val model = CustomQueryListModel(QueryConfig.Custom(CustomQueryDefaults.queries))
    model.saveAction() shouldBeSameInstanceAs SaveAction.Nothing
    model.restoreDefaults()
    model.saveAction() shouldBeSameInstanceAs SaveAction.SetToDefault
  }

  "restore, edit and edit back sets the default" {
    val model = CustomQueryListModel(custom("A"))
    model.restoreDefaults()
    model.moveDown(0)
    model.saveAction().shouldBeInstanceOf<SaveAction.Write>()
    model.moveUp(1)
    model.saveAction() shouldBeSameInstanceAs SaveAction.SetToDefault
  }

  "unreadable, replaced from JSON, then restored sets the default" {
    val model = CustomQueryListModel(unreadable)
    model.replaceAllFromJson(listOf(query("a", "A")))
    model.restoreDefaults()
    model.saveAction() shouldBeSameInstanceAs SaveAction.SetToDefault
  }

  "an edit after restoring defaults writes" {
    val model = CustomQueryListModel(custom("A"), ids())
    model.restoreDefaults()
    model.duplicate(0)
    model.saveAction().shouldBeInstanceOf<SaveAction.Write>()
  }

  "replacing from JSON after restoring defaults writes" {
    val model = CustomQueryListModel(custom("A"))
    model.restoreDefaults()
    model.replaceAllFromJson(listOf(query("b", "B")))
    model.saveAction().written() shouldBe CustomQueryCodec.encode(listOf(query("b", "B")))
  }

  "replacing from JSON with the original list saves nothing" {
    val model = CustomQueryListModel(custom("A"))
    model.replaceAllFromJson(listOf(query("id-A", "A")))
    model.saveAction() shouldBeSameInstanceAs SaveAction.Nothing
  }

  "duplicate inserts a copy after the row with a new id and a copy name" {
    val model = CustomQueryListModel(custom("A", "B"), ids())
    model.duplicate(0)
    model.names() shouldBe listOf("A", "A (copy)", "B")
    model.rows[1].id shouldBe "new-1"
    model.rows[0].name shouldBe "A"
  }

  "duplicate keeps a name that is not text as is" {
    val numbered = CustomQuery("n", JsonObject().apply { addProperty("name", 7) })
    val unnamed = CustomQuery("u", JsonObject())
    val model = CustomQueryListModel(QueryConfig.Custom(listOf(numbered, unnamed)), ids())
    model.duplicate(0)
    model.duplicate(2)
    model.rows[1].json.get("name") shouldBe JsonPrimitive(7)
    model.rows[3].json.has("name") shouldBe false
  }

  "add and replace store a copy of the query json" {
    val added = query("a", "A")
    val replacement = query("x", "B")
    val model = CustomQueryListModel(custom("Z"))
    model.add(added)
    model.replace(0, replacement)
    added.json.addProperty("search", "x")
    replacement.json.addProperty("search", "x")
    model.rows.none { it.json.has("search") } shouldBe true
  }

  "duplicate deep-copies the json" {
    val model = CustomQueryListModel(custom("A"), ids())
    model.duplicate(0)
    model.rows[1].json.addProperty("search", "x")
    model.rows[0].json.has("search") shouldBe false
  }

  "duplicate of an invalid element keeps the element under a new id" {
    val invalid = CustomQuery("bad", JsonObject(), JsonPrimitive(42))
    val model = CustomQueryListModel(QueryConfig.Custom(listOf(invalid)), ids())
    model.duplicate(0)
    model.rows[1].id shouldBe "new-1"
    model.rows[1].invalidElement shouldBe JsonPrimitive(42)
  }

  "replace keeps the id of the replaced row" {
    val model = CustomQueryListModel(custom("A", "B"))
    model.replace(1, query("other", "C"))
    model.rows.map { it.id } shouldBe listOf("id-A", "id-B")
    model.names() shouldBe listOf("A", "C")
  }

  "rows not edited keep their invalid element when another row changes" {
    val invalid = CustomQuery("bad", JsonObject(), JsonPrimitive(42))
    val model = CustomQueryListModel(QueryConfig.Custom(listOf(invalid, query("a", "A"))))
    model.replace(1, query("a", "Z"))
    model.rows[0].invalidElement shouldBe JsonPrimitive(42)
    model.saveAction().written() shouldBe CustomQueryCodec.encode(listOf(invalid, query("a", "Z")))
  }

  "add appends the query and keeps its id" {
    val model = CustomQueryListModel(custom("A"), ids())
    model.add(query("fresh", "N"))
    model.rows.map { it.id } shouldBe listOf("id-A", "fresh")
  }

  "add assigns a new id when the id is blank or already used" {
    val model = CustomQueryListModel(custom("A"), ids())
    model.add(query("id-A", "N"))
    model.add(query(" ", "M"))
    model.rows.map { it.id } shouldBe listOf("id-A", "new-1", "new-2")
  }

  "move up and down swap neighbours" {
    val model = CustomQueryListModel(custom("A", "B", "C"))
    model.moveUp(2)
    model.names() shouldBe listOf("A", "C", "B")
    model.moveDown(0)
    model.names() shouldBe listOf("C", "A", "B")
  }

  "moves at the ends and out-of-range indices are no-ops" {
    val model = CustomQueryListModel(custom("A", "B"))
    model.moveUp(0)
    model.moveDown(1)
    model.moveUp(5)
    model.remove(-1)
    model.duplicate(2)
    model.replace(9, query("x", "X"))
    model.names() shouldBe listOf("A", "B")
    model.saveAction() shouldBeSameInstanceAs SaveAction.Nothing
  }

  "rows are a snapshot that later edits do not change" {
    val model = CustomQueryListModel(custom("A", "B"))
    val before = model.rows
    model.remove(0)
    before.size shouldBe 2
  }
})
