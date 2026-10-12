package com.android.settings.security;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.android.internal.widget.LockscreenCredential;
import com.android.settings.R;
import com.android.settings.password.ChooseLockSettingsHelper;
import com.google.android.setupdesign.GlifRecyclerLayout;
import com.google.android.setupdesign.items.IItem;
import com.google.android.setupdesign.items.Item;
import com.google.android.setupdesign.items.ItemGroup;
import com.google.android.setupdesign.items.RecyclerItemAdapter;

/** Add, update or delete the owner's reboot PIN or password. Same flow as the duress screen. */
public class RebootCredentialMainActivity extends DuressPasswordActivity
        implements RecyclerItemAdapter.OnItemSelectedListener {
    private static final String TAG = RebootCredentialMainActivity.class.getSimpleName();

    private GlifRecyclerLayout layout;
    private LockscreenCredential userCredential;
    private boolean askedForUserCredentials;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // only the user that manages the alternate credentials, the service checks its credential
        if (!getLockPatternUtils().isAlternatesAuthority()) {
            finish();
            return;
        }

        var layout = new GlifRecyclerLayout(this);
        this.layout = layout;
        layout.setIcon(getDrawable(R.drawable.ic_lock));
        layout.setHeaderText(R.string.reboot_cred_pref_title);
        adjustDescriptionStyle(layout);
        layout.setDescriptionText(R.string.reboot_cred_description);

        setContentView(layout);

        // the owner credential isn't kept in the saved state: a recreated screen asks again
    }

    @Override
    protected boolean hasUserCredential() {
        return userCredential != null && !userCredential.isNone();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) {
            return;
        }

        if (userCredential == null) {
            if (!getLockPatternUtils().isSecure(getUserId())) {
                userCredential = LockscreenCredential.createNone();
            } else if (!askedForUserCredentials) {
                var b = new ChooseLockSettingsHelper.Builder(this);
                b.setRequestCode(REQ_CODE_OBTAIN_USER_CREDENTIALS);
                b.setReturnCredentials(true);
                b.setForegroundOnly(true);
                if (!b.show()) {
                    finish();
                    return;
                }
                askedForUserCredentials = true;
            }
        }

        updateActionList();
    }

    private void updateActionList() {
        if (userCredential == null) {
            Log.d(TAG, "no userCredential, skipping updateActionList");
            return;
        }

        var g = new ItemGroup();

        boolean has;
        boolean readable = true;
        try {
            has = getLockPatternUtils().hasRebootCredential(userCredential);
        } catch (RuntimeException e) {
            if (!isCorruptRecord(e)) {
                Log.e(TAG, "hasRebootCredential failed", e);
                var d = new AlertDialog.Builder(this);
                d.setMessage(getString(R.string.reboot_cred_status_error, e.toString()));
                d.setNeutralButton(R.string.duress_pwd_error_dialog_dismiss, null);
                d.setOnDismissListener(dialog -> finish());
                d.show();
                return;
            }
            // a stored record that can't be read: offer only to delete it
            has = true;
            readable = false;
        }
        boolean secure = getLockPatternUtils().isSecure(getUserId());
        layout.setDescriptionText(!readable ? R.string.reboot_cred_corrupt
                : (!secure && !has) ? R.string.reboot_cred_needs_lock
                : R.string.reboot_cred_description);
        if (has) {
            // setting needs a screen lock, deleting doesn't
            if (readable && secure) {
                g.addChild(createItem(R.string.reboot_cred_action_update, R.drawable.ic_edit));
            }
            g.addChild(createItem(R.string.reboot_cred_action_delete, R.drawable.ic_delete));
        } else if (secure) {
            g.addChild(createItem(R.string.reboot_cred_action_add, R.drawable.ic_add_24dp));
        }

        var adapter = new RecyclerItemAdapter(g);
        adapter.setOnItemSelectedListener(this);
        layout.setAdapter(adapter);
    }

    // the service reports an unreadable record with a fixed message
    static boolean isCorruptRecord(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if ("corrupt reboot credential record".equals(t.getMessage())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && userCredential != null) {
            userCredential.zeroize();
        }
    }

    private Item createItem(@StringRes int title, @DrawableRes int icon) {
        var i = new Item();
        i.setId(title);
        i.setTitle(getText(title));
        i.setIcon(getDrawable(icon));
        return i;
    }

    // RecyclerItemAdapter.OnItemSelectedListener
    @Override
    public void onItemSelected(IItem iitem) {
        Item item = (Item) iitem;
        int id = item.getId();

        if (id == R.string.reboot_cred_action_add || id == R.string.reboot_cred_action_update) {
            var i = new Intent(this, RebootCredentialSetupActivity.class);
            i.putExtra(RebootCredentialSetupActivity.EXTRA_TITLE_TEXT, id);
            i.putExtra(RebootCredentialSetupActivity.EXTRA_USER_CREDENTIAL, userCredential);
            allowNextOnStop = true;
            startActivityForResult(i, REQ_CODE_SETUP);
        } else if (id == R.string.reboot_cred_action_delete) {
            var b = new AlertDialog.Builder(this);
            b.setMessage(R.string.reboot_cred_action_delete_confirmation);
            b.setPositiveButton(R.string.duress_pwd_delete_button, (dialog, which) -> {
                try {
                    getLockPatternUtils().deleteRebootCredential(userCredential);
                } catch (Exception e) {
                    Log.e(TAG, "deleteRebootCredential failed", e);

                    var d = new AlertDialog.Builder(this);
                    d.setMessage(getString(R.string.reboot_cred_delete_error, e.toString()));
                    d.setNeutralButton(R.string.duress_pwd_error_dialog_dismiss, null);
                    d.show();
                    return;
                }
                updateActionList();
            });
            b.setNegativeButton(R.string.duress_pwd_cancel_button, null);
            b.show();
        }
    }

    static final int REQ_CODE_OBTAIN_USER_CREDENTIALS = 1;
    static final int REQ_CODE_SETUP = 2;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_CODE_SETUP) {
            if (resultCode == RESULT_CANCELED) {
                finish();
            }
            return;
        }

        if (requestCode != REQ_CODE_OBTAIN_USER_CREDENTIALS) {
            throw new IllegalStateException(Integer.toString(resultCode));
        }

        if (resultCode != RESULT_OK) {
            finish();
            return;
        }

        if (data == null) {
            throw new IllegalStateException("data == null");
        }

        var credential = data.getParcelableExtra(ChooseLockSettingsHelper.EXTRA_KEY_PASSWORD, LockscreenCredential.class);
        if (credential == null) {
            throw new IllegalStateException("no returned credential");
        }
        userCredential = credential;
    }
}
