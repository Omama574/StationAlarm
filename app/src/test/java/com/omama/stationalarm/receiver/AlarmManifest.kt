package com.omama.stationalarm.receiver

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the app's own `src/main/AndroidManifest.xml` as plain XML.
 *
 * Robolectric cannot be used for this: the manifest AGP hands to unit tests
 * (`packaged_manifests/debugUnitTest`) only contains the *library* components, not the app's own
 * services/receivers/permissions, so `PackageManager` would not know about them.
 */
internal object AlarmManifest {

    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    const val TOOLS_NS = "http://schemas.android.com/tools"

    private val file: File by lazy {
        listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from working dir ${File(".").absoluteFile}")
    }

    val root: Element by lazy {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE && it.nodeName == tag }
            .map { it as Element }

    fun Element.android(attr: String): String? =
        getAttributeNS(ANDROID_NS, attr).takeIf { it.isNotEmpty() }

    val application: Element get() = root.children("application").single()

    /** android:name of every <uses-permission> that is NOT marked tools:node="remove". */
    val requestedPermissions: Set<String>
        get() = root.children("uses-permission")
            .filter { it.getAttributeNS(TOOLS_NS, "node") != "remove" }
            .mapNotNull { it.android("name") }.toSet()

    /** android:name of every <uses-permission> carrying tools:node="remove". */
    val removedPermissions: Set<String>
        get() = root.children("uses-permission")
            .filter { it.getAttributeNS(TOOLS_NS, "node") == "remove" }
            .mapNotNull { it.android("name") }.toSet()

    fun component(kind: String, name: String): Element =
        application.children(kind).firstOrNull { it.android("name") == name }
            ?: error("<$kind android:name=\"$name\"> is not declared in AndroidManifest.xml")

    /** android:name of every activity/service/receiver/provider declared exported="true". */
    val exportedComponents: Set<String>
        get() = listOf("activity", "service", "receiver", "provider")
            .flatMap { application.children(it) }
            .filter { it.android("exported") == "true" }
            .mapNotNull { it.android("name") }.toSet()

    fun intentFilterActions(component: Element): Set<String> =
        component.children("intent-filter")
            .flatMap { it.children("action") }
            .mapNotNull { it.android("name") }.toSet()
}
