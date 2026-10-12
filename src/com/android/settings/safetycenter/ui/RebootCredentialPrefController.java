package com.android.settings.safetycenter.ui;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import androidx.preference.Preference;

import com.android.settings.core.BasePreferenceController;
import com.android.settings.ext.ExtSettingControllerHelper;
import com.android.settings.security.RebootCredentialMainActivity;

public class RebootCredentialPrefController extends BasePreferenceController {

    public RebootCredentialPrefController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    @Override
    public int getAvailabilityStatus() {
        return ExtSettingControllerHelper.getAlternatesSettingAvailability(mContext);
    }

    @Override
    public boolean handlePreferenceTreeClick(Preference preference) {
        if (!TextUtils.equals(preference.getKey(), getPreferenceKey())) {
            return super.handlePreferenceTreeClick(preference);
        }
        mContext.startActivity(new Intent(mContext, RebootCredentialMainActivity.class));
        return true;
    }
}
