package com.omama.stationalarm.service

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException

/**
 * Minimal reflection helpers. LocationService is behaviour-locked and keeps its decision logic in
 * private members, so the tests drive and observe it through reflection instead of changing the source.
 */
internal object Reflect {

    fun field(cls: Class<*>, name: String): Field {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException("$name on ${cls.name}")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(target: Any, name: String): T = field(target.javaClass, name).get(target) as T

    fun set(target: Any, name: String, value: Any?) {
        field(target.javaClass, name).set(target, value)
    }

    /** Invokes a (private) method by name + arity, unwrapping the reflective exception. */
    fun call(target: Any, name: String, vararg args: Any?): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val m = c.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == args.size && !it.isSynthetic && !it.isBridge
            }
            if (m != null) {
                m.isAccessible = true
                try {
                    return m.invoke(target, *args)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
            c = c.superclass
        }
        throw NoSuchMethodException("$name/${args.size} on ${target.javaClass.name}")
    }
}
