package moe.shizuku.manager;

import moe.shizuku.manager.utils.MultiLocaleEntity;

public class Helps {

    public static final MultiLocaleEntity ADB = new MultiLocaleEntity();
    public static final MultiLocaleEntity ADB_ANDROID11 = new MultiLocaleEntity();
    public static final MultiLocaleEntity APPS = new MultiLocaleEntity();
    public static final MultiLocaleEntity HOME = new MultiLocaleEntity();
    public static final MultiLocaleEntity DOWNLOAD = new MultiLocaleEntity();
    public static final MultiLocaleEntity SUI = new MultiLocaleEntity();
    public static final MultiLocaleEntity RISH = new MultiLocaleEntity();
    public static final MultiLocaleEntity ADB_PERMISSION = new MultiLocaleEntity();

    static {
        ADB.put("en", "https://shizuku.rikka.app/guide/setup/");

        ADB_ANDROID11.put("en", "https://shizuku.rikka.app/guide/setup/");

        APPS.put("en", "https://shizuku.rikka.app/apps/");

        HOME.put("en", "https://shizuku.rikka.app/");

        DOWNLOAD.put("en", "https://shizuku.rikka.app/download/");

        ADB_PERMISSION.put("en", "https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging-start-by-connecting-to-a-computer-the-permission-of-adb-is-limited");

        SUI.put("en", "https://github.com/RikkaApps/Sui");

        RISH.put("en", "https://github.com/RikkaApps/Shizuku-API/tree/master/rish");
    }
}
