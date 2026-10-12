package com.android.settings.security;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.IdRes;
import androidx.annotation.Nullable;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;
import com.android.settings.R;
import com.android.settings.SetupWizardUtils;
import com.google.android.setupcompat.template.FooterBarMixin;
import com.google.android.setupcompat.template.FooterButton;
import com.google.android.setupdesign.GlifLayout;
import com.google.android.setupdesign.util.ThemeHelper;

/**
 * First-setup step that creates the protected space: its PIN, the duress PIN and password and
 * an optional reboot PIN. Skipping leaves the phone as stock. Only works before the device is
 * provisioned; the service checks that too.
 */
public class ProtectedSpaceSetupActivity extends Activity {
    private static final String TAG = "ProtectedSpaceSetup";

    private static final int[] INPUTS = {
            R.id.owner_pin, R.id.protected_pin, R.id.protected_pin_confirmation,
            R.id.duress_pin, R.id.duress_pin_confirmation, R.id.duress_password,
            R.id.duress_password_confirmation, R.id.reboot_pin,
    };

    private FooterButton mSetUp;
    private boolean mWorking;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setTheme(SetupWizardUtils.getTheme(this, getIntent()));
        ThemeHelper.trySetDynamicColor(this);
        // skipping and finishing both move setup on
        setResult(RESULT_OK);

        if (getUserId() != 0 || Settings.Global.getInt(getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0) {
            finish();
            return;
        }

        setContentView(R.layout.protected_space_setup);
        GlifLayout layout = requireViewById(R.id.glif_layout);
        layout.setHeaderText(R.string.protected_space_title);
        layout.setDescriptionText(R.string.protected_space_description);
        DuressPasswordActivity.adjustDescriptionStyle(layout);

        FooterBarMixin footerBar = layout.getMixin(FooterBarMixin.class);
        mSetUp = new FooterButton.Builder(this)
                .setText(R.string.protected_space_set_up)
                .setButtonType(FooterButton.ButtonType.NEXT)
                .setListener(v -> setUp())
                .setTheme(com.google.android.setupdesign.R.style.SudGlifButton_Primary)
                .build();
        footerBar.setPrimaryButton(mSetUp);
        footerBar.setSecondaryButton(new FooterButton.Builder(this)
                .setText(R.string.protected_space_skip)
                .setButtonType(FooterButton.ButtonType.SKIP)
                .setListener(v -> finish())
                .setTheme(com.google.android.setupdesign.R.style.SudGlifButton_Secondary)
                .build());

        // typed credentials stay out of the saved instance state
        for (int id : INPUTS) {
            requireViewById(id).setSaveEnabled(false);
        }
    }

    @Override
    protected void onDestroy() {
        if (!isChangingConfigurations()) {
            for (int id : INPUTS) {
                EditText ed = findViewById(id);
                if (ed != null) {
                    ed.getText().clear();
                }
            }
        }
        super.onDestroy();
    }

    private String text(@IdRes int id) {
        return ((EditText) requireViewById(id)).getText().toString();
    }

    private boolean checkPin(@IdRes int id) {
        EditText ed = requireViewById(id);
        String s = ed.getText().toString();
        if (s.length() < 6 || !s.chars().allMatch(Character::isDigit)) {
            ed.setError(getText(R.string.protected_space_too_short));
            return false;
        }
        return true;
    }

    private boolean checkConfirmation(@IdRes int id, @IdRes int confirmationId) {
        if (!text(id).equals(text(confirmationId))) {
            ((EditText) requireViewById(confirmationId)).setError(
                    getText(R.string.protected_space_mismatch));
            return false;
        }
        return true;
    }

    private void setUp() {
        if (mWorking) {
            return;
        }
        boolean ok = checkPin(R.id.owner_pin) & checkPin(R.id.protected_pin)
                & checkPin(R.id.duress_pin);
        if (text(R.id.duress_password).isEmpty()) {
            ((EditText) requireViewById(R.id.duress_password)).setError(
                    getText(R.string.protected_space_too_short));
            ok = false;
        }
        boolean withReboot = !text(R.id.reboot_pin).isEmpty();
        if (withReboot) {
            ok &= checkPin(R.id.reboot_pin);
        }
        ok &= checkConfirmation(R.id.protected_pin, R.id.protected_pin_confirmation);
        ok &= checkConfirmation(R.id.duress_pin, R.id.duress_pin_confirmation);
        ok &= checkConfirmation(R.id.duress_password, R.id.duress_password_confirmation);
        if (!ok) {
            return;
        }

        LockscreenCredential owner = LockscreenCredential.createPin(text(R.id.owner_pin));
        LockscreenCredential protectedPin =
                LockscreenCredential.createPin(text(R.id.protected_pin));
        LockscreenCredential duressPin = LockscreenCredential.createPin(text(R.id.duress_pin));
        LockscreenCredential duressPassword =
                LockscreenCredential.createPassword(text(R.id.duress_password));
        LockscreenCredential reboot = withReboot
                ? LockscreenCredential.createPin(text(R.id.reboot_pin))
                : LockscreenCredential.createNone();
        mWorking = true;
        mSetUp.setEnabled(false);
        mSetUp.setText(this, R.string.protected_space_working);
        LockPatternUtils lpu = new LockPatternUtils(this);
        AsyncTask.execute(() -> {
            Exception error = null;
            try {
                lpu.provisionProtectedSpace(owner, protectedPin, duressPin, duressPassword,
                        reboot);
            } catch (Exception e) {
                Log.e(TAG, "provisionProtectedSpace failed", e);
                error = e;
            } finally {
                owner.zeroize();
                protectedPin.zeroize();
                duressPin.zeroize();
                duressPassword.zeroize();
                reboot.zeroize();
            }
            final Exception result = error;
            runOnUiThread(() -> onSetUpDone(result));
        });
    }

    private void onSetUpDone(@Nullable Exception error) {
        mWorking = false;
        if (isDestroyed()) {
            return;
        }
        if (error == null) {
            Toast.makeText(this, R.string.protected_space_done, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        mSetUp.setEnabled(true);
        mSetUp.setText(this, R.string.protected_space_set_up);
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.protected_space_failed,
                        String.valueOf(error.getMessage())))
                .setNeutralButton(R.string.duress_pwd_error_dialog_dismiss, null)
                .show();
    }
}
