package com.android.settings.security;

import android.app.ActivityThread;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.annotation.Nullable;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;

/**
 * Keeps the owner's new screen lock for a few minutes during first setup, so the protected
 * space step that follows doesn't ask for it again. Memory only, user 0 only, first setup only.
 */
public final class SetupOwnerCredential {
    private static final long KEEP_MS = 10 * 60 * 1000;

    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private static LockscreenCredential sCredential;
    private static long sSetAt;
    // a timer only clears the credential it was set for
    private static int sGeneration;

    private SetupOwnerCredential() {}

    public static synchronized void remember(LockscreenCredential credential, int userId) {
        clearLocked();
        if (userId != UserHandle.USER_SYSTEM || credential == null || !credential.isPin()
                || credential.size() < LockPatternUtils.MIN_NEW_PIN_SIZE || isSetupComplete()) {
            return;
        }
        sCredential = credential.duplicate();
        sSetAt = SystemClock.elapsedRealtime();
        // dropped on time even if the protected space step never runs
        final int generation = ++sGeneration;
        sHandler.postDelayed(() -> expire(generation), KEEP_MS);
    }

    private static synchronized void expire(int generation) {
        if (generation == sGeneration) {
            clearLocked();
        }
    }

    /** The kept credential, handed over once; the caller zeroizes it. */
    @Nullable
    public static synchronized LockscreenCredential take() {
        LockscreenCredential c = sCredential;
        sCredential = null;
        sGeneration++;
        boolean keep = false;
        try {
            keep = c != null && SystemClock.elapsedRealtime() - sSetAt <= KEEP_MS
                    && !isSetupComplete();
        } finally {
            if (c != null && !keep) {
                c.zeroize();
            }
        }
        return keep ? c : null;
    }

    private static void clearLocked() {
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
