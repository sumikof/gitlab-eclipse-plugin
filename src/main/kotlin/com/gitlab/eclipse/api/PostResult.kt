package com.gitlab.eclipse.api

/** Metadata of a successful (2xx) write, carried to the audit-log point. Body is not parsed. */
data class PostResult(val httpStatus: Int, val correlationId: String?)
