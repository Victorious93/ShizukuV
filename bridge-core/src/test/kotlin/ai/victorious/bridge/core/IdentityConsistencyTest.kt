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
        assertEquals("ShizukuV", BridgeIdentity.DISPLAY_NAME)
        assertEquals("com.victorious.shizukuv", BridgeIdentity.APPLICATION_ID)
        assertNotEquals("Shizuku", BridgeIdentity.DISPLAY_NAME)
        assertTrue(read("manager/src/main/res/values/strings.xml").contains("name=\"app_name\" translatable=\"false\">${BridgeIdentity.DISPLAY_NAME}<"))
        assertTrue(read("manager/src/main/AndroidManifest.xml").contains("android:label=\"@string/app_name\""))
    }

    @Test fun launcherIconsAreTheShizukuVStampNotUpstreamArtwork() {
        assertTrue("official stamp source must be kept", File(root, "docs/assets/shizukuv-stamp.jpg").isFile)
        val res = "manager/src/main/res"
        for (d in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"))
            for (n in listOf("ic_launcher.png", "ic_launcher_round.png", "ic_launcher_foreground.png", "ic_launcher_monochrome.png"))
                assertTrue("$res/mipmap-$d/$n", File(root, "$res/mipmap-$d/$n").isFile)
        assertTrue(File(root, "$res/mipmap-anydpi-v26/ic_launcher.xml").isFile)
        assertTrue(File(root, "$res/mipmap-anydpi-v26/ic_launcher_round.xml").isFile)
        val m = read("manager/src/main/AndroidManifest.xml")
        assertTrue(m.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(m.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
    }

    @Test fun themeIsDarkNeonOnly() {
        val colors = read("manager/src/main/res/values/colors.xml").lowercase()
        assertTrue(colors.contains("name=\"neon_background\">#0b0f1a<"))
        assertTrue(colors.contains("name=\"neon_cyan\">#00e5ff<"))
        assertTrue(colors.contains("name=\"neon_magenta\">#ff2bd6<"))
        val themes = read("manager/src/main/res/values/themes.xml")
        assertTrue(themes.contains("Theme.Material3.Dark."))
        assertFalse("no light theme parent", themes.contains("Theme.Material3.Light."))
        assertFalse("no light theme", themes.contains("Theme.Light"))
        assertTrue(themes.contains("<item name=\"android:colorBackground\">@color/neon_background</item>"))
        assertTrue(themes.contains("<item name=\"colorPrimary\">@color/neon_cyan</item>"))
        assertTrue(themes.contains("<item name=\"colorSecondary\">@color/neon_magenta</item>"))
        assertFalse(read("manager/src/main/res/values/styles.xml").contains("Theme.Light"))
        val forbidden = listOf("values-night", "color-night", "drawable-night", "layout-night")
        val res = File(root, "manager/src/main/res")
        assertTrue("night-qualified resource folders mean a light default exists: ${res.list()?.filter { it in forbidden }}",
            res.list().orEmpty().none { it in forbidden })
    }

    @Test fun uiShipsEnglishOnly() {
        val res = File(root, "manager/src/main/res")
        val localeDirs = res.list().orEmpty().filter { Regex("^values-(?!night$|v\\d+$|sw\\d+dp$)[a-zA-Z+].*").matches(it) }
        assertTrue("non-English string folders: $localeDirs", localeDirs.isEmpty())
        assertFalse(read("manager/src/main/AndroidManifest.xml").contains("localeConfig"))
        assertTrue(read("manager/build.gradle").contains("localeFilters += ['en']"))
    }

    @Test fun debugSigningIsFixedAndNeverARealReleaseKey() {
        assertTrue(File(root, "keystore/shizukuv-debug.keystore").isFile)
        val g = read("signing.gradle")
        assertTrue(g.contains("keystore/shizukuv-debug.keystore"))
        assertFalse("release must not fall back to the debug key", g.contains("signingConfigs.sign.storeFile = android.signingConfigs.debug"))
        assertTrue(read("manager/build.gradle").contains("signingConfig signingConfigs.debug"))
        val tracked = root.walkTopDown().onEnter { it.name !in setOf(".git", "build", ".gradle", ".cxx") }
            .filter { it.isFile && (it.extension in setOf("jks", "keystore", "p12", "pfx")) }
            .map { it.relativeTo(root).path }.toList()
        assertEquals("only the public debug keystore may be committed", listOf("keystore/shizukuv-debug.keystore"), tracked)
        assertTrue("signing.properties must stay git-ignored", File(root, ".gitignore").readText().lines().any { it.trim().endsWith("signing.properties") })
    }

    @Test fun apiIsVendoredNoSshSubmodule() {
        assertFalse(File(root, ".gitmodules").exists())
        assertTrue(File(root, "api/UPSTREAM.md").isFile)
    }

    @Test fun apache2LicenseAndUpstreamAttributionKept() {
        assertTrue(read("LICENSE").contains("Apache License"))
        val readme = read("README.md")
        assertTrue(readme.contains("RikkaApps/Shizuku"))
        assertTrue(readme.contains("Apache"))
        assertTrue(readme.contains("RikkaW"))
        assertTrue(File(root, "api/LICENSE").isFile)
    }
}
