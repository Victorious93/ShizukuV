package rikka.shizuku.server;

import ai.victorious.bridge.core.BridgeIdentity;

/** Victorious: identity values come from {@link BridgeIdentity}, the single JVM-side source of truth. */
public class ServerConstants {

    public static final int MANAGER_APP_NOT_FOUND = 50;

    public static final String PERMISSION = BridgeIdentity.PERMISSION_LEGACY_API;
    public static final String MANAGER_APPLICATION_ID = BridgeIdentity.APPLICATION_ID;
    public static final String REQUEST_PERMISSION_ACTION = BridgeIdentity.ACTION_REQUEST_PERMISSION;

    public static final int BINDER_TRANSACTION_getApplications = 10001;
}
