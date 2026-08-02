package com.gitlab.eclipse.ci.lint

import org.eclipse.core.resources.IStorage
import org.eclipse.jface.resource.ImageDescriptor
import org.eclipse.ui.IPersistableElement
import org.eclipse.ui.IStorageEditorInput

/** 世代/タブ分離キー: 正規化済み instanceUrl・projectId・§8.7 の安定 sourceId。 */
data class MergedYamlKey(val instanceUrl: String, val projectId: String, val sourceId: String)

/**
 * Transient in-memory editor input for a merged CI yaml, identified by [key] only.
 *
 * `equals`/`hashCode` depend solely on [key] so the same source reuses the same editor
 * while a different instance/project/source opens a different one. `exists() == false`
 * and a null persistable keep the input out of EditorHistory and workbench mementos.
 */
class MergedYamlEditorInput(val key: MergedYamlKey, val content: MergedYamlContent) : IStorageEditorInput {
  private val displayName = ".gitlab-ci (Merged).yml" // VSCode 固定名パリティ(merged_yaml_uri.ts:14)

  override fun getStorage(): IStorage = MergedYamlStorage(content, displayName)

  override fun exists(): Boolean = false

  override fun getName(): String = displayName

  override fun getToolTipText(): String = displayName

  override fun getImageDescriptor(): ImageDescriptor? = null

  override fun getPersistable(): IPersistableElement? = null

  override fun <T> getAdapter(adapter: Class<T>): T? = null

  override fun equals(other: Any?): Boolean = other is MergedYamlEditorInput && other.key == key

  override fun hashCode(): Int = key.hashCode()
}
