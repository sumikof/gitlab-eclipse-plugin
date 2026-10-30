package com.gitlab.eclipse.api

/** Metadata of a successful (2xx) write, carried to the audit-log point. Body is not parsed. */
data class PostResult(val httpStatus: Int, val correlationId: String?)

/** A successful (2xx) write and its parsed response body; [body] is null when the body did not parse. */
data class PostedObject<T>(val result: PostResult, val body: T?)
