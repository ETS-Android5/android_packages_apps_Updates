/*
 * Copyright (C) 2017 The LineageOS Project
 * Copyright (C) 2019 The PixelExperience Project
 * Copyright (C) 2019-2021 The Evolution X Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolution.ota;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.snackbar.Snackbar;

import org.evolution.ota.misc.FetchChangelog;
import org.json.JSONException;
import org.evolution.ota.controller.UpdaterController;
import org.evolution.ota.controller.UpdaterService;
import org.evolution.ota.download.DownloadClient;
import org.evolution.ota.misc.Constants;
import org.evolution.ota.misc.Utils;
import org.evolution.ota.model.UpdateInfo;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class UpdatesActivity extends UpdatesListActivity {

    private static final String TAG = "UpdatesActivity";
    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;

    private UpdatesListAdapter mAdapter;

    private View mRefreshIconView;
    private RotateAnimation mRefreshAnimation;

    private boolean isUpdateAvailable = false;

    private ProgressBar progressBar;
    private Button checkUpdateButton;
    private TextView updateStatus;
    private TextView androidVersion;
    private TextView evolutionVersion;
    private TextView securityVersion;
    private TextView lastUpdateCheck;
    private String LastUpdateCheck;

    private SharedPreferences sharedPref;

    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
            mAdapter.setUpdaterController(mUpdaterService.getUpdaterController());
            getUpdatesList();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mAdapter.setUpdaterController(null);
            mUpdaterService = null;
            mAdapter.notifyDataSetChanged();
        }
    };
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        mAdapter.onRequestPermissionsResult(requestCode, grantResults);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_updates);

        FetchChangelog changelog = new FetchChangelog();
        changelog.execute();

        sharedPref = this.getPreferences(Context.MODE_PRIVATE);
        LastUpdateCheck = sharedPref.getString("LastUpdateCheck", "Not checked");

        progressBar = findViewById(R.id.progress_bar);
        checkUpdateButton = findViewById(R.id.check_updates);
        updateStatus = findViewById(R.id.no_new_updates_view);
        androidVersion = findViewById(R.id.android_version);
        evolutionVersion = findViewById(R.id.evolution_version);
        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        lastUpdateCheck = findViewById(R.id.last_update_check);
        securityVersion = findViewById(R.id.security_version);

        androidVersion.setText(String.format(getResources()
                .getString(R.string.android_version, Build.VERSION.RELEASE)));
        evolutionVersion.setText(String.format(getResources()
                .getString(R.string.evolution_version, SystemProperties.get("ro.cherish.version"))));
        securityVersion.setText(String.format(getResources()
                .getString(R.string.security_patch_level), Utils.getSecurityPatchLevel()));
        lastUpdateCheck.setText(String.format(getResources()
                .getString(R.string.last_successful_check_for_update), LastUpdateCheck));
        checkUpdateButton.setOnClickListener(view -> {
            progressBar.setVisibility(View.VISIBLE);
            checkUpdateButton.setVisibility(View.GONE);
            securityVersion.setVisibility(View.GONE);
            lastUpdateCheck.setVisibility(View.GONE);
            downloadUpdatesList(true);
        });

        downloadUpdatesList(true);
        mAdapter = new UpdatesListAdapter(this);
        recyclerView.setAdapter(mAdapter);
        RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(this) {
            @Override
            public boolean canScrollVertically() {
                return false;
            }
        };
        recyclerView.setLayoutManager(layoutManager);
        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    handleDownloadStatusChange(downloadId);
                    mAdapter.notifyDataSetChanged();
                } else if (UpdaterController.ACTION_NETWORK_UNAVAILABLE.equals(intent.getAction())) {
                    showSnackbar(R.string.snack_download_failed, Snackbar.LENGTH_LONG);
                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction()) ||
                        UpdaterController.ACTION_INSTALL_PROGRESS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    mAdapter.notifyItemChanged(downloadId);
                } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    mAdapter.removeItem(downloadId);
                    hideUpdates();
                    downloadUpdatesList(false);
                }else if (ExportUpdateService.ACTION_EXPORT_STATUS.equals(intent.getAction())){
                    int status = intent.getIntExtra(ExportUpdateService.EXTRA_EXPORT_STATUS, -1);
                    handleExportStatusChanged(status);
                }
            }
        };

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        mRefreshAnimation = new RotateAnimation(0, 360, Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f);
        mRefreshAnimation.setInterpolator(new LinearInterpolator());
        mRefreshAnimation.setDuration(1000);
    }

    private void handleExportStatusChanged(int status){
        switch(status){
            case ExportUpdateService.EXPORT_STATUS_RUNNING:
                showSnackbar(R.string.dialog_export_title, Snackbar.LENGTH_SHORT);
                break;
            case ExportUpdateService.EXPORT_STATUS_ALREADY_RUNNING:
                showSnackbar(R.string.toast_already_exporting, Snackbar.LENGTH_SHORT);
                break;
            case ExportUpdateService.EXPORT_STATUS_SUCCESS:
                showSnackbar(R.string.notification_export_success, Snackbar.LENGTH_SHORT);
                break;
            case ExportUpdateService.EXPORT_STATUS_FAILED:
                showSnackbar(R.string.notification_export_fail, Snackbar.LENGTH_SHORT);
                break;
            default:
                break;
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        try {
            Intent intent = new Intent(this, UpdaterService.class);
            startService(intent);
            bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        } catch (IllegalStateException ignored) {

        }

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        intentFilter.addAction(UpdaterController.ACTION_NETWORK_UNAVAILABLE);
        intentFilter.addAction(ExportUpdateService.ACTION_EXPORT_STATUS);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    public void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
        if (mUpdaterService != null) {
            unbindService(mConnection);
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        downloadUpdatesList(true);
        super.onResume();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_toolbar, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_refresh: {
                downloadUpdatesList(true);
                return true;
            }
            case R.id.menu_preferences: {
                showPreferencesDialog();
                return true;
            }
            case R.id.menu_show_changelog: {
                startActivity(new Intent(this, LocalChangelogActivity.class));
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    private void hideUpdates() {
        findViewById(R.id.update_ic).setVisibility(View.VISIBLE);
        updateStatus.setVisibility(View.VISIBLE);
        updateStatus.setText(getResources().getString(R.string.list_no_updates));

        findViewById(R.id.recycler_view).setVisibility(View.GONE);
        androidVersion.setVisibility(View.VISIBLE);
        evolutionVersion.setVisibility(View.VISIBLE);
        securityVersion.setVisibility(View.VISIBLE);
        lastUpdateCheck.setVisibility(View.VISIBLE);
    }

    private void showUpdates() {
        findViewById(R.id.update_ic).setVisibility(View.GONE);
        updateStatus.setVisibility(View.GONE);
        updateStatus.setText(getResources().getString(R.string.system_update_available));

        findViewById(R.id.recycler_view).setVisibility(View.VISIBLE);
        androidVersion.setVisibility(View.GONE);
        evolutionVersion.setVisibility(View.GONE);
        securityVersion.setVisibility(View.GONE);
        lastUpdateCheck.setVisibility(View.GONE);

        checkUpdateButton.setVisibility(View.GONE);
    }

    private void loadUpdatesList(File jsonFile, boolean manualRefresh)
            throws IOException, JSONException {
        Log.d(TAG, "Adding remote updates");
        UpdaterController controller = mUpdaterService.getUpdaterController();

        UpdateInfo newUpdate = Utils.parseJson(jsonFile, true);
        boolean updateAvailable = newUpdate != null && controller.addUpdate(newUpdate);

        if (newUpdate != null) {
            isUpdateAvailable = Utils.isCurrentVersion(newUpdate);
        } else {
            isUpdateAvailable = false;
        }

        if (manualRefresh) {
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("EEE, h:mm a");
            String date = simpleDateFormat.format(new Date());

            Log.d("HRITIK", date);

            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putString("LastUpdateCheck", date);
            editor.apply();

            LastUpdateCheck = sharedPref.getString("LastUpdateCheck", "Not checked");
            lastUpdateCheck.setText(String.format(getResources()
                    .getString(R.string.last_successful_check_for_update), LastUpdateCheck));
        }

        List<String> updateIds = new ArrayList<>();
        List<UpdateInfo> sortedUpdates = controller.getUpdates();
        hideUpdates();
        if (newUpdate != null && Utils.isCompatible(newUpdate) && !sortedUpdates.isEmpty()) {
            sortedUpdates.sort((u1, u2) -> Long.compare(u2.getTimestamp(), u1.getTimestamp()));
            for (UpdateInfo update : sortedUpdates) {
                if (Utils.isCompatible(update)) {
                    updateIds.add(update.getDownloadId());
                    break; // Limit to 1
                }
            }
            mAdapter.setData(updateIds);
            mAdapter.notifyDataSetChanged();
        }
    }

    private void getUpdatesList() {
        File jsonFile = Utils.getCachedUpdateList(this);
        if (jsonFile.exists()) {
            try {
                if (mUpdaterService != null)
                    loadUpdatesList(jsonFile, false);
                Log.d(TAG, "Cached list parsed");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error while parsing json list", e);
            }
        } else {
            downloadUpdatesList(false);
        }
    }

    private void processNewJson(File json, File jsonNew, boolean manualRefresh) {
        try {
            if (mUpdaterService != null)
                loadUpdatesList(jsonNew, manualRefresh);
            if (json.exists() && Utils.isUpdateCheckEnabled(this) &&
                    Utils.checkForNewUpdates(json, jsonNew)) {
                UpdatesCheckReceiver.updateRepeatingUpdatesCheck(this);
            }
            // In case we set a one-shot check because of a previous failure
            UpdatesCheckReceiver.cancelUpdatesCheck(this);
            jsonNew.renameTo(json);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not read json", e);
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
        }
    }

    private void downloadUpdatesList(final boolean manualRefresh) {
        final File jsonFile = Utils.getCachedUpdateList(this);
        final File jsonFileTmp = new File(jsonFile.getAbsolutePath() + UUID.randomUUID());
        String url = Utils.getServerURL();
        Log.d(TAG, "Checking " + url);

        DownloadClient.DownloadCallback callback = new DownloadClient.DownloadCallback() {
            @Override
            public void onFailure(final boolean cancelled) {
                Log.e(TAG, "Could not download updates list");
                runOnUiThread(() -> {
                    if (!cancelled) {
                        showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
                    }
                    refreshAnimationStop();
                });
            }

            @Override
            public void onResponse(int statusCode, String url,
                                   DownloadClient.Headers headers) {
            }

            @Override
            public void onSuccess(File destination) {
                runOnUiThread(() -> {
                    Log.d(TAG, "List downloaded");
                    processNewJson(jsonFile, jsonFileTmp, manualRefresh);
                    refreshAnimationStop();
                });
            }
        };

        final DownloadClient downloadClient;
        try {
            downloadClient = new DownloadClient.Builder()
                    .setUrl(url)
                    .setDestination(jsonFileTmp)
                    .setDownloadCallback(callback)
                    .build();
        } catch (IOException exception) {
            Log.e(TAG, "Could not build download client");
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
            return;
        }

        refreshAnimationStart();
        downloadClient.start();
    }

    private void handleDownloadStatusChange(String downloadId) {
        UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
        switch (update.getStatus()) {
            case PAUSED_ERROR:
                showSnackbar(R.string.snack_download_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFICATION_FAILED:
                showSnackbar(R.string.snack_download_verification_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFIED:
                showSnackbar(R.string.snack_download_verified, Snackbar.LENGTH_LONG);
                break;
        }
    }

    @Override
    public void showSnackbar(int stringId, int duration) {
        Snackbar.make(findViewById(R.id.main_container), stringId, duration).show();
    }

    private void refreshAnimationStart() {
        progressBar.setVisibility(View.VISIBLE);
        checkUpdateButton.setVisibility(View.GONE);
        securityVersion.setVisibility(View.GONE);
        lastUpdateCheck.setVisibility(View.GONE);
        androidVersion.setVisibility(View.GONE);
        evolutionVersion.setVisibility(View.GONE);

        if (mRefreshIconView == null) {
            mRefreshIconView = findViewById(R.id.menu_refresh);
        }
        if (mRefreshIconView != null) {
            mRefreshAnimation.setRepeatCount(Animation.INFINITE);
            mRefreshIconView.startAnimation(mRefreshAnimation);
            mRefreshIconView.setEnabled(false);
        }
    }

    private void refreshAnimationStop() {
        progressBar.setVisibility(View.GONE);
        checkUpdateButton.setVisibility(View.VISIBLE);
        if (isUpdateAvailable) {
            showUpdates();
        } else {
            hideUpdates();
        }

        if (mRefreshIconView != null) {
            mRefreshAnimation.setRepeatCount(0);
            mRefreshIconView.setEnabled(true);
        }
    }

    private void showPreferencesDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.preferences_dialog, null);
        Spinner autoCheckInterval =
                view.findViewById(R.id.preferences_auto_updates_check_interval);
        Switch dataWarning = view.findViewById(R.id.preferences_mobile_data_warning);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        autoCheckInterval.setSelection(Utils.getUpdateCheckSetting(this));
        dataWarning.setChecked(prefs.getBoolean(Constants.PREF_MOBILE_DATA_WARNING, true));

        new AlertDialog.Builder(this, R.style.AppTheme_AlertDialogStyle)
                .setTitle(R.string.menu_preferences)
                .setView(view)
                .setOnDismissListener(dialogInterface -> {
                    prefs.edit()
                            .putInt(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
                                    autoCheckInterval.getSelectedItemPosition())
                            .putBoolean(Constants.PREF_MOBILE_DATA_WARNING,
                                    dataWarning.isChecked())
                            .apply();

                    if (Utils.isUpdateCheckEnabled(this)) {
                        UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(this);
                    } else {
                        UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(this);
                        UpdatesCheckReceiver.cancelUpdatesCheck(this);
                    }
                })
                .show();
    }
}
