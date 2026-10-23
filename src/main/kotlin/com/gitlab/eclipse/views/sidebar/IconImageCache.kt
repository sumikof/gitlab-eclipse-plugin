package com.gitlab.eclipse.views.sidebar

import org.eclipse.swt.SWTError

/**
 * Name-keyed cache of icons that are costly to create (no Display needed: [T] is an `Image` in the view).
 * [create] runs once per name; a null result or a thrown [Exception] or [SWTError] is remembered too, so a missing
 * icon is not retried on every repaint. [SWTError] (e.g. no more handles) extends `Error`, so it is caught by name;
 * any other `Error` propagates. A failure is reported once through [onError] and the caller decides how to log it.
 * Not thread-safe: confined to the UI thread.
 */
class IconImageCache<T : Any>(
  private val create: (String) -> T?,
  private val onError: (String, Throwable) -> Unit,
) {
  private val entries = HashMap<String, T?>()

  fun get(iconName: String): T? {
    if (entries.containsKey(iconName)) return entries[iconName]
    val created =
      try {
        create(iconName)
      } catch (e: Exception) {
        onError(iconName, e)
        null
      } catch (e: SWTError) {
        onError(iconName, e)
        null
      }
    entries[iconName] = created
    return created
  }

  fun clear() {
    entries.clear()
  }
}
