package com.android.settings.safetycenter.ui;

import android.content.Context;
import android.provider.Settings;

import com.android.settings.R;
import com.android.settings.core.TogglePreferenceController;
import com.android.settings.ext.ExtSettingControllerHelper;

/** In the protected space: whether the status bar shows its icon. */
public class ProtectedSpaceIndicatorPrefController extends TogglePreferenceController {
    // read by SystemUI's PhoneStatusBarPolicy for this user
    private static final String KEY = "protected_space_indicator";

    public ProtectedSpaceIndicatorPrefController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    @Override
    public int getAvailabilityStatus() {
        return ExtSettingControllerHelper.isProtectedSpaceUser(mContext)
                ? AVAILABLE : DISABLED_FOR_USER;
    }

    @Override
    public boolean isChecked() {
        return Settings.Secure.getInt(mContext.getContentResolver(), KEY, 1) != 0;
    }

    @Override
    public boolean setChecked(boolean isChecked) {
        return Settings.Secure.putInt(mContext.getContentResolver(), KEY, isChecked ? 1 : 0);
    }

    @Override
    public int getSliceHighlightMenuRes() {
        return R.string.menu_key_safety_center;
    }
}
