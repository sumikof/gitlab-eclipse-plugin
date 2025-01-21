package com.gitlab.eclipse.di

import com.gitlab.eclipse.di.utils.ServiceInstance
import com.gitlab.eclipse.utils.logger

class DIContainer {
  private val logger by lazy { logger<DIContainer>() }
  private val registry: MutableMap<Class<*>, ServiceInstance> = mutableMapOf()

  fun <T> getService(type: Class<T>): T {
    val service = registry[type]
      ?: error("No service registered for ${type.simpleName}.")

    return try {
      type.cast(service.get())
    } catch (e: ClassCastException) {
      error("Service of type ${service.javaClass.simpleName} is not of type ${type.simpleName}.")
    }
  }

  fun register(type: Class<*>, service: ServiceInstance) {
    if (registry.containsKey(type)) {
      return logger.warn("Already registered instance for type ${type.simpleName}. Skipping.")
    }

    registry[type] = service
  }
}
