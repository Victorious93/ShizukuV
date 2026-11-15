# Moving from upstream Shizuku

Victorious Bridge is a **separate app**. It installs alongside Shizuku and shares nothing with it.

1. Install Victorious Bridge (`ai.victorious.bridge`). Upstream Shizuku is untouched and can keep running for apps that use it.
2. Start the service from the Bridge (root, wireless debugging, or the adb command it shows). The two services use different process names
   and state files, so starting one never kills the other.
3. Apps that should use the Bridge must be **rebuilt** with the SDK (see COMPATIBILITY). Each asks for access and you approve it again.

**Nothing is transferred automatically.** Authorizations are not copied from Shizuku (they are bound to a different identity and, here, to a
signing key and a duration you choose), and the ADB pairing key is not copied (a private key must not move between installations). Pair again
if you use wireless debugging. To remove upstream afterwards, uninstall it; the Bridge does not depend on it.

Terminal apps: re-export `rish` and `rish_shizuku.dex` from the Bridge (the old dex points at the old package).
