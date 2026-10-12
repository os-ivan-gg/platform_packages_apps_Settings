package com.android.settings.security;

import android.app.ActivityThread;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.annotation.Nullable;

import com.android.internal.widget.LockscreenCredential;

/**
 * Keeps the owner's new screen lock for a few minutes during first setup, so the protected
 * space step that follows doesn't ask for it again. Memory only, user 0 only, first setup only.
 */
public final class SetupOwnerCredential {
    private static final long KEEP_MS = 10 * 60 * 1000;

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static final Runnable sExpire = SetupOwnerCredential::clear;

    private static LockscreenCredential sCredential;
    private static long sSetAt;

    private SetupOwnerCredential() {}

    public static synchronized void remember(LockscreenCredential credential, int userId) {
        clearLocked();
        if (userId != UserHandle.USER_SYSTEM || credential == null || !credential.isPin()
                || isSetupComplete()) {
            return;
        }
        sCredential = credential.duplicate();
        sSetAt = SystemClock.elapsedRealtime();
        // dropped on time even if the protected space step never runs
        sHandler.postDelayed(sExpire, KEEP_MS);
    }

    public static synchronized void clear() {
        clearLocked();
    }

    /** The kept credential, handed over once; the caller zeroizes it. */
    @Nullable
    public static synchronized LockscreenCredential take() {
        LockscreenCredential c = sCredential;
        sCredential = null;
        sHandler.removeCallbacks(sExpire);
        if (c != null && (SystemClock.elapsedRealtime() - sSetAt > KEEP_MS || isSetupComplete())) {
            c.zeroize();
            return null;
        }
        return c;
    }

    private static void clearLocked() {
        sHandler.removeCallbacks(sExpire);
        if (sCredential != null) {
            sCredential.zeroize();
            sCredential = null;
        }
    }

    private static boolean isSetupComplete() {
        Application app = ActivityThread.currentApplication();
        return app == null || Settings.Secure.getIntForUser(app.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_SYSTEM) != 0;
    }
}
