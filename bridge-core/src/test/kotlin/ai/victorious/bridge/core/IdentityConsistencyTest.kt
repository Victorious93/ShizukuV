package ai.victorious.bridge.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The identity is repeated in places that cannot share a constant (Gradle, manifests, native code, the vendored
 * provider, the starter dex). This test fails the build if any copy drifts, or if an upstream Shizuku identifier
 * sneaks back in - the failure mode of a blind search-and-replace migration.
 */
class IdentityConsistencyTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle").exists() }
    private fun read(path: String) = File(root, path).readText()

    private val upstreamIds = listOf(
        "moe.shizuku.privileged.api", "moe.shizuku.manager.permission", "moe.shizuku.client.V3_SUPPORT",
        "shizuku_server", "com.android.shell/shizuku.json",
    )

    @Test fun gradleSourceOfTruthMatchesKotlinIdentity() {
        val g = read("build.gradle")
        assertTrue(g.contains("bridgeApplicationId = '${BridgeIdentity.APPLICATION_ID}'"))
        assertTrue(g.contains("bridgeDisplayName = '${BridgeIdentity.DISPLAY_NAME}'"))
    }

    @Test fun providerLibraryUsesTheSameIdentity() {
        val p = read("api/provider/src/main/java/rikka/shizuku/ShizukuProvider.java")
        assertTrue(p.contains("\"${BridgeIdentity.APPLICATION_ID}\""))
        assertTrue(p.contains("\"${BridgeIdentity.PERMISSION_LEGACY_API}\""))
        assertTrue(p.contains("\"${BridgeIdentity.EXTRA_BINDER}\""))
        assertTrue(p.contains("\"${BridgeIdentity.PROVIDER_AUTHORITY_SUFFIX}\""))
        assertTrue(read("api/provider/src/main/AndroidManifest.xml").contains(BridgeIdentity.META_CLIENT_SUPPORT))
    }

    @Test fun starterLiteralsMatch() {
        val s = read("starter/src/main/java/moe/shizuku/starter/ServiceStarter.java")
        assertTrue(s.contains("\"${BridgeIdentity.APPLICATION_ID}\""))
        assertTrue(s.contains("\"${BridgeIdentity.EXTRA_BINDER}\""))
        assertTrue(s.contains("\"${BridgeIdentity.PROVIDER_AUTHORITY_SUFFIX}\""))
    }

    @Test fun nativeStarterGetsPackageFromBuildAndUsesDistinctNames() {
        val c = read("manager/src/main/jni/starter.cpp")
        assertTrue(c.contains("BRIDGE_PACKAGE_NAME"))
        assertTrue(c.contains("\"${BridgeIdentity.SERVER_PROCESS_NAME}\""))
        assertTrue(c.contains(BridgeIdentity.STARTER_DONE_MARKER))
        assertTrue(read("manager/build.gradle").contains("-DBRIDGE_PACKAGE_NAME=\${rootProject.ext.bridgeApplicationId}"))
        assertTrue(read("manager/src/main/java/moe/shizuku/manager/starter/StarterActivity.kt").contains(BridgeIdentity.STARTER_DONE_MARKER))
    }

    @Test fun manifestDerivesPermissionsAndAuthorityFromApplicationId() {
        val m = read("manager/src/main/AndroidManifest.xml")
        assertTrue(m.contains("\${applicationId}.permission.MANAGER"))
        assertTrue(m.contains("\${applicationId}.permission.LEGACY_API_V1"))
        assertTrue(m.contains("\${applicationId}${BridgeIdentity.PROVIDER_AUTHORITY_SUFFIX}"))
        assertEquals("${BridgeIdentity.APPLICATION_ID}.permission.MANAGER", BridgeIdentity.PERMISSION_MANAGER)
        assertEquals("${BridgeIdentity.APPLICATION_ID}.permission.LEGACY_API_V1", BridgeIdentity.PERMISSION_LEGACY_API)
    }

    @Test fun shellLoaderTakesPackageFromBuildConfig() {
        assertTrue(read("shell/build.gradle").contains("rootProject.ext.bridgeApplicationId"))
        assertTrue(read("shell/src/main/java/rikka/shizuku/shell/ShizukuShellLoader.java").contains("BuildConfig.BRIDGE_APPLICATION_ID"))
    }

    @Test fun noUpstreamIdentifierRemainsInSources() {
        val dirs = listOf("manager/src", "server/src", "starter/src", "shell/src", "common/src", "api/provider/src", "api/aidl/src",
            "api/server-shared/src", "api/rish/src", "api/api/src", "api/shared/src", "bridge-sdk/src")
        val offenders = ArrayList<String>()
        for (d in dirs) File(root, d).walkTopDown().filter { it.isFile && it.extension in setOf("java", "kt", "xml", "cpp", "h", "gradle", "aidl") }
            .forEach { f -> val t = f.readText(); upstreamIds.filter { it in t }.forEach { offenders += "${f.relativeTo(root)} -> $it" } }
        assertTrue("upstream identifiers found: $offenders", offenders.isEmpty())
    }

    @Test fun identityIsDistinctFromUpstreamSoBothCanBeInstalled() {
        assertNotEquals("moe.shizuku.privileged.api", BridgeIdentity.APPLICATION_ID)
        assertFalse(BridgeIdentity.PERMISSION_MANAGER.startsWith("moe.shizuku"))
        assertFalse(BridgeIdentity.PERMISSION_LEGACY_API.startsWith("moe.shizuku"))
        assertNotEquals("shizuku_server", BridgeIdentity.SERVER_PROCESS_NAME)
        assertNotEquals("/data/user_de/0/com.android.shell/shizuku.json", BridgeIdentity.SERVER_CONFIG_FILE)
        // provider authority = <client package> + suffix, so it can never equal upstream's <package>.shizuku
        assertFalse(BridgeIdentity.PROVIDER_AUTHORITY_SUFFIX.endsWith(".shizuku"))
    }

    @Test fun brandNameIsNotUpstreamsAndStringsAreRebranded() {
        assertFalse(BridgeIdentity.DISPLAY_NAME.contains("Shizuku"))
        assertTrue(read("manager/src/main/res/values/strings.xml").contains("name=\"app_name\" translatable=\"false\">${BridgeIdentity.DISPLAY_NAME}<"))
        assertFalse("upstream launcher artwork must not ship", File(root, "manager/src/main/res/mipmap-xxxhdpi/ic_launcher.png").exists())
    }
}
