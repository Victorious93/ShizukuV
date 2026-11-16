package moe.shizuku.server;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuServiceConnection;
import moe.shizuku.server.IBridgeCallback;

interface IShizukuService {

    int getVersion() = 2;

    int getUid() = 3;

    int checkPermission(String permission) = 4;

    IRemoteProcess newProcess(in String[] cmd, in String[] env, in String dir) = 7;

    String getSELinuxContext() = 8;

    String getSystemProperty(in String name, in String defaultValue) = 9;

    void setSystemProperty(in String name, in String value) = 10;

    int addUserService(in IShizukuServiceConnection conn, in Bundle args) = 11;

    int removeUserService(in IShizukuServiceConnection conn, in Bundle args) = 12;

    void requestPermission(int requestCode) = 14;

    boolean checkSelfPermission() = 15;

    boolean shouldShowRequestPermissionRationale() = 16;

    void attachApplication(in IShizukuApplication application,in Bundle args) = 17;

    void exit() = 100;

    void attachUserService(in IBinder binder, in Bundle options) = 101;

    oneway void dispatchPackageChanged(in Intent intent) = 102;

    boolean isHidden(int uid) = 103;

    oneway void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, in Bundle data) = 104;

    int getFlagsForUid(int uid, int mask) = 105;

    void updateFlagsForUid(int uid, int mask, int value) = 106;

    // ---- Victorious controlled capability API. New transaction codes only; no existing code changed. ----

    // Capability negotiation: what this service supports and what the caller currently holds.
    Bundle bridgeNegotiate(in Bundle clientInfo) = 200;

    // Submit one controlled operation. The result is delivered once through the callback.
    oneway void bridgeSubmit(in Bundle request, IBridgeCallback callback) = 201;

    // Cancel one of the caller's own requests. False if unknown or finished.
    boolean bridgeCancel(String requestId) = 202;

    // Ask the owner to grant scoped capabilities (shown in the manager). Reply arrives through
    // IShizukuApplication.dispatchRequestPermissionResult with the same requestCode.
    oneway void bridgeRequestAccess(int requestCode, in String[] capabilities, in String[] fileRoots) = 203;

    // Manager-only administration (grants, revoke, activity, policy, approvals, diagnostics).
    Bundle bridgeManage(in Bundle command) = 210;
}
