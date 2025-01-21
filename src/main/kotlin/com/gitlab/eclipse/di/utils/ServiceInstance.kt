package com.gitlab.eclipse.di.utils

sealed interface ServiceInstance {
  fun get(): Any
}

class LazyServiceInstance(
  private val initializer: () -> Any
) : ServiceInstance {
  private var instance: Any? = null

  override fun get(): Any {
    if (instance == null) {
      instance = initializer.invoke()
    }

    return instance!!
  }
}

class EagerServiceInstance(private val instance: Any) : ServiceInstance {
  override fun get() = instance
}
