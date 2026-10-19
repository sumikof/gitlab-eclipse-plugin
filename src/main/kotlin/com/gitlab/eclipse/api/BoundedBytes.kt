package com.gitlab.eclipse.api

import java.io.ByteArrayOutputStream
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A response body read under a size cap: the whole body, or the fact that it exceeded the cap. */
sealed interface BoundedBytes {
  /** The complete body; at most the cap. */
  class Ok(val bytes: ByteArray) : BoundedBytes

  /** The body was larger than the cap; nothing beyond the cap was kept. */
  object TooLarge : BoundedBytes
}

/** Arms a one-shot timer; [onExpiry] runs on some other thread once the timeout has passed. */
fun interface BodyDeadline {
  /** Arms the timer and returns a function that disarms it (a no-op once it has fired). */
  fun arm(timeout: Duration, onExpiry: () -> Unit): () -> Unit
}

/**
 * Production [BodyDeadline]: one shared daemon timer thread that ends after a short idle period. A
 * disarmed timer is removed from the queue at once, so a completed body is not kept reachable by its
 * pending deadline.
 */
internal object SharedBodyDeadline : BodyDeadline {
  private const val IDLE_KEEP_ALIVE_SECONDS = 5L

  private val timer: ScheduledThreadPoolExecutor by lazy {
    ScheduledThreadPoolExecutor(1) { runnable ->
      Thread(runnable, "GitLab MR diff body deadline").apply { isDaemon = true }
    }.apply {
      removeOnCancelPolicy = true
      // Let the thread die when idle: a lingering worker would pin this bundle's classloader
      // after the bundle stops; the next arm starts a fresh one.
      setKeepAliveTime(IDLE_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS)
      allowCoreThreadTimeOut(true)
    }
  }

  override fun arm(timeout: Duration, onExpiry: () -> Unit): () -> Unit {
    val pending = timer.schedule(onExpiry, timeout.toMillis(), TimeUnit.MILLISECONDS)
    return { pending.cancel(false) }
  }

  /** Test-only: the executor, to check its configuration. */
  internal fun timerForTest(): ScheduledThreadPoolExecutor = timer

  /** Test-only: timers still queued. */
  internal fun pendingForTest(): Int = timer.queue.size
}

/** How long a body may take once its headers arrived; the request timeout covers only the headers. */
private const val DEFAULT_BODY_TIMEOUT_SECONDS = 30L
private val DEFAULT_BODY_TIMEOUT: Duration = Duration.ofSeconds(DEFAULT_BODY_TIMEOUT_SECONDS)

/** Inclusive bounds of the HTTP 2xx status class. */
private const val SUCCESS_MIN = 200
private const val SUCCESS_MAX = 299

/**
 * Reads a 2xx body into memory up to [maxBytes] (REVIEW-01 design §5.1, F4).
 *
 * - non-2xx → the body is not read at all (the subscription is cancelled) and the result is `null`; the
 *   caller reports the status only, so an error page is never held or logged;
 * - a `Content-Length` over the cap → [BoundedBytes.TooLarge] without requesting any body;
 * - otherwise the body is accumulated and, the moment it passes the cap (chunked responses carry
 *   no length), the subscription is cancelled and the result is [BoundedBytes.TooLarge].
 */
internal class BoundedBytesHandler(
  private val maxBytes: Int,
  internal val bodyTimeout: Duration = DEFAULT_BODY_TIMEOUT,
  private val deadline: BodyDeadline = SharedBodyDeadline,
) : HttpResponse.BodyHandler<BoundedBytes?> {
  override fun apply(responseInfo: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<BoundedBytes?> {
    if (responseInfo.statusCode() !in SUCCESS_MIN..SUCCESS_MAX) {
      return RejectingSubscriber(null)
    }
    val declared = responseInfo.headers().firstValueAsLong("Content-Length")
    if (declared.isPresent && declared.asLong > maxBytes) return RejectingSubscriber(BoundedBytes.TooLarge)
    return LimitingSubscriber(maxBytes, bodyTimeout, deadline)
  }
}

/**
 * Cancels at once, reading nothing, and answers [answer]: `null` for an error status (only the status
 * is reported, so a stalled error page cannot block), [BoundedBytes.TooLarge] for a declared length
 * over the cap. Cancelling closes the connection instead of returning it to the pool.
 */
private class RejectingSubscriber(private val answer: BoundedBytes?) : HttpResponse.BodySubscriber<BoundedBytes?> {
  private val result = CompletableFuture<BoundedBytes?>()

  override fun getBody(): CompletionStage<BoundedBytes?> = result

  override fun onSubscribe(subscription: Flow.Subscription) {
    subscription.cancel()
    result.complete(answer)
  }

  override fun onNext(item: List<ByteBuffer>) = Unit

  override fun onError(throwable: Throwable) {
    result.complete(answer)
  }

  override fun onComplete() {
    result.complete(answer)
  }
}

/**
 * Accumulates the body until it completes, passes [maxBytes], or outlives [bodyTimeout]. The HTTP
 * client calls it serially; the deadline fires on another thread, which is why [subscription] is
 * volatile and every completion goes through the (thread-safe, first-wins) [result] future. On
 * expiry the subscription is cancelled and the body fails with [HttpTimeoutException], which
 * `HttpClient.send` rethrows as such — a stalled or trickling body cannot block the caller forever.
 */
private class LimitingSubscriber(
  private val maxBytes: Int,
  private val bodyTimeout: Duration,
  private val deadline: BodyDeadline,
) : HttpResponse.BodySubscriber<BoundedBytes?> {
  private val result = CompletableFuture<BoundedBytes?>()
  private var buffer: ByteArrayOutputStream? = ByteArrayOutputStream()

  @Volatile
  private var subscription: Flow.Subscription? = null

  @Volatile
  private var disarm: () -> Unit = {}
  private var total = 0L

  override fun getBody(): CompletionStage<BoundedBytes?> = result

  override fun onSubscribe(subscription: Flow.Subscription) {
    this.subscription = subscription
    // The expiry action captures only the future and the subscription, never this subscriber
    // (and so never its buffer); it is disarmed as soon as the body ends.
    val body = result
    disarm = deadline.arm(bodyTimeout) {
      if (body.completeExceptionally(HttpTimeoutException(BODY_TIMED_OUT))) subscription.cancel()
    }
    subscription.request(Long.MAX_VALUE)
  }

  override fun onNext(item: List<ByteBuffer>) {
    val sink = buffer ?: return
    if (result.isDone) return
    for (chunk in item) {
      total += chunk.remaining()
      if (total > maxBytes) {
        subscription?.cancel()
        finish { result.complete(BoundedBytes.TooLarge) }
        return
      }
      val bytes = ByteArray(chunk.remaining())
      chunk.get(bytes)
      sink.write(bytes)
    }
  }

  override fun onError(throwable: Throwable) {
    finish { result.completeExceptionally(throwable) }
  }

  override fun onComplete() {
    val bytes = buffer?.toByteArray() ?: return
    finish { result.complete(BoundedBytes.Ok(bytes)) }
  }

  /** Completes [result] (first completion wins), stops the deadline and drops the buffer. */
  private inline fun finish(complete: () -> Unit) {
    complete()
    disarm()
    buffer = null
  }

  private companion object {
    const val BODY_TIMED_OUT = "Response body not received in time"
  }
}
