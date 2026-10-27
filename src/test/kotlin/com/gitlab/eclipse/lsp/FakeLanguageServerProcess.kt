package com.gitlab.eclipse.lsp

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

internal enum class InitializeReply { SUCCESS, FAILURE, SUCCESS_THEN_EXIT }

/**
 * Stands in for the real language-server process so the lifecycle state machine can be
 * exercised headlessly and deterministically. Speaks just enough of the LSP wire protocol
 * to answer the initialize request (success or failure per [initializeReply]); requests
 * without an id (notifications) are ignored. Unlike a real [Process], [destroy] does NOT
 * complete [onExit]; tests deliver the exit notification explicitly via [completeExit],
 * which is exactly the asynchronous gap the provider's identity guard must survive.
 */
internal class FakeLanguageServerProcess(
  private val initializeReply: InitializeReply = InitializeReply.SUCCESS,
  /**
   * Held shut until [releaseInitializeReply], so a test can place other work — another start,
   * an assertion — between the initialize request and the answer to it. Open by default.
   */
  private val initializeGate: CountDownLatch = CountDownLatch(0),
) : Process() {
  private val exit = CompletableFuture<Process>()

  private val requestSink = PipedInputStream(PIPE_BUFFER_BYTES)
  private val stdin = PipedOutputStream(requestSink)
  private val stdout = PipedInputStream(PIPE_BUFFER_BYTES)
  private val responseSink = PipedOutputStream(stdout)

  // Connected-but-never-written pipe: the stderr pull loop blocks instead of spinning,
  // and shutdownNow() interrupts it (InterruptedIOException ends the task).
  private val stderr = PipedInputStream(PipedOutputStream())

  var destroyed = false
    private set

  /**
   * Every framed message the provider sent, in arrival order, so a test can assert on what
   * actually went over the wire rather than on the params object the provider built.
   */
  val receivedMessages: List<String> get() = messages
  private val messages = CopyOnWriteArrayList<String>()

  @Suppress("unused")
  private val responder = Thread {
    try {
      while (true) {
        val body = readFramedMessage() ?: break
        messages += body
        // Notifications (initialized, didChangeConfiguration, ...) have no id: skip them.
        val id = REQUEST_ID.find(body)?.groupValues?.get(1)
        if (id != null) {
          initializeGate.await()
          if (initializeReply == InitializeReply.SUCCESS_THEN_EXIT) {
            // Deterministic respond-then-die: exit is observably done BEFORE the reply is
            // written, so whichever race arm restart sees first (exit or reply), the
            // process is already provably dead and restart must report failure. Completing
            // from a helper thread keeps the responder free: exit dependents include the
            // provider's lock-taking guard, which would otherwise capture this thread and
            // stall the reply until restart times out.
            Thread { completeExit() }.apply {
              isDaemon = true
              name = "fake-language-server-exit"
              start()
            }
            while (!exit.isDone) {
              Thread.yield()
            }
          }
          val reply = when (initializeReply) {
            InitializeReply.SUCCESS, InitializeReply.SUCCESS_THEN_EXIT ->
              """{"jsonrpc":"2.0","id":$id,"result":{"capabilities":{}}}"""
            InitializeReply.FAILURE ->
              """{"jsonrpc":"2.0","id":$id,"error":{"code":-32603,"message":"initialize rejected"}}"""
          }
          val bytes = reply.toByteArray(Charsets.UTF_8)
          responseSink.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.UTF_8))
          responseSink.write(bytes)
          responseSink.flush()
        }
      }
    } catch (_: IOException) {
      // Pipes closed by destroy(): the fake server is gone.
    } catch (_: InterruptedException) {
      // Declared by the gate's await(). Nothing in the suite interrupts this daemon thread; the
      // catch is here so that ending the responder is the answer if anything ever does.
    }
  }.apply {
    isDaemon = true
    name = "fake-language-server"
    start()
  }

  private fun readFramedMessage(): String? {
    var contentLength = -1
    while (true) {
      val line = readHeaderLine() ?: return null
      if (line.isEmpty()) break
      CONTENT_LENGTH.find(line)?.let { contentLength = it.groupValues[1].toInt() }
    }
    if (contentLength < 0) return null
    val body = ByteArray(contentLength)
    var read = 0
    while (read < contentLength) {
      val n = requestSink.read(body, read, contentLength - read)
      if (n < 0) return null
      read += n
    }
    return String(body, Charsets.UTF_8)
  }

  private fun readHeaderLine(): String? {
    val line = StringBuilder()
    while (true) {
      val b = requestSink.read()
      if (b < 0) return null
      if (b == '\n'.code) break
      if (b != '\r'.code) line.append(b.toChar())
    }
    return line.toString()
  }

  fun completeExit() {
    exit.complete(this)
  }

  fun releaseInitializeReply() {
    initializeGate.countDown()
  }

  override fun getOutputStream(): OutputStream = stdin
  override fun getInputStream(): InputStream = stdout
  override fun getErrorStream(): InputStream = stderr
  override fun waitFor(): Int {
    exit.get()
    return 0
  }

  override fun exitValue(): Int = if (exit.isDone) 0 else throw IllegalThreadStateException()
  override fun destroy() {
    destroyed = true
    runCatching { stdin.close() }
    runCatching { requestSink.close() }
    runCatching { responseSink.close() }
    runCatching { stdout.close() }
  }

  override fun onExit(): CompletableFuture<Process> = exit
  override fun pid(): Long = 4242L

  companion object {
    private const val PIPE_BUFFER_BYTES = 1 shl 16

    // Echoes the id verbatim (quoted or bare) so both string and numeric lsp4j ids round-trip.
    private val REQUEST_ID = Regex("\"id\"\\s*:\\s*(\"?[0-9]+\"?)")
    private val CONTENT_LENGTH = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
  }
}
