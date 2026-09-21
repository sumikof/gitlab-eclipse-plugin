package com.gitlab.eclipse.diagnostics

/**
 * The plugin's own log lines, kept in memory so a diagnostics export has something to attach
 * (design §8.1).
 *
 * A port of the reference extension's `src/common/diagnostics/log_collector.ts`, including its
 * [DEFAULT_CAPACITY] of 5000 lines: past that the oldest line is overwritten, so a long-running
 * workbench cannot grow this without bound.
 *
 * **In memory only, deliberately.** The reference keeps its collector in memory too — the on-disk
 * counterpart already exists for the half that matters most, the language server's own
 * `language_server.log`. Nothing here survives a restart, and nothing here is written out until the
 * user asks for it.
 *
 * Thread-safe: [append] is called from whichever thread logged, which for this plugin includes
 * lsp4j's dispatch thread, the UI thread and arbitrary coroutine workers. The critical section is
 * one array write — callers format their line before they get here (design §13, N2).
 */
open class LogRingBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

  private val lock = Any()
  private val lines = arrayOfNulls<String>(capacity)
  private var cursor = 0
  private var full = false

  /** Appends one already-formatted line, overwriting the oldest once the buffer is full. */
  open fun append(line: String) {
    synchronized(lock) {
      lines[cursor] = line
      cursor = (cursor + 1) % capacity
      if (cursor == 0) full = true
    }
  }

  /**
   * Every retained line in chronological order, newline-joined; `""` when nothing has been logged.
   *
   * Returns a snapshot. A line appended while a caller is formatting the result simply is not in
   * it — that is the whole of the concurrency contract here (design §13).
   */
  fun getAll(): String = snapshot().joinToString("\n")

  /** How many lines are retained: the number appended, or [capacity] once it has wrapped. */
  fun lineCount(): Int = synchronized(lock) { if (full) capacity else cursor }

  /** Drops every retained line. */
  fun clear() {
    synchronized(lock) {
      lines.fill(null)
      cursor = 0
      full = false
    }
  }

  private fun snapshot(): List<String> = synchronized(lock) {
    if (!full) {
      // Nothing has wrapped yet, so index order is already chronological.
      List(cursor) { lines[it].orEmpty() }
    } else {
      // The cursor sits on the oldest line; everything before it is newer.
      List(capacity) { lines[(cursor + it) % capacity].orEmpty() }
    }
  }

  companion object {
    /** Matches the reference extension's `LogCollector.#MAX_LINES`. */
    const val DEFAULT_CAPACITY: Int = 5000
  }
}
