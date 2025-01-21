package com.gitlab.eclipse.di

import com.gitlab.eclipse.di.annotations.Factory
import com.gitlab.eclipse.di.annotations.Service
import com.gitlab.eclipse.di.annotations.SingletonFactory
import com.gitlab.eclipse.di.utils.EagerServiceInstance
import com.gitlab.eclipse.di.utils.LazyServiceInstance
import com.gitlab.eclipse.di.utils.Loading
import org.reflections.Reflections

class DIModule(pkgName: String, private val container: DIContainer) {
  init {
    val reflections = Reflections(pkgName)

    val services = reflections.getTypesAnnotatedWith(Service::class.java)
    services.registerServices()

    val factories = reflections.getTypesAnnotatedWith(Factory::class.java)
    factories.registerFactories()
  }

  // TODO: Refactor, actually make resilient
  private fun Set<Class<*>>.registerServices() {
    val (lazyServiceClasses, eagerServiceClasses) = partition {
      it.getAnnotation(Service::class.java).loading == Loading.LAZY
    }

    lazyServiceClasses.forEach {
      container.register(it, LazyServiceInstance { it.getDeclaredConstructor().newInstance() })
    }

    eagerServiceClasses.forEach {
      container.register(it, EagerServiceInstance(it.getDeclaredConstructor().newInstance()))
    }
  }

  // TODO: Refactor, actually make resilient
  private fun Set<Class<*>>.registerFactories() {
    val (lazyFactoryClasses, eagerFactoryClasses) = partition {
      it.getAnnotation(Factory::class.java).loading == Loading.LAZY
    }

    lazyFactoryClasses.forEach {
      val createMethod = it.methods.firstOrNull { method -> method.name == "create" && method.parameterCount == 0 }
        ?: error("${it.simpleName} does not have a create method that takes no parameters.")

      container.register(
        type = createMethod.returnType,
        service = LazyServiceInstance {
          (it.getDeclaredConstructor().newInstance() as SingletonFactory<Any>).create()
        }
      )
    }

    eagerFactoryClasses.forEach {
      val createMethod = it.methods.firstOrNull { method -> method.name == "create" && method.parameterCount == 0 }
        ?: error("${it.simpleName} does not have a create method that takes no parameters.")

      container.register(
        type = createMethod.returnType,
        service = EagerServiceInstance((it.getDeclaredConstructor().newInstance() as SingletonFactory<Any>).create())
      )
    }
  }
}
