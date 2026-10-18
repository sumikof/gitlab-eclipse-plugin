package com.gitlab.eclipse.preferences.storage

import org.eclipse.equinox.security.storage.ISecurePreferences
import org.eclipse.equinox.security.storage.StorageException

/**
 * In-memory [ISecurePreferences] tree for headless tests. Mirrors the Equinox behaviour the code relies on:
 * `get` of an absent key returns the default without decrypting, `keys()` never decrypts, `node()` creates the
 * node and `nodeExists()` does not. [failDecrypt] makes every read of an encrypted value throw, as a cancelled
 * master-password prompt does; [decryptedReads] counts reads of encrypted values (each may prompt).
 */
class FakeSecurePreferences private constructor(
  private val parent: FakeSecurePreferences?,
  private val name: String,
  private val shared: Shared,
) : ISecurePreferences {
  constructor() : this(null, "", Shared())

  /** State of the whole tree, as one secure storage holds it. */
  class Shared {
    var failDecrypt = false
    var decryptedReads = 0
    var flushes = 0

    /** Runs on every read of an encrypted value, before it decrypts (a test can interleave work there). */
    var onDecrypt: (() -> Unit)? = null
  }

  private val values = linkedMapOf<String, Pair<String, Boolean>>()
  private val children = linkedMapOf<String, FakeSecurePreferences>()

  var failDecrypt: Boolean
    get() = shared.failDecrypt
    set(value) {
      shared.failDecrypt = value
    }
  var onDecrypt: (() -> Unit)?
    get() = shared.onDecrypt
    set(value) {
      shared.onDecrypt = value
    }
  val decryptedReads: Int get() = shared.decryptedReads
  val flushes: Int get() = shared.flushes

  fun encryptedOf(key: String): Boolean? = values[key]?.second

  override fun put(key: String, value: String?, encrypt: Boolean) {
    if (value == null) values.remove(key) else values[key] = value to encrypt
  }

  override fun get(key: String, def: String?): String? {
    val (value, encrypted) = values[key] ?: return def
    if (encrypted) {
      shared.decryptedReads++
      shared.onDecrypt?.invoke()
      if (failDecrypt) throw StorageException(StorageException.NO_PASSWORD, "cancelled")
    }
    return value
  }

  override fun remove(key: String) {
    values.remove(key)
  }

  override fun clear() = values.clear()
  override fun keys(): Array<String> = values.keys.toTypedArray()
  override fun childrenNames(): Array<String> = children.keys.toTypedArray()
  override fun parent(): ISecurePreferences? = parent

  override fun node(pathName: String): FakeSecurePreferences =
    pathName.split('/').filter { it.isNotEmpty() }.fold(this) { node, part ->
      require(part.all { it.code in 32..126 }) { "invalid node name" }
      node.children.getOrPut(part) { FakeSecurePreferences(node, part, shared) }
    }

  override fun nodeExists(pathName: String): Boolean {
    var node: FakeSecurePreferences = this
    for (part in pathName.split('/').filter { it.isNotEmpty() }) node = node.children[part] ?: return false
    return true
  }

  override fun removeNode() {
    parent?.children?.remove(name)
  }

  override fun name(): String = name
  override fun absolutePath(): String = (parent?.absolutePath()?.trimEnd('/') ?: "") + "/" + name

  override fun flush() {
    shared.flushes++
  }

  override fun putInt(key: String, value: Int, encrypt: Boolean) = put(key, value.toString(), encrypt)
  override fun getInt(key: String, def: Int): Int = get(key, null)?.toInt() ?: def
  override fun putLong(key: String, value: Long, encrypt: Boolean) = put(key, value.toString(), encrypt)
  override fun getLong(key: String, def: Long): Long = get(key, null)?.toLong() ?: def
  override fun putBoolean(key: String, value: Boolean, encrypt: Boolean) = put(key, value.toString(), encrypt)
  override fun getBoolean(key: String, def: Boolean): Boolean = get(key, null)?.toBoolean() ?: def
  override fun putFloat(key: String, value: Float, encrypt: Boolean) = put(key, value.toString(), encrypt)
  override fun getFloat(key: String, def: Float): Float = get(key, null)?.toFloat() ?: def
  override fun putDouble(key: String, value: Double, encrypt: Boolean) = put(key, value.toString(), encrypt)
  override fun getDouble(key: String, def: Double): Double = get(key, null)?.toDouble() ?: def
  override fun putByteArray(key: String, value: ByteArray?, encrypt: Boolean) =
    put(key, value?.let { String(it) }, encrypt)

  override fun getByteArray(key: String, def: ByteArray?): ByteArray? = get(key, null)?.toByteArray() ?: def
  override fun isEncrypted(key: String): Boolean = values[key]?.second ?: false
}
