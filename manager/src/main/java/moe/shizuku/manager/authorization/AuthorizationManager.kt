package moe.shizuku.manager.authorization

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Parcel
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Manifest
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.ShizukuSystemApis
import rikka.shizuku.server.ServerConstants
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.Shizuku
import java.util.*

object AuthorizationManager {

    private const val FLAG_ALLOWED = 1 shl 1
    private const val FLAG_DENIED = 1 shl 2
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

    private fun getApplications(userId: Int): List<PackageInfo> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            data.writeInt(userId)
            try {
                Shizuku.getBinder()!!.transact(ServerConstants.BINDER_TRANSACTION_getApplications, data, reply, 0)
            } catch (e: Throwable) {
                throw RuntimeException(e)
            }
            reply.readException()
            @Suppress("UNCHECKED_CAST")
            (ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<PackageInfo>).list!!
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun getPackages(): List<PackageInfo> {
        val packages: MutableList<PackageInfo> = ArrayList()
        if (Shizuku.isPreV11() || (Shizuku.getVersion() == 11 && Shizuku.getServerPatchVersion() < 3)) {
            val allPackages: MutableList<PackageInfo> = ArrayList()
            for (user in ShizukuSystemApis.getUsers(useCache = false)) {
                try {
                    allPackages.addAll(ShizukuSystemApis.getInstalledPackages((PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS).toLong(), user.id))
                } catch (e: Throwable) {
                    LOGGER.w(e, "getInstalledPackages")
                }
            }
            for (pi in allPackages) {
                if (BuildConfig.APPLICATION_ID == pi.packageName) continue
                if (pi.applicationInfo?.metaData?.getBoolean(ai.victorious.bridge.core.BridgeIdentity.META_CLIENT_SUPPORT) != true) continue
                if (pi.requestedPermissions?.contains(Manifest.permission.LEGACY_API_V1) != true) continue

                packages.add(pi)
            }
        } else {
            packages.addAll(getApplications(-1))
        }
        return packages
    }

    /** Snapshot of the service's grant store, refreshed with the app list. Display only; the service decides access. */
    @Volatile
    var grantsByUid: Map<Int, moe.shizuku.manager.bridge.GrantInfo> = emptyMap()

    fun refreshGrants() {
        grantsByUid = moe.shizuku.manager.bridge.BridgeAdmin.grants().associateBy { it.uid }
    }

    fun accessSummary(context: android.content.Context, uid: Int): String {
        val legacyFlag = try { (Shizuku.getFlagsForUid(uid, MASK_PERMISSION) and FLAG_ALLOWED) == FLAG_ALLOWED } catch (_: Throwable) { false }
        val g = grantsByUid[uid]
        val legacy = legacyFlag || g?.capabilities?.contains(ai.victorious.bridge.core.Capability.LEGACY_FULL) == true
        if (legacy) return context.getString(moe.shizuku.manager.R.string.apps_access_legacy) + expiry(context, g)
        if (g == null) return context.getString(moe.shizuku.manager.R.string.apps_access_none)
        val caps = g.capabilities.joinToString { it.id }
        return context.getString(moe.shizuku.manager.R.string.apps_access_scoped, caps) + " (" + (g.mode?.id ?: "?") + ")" + expiry(context, g)
    }

    private fun expiry(context: android.content.Context, g: moe.shizuku.manager.bridge.GrantInfo?): String =
        g?.remainingMs?.let { ", " + context.getString(moe.shizuku.manager.R.string.apps_access_expires, moe.shizuku.manager.bridge.formatDuration(it)) } ?: ""

    fun granted(packageName: String, uid: Int): Boolean {
        if (grantsByUid.containsKey(uid)) return true
        return if (Shizuku.isPreV11()) {
            ShizukuSystemApis.checkPermission(Manifest.permission.LEGACY_API_V1, packageName, uid / 100000) == PackageManager.PERMISSION_GRANTED
        } else {
            (Shizuku.getFlagsForUid(uid, MASK_PERMISSION) and FLAG_ALLOWED) == FLAG_ALLOWED
        }
    }

    fun grant(packageName: String, uid: Int) {
        if (Shizuku.isPreV11()) {
            ShizukuSystemApis.grantRuntimePermission(packageName, Manifest.permission.LEGACY_API_V1, uid / 100000)
        } else {
            Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)
        }
    }

    fun revoke(packageName: String, uid: Int) {
        if (Shizuku.isPreV11()) {
            ShizukuSystemApis.revokeRuntimePermission(packageName, Manifest.permission.LEGACY_API_V1, uid / 100000)
        } else {
            Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, 0)
        }
    }
}
