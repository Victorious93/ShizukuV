package moe.shizuku.manager.shell;

import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;

import rikka.rish.Rish;
import rikka.rish.RishConfig;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuApiConstants;

public class Shell extends Rish {

    @Override
    public void requestPermission(Runnable onGrantedRunnable) {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onGrantedRunnable.run();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            System.err.println("Permission denied");
            System.err.flush();
            System.exit(1);
        } else {
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    Shizuku.removeRequestPermissionResultListener(this);

                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        onGrantedRunnable.run();
                    } else {
                        System.err.println("Permission denied");
                        System.err.flush();
                        System.exit(1);
                    }
                }
            });
            Shizuku.requestPermission(0);
        }
    }

    /**
     * Victorious: say plainly which privilege this shell runs with, once, on stderr. rish is a legacy full-access
     * client, so the line also names that. Set RISH_QUIET=1 to suppress it (e.g. in scripts that parse stderr).
     */
    private static void announcePrivilege(String callerPackage) {
        if ("1".equals(System.getenv("RISH_QUIET"))) return;
        try {
            int uid = Shizuku.getUid();
            String mode = uid == 0 ? "root" : uid == 2000 ? "ADB (shell)" : "uid " + uid;
            ai.victorious.bridge.sdk.BridgeInfo info = ai.victorious.bridge.sdk.VictoriousBridge.INSTANCE.negotiate();
            String level = info == null ? "unknown" : info.getAccessLevel().name().toLowerCase();
            System.err.println("rish: commands run as " + mode + " through Victorious Bridge; " + callerPackage
                    + " has " + level + " access. (RISH_QUIET=1 hides this line)");
            System.err.flush();
        } catch (Throwable ignored) {
        }
    }

    public static void main(String[] args, String packageName, IBinder binder, Handler handler) {
        RishConfig.init(binder, ShizukuApiConstants.BINDER_DESCRIPTOR, 30000);
        Shizuku.onBinderReceived(binder, packageName);
        Shizuku.addBinderReceivedListenerSticky(() -> {
            int version = Shizuku.getVersion();
            if (version < 12) {
                System.err.println("Rish requires server 12 (running " + version + ")");
                System.err.flush();
                System.exit(1);
            }
            announcePrivilege(packageName);
            new Shell().start(args);
        });
    }
}
