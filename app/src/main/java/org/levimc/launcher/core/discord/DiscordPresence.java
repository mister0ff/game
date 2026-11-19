package org.levimc.launcher.core.discord;

import android.app.Application;

/** Discord Rich Presence is not shipped in StarCat Launcher; this stub keeps the call sites intact. */
public final class DiscordPresence {
    private DiscordPresence() {}
    public static void initialize(Application application) {}
    public static boolean isConfigured() { return false; }
    public static void refresh() {}
    public static void shutdown() {}
}
