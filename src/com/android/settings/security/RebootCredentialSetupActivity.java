package com.android.settings.security;

import static com.android.internal.widget.LockDomain.Secondary;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.PasswordValidationError;
import com.android.settings.R;
import com.android.settings.password.ChooseLockPassword;
import com.android.settings.password.ChooseLockPassword.ChooseLockPasswordFragment.PasswordValidationErrorConverter;
import com.google.android.setupcompat.template.FooterBarMixin;
import com.google.android.setupcompat.template.FooterButton;
import com.google.android.setupdesign.GlifLayout;

import java.util.List;
import java.util.Objects;

public class RebootCredentialSetupActivity extends DuressPasswordActivity {
    static final String TAG = RebootCredentialSetupActivity.class.getSimpleName();
    static final String EXTRA_USER_CREDENTIAL = "user_credential";
    static final String EXTRA_TITLE_TEXT = "title";
    private boolean isUpdate;
    private boolean hasSecondFactor;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!getLockPatternUtils().isAlternatesAuthority()) {
            finish();
            return;
        }

        setContentView(R.layout.reboot_credential_setup);

        GlifLayout layout = requireViewById(R.id.glif_layout);
        int headerText = getIntent().getExtras().getNumber(EXTRA_TITLE_TEXT);
        isUpdate = headerText == R.string.reboot_cred_action_update;
        layout.setHeaderText(headerText);
        adjustDescriptionStyle(layout);

        FooterBarMixin footerBar = layout.getMixin(FooterBarMixin.class);
        {
            var b = new FooterButton.Builder(this);
            b.setText(isUpdate ? R.string.duress_pwd_update_button : R.string.duress_pwd_add_button);
            b.setButtonType(FooterButton.ButtonType.DONE);
            b.setListener(v -> save());
            b.setTheme(com.google.android.setupdesign.R.style.SudGlifButton_Primary);
            footerBar.setPrimaryButton(b.build());
        }
        {
            var b = new FooterButton.Builder(this);
            b.setText(R.string.duress_pwd_cancel_button);
            b.setButtonType(FooterButton.ButtonType.CANCEL);
            b.setListener(v -> finish());
            b.setTheme(com.google.android.setupdesign.R.style.SudGlifButton_Secondary);
            footerBar.setSecondaryButton(b.build());
        }

        RadioGroup type = requireViewById(R.id.credential_type);
        // with a protected space the lock screen only takes a PIN of the shared length
        if (LayoutPinLength.get(this) > 0) {
            type.setVisibility(View.GONE);
        }
        type.setOnCheckedChangeListener((group, checkedId) -> updateInputType());
        updateInputType();

        // the collision check against the second-factor PIN compares in memory, so it's needed
        hasSecondFactor = getLockPatternUtils().getCredentialTypeForUser(getUserId(), Secondary)
                != CREDENTIAL_TYPE_NONE;
        int v = hasSecondFactor ? View.VISIBLE : View.GONE;
        requireViewById(R.id.second_factor_title).setVisibility(v);
        requireViewById(R.id.second_factor_input).setVisibility(v);

        // typed credentials stay out of the saved instance state
        for (int id : INPUTS) {
            requireViewById(id).setSaveEnabled(false);
        }
    }

    private static final int[] INPUTS = {
            R.id.credential_input, R.id.credential_input_confirmation, R.id.second_factor_input,
    };

    @Override
    protected void onDestroy() {
        if (!isChangingConfigurations()) {
            getIntent().removeExtra(EXTRA_USER_CREDENTIAL);
            for (int id : INPUTS) {
                EditText ed = findViewById(id);
                if (ed != null) {
                    ed.getText().clear();
                }
            }
        }
        super.onDestroy();
    }

    private boolean isPin() {
        RadioGroup type = requireViewById(R.id.credential_type);
        return type.getCheckedRadioButtonId() == R.id.type_pin;
    }

    private void updateInputType() {
        boolean pin = isPin();
        int inputType = pin
                ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        EditText main = requireViewById(R.id.credential_input);
        EditText confirmation = requireViewById(R.id.credential_input_confirmation);
        main.setInputType(inputType);
        confirmation.setInputType(inputType);
        main.setHint(pin ? R.string.reboot_cred_enter_pin : R.string.reboot_cred_enter_password);
        confirmation.setHint(pin ? R.string.reboot_cred_confirm_pin
                : R.string.reboot_cred_confirm_password);
        main.setError(null);
        confirmation.setError(null);
    }

    private LockscreenCredential createCredential(@androidx.annotation.IdRes int id) {
        EditText ed = requireViewById(id);
        String text = ed.getText().toString();
        return isPin() ? LockscreenCredential.createPin(text)
                : LockscreenCredential.createPassword(text);
    }

    private void save() {
        if (!checkInput() || !checkConfirmation()) {
            return;
        }
        if (hasSecondFactor) {
            EditText ed = requireViewById(R.id.second_factor_input);
            if (ed.getText().length() == 0) {
                ed.setError(getText(R.string.reboot_cred_second_factor_missing));
                return;
            }
        }

        var userCredential = getIntent().getParcelableExtra(EXTRA_USER_CREDENTIAL, LockscreenCredential.class);
        Objects.requireNonNull(userCredential, EXTRA_USER_CREDENTIAL);
        LockscreenCredential credential = createCredential(R.id.credential_input);
        LockscreenCredential secondFactor = LockscreenCredential.createNone();
        if (hasSecondFactor) {
            EditText ed = requireViewById(R.id.second_factor_input);
            secondFactor = LockscreenCredential.createPin(ed.getText().toString());
        }

        try {
            getLockPatternUtils().setRebootCredential(userCredential, secondFactor, credential);
        } catch (Exception e) {
            Log.e(TAG, "setRebootCredential failed", e);

            var d = new AlertDialog.Builder(this);
            d.setMessage(getString(R.string.reboot_cred_save_error, describe(e)));
            d.setNeutralButton(R.string.duress_pwd_error_dialog_dismiss, null);
            d.show();
            return;
        } finally {
            credential.zeroize();
            secondFactor.zeroize();
        }
        Toast.makeText(this,
                isUpdate ? R.string.reboot_cred_toast_updated : R.string.reboot_cred_toast_added,
                Toast.LENGTH_LONG).show();
        finish();
    }

    /** The service reports collisions with fixed messages, see DuressPasswordHelper. */
    private String describe(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = String.valueOf(t.getMessage());
            if (msg.startsWith("collision: owner credential")) {
                return getString(R.string.reboot_cred_collision_owner);
            } else if (msg.startsWith("collision: owner second factor")) {
                return getString(R.string.reboot_cred_collision_second_factor);
            } else if (msg.startsWith("collision: duress credential")) {
                return getString(R.string.reboot_cred_collision_duress);
            } else if (msg.startsWith("owner credential changed")) {
                return getString(R.string.reboot_cred_owner_changed);
            }
        }
        return e.toString();
    }

    private boolean checkInput() {
        EditText ed = requireViewById(R.id.credential_input);
        LockscreenCredential c = createCredential(R.id.credential_input);

        List<PasswordValidationError> errors = LockPatternUtils.validateDuressCredential(c);
        String error = null;
        boolean res = true;
        if (!errors.isEmpty()) {
            res = false;
            var pvec = new PasswordValidationErrorConverter(this, c.isPassword(),
                    ChooseLockPassword.ChooseLockPasswordFragment.ProfileType.None, errors);
            error = String.join("\n", pvec.convertErrorCodeToMessages());
        } else if ((error = LayoutPinLength.error(this, c)) != null) {
            res = false;
        }
        c.zeroize();
        ed.setError(error);
        if (!res) {
            // the error popup only shows on the focused field
            ed.requestFocus();
        }
        return res;
    }

    private boolean checkConfirmation() {
        EditText main = requireViewById(R.id.credential_input);
        EditText confirmation = requireViewById(R.id.credential_input_confirmation);

        boolean res = main.getText().toString().equals(confirmation.getText().toString());
        confirmation.setError(res ? null : getText(isPin()
                ? R.string.lockpassword_confirm_pins_dont_match
                : R.string.lockpassword_confirm_passwords_dont_match));
        if (!res) {
            confirmation.requestFocus();
        }
        return res;
    }
}
