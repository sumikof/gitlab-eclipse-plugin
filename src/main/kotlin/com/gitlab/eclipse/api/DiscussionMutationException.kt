package com.gitlab.eclipse.api

/**
 * L3 failure: HTTP 200, no top-level `errors`, but the mutation payload's own `errors` array is
 * non-empty (design §11.1). Distinct from [GraphQlException] because a GraphQL response that
 * carries a `data` key is classified Ambiguous, whereas an L3 payload rejection is Definite: the
 * server states that it executed the mutation and refused it, so there is no side effect.
 */
class DiscussionMutationException(val messages: List<String>) :
  RuntimeException("GitLab rejected the mutation")
