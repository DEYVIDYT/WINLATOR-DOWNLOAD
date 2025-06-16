package com.winlator.Download;

import android.content.Intent;
// SharedPreferences import removed as it's now encapsulated in AppSettings
import android.os.Bundle;
import android.text.InputType;
// Button import will be replaced by MaterialButton if not used by other elements
import android.widget.EditText;
import android.widget.TextView;
// import android.widget.Toast; // Not strictly needed if no toasts are shown
import android.view.View; // Added for View.VISIBLE/GONE

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton; // Added import
import com.google.android.material.color.DynamicColors;
import com.google.android.material.slider.Slider; // Added import for Slider
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.winlator.Download.utils.AppSettings; // Added import

public class SettingsActivity extends AppCompatActivity {

    // SharedPreferences keys are now in AppSettings.java

    // private Button btnConfigureUploadApi; // Removed
    private MaterialButton btnLinkCryptorTool; // Changed type
    private MaterialButton btn_select_download_folder; // Changed type
    private TextView tv_selected_download_folder;
    private SwitchMaterial switch_direct_community_downloads;

    // New UI elements for multi-threaded download
    private SwitchMaterial switchEnableMultithreadDownload;
    private TextView tvLabelDownloadThreads;
    private Slider sliderDownloadThreads;
    private TextView tvDownloadThreadsValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivityIfAvailable(this);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Configurações");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        initViews();
        loadSettings(); // Load settings after initializing views
        setupClickListeners();
    }

    private void initViews() {
        // btnConfigureUploadApi = findViewById(R.id.btn_configure_upload_api); // Remains Removed
        btnLinkCryptorTool = findViewById(R.id.btn_link_cryptor_tool);
        btn_select_download_folder = findViewById(R.id.btn_select_download_folder);
        tv_selected_download_folder = findViewById(R.id.tv_selected_download_folder);
        switch_direct_community_downloads = findViewById(R.id.switch_direct_community_downloads);

        // Initialize new UI elements
        switchEnableMultithreadDownload = findViewById(R.id.switch_enable_multithread_download);
        tvLabelDownloadThreads = findViewById(R.id.tv_label_download_threads);
        sliderDownloadThreads = findViewById(R.id.slider_download_threads);
        tvDownloadThreadsValue = findViewById(R.id.tv_download_threads_value);
    }

    private void loadSettings() {
        // Use AppSettings to get values
        String downloadPath = AppSettings.getDownloadPath(this);
        tv_selected_download_folder.setText(downloadPath);
        boolean disableDirectDownloads = AppSettings.getDisableDirectDownloads(this);
        switch_direct_community_downloads.setChecked(disableDirectDownloads);

        // Load multi-threaded download settings
        // These AppSettings methods will be created in the next subtask.
        // Using defaults for now to ensure compilability if AppSettings is not yet updated.
        boolean isMultithreadEnabled = AppSettings.isMultithreadDownloadEnabled(this);
        switchEnableMultithreadDownload.setChecked(isMultithreadEnabled);

        int downloadThreads = AppSettings.getDownloadThreadsCount(this);
        sliderDownloadThreads.setValue(downloadThreads > 0 ? downloadThreads : 4.0f); // Ensure value is at least 1 or a default
        tvDownloadThreadsValue.setText(String.valueOf((int)sliderDownloadThreads.getValue()));

        updateThreadControlsVisibility(isMultithreadEnabled);
    }

    private void setupClickListeners() {
        // if (btnConfigureUploadApi != null) { // Remains Removed
            // btnConfigureUploadApi.setOnClickListener(v -> { // Remains Removed
                // Intent intent = new Intent(SettingsActivity.this, UploadApiSettingsHostActivity.class); // Remains Removed
                // startActivity(intent); // Remains Removed
            // }); // Remains Removed
        // } // Remains Removed
        if (btnLinkCryptorTool != null) {
            btnLinkCryptorTool.setOnClickListener(v -> {
                Intent intent = new Intent(SettingsActivity.this, LinkCryptorActivity.class);
                startActivity(intent);
            });
        }

        if (btn_select_download_folder != null) {
            btn_select_download_folder.setOnClickListener(v -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Set Download Path");

                final EditText input = new EditText(this);
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                // Pre-fill EditText: if current path is the default, show empty, otherwise show current path.
                String currentPath = AppSettings.getDownloadPath(this);
                if (currentPath.equals(AppSettings.DEFAULT_DOWNLOAD_PATH)) {
                    input.setText("");
                } else {
                    input.setText(currentPath);
                }
                builder.setView(input);

                builder.setPositiveButton("OK", (dialog, which) -> {
                    String path = input.getText().toString().trim();
                    if (path.isEmpty()) {
                        // If user clears input, reset to the defined default path
                        path = AppSettings.DEFAULT_DOWNLOAD_PATH;
                    }
                    AppSettings.setDownloadPath(this, path); // Use AppSettings to set value
                    tv_selected_download_folder.setText(path);
                });
                builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
                builder.show();
            });
        }

        if (switch_direct_community_downloads != null) {
            switch_direct_community_downloads.setOnCheckedChangeListener((buttonView, isChecked) -> {
                AppSettings.setDisableDirectDownloads(this, isChecked);
            });
        }

        // Listeners for multi-threaded download settings
        if (switchEnableMultithreadDownload != null) {
            switchEnableMultithreadDownload.setOnCheckedChangeListener((buttonView, isChecked) -> {
                AppSettings.setMultithreadDownloadEnabled(this, isChecked); // Placeholder
                updateThreadControlsVisibility(isChecked);
                // If enabling, ensure the current slider value is saved once.
                if (isChecked) {
                    AppSettings.setDownloadThreadsCount(this, (int) sliderDownloadThreads.getValue()); // Placeholder
                }
            });
        }

        if (sliderDownloadThreads != null) {
            sliderDownloadThreads.addOnChangeListener((slider, value, fromUser) -> {
                if (fromUser) { // Only update text if change is from user to avoid issues during load
                    tvDownloadThreadsValue.setText(String.valueOf((int) value));
                }
            });

            sliderDownloadThreads.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(Slider slider) {
                    // Can be used if needed, e.g., for haptic feedback
                }

                @Override
                public void onStopTrackingTouch(Slider slider) {
                    int intValue = (int) slider.getValue();
                    tvDownloadThreadsValue.setText(String.valueOf(intValue)); // Ensure text is set on stop
                    AppSettings.setDownloadThreadsCount(this, intValue); // Placeholder
                    Log.d("SettingsActivity", "Saved thread count: " + intValue);
                }
            });
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    private void updateThreadControlsVisibility(boolean isEnabled) {
        if (isEnabled) {
            tvLabelDownloadThreads.setVisibility(View.VISIBLE);
            sliderDownloadThreads.setVisibility(View.VISIBLE);
            tvDownloadThreadsValue.setVisibility(View.VISIBLE);
            // Ensure the text view is updated with the current slider value when made visible
            if (sliderDownloadThreads != null && tvDownloadThreadsValue != null) { // Check for null before accessing
                tvDownloadThreadsValue.setText(String.valueOf((int) sliderDownloadThreads.getValue()));
            }
        } else {
            tvLabelDownloadThreads.setVisibility(View.GONE);
            sliderDownloadThreads.setVisibility(View.GONE);
            tvDownloadThreadsValue.setVisibility(View.GONE);
        }
    }
}
