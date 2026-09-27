package com.gitlab.eclipse.codesuggestions.tutorial

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.resources.IProject
import org.eclipse.core.runtime.CoreException
import org.eclipse.core.runtime.QualifiedName
import org.eclipse.ui.preferences.ScopedPreferenceStore

/**
 * Reads and writes the ownership record design §9.2 uses to tell "the project this plugin created
 * for the Tutorial" apart from a same-named project the user made or imported themselves.
 *
 * Ownership is never decided from the project's name or its filesystem path alone (§9.2): both
 * would misidentify a user project that happens to share the name, or a directory reused after a
 * "keep contents" delete. Instead, a random ID is written to two places at creation time — the
 * project's own persistent property and this plugin's (hidden) preference store — plus the
 * project's resolved location URI to the preference store. [isOwned] is true only when a project
 * is open and both recorded values match what is on the project right now.
 *
 * The preference store is injected (same seam as `PublishRecordStore`) so tests use a fake; the
 * project is an interface ([IProject]) and needs no seam of its own.
 */
class DuoTutorialOwnership(private val store: ScopedPreferenceStore = service()) {
  private val logger by lazy { logger<DuoTutorialOwnership>() }

  /**
   * Records [id] and [locationUri] as the current Tutorial project's ownership, persisting both
   * before returning. Replaces whatever was recorded before — design §9.2 keeps one record, the
   * most recent creation.
   *
   * Returns false if persistence failed; the store is put back to its previous values first ([save]
   * throwing must not leave one key updated and the other stale, which would make neither one
   * match a real project).
   */
  fun record(id: String, locationUri: String): Boolean {
    val previousId = store.getString(PreferenceConstants.DUO_TUTORIAL_PROJECT_ID)
    val previousLocation = store.getString(PreferenceConstants.DUO_TUTORIAL_PROJECT_LOCATION)
    return try {
      store.setValue(PreferenceConstants.DUO_TUTORIAL_PROJECT_ID, id)
      store.setValue(PreferenceConstants.DUO_TUTORIAL_PROJECT_LOCATION, locationUri)
      store.save()
      true
    } catch (e: Exception) {
      store.setValue(PreferenceConstants.DUO_TUTORIAL_PROJECT_ID, previousId)
      store.setValue(PreferenceConstants.DUO_TUTORIAL_PROJECT_LOCATION, previousLocation)
      // Type only (A16): the value itself is a random ID and a location URI, neither logged.
      logger.error("GitLab Duo Tutorial ownership record could not be saved: ${e.javaClass.name}")
      false
    }
  }

  /**
   * True when [project] is open, its persistent property [PROPERTY_ID] equals the recorded ID, and
   * its `locationURI` equals the recorded location (design §9.2).
   *
   * A closed project is never inspected: its persistent property cannot be read reliably, so §9.2
   * has the caller refuse before this is ever called for one. Nothing is recorded yet ⇒ not owned,
   * rather than treating a blank comparison as a match. Reading the persistent property can throw
   * [CoreException] (e.g. the project was deleted between the caller's checks and this call); that
   * is treated the same as "does not match" — the safe side per §9.2.
   */
  fun isOwned(project: IProject): Boolean {
    if (!project.isOpen) return false
    val recordedId = store.getString(PreferenceConstants.DUO_TUTORIAL_PROJECT_ID)
    val recordedLocation = store.getString(PreferenceConstants.DUO_TUTORIAL_PROJECT_LOCATION)
    if (recordedId.isNullOrEmpty() || recordedLocation.isNullOrEmpty()) return false
    return try {
      project.getPersistentProperty(PROPERTY_ID) == recordedId &&
        project.locationURI?.toString() == recordedLocation
    } catch (_: CoreException) {
      false
    }
  }

  companion object {
    /** The persistent property written on the Tutorial project at creation time (design §9.2). */
    val PROPERTY_ID: QualifiedName = QualifiedName("com.gitlab.eclipse", "duoTutorialId")
  }
}
