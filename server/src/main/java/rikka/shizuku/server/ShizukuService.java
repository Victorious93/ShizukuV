package rikka.shizuku.server;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_UID;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME;
import static rikka.shizuku.server.ServerConstants.MANAGER_APPLICATION_ID;
import static rikka.shizuku.server.ServerConstants.PERMISSION;

import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.ddm.DdmHandleAppName;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import kotlin.collections.ArraysKt;
import ai.victorious.bridge.core.BridgeIdentity;
import ai.victorious.bridge.core.BridgeProtocol;
import ai.victorious.bridge.core.CallerIdentity;
import moe.shizuku.api.BinderContainer;
import moe.shizuku.server.IBridgeCallback;
import moe.shizuku.common.util.BuildUtils;
import moe.shizuku.common.util.OsUtils;
import moe.shizuku.server.IShizukuApplication;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.DeviceIdleControllerApis;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.PermissionManagerApis;
import rikka.hidden.compat.UserManagerApis;
import rikka.parcelablelist.ParcelableListSlice;
import rikka.rish.RishConfig;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.api.IContentProviderUtils;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.UserHandleCompat;

public class ShizukuService extends Service<ShizukuUserServiceManager, ShizukuClientManager, ShizukuConfigManager> {

    public static void main(String[] args) {
        DdmHandleAppName.setAppName("victorious_bridge_server", 0);
        RishConfig.setLibraryPath(System.getProperty("victorious.bridge.library.path"));

        Looper.prepareMainLooper();
        new ShizukuService();
        Looper.loop();
    }

    private static void waitSystemService(String name) {
        while (ServiceManager.getService(name) == null) {
            try {
                LOGGER.i("service " + name + " is not started, wait 1s.");
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                LOGGER.w(e.getMessage(), e);
            }
        }
    }

    public static ApplicationInfo getManagerApplicationInfo() {
        return PackageManagerApis.getApplicationInfoNoThrow(MANAGER_APPLICATION_ID, 0, 0);
    }

    @SuppressWarnings({"FieldCanBeLocal"})
    private final Handler mainHandler = new Handler(Looper.myLooper());
    //private final Context systemContext = HiddenApiBridge.getSystemContext();
    private final ShizukuClientManager clientManager;
    private final ShizukuConfigManager configManager;
    private final int managerAppId;
    private final BridgeController bridge;
    private final java.util.Set<String> managerSigners;

    public ShizukuService() {
        super();

        HandlerUtil.setMainHandler(mainHandler);

        LOGGER.i("starting server...");

        waitSystemService("package");
        waitSystemService(Context.ACTIVITY_SERVICE);
        waitSystemService(Context.USER_SERVICE);
        waitSystemService(Context.APP_OPS_SERVICE);

        ApplicationInfo ai = getManagerApplicationInfo();
        if (ai == null) {
            System.exit(ServerConstants.MANAGER_APP_NOT_FOUND);
        }

        assert ai != null;
        managerAppId = ai.uid;

        configManager = getConfigManager();
        clientManager = getClientManager();

        // Victorious: pin the manager's signing identity at start. Every manager-only call re-verifies it, so a package
        // that merely shares the manager's name (or a replaced APK signed by someone else) is not treated as the manager.
        managerSigners = new PackageIdentityResolver().resolve(managerAppId) != null
                ? new PackageIdentityResolver().resolve(managerAppId).getSignerDigests() : java.util.Collections.emptySet();
        bridge = new BridgeController(this);
        bridge.start();

        ApkChangedObservers.start(ai.sourceDir, () -> {
            if (getManagerApplicationInfo() == null) {
                LOGGER.w("manager app is uninstalled in user 0, exiting...");
                System.exit(ServerConstants.MANAGER_APP_NOT_FOUND);
            }
        });

        BinderSender.register(this);

        mainHandler.post(() -> {
            sendBinderToClient();
            sendBinderToManager();
        });
    }

    @Override
    public ShizukuUserServiceManager onCreateUserServiceManager() {
        return new ShizukuUserServiceManager();
    }

    @Override
    public ShizukuClientManager onCreateClientManager() {
        return new ShizukuClientManager(getConfigManager());
    }

    @Override
    public ShizukuConfigManager onCreateConfigManager() {
        return new ShizukuConfigManager();
    }

    @Override
    public boolean checkCallerManagerPermission(String func, int callingUid, int callingPid) {
        return isVerifiedManager(callingUid);
    }

    /** Victorious: same app id AND the signing certificate pinned when the service started. Fails closed if none could be read. */
    private boolean isVerifiedManager(int callingUid) {
        if (UserHandleCompat.getAppId(callingUid) != managerAppId) return false;
        if (managerSigners.isEmpty()) return false;
        CallerIdentity now = new PackageIdentityResolver().resolve(callingUid);
        return now != null && now.getSignerDigests().equals(managerSigners);
    }

    @Override
    protected boolean isClientAllowed(ClientRecord clientRecord) {
        // Legacy full access, evaluated per call: either the upstream-style runtime-permission/config route, or an explicit
        // LEGACY_FULL grant in the grant store (which expires, is identity-bound and is revocable).
        return clientRecord.allowed || bridge.getGrants().isLegacyFullActive(clientRecord.uid);
    }

    boolean isLegacyAllowedFor(ClientRecord record) {
        return isClientAllowed(record);
    }

    /** True if the uid still has legacy access through something other than the grant store (runtime permission / config flag). */
    boolean hasLegacyRouteOtherThanGrantStore(int uid) {
        for (ClientRecord r : clientManager.findClients(uid)) {
            if (r.allowed) return true;
        }
        ShizukuConfig.PackageEntry entry = configManager.find(uid);
        return entry != null && entry.isAllowed();
    }

    int removeUserServicesForUid(int uid) {
        int n = 0;
        for (String pkg : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
            n += getUserServiceManager().removeUserServicesForPackage(pkg);
        }
        return n;
    }

    ShizukuClientManager clientManager() {
        return clientManager;
    }

    /** UserInfo.userType exists from API 30; before that the managed-profile flag is the signal. */
    static boolean isManagedProfile(UserInfo userInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return "android.os.usertype.profile.MANAGED".equals(userInfo.userType);
        }
        return (userInfo.flags & UserInfo.FLAG_MANAGED_PROFILE) != 0;
    }

    boolean startManagerActivity(Intent intent, int userId) {
        UserInfo userInfo = UserManagerApis.getUserInfo(userId);
        boolean isWorkProfileUser = userInfo != null && isManagedProfile(userInfo);
        ActivityManagerApis.startActivityNoThrow(intent, null, isWorkProfileUser ? 0 : userId);
        return true;
    }

    void showBridgeAccessConfirmation(int requestCode, @NonNull ClientRecord clientRecord, int userId, String[] caps, String[] roots) {
        ApplicationInfo ai = PackageManagerApis.getApplicationInfoNoThrow(clientRecord.packageName, 0, userId);
        if (ai == null) {
            clientRecord.dispatchRequestPermissionResult(requestCode, false);
            return;
        }
        Intent intent = new Intent(ServerConstants.REQUEST_PERMISSION_ACTION)
                .setPackage(MANAGER_APPLICATION_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                .putExtra("uid", clientRecord.uid)
                .putExtra("pid", clientRecord.pid)
                .putExtra("requestCode", requestCode)
                .putExtra("applicationInfo", ai)
                .putExtra(BridgeProtocol.EXTRA_REQUESTED_CAPS, caps)
                .putExtra(BridgeProtocol.EXTRA_REQUESTED_ROOTS, roots);
        startManagerActivity(intent, userId);
    }

    /** Explicit owner revoke for one uid: ends the grant, closes legacy routes, stops managed processes and user services. */
    boolean revokeEverythingFor(int uid) {
        boolean any = bridge.getGrants().revoke(uid);
        for (ClientRecord r : clientManager.findClients(uid)) {
            if (r.allowed) any = true;
            r.allowed = false;
        }
        ShizukuConfig.PackageEntry entry = configManager.find(uid);
        if (entry != null && entry.isAllowed()) {
            any = true;
            configManager.update(uid, null, ConfigManager.MASK_PERMISSION, 0);
        }
        for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
            try {
                PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(packageName, PackageManager.GET_PERMISSIONS, UserHandleCompat.getUserId(uid));
                if (pi != null && pi.requestedPermissions != null && ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    PermissionManagerApis.revokeRuntimePermission(packageName, PERMISSION, UserHandleCompat.getUserId(uid));
                }
            } catch (Throwable t) {
                LOGGER.w(t, "revoke runtime permission");
            }
        }
        terminateProcessesForUid(uid);
        removeUserServicesForUid(uid);
        return any;
    }

    List<Integer> revokeEverything() {
        List<Integer> uids = new ArrayList<>();
        for (ai.victorious.bridge.core.Grant g : bridge.getGrants().list()) uids.add(g.getUid());
        for (ClientRecord r : new ArrayList<>(clientManager.findClients(-1))) uids.add(r.uid);
        for (ShizukuConfig.PackageEntry e : configEntriesSnapshot()) uids.add(e.uid);
        List<Integer> done = new ArrayList<>();
        for (int uid : new java.util.LinkedHashSet<>(uids)) {
            if (UserHandleCompat.getAppId(uid) == managerAppId) continue;
            revokeEverythingFor(uid);
            done.add(uid);
        }
        bridge.cancelAllOperations(ai.victorious.bridge.core.ErrorCode.REVOKED);
        return done;
    }

    private List<ShizukuConfig.PackageEntry> configEntriesSnapshot() {
        List<ShizukuConfig.PackageEntry> l = new ArrayList<>();
        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            for (PackageInfo pi : PackageManagerApis.getInstalledPackagesNoThrow(0, userId)) {
                if (pi.applicationInfo == null) continue;
                ShizukuConfig.PackageEntry e = configManager.find(pi.applicationInfo.uid);
                if (e != null && !l.contains(e)) l.add(e);
            }
        }
        return l;
    }

    // ---- Victorious controlled capability API (new transaction codes 200+) ----

    @Override
    public Bundle bridgeNegotiate(Bundle clientInfo) {
        return bridge.negotiate(clientManager.findClient(Binder.getCallingUid(), Binder.getCallingPid()));
    }

    @Override
    public void bridgeSubmit(Bundle request, IBridgeCallback callback) {
        bridge.submit(Binder.getCallingUid(), clientManager.findClient(Binder.getCallingUid(), Binder.getCallingPid()), request, callback);
    }

    @Override
    public boolean bridgeCancel(String requestId) {
        return bridge.cancel(Binder.getCallingUid(), requestId);
    }

    @Override
    public void bridgeRequestAccess(int requestCode, String[] capabilities, String[] fileRoots) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        ClientRecord record = clientManager.findClient(callingUid, callingPid);
        if (record == null) {
            throw new IllegalStateException("Not an attached client");
        }
        bridge.requestScopedAccess(record, requestCode, capabilities, fileRoots, UserHandleCompat.getUserId(callingUid));
    }

    @Override
    public Bundle bridgeManage(Bundle command) {
        enforceManagerPermission("bridgeManage");
        return bridge.manage(command);
    }

    private int checkCallingPermission() {
        try {
            return ActivityManagerApis.checkPermission(ServerConstants.PERMISSION,
                    Binder.getCallingPid(),
                    Binder.getCallingUid());
        } catch (Throwable tr) {
            LOGGER.w(tr, "checkCallingPermission");
            return PackageManager.PERMISSION_DENIED;
        }
    }

    @Override
    public boolean checkCallerPermission(String func, int callingUid, int callingPid, @Nullable ClientRecord clientRecord) {
        if (UserHandleCompat.getAppId(callingUid) == managerAppId) {
            return true;
        }
        if (clientRecord == null && checkCallingPermission() == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return false;
    }

    @Override
    public void exit() {
        enforceManagerPermission("exit");
        LOGGER.i("exit");
        bridge.lifecycle("service stopping on request");
        bridge.shutdown();
        System.exit(0);
    }

    @Override
    public void attachUserService(IBinder binder, Bundle options) {
        enforceManagerPermission("func");

        super.attachUserService(binder, options);
    }

    @Override
    public void attachApplication(IShizukuApplication application, Bundle args) {
        if (application == null || args == null) {
            return;
        }

        String requestPackageName = args.getString(ATTACH_APPLICATION_PACKAGE_NAME);
        if (requestPackageName == null) {
            return;
        }
        int apiVersion = args.getInt(ATTACH_APPLICATION_API_VERSION, -1);

        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        boolean isManager;
        ClientRecord clientRecord = null;

        List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(callingUid);
        if (!packages.contains(requestPackageName)) {
            LOGGER.w("Request package " + requestPackageName + "does not belong to uid " + callingUid);
            throw new SecurityException("Request package " + requestPackageName + "does not belong to uid " + callingUid);
        }

        isManager = MANAGER_APPLICATION_ID.equals(requestPackageName);
        if (isManager && !isVerifiedManager(callingUid)) {
            LOGGER.w("package %s claims to be the manager but its signing identity does not match", requestPackageName);
            throw new SecurityException("manager identity mismatch");
        }

        if (clientManager.findClient(callingUid, callingPid) == null) {
            synchronized (this) {
                clientRecord = clientManager.addClient(callingUid, callingPid, application, requestPackageName, apiVersion);
            }
            if (clientRecord == null) {
                LOGGER.w("Add client failed");
                return;
            }
        }

        LOGGER.d("attachApplication: %s %d %d", requestPackageName, callingUid, callingPid);

        int replyServerVersion = ShizukuApiConstants.SERVER_VERSION;
        if (apiVersion == -1) {
            // ShizukuBinderWrapper has adapted API v13 in dev.rikka.shizuku:api 12.2.0, however
            // attachApplication in 12.2.0 is still old, so that server treat the client as pre 13.
            // This finally cause transactRemote fails.
            // So we can pass 12 here to pretend we are v12 server.
            replyServerVersion = 12;
        }

        Bundle reply = new Bundle();
        reply.putInt(BIND_APPLICATION_SERVER_UID, OsUtils.getUid());
        reply.putInt(BIND_APPLICATION_SERVER_VERSION, replyServerVersion);
        reply.putString(BIND_APPLICATION_SERVER_SECONTEXT, OsUtils.getSELinuxContext());
        reply.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, ShizukuApiConstants.SERVER_PATCH_VERSION);
        if (!isManager) {
            reply.putBoolean(BIND_APPLICATION_PERMISSION_GRANTED, isClientAllowed(Objects.requireNonNull(clientRecord)));
            reply.putBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);
        } else {
            try {
                PermissionManagerApis.grantRuntimePermission(MANAGER_APPLICATION_ID,
                        WRITE_SECURE_SETTINGS, UserHandleCompat.getUserId(callingUid));
            } catch (RemoteException e) {
                LOGGER.w(e, "grant WRITE_SECURE_SETTINGS");
            }
            bridge.grantManager(callingUid);
        }
        try {
            application.bindApplication(reply);
        } catch (Throwable e) {
            LOGGER.w(e, "attachApplication");
        }
    }

    @Override
    public void showPermissionConfirmation(int requestCode, @NonNull ClientRecord clientRecord, int callingUid, int callingPid, int userId) {
        ApplicationInfo ai = PackageManagerApis.getApplicationInfoNoThrow(clientRecord.packageName, 0, userId);
        if (ai == null) {
            return;
        }

        PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(MANAGER_APPLICATION_ID, 0, userId);
        UserInfo userInfo = UserManagerApis.getUserInfo(userId);
        boolean isWorkProfileUser = isManagedProfile(userInfo);
        if (pi == null && !isWorkProfileUser) {
            LOGGER.w("Manager not found in non work profile user %d. Revoke permission", userId);
            clientRecord.dispatchRequestPermissionResult(requestCode, false);
            return;
        }

        Intent intent = new Intent(ServerConstants.REQUEST_PERMISSION_ACTION)
                .setPackage(MANAGER_APPLICATION_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                .putExtra("uid", callingUid)
                .putExtra("pid", callingPid)
                .putExtra("requestCode", requestCode)
                .putExtra("applicationInfo", ai);
        bridge.noteLegacyRequest(callingUid, callingPid, requestCode);
        ActivityManagerApis.startActivityNoThrow(intent, null, isWorkProfileUser ? 0 : userId);
    }

    @Override
    public void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, Bundle data) throws RemoteException {
        if (UserHandleCompat.getAppId(Binder.getCallingUid()) != managerAppId) {
            LOGGER.w("dispatchPermissionConfirmationResult called not from the manager package");
            return;
        }

        if (data == null) {
            return;
        }

        boolean allowed = data.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED);
        boolean onetime = data.getBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME);
        if (allowed) {
            // Victorious: approvals now travel through bridgeManage(access.apply), which records mode, capabilities and
            // identity. A bare "allowed" from this old entry point must not create access of unspecified scope.
            LOGGER.w("dispatchPermissionConfirmationResult: ignoring allowed=true; use bridgeManage access.apply");
            return;
        }

        LOGGER.i("dispatchPermissionConfirmationResult: uid=%d, pid=%d, requestCode=%d, allowed=%s, onetime=%s",
                requestUid, requestPid, requestCode, Boolean.toString(allowed), Boolean.toString(onetime));

        List<ClientRecord> records = clientManager.findClients(requestUid);
        List<String> packages = new ArrayList<>();
        if (records.isEmpty()) {
            LOGGER.w("dispatchPermissionConfirmationResult: no client for uid %d was found", requestUid);
        } else {
            for (ClientRecord record : records) {
                packages.add(record.packageName);
                record.allowed = allowed;
                if (record.pid == requestPid) {
                    record.dispatchRequestPermissionResult(requestCode, allowed);
                }
            }
        }

        if (!onetime) {
            configManager.update(requestUid, packages, ConfigManager.MASK_PERMISSION, allowed ? ConfigManager.FLAG_ALLOWED : ConfigManager.FLAG_DENIED);
        }

        if (!onetime && allowed) {
            int userId = UserHandleCompat.getUserId(requestUid);

            for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(requestUid)) {
                PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(packageName, PackageManager.GET_PERMISSIONS, userId);
                if (pi == null || pi.requestedPermissions == null || !ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    continue;
                }

                int deviceId = 0;//Context.DEVICE_ID_DEFAULT
                if (allowed) {
                    PermissionManagerApis.grantRuntimePermission(packageName, PERMISSION, userId);
                } else {
                    PermissionManagerApis.revokeRuntimePermission(packageName, PERMISSION, userId);
                }
            }
        }
    }

    private int  getFlagsForUidInternal(int uid, int mask, boolean allowRuntimePermission) {
        ShizukuConfig.PackageEntry entry = configManager.find(uid);
        if (entry != null) {
            return entry.flags & mask;
        }

        if (allowRuntimePermission && (mask & ConfigManager.MASK_PERMISSION) != 0) {
            int userId = UserHandleCompat.getUserId(uid);
            for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(packageName, PackageManager.GET_PERMISSIONS, userId);
                if (pi == null || pi.requestedPermissions == null || !ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    continue;
                }

                try {
                    if (PermissionManagerApis.checkPermission(PERMISSION, uid) == PackageManager.PERMISSION_GRANTED) {
                        return ConfigManager.FLAG_ALLOWED;
                    }
                } catch (Throwable e) {
                    LOGGER.w("getFlagsForUid");
                }
            }
        }
        return 0;
    }

    @Override
    public int getFlagsForUid(int uid, int mask) {
        if (UserHandleCompat.getAppId(Binder.getCallingUid()) != managerAppId) {
            LOGGER.w("updateFlagsForUid is allowed to be called only from the manager");
            return 0;
        }
        return getFlagsForUidInternal(uid, mask, true);
    }

    @Override
    public void updateFlagsForUid(int uid, int mask, int value) throws RemoteException {
        if (UserHandleCompat.getAppId(Binder.getCallingUid()) != managerAppId) {
            LOGGER.w("updateFlagsForUid is allowed to be called only from the manager");
            return;
        }

        int userId = UserHandleCompat.getUserId(uid);

        if ((mask & ConfigManager.MASK_PERMISSION) != 0) {
            boolean allowed = (value & ConfigManager.FLAG_ALLOWED) != 0;
            boolean denied = (value & ConfigManager.FLAG_DENIED) != 0;

            List<ClientRecord> records = clientManager.findClients(uid);
            if (allowed) {
                try {
                    bridge.getGrants().grant(uid, java.util.Collections.singleton(ai.victorious.bridge.core.Capability.LEGACY_FULL),
                            ai.victorious.bridge.core.GrantMode.UNTIL_REVOKED, java.util.Collections.emptyList());
                } catch (RuntimeException e) {
                    LOGGER.w(e, "grant legacy");
                }
            } else {
                bridge.getGrants().revoke(uid);
                terminateProcessesForUid(uid);
            }
            for (ClientRecord record : records) {
                if (allowed) {
                    // allowance is derived from the grant store on every call; nothing is cached on the record
                } else {
                    record.allowed = false;
                    ActivityManagerApis.forceStopPackageNoThrow(record.packageName, UserHandleCompat.getUserId(record.uid));
                    onPermissionRevoked(record.packageName);
                }
            }

            for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(packageName, PackageManager.GET_PERMISSIONS, userId);
                if (pi == null || pi.requestedPermissions == null || !ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    continue;
                }

                int deviceId = 0;//Context.DEVICE_ID_DEFAULT
                if (allowed) {
                    PermissionManagerApis.grantRuntimePermission(packageName, PERMISSION, userId);
                } else {
                    PermissionManagerApis.revokeRuntimePermission(packageName, PERMISSION, userId);
                }

                // TODO kill user service using
            }
        }

        configManager.update(uid, null, mask, value);
    }

    private void onPermissionRevoked(String packageName) {
        // TODO add runtime permission listener
        getUserServiceManager().removeUserServicesForPackage(packageName);
    }

    private ParcelableListSlice<PackageInfo> getApplications(int userId) {
        List<PackageInfo> list = new ArrayList<>();
        List<Integer> users = new ArrayList<>();
        if (userId == -1) {
            users.addAll(UserManagerApis.getUserIdsNoThrow());
        } else {
            users.add(userId);
        }

        for (int user : users) {
            for (PackageInfo pi : PackageManagerApis.getInstalledPackagesNoThrow(PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS, user)) {
                if (Objects.equals(MANAGER_APPLICATION_ID, pi.packageName)) continue;
                if (pi.applicationInfo == null) continue;

                int uid = pi.applicationInfo.uid;
                int flags = 0;
                ShizukuConfig.PackageEntry entry = configManager.find(uid);
                if (entry != null) {
                    if (entry.packages != null && !entry.packages.contains(pi.packageName))
                        continue;
                    flags = entry.flags & ConfigManager.MASK_PERMISSION;
                }

                if (flags != 0) {
                    list.add(pi);
                } else if (isBridgeClient(pi)) {
                    list.add(pi);
                }
            }

        }
        return new ParcelableListSlice<>(list);
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        //LOGGER.d("transact: code=%d, calling uid=%d", code, Binder.getCallingUid());
        if (code == ServerConstants.BINDER_TRANSACTION_getApplications) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            int userId = data.readInt();
            ParcelableListSlice<PackageInfo> result = getApplications(userId);
            reply.writeNoException();
            result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    /** True for apps that declare the bridge provider (SDK meta-data) or the legacy permission. Used for Binder delivery and listing only. */
    static boolean isBridgeClient(PackageInfo pi) {
        if (pi == null) return false;
        if (pi.applicationInfo != null && pi.applicationInfo.metaData != null
                && pi.applicationInfo.metaData.getBoolean(BridgeIdentity.META_CLIENT_SUPPORT, false)) {
            return true;
        }
        return pi.requestedPermissions != null && ArraysKt.contains(pi.requestedPermissions, PERMISSION);
    }

    void sendBinderToClient() {
        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            sendBinderToClient(this, userId);
        }
    }

    private static void sendBinderToClient(Binder binder, int userId) {
        try {
            for (PackageInfo pi : PackageManagerApis.getInstalledPackagesNoThrow(PackageManager.GET_PERMISSIONS | PackageManager.GET_META_DATA, userId)) {
                if (pi == null)
                    continue;

                if (isBridgeClient(pi)) {
                    sendBinderToUserApp(binder, pi.packageName, userId);
                }
            }
        } catch (Throwable tr) {
            LOGGER.e("exception when call getInstalledPackages", tr);
        }
    }

    void sendBinderToManager() {
        sendBinderToManger(this);
    }

    private static void sendBinderToManger(Binder binder) {
        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            sendBinderToManger(binder, userId);
        }
    }

    static void sendBinderToManger(Binder binder, int userId) {
        sendBinderToUserApp(binder, MANAGER_APPLICATION_ID, userId);
    }

    static void sendBinderToUserApp(Binder binder, String packageName, int userId) {
        sendBinderToUserApp(binder, packageName, userId, true);
    }

    static void sendBinderToUserApp(Binder binder, String packageName, int userId, boolean retry) {
        try {
            DeviceIdleControllerApis.addPowerSaveTempWhitelistApp(packageName, 30 * 1000, userId,
                    316/* PowerExemptionManager#REASON_SHELL */, "shell");
            LOGGER.v("Add %d:%s to power save temp whitelist for 30s", userId, packageName);
        } catch (Throwable tr) {
            LOGGER.e(tr, "Failed to add %d:%s to power save temp whitelist", userId, packageName);
        }

        String name = packageName + BridgeIdentity.PROVIDER_AUTHORITY_SUFFIX;
        IContentProvider provider = null;

        /*
         When we pass IBinder through binder (and really crossed process), the receive side (here is system_server process)
         will always get a new instance of android.os.BinderProxy.

         In the implementation of getContentProviderExternal and removeContentProviderExternal, received
         IBinder is used as the key of a HashMap. But hashCode() is not implemented by BinderProxy, so
         removeContentProviderExternal will never work.

         Luckily, we can pass null. When token is token, count will be used.
         */
        IBinder token = null;

        try {
            provider = ActivityManagerApis.getContentProviderExternal(name, userId, token, name);
            if (provider == null) {
                LOGGER.e("provider is null %s %d", name, userId);
                return;
            }
            if (!provider.asBinder().pingBinder()) {
                LOGGER.e("provider is dead %s %d", name, userId);

                if (retry) {
                    // For unknown reason, sometimes this could happens
                    // Kill Shizuku app and try again could work
                    ActivityManagerApis.forceStopPackageNoThrow(packageName, userId);
                    LOGGER.e("kill %s in user %d and try again", packageName, userId);
                    Thread.sleep(1000);
                    sendBinderToUserApp(binder, packageName, userId, false);
                }
                return;
            }

            if (!retry) {
                LOGGER.e("retry works");
            }

            Bundle extra = new Bundle();
            extra.putParcelable(BridgeIdentity.EXTRA_BINDER, new BinderContainer(binder));

            Bundle reply = IContentProviderUtils.callCompat(provider, null, name, "sendBinder", null, extra);
            if (reply != null) {
                LOGGER.i("send binder to user app %s in user %d", packageName, userId);
            } else {
                LOGGER.w("failed to send binder to user app %s in user %d", packageName, userId);
            }
        } catch (Throwable tr) {
            LOGGER.e(tr, "failed send binder to user app %s in user %d", packageName, userId);
        } finally {
            if (provider != null) {
                try {
                    ActivityManagerApis.removeContentProviderExternal(name, token);
                } catch (Throwable tr) {
                    LOGGER.w(tr, "removeContentProviderExternal");
                }
            }
        }
    }

    // ------ Sui only ------

    @Override
    public void dispatchPackageChanged(Intent intent) throws RemoteException {

    }

    @Override
    public boolean isHidden(int uid) throws RemoteException {
        return false;
    }
}
