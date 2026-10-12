package com.android.settings.safetycenter.ui;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.text.TextUtils;

import androidx.preference.Preference;

import com.android.internal.widget.LockPatternUtils;
import com.android.settings.core.BasePreferenceController;
import com.android.settings.security.ProtectedSpaceSetupActivity;

/** Offers protected space setup in user 0 while the device has none, e.g. after a skip. */
public class ProtectedSpacePrefController extends BasePreferenceController {

    public ProtectedSpacePrefController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    @Override
    public int getAvailabilityStatus() {
        if (mContext.getUserId() != UserHandle.USER_SYSTEM) {
            return DISABLED_FOR_USER;
        }
        try {
            // user 0 is the authority until a protected space exists
            return new LockPatternUtils(mContext).getAlternatesAuthority()
                    == UserHandle.USER_SYSTEM ? AVAILABLE : DISABLED_FOR_USER;
        } catch (RuntimeException e) {
            return DISABLED_FOR_USER;
        }
    }

    @Override
    public boolean handlePreferenceTreeClick(Preference preference) {
        if (!TextUtils.equals(preference.getKey(), getPreferenceKey())) {
            return super.handlePreferenceTreeClick(preference);
        }
        mContext.startActivity(new Intent(mContext, ProtectedSpaceSetupActivity.class));
        return true;
    }
}
