package com.tumuyan.ncnn.realsr;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;

public class VideoUpscaleActivity extends AppCompatActivity {
    private static final int REQUEST_VIDEO = 3201;

    private final String[] modelFolders = {
            "models-Real-ESRGAN",
            "models-RealeSR-general-v3",
            "models-ESRGAN-Nomos8kSC"
    };

    private Uri selectedVideo;
    private String selectedName = "video";
    private Spinner modelSpinner;
    private Spinner scaleSpinner;
    private Button startButton;
    private Button cancelButton;
    private ProgressBar progressBar;
    private TextView selectedText;
    private TextView statusText;
    private TextView outputText;
    private VideoUpscaleService service;
    private boolean bound;

    private final VideoUpscaleService.Listener serviceListener =
            (message, completed, success, outputPath) -> renderStatus(message, completed, success, outputPath);

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            VideoUpscaleService.LocalBinder localBinder = (VideoUpscaleService.LocalBinder) binder;
            service = localBinder.getService();
            service.setListener(serviceListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_upscale);
        setTitle(R.string.video_upscale_title);

        selectedText = findViewById(R.id.tv_video_selected);
        statusText = findViewById(R.id.tv_video_status);
        outputText = findViewById(R.id.tv_video_output);
        progressBar = findViewById(R.id.video_progress);
        modelSpinner = findViewById(R.id.spinner_video_model);
        scaleSpinner = findViewById(R.id.spinner_video_scale);
        startButton = findViewById(R.id.btn_start_video);
        cancelButton = findViewById(R.id.btn_cancel_video);

        ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.video_model_options));
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(modelAdapter);

        ArrayAdapter<String> scaleAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.video_scale_options));
        scaleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        scaleSpinner.setAdapter(scaleAdapter);

        findViewById(R.id.btn_select_video).setOnClickListener(view -> selectVideo());
        startButton.setOnClickListener(view -> startUpscale());
        cancelButton.setOnClickListener(view -> cancelUpscale());

        renderStatus("Ready. Select a video to begin.", true, false, null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!bound) {
            Intent bindIntent = new Intent(this, VideoUpscaleService.class);
            bound = bindService(bindIntent, connection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onStop() {
        if (bound) {
            if (service != null) {
                service.setListener(null);
            }
            unbindService(connection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    private void selectVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_VIDEO);
    }

    private void startUpscale() {
        if (selectedVideo == null) {
            Toast.makeText(this, R.string.video_no_input, Toast.LENGTH_SHORT).show();
            return;
        }

        File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        File outputDir = new File(movies, "RealSR");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            statusText.setText("Cannot create Movies/RealSR output directory.");
            return;
        }

        String baseName = selectedName;
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) {
            baseName = baseName.substring(0, dot);
        }
        baseName = baseName.replaceAll("[^A-Za-z0-9._-]", "_");
        int scale = scaleSpinner.getSelectedItemPosition() == 0 ? 4 : 2;
        String suffix = scale == 4 ? "_RealSR_4x.mp4" : "_RealSR_2x.mp4";
        File outputFile = new File(outputDir, baseName + suffix);
        if (outputFile.exists()) {
            String uniqueSuffix = "_" + System.currentTimeMillis();
            outputFile = new File(outputDir, baseName + uniqueSuffix + suffix);
        }

        String modelFolder = modelFolders[modelSpinner.getSelectedItemPosition()];
        Intent intent = new Intent(this, VideoUpscaleService.class);
        intent.setAction(VideoUpscaleService.ACTION_START);
        intent.putExtra(VideoUpscaleService.EXTRA_INPUT_URI, selectedVideo.toString());
        intent.putExtra(VideoUpscaleService.EXTRA_OUTPUT_PATH, outputFile.getAbsolutePath());
        intent.putExtra(VideoUpscaleService.EXTRA_MODEL_FOLDER, modelFolder);
        intent.putExtra(VideoUpscaleService.EXTRA_OUTPUT_SCALE, scale);

        ContextCompat.startForegroundService(this, intent);
        renderStatus(getString(R.string.video_processing), false, false, null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void cancelUpscale() {
        if (service != null) {
            service.cancelUpscale();
        } else {
            Intent intent = new Intent(this, VideoUpscaleService.class);
            intent.setAction(VideoUpscaleService.ACTION_CANCEL);
            startService(intent);
        }
    }

    private void renderStatus(String message, boolean completed, boolean success, String outputPath) {
        statusText.setText(message);
        progressBar.setVisibility(completed ? View.GONE : View.VISIBLE);
        cancelButton.setVisibility(completed ? View.GONE : View.VISIBLE);
        startButton.setEnabled(completed);
        if (completed) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (success && outputPath != null && !outputPath.isEmpty()) {
            outputText.setText(getString(R.string.video_output, outputPath));
        } else if (completed) {
            outputText.setText("");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VIDEO && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            selectedVideo = data.getData();
            try {
                int permissionFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if (permissionFlags != 0) {
                    getContentResolver().takePersistableUriPermission(selectedVideo, permissionFlags);
                }
            } catch (SecurityException ignored) {
            }
            selectedName = queryDisplayName(selectedVideo);
            selectedText.setText(selectedName);
            outputText.setText("");
            renderStatus(getString(R.string.video_ready), true, false, null);
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    return name == null ? "video" : name;
                }
            }
        } catch (Exception ignored) {
        }
        return "video";
    }

    @Override
    protected void onDestroy() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }
}
