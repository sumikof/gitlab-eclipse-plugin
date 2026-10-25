package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.ci.actions.normalizeInstanceUrl

/**
 * One structured audit line for the Eclipse Error Log — the READ analog of writeAuditMessage
 * (design §16). NEVER contains a token or response body (it has no parameter that could carry
 * one). [outcome] is a short read-verb tag (e.g. "connectionRejected", "http", "timeout",
 * "io", "unexpected"); http status + correlation id are appended only when present.
 */
fun readAuditMessage(
  action: String,
  instanceUrl: String,
  projectId: Long?,
  jobId: Long,
  outcome: String,
  httpStatus: Int? = null,
  correlationId: String? = null,
): String = buildString {
  append("ciReadAction action=").append(action)
  append(" instanceUrl=").append(normalizeInstanceUrl(instanceUrl))
  append(" projectId=").append(projectId)
  append(" jobId=").append(jobId)
  append(" outcome=").append(outcome)
  httpStatus?.let { append(" httpStatus=").append(it) }
  correlationId?.let { append(" correlationId=").append(it) }
}

/**
 * Audit line for the job log viewer. Unlike [readAuditMessage] it has no instance URL, and nothing in it can carry
 * a URL, a file path, log text or a credential: only fixed-vocabulary tags, numeric ids and the exception class.
 */
fun jobLogAuditMessage(
  action: String,
  projectId: Long?,
  jobId: Long,
  outcome: String,
  httpStatus: Int? = null,
  correlationId: String? = null,
  exceptionClass: String? = null,
): String = buildString {
  append("ciReadAction action=").append(action)
  append(" projectId=").append(projectId)
  append(" jobId=").append(jobId)
  append(" outcome=").append(outcome)
  httpStatus?.let { append(" httpStatus=").append(it) }
  correlationId?.let { append(" correlationId=").append(it) }
  exceptionClass?.let { append(" exceptionClass=").append(it) }
}
