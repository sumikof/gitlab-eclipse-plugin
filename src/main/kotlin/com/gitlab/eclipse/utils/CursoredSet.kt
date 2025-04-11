package com.gitlab.eclipse.utils

/**
 * A set-like collection that maintains insertion order and tracks a cursor position.
 * Provides methods to navigate through items (with circular wraparound at boundaries)
 * without needing to specify the current position externally.
 */
class CursoredSet<T> {
  private val items = mutableListOf<T>()

  // -1 indicates no current item (empty collection), 0+ indicates position in items list
  var currentIndex: Int = -1
    private set

  val size: Int
    get() = items.size

  /**
   * Adds an item to the collection if it doesn't already exist.
   * @return true if the item was added, false otherwise
   */
  fun add(item: T): Boolean {
    if (item in items) return false
    items.add(item)
    if (currentIndex == -1) currentIndex = 0
    return true
  }

  /**
   * Adds all items from the collection that don't already exist in this set.
   * @return true if any items were added, false otherwise
   */
  fun addAll(elements: Collection<T>): Boolean {
    var added = false
    for (item in elements) {
      if (add(item)) {
        added = true
      }
    }

    return added
  }

  /**
   * Gets the current item or null if collection is empty.
   */
  fun getCurrent(): T? {
    if (currentIndex == -1 || items.isEmpty()) return null
    return items[currentIndex]
  }

  /**
   * Gets the next item in the collection, cycling back to the beginning if necessary.
   * Updates the current position.
   * @return The next item or null if collection is empty.
   */
  fun getNext(): T? {
    if (items.isEmpty()) return null
    if (currentIndex == -1) currentIndex = 0

    currentIndex = (currentIndex + 1) % items.size
    return items[currentIndex]
  }

  /**
   * Gets the previous item in the collection, cycling to the end if necessary.
   * Updates the current position.
   * @return The previous item or null if collection is empty.
   */
  fun getPrevious(): T? {
    if (items.isEmpty()) return null
    if (currentIndex == -1) currentIndex = 0

    currentIndex = (currentIndex - 1 + items.size) % items.size
    return items[currentIndex]
  }

  /**
   * Clears all items from the collection and resets the cursor position.
   */
  fun clear() {
    items.clear()
    currentIndex = -1
  }
}
