/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.app.Fragment;
import android.app.FragmentManager;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.crimera.legacydownloader.FolderPickerFragment;
import app.morphe.extension.crimera.legacydownloader.StorageUtils;
import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.patches.analyzer.ProfileAnalyzerActivity;
import app.morphe.extension.instagram.patches.dm.DeletedMessagesActivity;
import app.morphe.extension.instagram.patches.dm.InboxLock;
import app.morphe.extension.instagram.patches.download.gallery.DownloadHistoryActivity;
import app.morphe.extension.instagram.patches.download.gallery.GalleryBackup;
import app.morphe.extension.instagram.patches.download.gallery.PrivateGalleryActivity;
import app.morphe.extension.instagram.patches.instants.InstantsVaultActivity;
import app.morphe.extension.instagram.settings.preference.fragments.BackupPrefFragment;
import app.morphe.extension.instagram.settings.preference.fragments.RestorePrefFragment;
import app.morphe.extension.shared.Logger;

public class ActivityHook {

    private static final String PROXY_ACTIVITY_CLASS = "com.instagram.modal.ModalActivity";
    private static final String PROXY_TRANSPARENT_ACTIVITY_CLASS = "com.instagram.modal.TransparentModalActivity";
    // Legacy stored names: launchers keep these extras in pinned settings shortcuts.
    private static final String EXTRA_SPOOKD = "piko";
    private static final String EXTRA_SPOOKD_LAUNCH_TYPE = "piko_launch_type";
    private static final String EXTRA_SHORTCUT = "shortcut";

    static final String LAUNCH_TYPE_SETTINGS = "settings";
    static final String LAUNCH_TYPE_BACKUP = "backup";
    static final String LAUNCH_TYPE_RESTORE = "restore";
    static final String LAUNCH_TYPE_FOLDER_PICKER = "folder_picker";
    static final String LAUNCH_TYPE_DELETED_MESSAGES = "deleted_messages";
    static final String LAUNCH_TYPE_INSTANTS_VAULT = "instants_vault";
    static final String LAUNCH_TYPE_PROFILE_ANALYZER = "profile_analyzer";
    static final String LAUNCH_TYPE_PRIVATE_GALLERY = "private_gallery";
    static final String LAUNCH_TYPE_DOWNLOAD_HISTORY = "download_history";
    static final String LAUNCH_TYPE_GALLERY_PICKER = "gallery_picker";


    public static void launchActivity(Context context, Class<?> activityClass) {
        if (activityClass != null && activityClass.equals(InstantsVaultActivity.class)) {
            startInstantsVaultActivity(context);
            return;
        }
        if (activityClass != null && activityClass.equals(DeletedMessagesActivity.class)) {
            startDeletedMessagesActivity(context);
            return;
        }
        if (activityClass != null && activityClass.equals(SettingsActivity.class)) {
            startSpookdActivity(null, null);
            return;
        }
        Intent intent = new Intent(context, activityClass);
        launchActivity(context, intent);
    }

    /** @return false when the activity could not be started; the failure is logged. */
    private static boolean launchActivity(Context context, Intent intent) {
        try {
            Activity currentActivity = app.morphe.extension.shared.Utils.getActivity();
            if (currentActivity != null && !currentActivity.isFinishing() && !currentActivity.isDestroyed()) {
                currentActivity.startActivity(intent);
                return true;
            }
            if (context instanceof Activity && !((Activity) context).isFinishing() && !((Activity) context).isDestroyed()) {
                ((Activity) context).startActivity(intent);
                return true;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Logger.printException(() -> "launchActivity failure", e);
            PikoUtils.logger(e);
            return false;
        }
    }

    private static Intent createProxyIntent(Context context, String activityClass) {
        Intent intent = new Intent();
        intent.setClassName(context.getPackageName(), activityClass);
        intent.putExtra(EXTRA_SPOOKD, true);
        intent.putExtra("fragment_arguments", new Bundle());
        return intent;
    }

    private static Intent createProxyIntent(Context context) {
        return createProxyIntent(context, PROXY_ACTIVITY_CLASS);
    }

    public static boolean isSpookdIntent(Intent intent) {
        if (intent == null) return false;
        try {
            if (intent.getBooleanExtra(EXTRA_SPOOKD, false)) return true;
        } catch (Throwable ignored) {}
        try {
            if ("true".equalsIgnoreCase(intent.getStringExtra(EXTRA_SPOOKD))) return true;
        } catch (Throwable ignored) {}
        try {
            if (LAUNCH_TYPE_SETTINGS.equals(intent.getStringExtra(EXTRA_SHORTCUT))) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isSpookd(Activity activity) {
        if (activity == null) return false;
        return isSpookdIntent(activity.getIntent());
    }

    public static boolean create(Activity activity, Bundle savedInstanceState) {
        Intent intent = activity == null ? null : activity.getIntent();
        if (!isSpookdIntent(intent)) return false;

        if (activity instanceof androidx.activity.ComponentActivity) {
            try {
                ((androidx.activity.ComponentActivity) activity).addOnNewIntentListener(newIntent -> handleNewIntent(activity, newIntent));
            } catch (Throwable ignored) {
            }
        }

        return dispatchLaunch(activity, intent, savedInstanceState);
    }

    public static boolean onBackPressed(Activity activity) {
        if (!isSpookd(activity)) return false;
        Intent intent = activity.getIntent();
        String launchType = intent != null ? intent.getStringExtra(EXTRA_SPOOKD_LAUNCH_TYPE) : null;
        if (launchType == null && intent != null && LAUNCH_TYPE_SETTINGS.equals(intent.getStringExtra(EXTRA_SHORTCUT))) {
            launchType = LAUNCH_TYPE_SETTINGS;
        }
        if (launchType == null || LAUNCH_TYPE_SETTINGS.equals(launchType)) {
            return SettingsActivity.handleBackPressed(activity);
        } else if (LAUNCH_TYPE_DELETED_MESSAGES.equals(launchType)) {
            return DeletedMessagesActivity.handleBackPressed(activity);
        } else if (LAUNCH_TYPE_INSTANTS_VAULT.equals(launchType)) {
            return InstantsVaultActivity.handleBackPressed(activity);
        } else if (LAUNCH_TYPE_PRIVATE_GALLERY.equals(launchType)) {
            return PrivateGalleryActivity.handleBackPressed(activity);
        }
        return false;
    }

    private static void handleNewIntent(Activity activity, Intent newIntent) {
        if (!isSpookdIntent(newIntent)) return;
        activity.setIntent(newIntent);
        dispatchLaunch(activity, newIntent, null);
    }

    private static boolean dispatchLaunch(Activity activity, Intent intent, Bundle savedInstanceState) {
        String launchType = intent.getStringExtra(EXTRA_SPOOKD_LAUNCH_TYPE);
        if (launchType == null && LAUNCH_TYPE_SETTINGS.equals(intent.getStringExtra(EXTRA_SHORTCUT))) {
            launchType = LAUNCH_TYPE_SETTINGS;
        }
        if (launchType == null) launchType = LAUNCH_TYPE_SETTINGS;

        Bundle extras = intent.getExtras();

        switch (launchType) {
            case LAUNCH_TYPE_SETTINGS:
                SettingsActivity.setupOnActivity(activity, savedInstanceState);
                break;
            case LAUNCH_TYPE_DELETED_MESSAGES:
                InboxLock.confirmViewer(activity, () -> DeletedMessagesActivity.setupOnActivity(activity));
                break;
            case LAUNCH_TYPE_INSTANTS_VAULT:
                InboxLock.confirmViewer(activity, () -> InstantsVaultActivity.setupOnActivity(activity));
                break;
            case LAUNCH_TYPE_PROFILE_ANALYZER:
                InboxLock.confirmViewer(activity, () -> ProfileAnalyzerActivity.setupOnActivity(activity));
                break;
            case LAUNCH_TYPE_PRIVATE_GALLERY:
                PrivateGalleryActivity.open(activity);
                break;
            case LAUNCH_TYPE_DOWNLOAD_HISTORY:
                DownloadHistoryActivity.open(activity);
                break;
            case LAUNCH_TYPE_BACKUP:
            case LAUNCH_TYPE_RESTORE:
            case LAUNCH_TYPE_FOLDER_PICKER:
            case LAUNCH_TYPE_GALLERY_PICKER:
                if (activity.getWindow() != null) {
                    activity.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                }
                attachPickerFragment(activity, launchType, extras);
                break;
            default:
                return false;
        }
        return true;
    }

    private static void attachPickerFragment(Activity activity, String launchType, Bundle extras) {
        FragmentManager fm = activity.getFragmentManager();
        if (fm == null || fm.findFragmentByTag("spookd_picker") != null) {
            return;
        }
        Fragment fragment;
        switch (launchType) {
            case LAUNCH_TYPE_BACKUP:
                fragment = new BackupPrefFragment();
                break;
            case LAUNCH_TYPE_RESTORE:
                fragment = new RestorePrefFragment();
                break;
            case LAUNCH_TYPE_FOLDER_PICKER:
                fragment = new FolderPickerFragment();
                break;
            case LAUNCH_TYPE_GALLERY_PICKER:
                fragment = new GalleryBackup.Picker();
                break;
            default:
                return;
        }
        if (extras != null) {
            fragment.setArguments(extras);
        }
        fm.beginTransaction()
                .add(fragment, "spookd_picker")
                .commitAllowingStateLoss();
    }

    public static boolean startSpookdActivity(String fragment_name, String title) {
        return startSpookdActivity(fragment_name, title, null);
    }

    public static boolean startSpookdActivity(String fragment_name, String title, String targetPreferenceKey) {
        Context context = PikoUtils.getContext();
        Intent intent = createProxyIntent(context);
        intent.putExtra(EXTRA_SPOOKD_LAUNCH_TYPE, LAUNCH_TYPE_SETTINGS);
        intent.putExtra(Constants.SPOOKD_FRAGMENT_NAME, fragment_name);
        intent.putExtra(Constants.SPOOKD_FRAGMENT_TITLE, title);
        if (targetPreferenceKey != null && !targetPreferenceKey.isEmpty()) {
            intent.putExtra(Constants.SPOOKD_SEARCH_TARGET_PREFERENCE_KEY, targetPreferenceKey);
        }
        return launchActivity(context, intent);
    }

    public static Intent createDeletedMessagesIntent(Context context) {
        Intent intent = createProxyIntent(context);
        intent.putExtra(EXTRA_SPOOKD_LAUNCH_TYPE, LAUNCH_TYPE_DELETED_MESSAGES);
        return intent;
    }

    public static boolean startDeletedMessagesActivity(Context context) {
        return launchActivity(context, createDeletedMessagesIntent(context));
    }

    public static boolean startProfileAnalyzerActivity(Context context) {
        Intent intent = createProxyIntent(context);
        intent.putExtra(EXTRA_SPOOKD_LAUNCH_TYPE, LAUNCH_TYPE_PROFILE_ANALYZER);
        return launchActivity(context, intent);
    }

    public static boolean startPrivateGalleryActivity(Context context) {
        Intent intent = createProxyIntent(context);
        intent.putExtra(EXTRA_SPOOKD_LAUNCH_TYPE, LAUNCH_TYPE_PRIVATE_GALLERY);
        return launchActivity(context, intent);
    }

    /**
     * Opens a transparent screen that only hosts the gallery's backup file picker, with
     * {@code arguments} as the picker's; it is no Inbox lock viewer, so it outlives the picker.
     */
    public static boolean startGalleryPicker(Context context, Bundle arguments) {
        Intent intent = createProxyIntent(context, PROXY_TRANSPARENT_ACTIVITY_CLASS);
        intent.putExtra(EXTRA_SPOOKD_LAUNCH_TYPE, LAUNCH_TYPE_GALLERY_PICKER);
        intent.putExtras(arguments);
        return launchActivity(context, intent);
    }

    public static boolean startDownloadHistoryActivity(Context context) {
        Intent intent = createProxyIntent(context);
        intent.putExtra(EXTRA_SPOOKD_LAUNCH_TYPE, LAUNCH_TYPE_DOWNLOAD_HISTORY);
        return launchActivity(context, intent);
    }

    public static void startInstantsVaultActivity(Context context) {
        Intent intent = createProxyIntent(context);
        intent.putExtra(EXTRA_SPOOKD_LAUNCH_TYPE, LAUNCH_TYPE_INSTANTS_VAULT);
        launchActivity(context, intent);
    }

    public static void launchFragment(Context ctx, String bundleKey) {
        String launchType;
        if (bundleKey.equals("spookd_export_dev_overrides") || bundleKey.equals("spookd_export_pref")) {
            launchType = LAUNCH_TYPE_BACKUP;
        } else if (bundleKey.equals("spookd_import_dev_overrides") || bundleKey.equals("spookd_import_id_mapping")
                || bundleKey.equals("spookd_import_pref") || bundleKey.equals("spookd_pref_add_font")) {
            launchType = LAUNCH_TYPE_RESTORE;
        } else if (bundleKey.equals("spookd_download_set_path")) {
            launchType = LAUNCH_TYPE_FOLDER_PICKER;
        } else {
            return;
        }

        Intent intent = createProxyIntent(ctx, PROXY_TRANSPARENT_ACTIVITY_CLASS);
        intent.putExtra(EXTRA_SPOOKD_LAUNCH_TYPE, launchType);
        intent.putExtra(bundleKey, true);
        launchActivity(ctx, intent);
    }

    public static void handleUrlIntent(Boolean isVideo, String mediaUrl) {
        String dataType = "image/*";
        String exportHeaderString = str("spookd_open_image_with");
        if (isVideo) {
            dataType = "video/*";
            exportHeaderString = str("spookd_open_video_with");
        }

        Uri uri = Uri.parse(mediaUrl);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, dataType);
        Intent chooserIntent = Intent.createChooser(intent, exportHeaderString);
        PikoUtils.launchIntent(chooserIntent);
    }
}
