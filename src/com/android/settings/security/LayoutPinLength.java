package com.android.settings.security;

import android.content.Context;

import androidx.annotation.Nullable;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;
import com.android.settings.R;

/**
 * Once a protected space exists every PIN has the same number of digits; lock_settings refuses
 * others. This lets the PIN screens say so before saving.
 */
public final class LayoutPinLength {
    private LayoutPinLength() {}

    /** The required number of digits, or 0 without a protected space. */
    public static int get(Context context) {
        try {
            return new LockPatternUtils(context).getLayoutPinLength();
        } catch (RuntimeException e) {
            // the service still checks on save
            return 0;
        }
    }

    /** The message for a PIN of the wrong length, or null. */
    @Nullable
    public static String error(Context context, LockscreenCredential credential) {
        if (!credential.isPin() || credential.size() == 0) {
            return null;
        }
        int length = get(context);
        return length > 0 && credential.size() != length
                ? context.getString(R.string.layout_pin_length, length) : null;
    }
}
