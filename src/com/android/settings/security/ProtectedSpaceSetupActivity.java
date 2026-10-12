package com.android.settings.security;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;
import com.android.settings.R;
import com.android.settings.SetupWizardUtils;
import com.google.android.setupcompat.template.FooterBarMixin;
import com.google.android.setupcompat.template.FooterButton;
import com.google.android.setupdesign.GlifLayout;
import com.google.android.setupdesign.util.ThemeHelper;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Sets up the protected space, one question per screen like the rest of setup: the owner's
 * PIN, the protected space PIN, an optional duress PIN and password, and an optional reboot PIN.
 * Runs from first setup and from Settings in user 0 while no protected space exists. In first
 * setup the owner's PIN comes from the screen lock step and isn't asked again.
 */
public class ProtectedSpaceSetupActivity extends Activity {
    private static final String TAG = "ProtectedSpaceSetup";
    private static final int MIN_PIN = 6;
    private static final int MIN_PASSWORD = 4;
    private static final int RANDOM_PASSWORD_BYTES = 24;

    private enum Step {
        INTRO, OWNER, PROTECTED, PROTECTED_CONFIRM, DURESS, DURESS_CONFIRM, DURESS_PASSWORD,
        DURESS_PASSWORD_CONFIRM, REBOOT, REBOOT_CONFIRM, WORKING
    }

    private GlifLayout mLayout;
    private EditText mInput;
    private TextView mError;
    private FooterButton mPrimary;
    private FooterButton mSecondary;
    private Step mStep = Step.INTRO;
    private boolean mBusy;

    // entered so far; zeroized when the screen goes away
    private LockscreenCredential mOwner, mProtected, mDuress, mDuressPassword, mReboot;
    private LockscreenCredential mFirstEntry;
    // the owner's PIN came from the screen lock step of first setup
    private boolean mOwnerFromSetup;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setTheme(SetupWizardUtils.getTheme(this, getIntent()));
        ThemeHelper.trySetDynamicColor(this);
        // skipping and finishing both move first setup on; back from the intro goes back
        setResult(RESULT_OK);

        if (getUserId() != UserHandle.USER_SYSTEM || hasProtectedSpace()) {
            finish();
            return;
        }

        setContentView(R.layout.protected_space_setup);
        mLayout = requireViewById(R.id.glif_layout);
        mInput = requireViewById(R.id.input);
        mError = requireViewById(R.id.error);
        // typed credentials stay out of the saved instance state
        mInput.setSaveEnabled(false);
        mInput.setOnEditorActionListener(this::onEditorAction);

        FooterBarMixin footerBar = mLayout.getMixin(FooterBarMixin.class);
        mPrimary = new FooterButton.Builder(this)
                .setText(R.string.protected_space_set_up)
                .setButtonType(FooterButton.ButtonType.NEXT)
                .setListener(v -> onPrimary())
                .setTheme(com.google.android.setupdesign.R.style.SudGlifButton_Primary)
                .build();
        mSecondary = new FooterButton.Builder(this)
                .setText(R.string.protected_space_skip)
                .setButtonType(FooterButton.ButtonType.SKIP)
                .setListener(v -> onSecondary())
                .setTheme(com.google.android.setupdesign.R.style.SudGlifButton_Secondary)
                .build();
        footerBar.setPrimaryButton(mPrimary);
        footerBar.setSecondaryButton(mSecondary);

        mOwner = SetupOwnerCredential.take();
        mOwnerFromSetup = mOwner != null;

        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBack);
        show(Step.INTRO);
    }

    private boolean hasProtectedSpace() {
        try {
            // user 0 learns USER_NULL once another user manages the alternates
            return new LockPatternUtils(this).getAlternatesAuthority() != UserHandle.USER_SYSTEM;
        } catch (RuntimeException e) {
            return true;
        }
    }

    @Override
    protected void onDestroy() {
        zeroize(mOwner, mProtected, mDuress, mDuressPassword, mReboot, mFirstEntry);
        if (mInput != null) {
            mInput.getText().clear();
        }
        super.onDestroy();
    }

    private static void zeroize(LockscreenCredential... credentials) {
        for (LockscreenCredential c : credentials) {
            if (c != null) {
                c.zeroize();
            }
        }
    }

    private boolean isPasswordStep(Step step) {
        return step == Step.DURESS_PASSWORD || step == Step.DURESS_PASSWORD_CONFIRM;
    }

    private void show(Step step) {
        mStep = step;
        mError.setText("");
        mInput.getText().clear();
        @StringRes int header;
        @StringRes int description;
        switch (step) {
            case INTRO -> {
                header = R.string.protected_space_title;
                description = R.string.protected_space_intro;
            }
            case OWNER -> {
                header = R.string.protected_space_owner_header;
                description = R.string.protected_space_owner_description;
            }
            case PROTECTED -> {
                header = R.string.protected_space_protected_header;
                description = R.string.protected_space_protected_description;
            }
            case DURESS -> {
                header = R.string.protected_space_duress_header;
                description = R.string.protected_space_duress_description;
            }
            case DURESS_PASSWORD -> {
                header = R.string.protected_space_duress_password_header;
                description = R.string.protected_space_duress_password_description;
            }
            case REBOOT -> {
                header = R.string.protected_space_reboot_header;
                description = R.string.protected_space_reboot_description;
            }
            case DURESS_PASSWORD_CONFIRM -> {
                header = R.string.protected_space_reenter_password_header;
                description = R.string.protected_space_duress_password_description;
            }
            case WORKING -> {
                header = R.string.protected_space_working_header;
                description = R.string.protected_space_working_description;
            }
            default -> {
                header = R.string.protected_space_reenter_header;
                description = 0;
            }
        }
        mLayout.setHeaderText(header);
        if (description != 0) {
            mLayout.setDescriptionText(description);
        } else {
            mLayout.setDescriptionText("");
        }

        boolean field = step != Step.INTRO && step != Step.WORKING;
        mInput.setVisibility(field ? View.VISIBLE : View.GONE);
        mError.setVisibility(field ? View.VISIBLE : View.GONE);
        requireViewById(R.id.progress).setVisibility(
                step == Step.WORKING ? View.VISIBLE : View.GONE);
        mInput.setInputType(isPasswordStep(step)
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        mPrimary.setVisibility(step == Step.WORKING ? View.INVISIBLE : View.VISIBLE);
        mPrimary.setEnabled(true);
        mPrimary.setText(this, switch (step) {
            case INTRO -> R.string.protected_space_set_up;
            case PROTECTED_CONFIRM, DURESS_CONFIRM, DURESS_PASSWORD_CONFIRM, REBOOT_CONFIRM ->
                    R.string.protected_space_confirm_button;
            default -> R.string.protected_space_next;
        });
        // skip: the whole space on the intro, or one of the optional credentials
        boolean skippable = step == Step.INTRO || step == Step.DURESS
                || step == Step.DURESS_PASSWORD || step == Step.REBOOT;
        mSecondary.setVisibility(skippable ? View.VISIBLE : View.GONE);

        if (field) {
            mInput.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(mInput, 0);
        }
    }

    private boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE
                || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
            onPrimary();
            return true;
        }
        return false;
    }

    private void onBack() {
        if (mBusy || mStep == Step.WORKING) {
            return;
        }
        Step previous = switch (mStep) {
            case INTRO -> null;
            case OWNER -> Step.INTRO;
            case PROTECTED, PROTECTED_CONFIRM -> mOwnerFromSetup ? Step.INTRO : Step.OWNER;
            case DURESS, DURESS_CONFIRM -> Step.PROTECTED;
            case DURESS_PASSWORD, DURESS_PASSWORD_CONFIRM -> Step.DURESS;
            case REBOOT, REBOOT_CONFIRM -> mDuress == null ? Step.DURESS
                    : Step.DURESS_PASSWORD;
            default -> null;
        };
        if (previous == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        forgetFrom(previous);
        show(previous);
    }

    // going back to a step drops what was chosen there and after it
    private void forgetFrom(Step step) {
        zeroize(mFirstEntry);
        mFirstEntry = null;
        int from = step.ordinal();
        if (from <= Step.OWNER.ordinal() && !mOwnerFromSetup) {
            zeroize(mOwner);
            mOwner = null;
        }
        if (from <= Step.PROTECTED.ordinal()) {
            zeroize(mProtected);
            mProtected = null;
        }
        if (from <= Step.DURESS.ordinal()) {
            zeroize(mDuress);
            mDuress = null;
        }
        if (from <= Step.DURESS_PASSWORD.ordinal()) {
            zeroize(mDuressPassword);
            mDuressPassword = null;
        }
        if (from <= Step.REBOOT.ordinal()) {
            zeroize(mReboot);
            mReboot = null;
        }
    }

    private void onSecondary() {
        if (mBusy) {
            return;
        }
        if (mStep == Step.INTRO) {
            finish();
        } else if (mStep == Step.DURESS) {
            // no duress PIN, so no duress password either
            zeroize(mDuress, mDuressPassword);
            mDuress = null;
            mDuressPassword = null;
            show(Step.REBOOT);
        } else if (mStep == Step.DURESS_PASSWORD) {
            // duress needs a password too; one nobody knows leaves only the PIN usable
            zeroize(mDuressPassword);
            mDuressPassword = randomPassword();
            show(Step.REBOOT);
        } else if (mStep == Step.REBOOT) {
            zeroize(mReboot);
            mReboot = null;
            provision();
        }
    }

    private void error(@StringRes int message) {
        mError.setText(message);
        mInput.getText().clear();
    }

    private LockscreenCredential entered() {
        String text = mInput.getText().toString();
        return isPasswordStep(mStep) ? LockscreenCredential.createPassword(text)
                : LockscreenCredential.createPin(text);
    }

    private boolean validPin(String s) {
        return s.length() >= MIN_PIN && s.chars().allMatch(Character::isDigit);
    }

    // With auto-confirm the lock screen submits as soon as the owner's PIN length is typed,
    // so a longer PIN could never be entered; every PIN then has the owner's length.
    private boolean wrongLengthForAutoConfirm(String s) {
        return mOwner != null && s.length() != mOwner.size()
                && new LockPatternUtils(this).isAutoPinConfirmEnabled(UserHandle.USER_SYSTEM);
    }

    // a new PIN must differ from every credential chosen before it
    private boolean taken(LockscreenCredential c) {
        for (LockscreenCredential other : new LockscreenCredential[] {mOwner, mProtected,
                mDuress}) {
            if (other != null && other.equals(c)) {
                return true;
            }
        }
        return false;
    }

    private void onPrimary() {
        if (mBusy) {
            return;
        }
        String text = mInput.getText().toString();
        switch (mStep) {
            case INTRO -> show(mOwnerFromSetup ? Step.PROTECTED : Step.OWNER);
            case OWNER -> {
                if (!validPin(text)) {
                    error(R.string.protected_space_too_short);
                    return;
                }
                checkOwner(entered());
            }
            case PROTECTED, DURESS, REBOOT -> {
                if (!validPin(text)) {
                    error(R.string.protected_space_too_short);
                    return;
                }
                if (wrongLengthForAutoConfirm(text)) {
                    mError.setText(getString(R.string.protected_space_auto_confirm_length,
                            mOwner.size()));
                    mInput.getText().clear();
                    return;
                }
                LockscreenCredential c = entered();
                if (taken(c)) {
                    c.zeroize();
                    error(R.string.protected_space_taken);
                    return;
                }
                zeroize(mFirstEntry);
                mFirstEntry = c;
                show(switch (mStep) {
                    case PROTECTED -> Step.PROTECTED_CONFIRM;
                    case DURESS -> Step.DURESS_CONFIRM;
                    default -> Step.REBOOT_CONFIRM;
                });
            }
            case DURESS_PASSWORD -> {
                if (text.length() < MIN_PASSWORD) {
                    error(R.string.protected_space_password_too_short);
                    return;
                }
                zeroize(mFirstEntry);
                mFirstEntry = entered();
                show(Step.DURESS_PASSWORD_CONFIRM);
            }
            case PROTECTED_CONFIRM, DURESS_CONFIRM, DURESS_PASSWORD_CONFIRM, REBOOT_CONFIRM -> {
                LockscreenCredential c = entered();
                if (mFirstEntry == null || !mFirstEntry.equals(c)) {
                    c.zeroize();
                    zeroize(mFirstEntry);
                    mFirstEntry = null;
                    // back to choosing, as the screen lock step does
                    show(switch (mStep) {
                        case PROTECTED_CONFIRM -> Step.PROTECTED;
                        case DURESS_CONFIRM -> Step.DURESS;
                        case DURESS_PASSWORD_CONFIRM -> Step.DURESS_PASSWORD;
                        default -> Step.REBOOT;
                    });
                    mError.setText(R.string.protected_space_mismatch);
                    return;
                }
                zeroize(mFirstEntry);
                mFirstEntry = null;
                switch (mStep) {
                    case PROTECTED_CONFIRM -> {
                        mProtected = c;
                        show(Step.DURESS);
                    }
                    case DURESS_CONFIRM -> {
                        mDuress = c;
                        show(Step.DURESS_PASSWORD);
                    }
                    case DURESS_PASSWORD_CONFIRM -> {
                        mDuressPassword = c;
                        show(Step.REBOOT);
                    }
                    default -> {
                        mReboot = c;
                        provision();
                    }
                }
            }
            default -> { }
        }
    }

    // checked here so a typo shows on its own screen, not at the end
    private void checkOwner(LockscreenCredential owner) {
        mBusy = true;
        mPrimary.setEnabled(false);
        LockPatternUtils lpu = new LockPatternUtils(this);
        AsyncTask.execute(() -> {
            boolean matched;
            try (LockscreenCredential copy = owner.duplicate()) {
                matched = lpu.checkCredential(copy, UserHandle.USER_SYSTEM, null).isMatched();
            } catch (Exception e) {
                Log.e(TAG, "owner check failed", e);
                matched = false;
            }
            final boolean ok = matched;
            runOnUiThread(() -> {
                mBusy = false;
                if (isDestroyed()) {
                    owner.zeroize();
                    return;
                }
                mPrimary.setEnabled(true);
                if (!ok) {
                    owner.zeroize();
                    error(R.string.protected_space_wrong_owner);
                    return;
                }
                zeroize(mOwner);
                mOwner = owner;
                show(Step.PROTECTED);
            });
        });
    }

    private void provision() {
        mBusy = true;
        show(Step.WORKING);
        LockPatternUtils lpu = new LockPatternUtils(this);
        LockscreenCredential owner = mOwner.duplicate();
        LockscreenCredential protectedPin = mProtected.duplicate();
        LockscreenCredential duress = mDuress != null ? mDuress.duplicate()
                : LockscreenCredential.createNone();
        LockscreenCredential duressPassword = mDuress != null ? mDuressPassword.duplicate()
                : LockscreenCredential.createNone();
        LockscreenCredential reboot = mReboot != null ? mReboot.duplicate()
                : LockscreenCredential.createNone();
        AsyncTask.execute(() -> {
            Exception error = null;
            try {
                lpu.provisionProtectedSpace(owner, protectedPin, duress, duressPassword, reboot);
            } catch (Exception e) {
                Log.e(TAG, "provisionProtectedSpace failed", e);
                error = e;
            } finally {
                zeroize(owner, protectedPin, duress, duressPassword, reboot);
            }
            final Exception result = error;
            runOnUiThread(() -> onProvisioned(result));
        });
    }

    private static LockscreenCredential randomPassword() {
        byte[] bytes = new byte[RANDOM_PASSWORD_BYTES];
        new SecureRandom().nextBytes(bytes);
        String password = Base64.getEncoder().withoutPadding().encodeToString(bytes);
        Arrays.fill(bytes, (byte) 0);
        return LockscreenCredential.createPassword(password);
    }

    private void onProvisioned(@Nullable Exception error) {
        mBusy = false;
        if (isDestroyed()) {
            return;
        }
        if (error == null) {
            Toast.makeText(this, R.string.protected_space_done, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        // a retry starts over; the owner's PIN from first setup stays
        forgetFrom(Step.OWNER);
        show(Step.INTRO);
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.protected_space_failed,
                        String.valueOf(error.getMessage())))
                .setNeutralButton(R.string.duress_pwd_error_dialog_dismiss, null)
                .show();
    }
}
