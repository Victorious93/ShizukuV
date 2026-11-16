package ai.victorious.bridge.core

/**
 * Single source of truth for the Victorious Bridge identity on the JVM side.
 *
 * Every other place that must repeat these strings (Gradle `bridgeApplicationId`, manifests, the
 * native starter, the vendored provider) is checked against this object by `IdentityConsistencyTest`,
 * so a partial rename fails the build instead of failing on a device.
 *
 * This identity is deliberately *not* upstream Shizuku's: upstream's README forbids reusing its
 * application id, app name, icons or `moe.shizuku.manager.permission.*` permission names. Clients
 * built against upstream's `ShizukuProvider` therefore cannot talk to this service; see
 * docs/COMPATIBILITY.md. The AIDL package `moe.shizuku.server` and the `rikka.shizuku` API package are
 * wire-protocol names, not branding, and are intentionally unchanged.
 */
object BridgeIdentity {
    const val DISPLAY_NAME = "Victorious Bridge"
    const val APPLICATION_ID = "ai.victorious.bridge"

    /** Signature-level permission held only by the manager itself. */
    const val PERMISSION_MANAGER = "$APPLICATION_ID.permission.MANAGER"

    /**
     * Runtime permission that marks an app as having asked for (and the user having granted)
     * LEGACY full access. Scoped capability grants never use this permission.
     */
    const val PERMISSION_LEGACY_API = "$APPLICATION_ID.permission.LEGACY_API_V1"
    const val PERMISSION_GROUP = "$APPLICATION_ID.permission-group.API"

    const val ACTION_REQUEST_PERMISSION = "$APPLICATION_ID.intent.action.REQUEST_PERMISSION"
    /** Manifest meta-data (boolean) a client declares so the manager lists it. */
    const val META_CLIENT_SUPPORT = "$APPLICATION_ID.client.SUPPORT"

    const val ACTION_BRIDGE = "$APPLICATION_ID.action.BRIDGE"
    const val EXTRA_BINDER = "$APPLICATION_ID.intent.extra.BINDER"

    /** Suffix appended to a *client's* package name to form its binder-receiving provider authority. */
    const val PROVIDER_AUTHORITY_SUFFIX = ".victoriousbridge"

    const val SERVER_PROCESS_NAME = "victorious_bridge_server"
    const val STARTER_DONE_MARKER = "info: victorious_bridge_starter exit with 0"
    const val SERVER_CONFIG_FILE = "/data/user_de/0/com.android.shell/victorious_bridge.json"
    const val SERVER_GRANTS_FILE = "/data/user_de/0/com.android.shell/victorious_bridge_grants.json"
    const val SERVER_ACTIVITY_FILE = "/data/user_de/0/com.android.shell/victorious_bridge_activity.jsonl"

    /** Semantic version of the controlled-API contract exposed to clients (bumped on breaking change). */
    const val BRIDGE_API_VERSION = 1
}
