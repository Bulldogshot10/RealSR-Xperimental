package com.tumuyan.ncnn.realsr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoUpscaleService extends Service {
    public static final String ACTION_START = "com.tumuyan.ncnn.realsr.VIDEO_UPSCALE_START";
    public static final String ACTION_CANCEL = "com.tumuyan.ncnn.realsr.VIDEO_UPSCALE_CANCEL";
    public static final String EXTRA_INPUT_URI = "input_uri";
    public static final String EXTRA_OUTPUT_PATH = "output_path";
    public static final String EXTRA_MODEL_FOLDER = "model_folder";
    public static final String EXTRA_OUTPUT_SCALE = "output_scale";
    public static final String EXTRA_MODEL_SCALE = "model_scale";

    private static final String CHANNEL_ID = "video_upscale";
    private static final int NOTIFICATION_ID = 42;
    private static final String TAG = "VideoUpscaleService";

    public interface Listener {
        void onStatus(String message, boolean completed, boolean success, String outputPath);
    }

    public class LocalBinder extends Binder {
        public VideoUpscaleService getService() {
            return VideoUpscaleService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private volatile VideoUpscaleProcessor processor;
    private volatile boolean running;
    private volatile boolean completed = true;
    private volatile boolean successful;
    private volatile boolean cancelRequested;
    private volatile String status = "Ready. Select a video to begin.";
    private volatile String outputPath = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelUpscale();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(intent.getAction())) {
            if (running) {
                publish("A video upscale is already running.", false, false);
                return START_NOT_STICKY;
            }
            String inputUri = intent.getStringExtra(EXTRA_INPUT_URI);
            outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH);
            String modelFolder = intent.getStringExtra(EXTRA_MODEL_FOLDER);
            int outputScale = intent.getIntExtra(EXTRA_OUTPUT_SCALE, 4);
            int modelScale = intent.getIntExtra(EXTRA_MODEL_SCALE, 4);
            if (inputUri == null || outputPath == null || modelFolder == null) {
                publish("Missing video upscale parameters.", true, false);
                return START_NOT_STICKY;
            }

            running = true;
            completed = false;
            successful = false;
            cancelRequested = false;
            status = "Preparing video…";
            startForeground(NOTIFICATION_ID, buildNotification(status, true));
            executor.execute(() -> runUpscale(inputUri, outputPath, modelFolder, outputScale, modelScale, startId));
        }
        return START_NOT_STICKY;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) {
            final String currentStatus = status;
            final boolean currentCompleted = completed;
            final boolean currentSuccessful = successful;
            final String currentOutputPath = outputPath;
            mainHandler.post(() -> listener.onStatus(
                    currentStatus, currentCompleted, currentSuccessful, currentOutputPath));
        }
    }

    public void cancelUpscale() {
        cancelRequested = true;
        VideoUpscaleProcessor current = processor;
        if (current != null) {
            current.cancel();
        }
        publish("Cancelling video upscale…", false, false);
    }

    private void runUpscale(String inputUri, String destination, String modelFolder,
                            int outputScale, int modelScale, int startId) {
        File copiedInput = null;
        try {
            copiedInput = copyInputVideo(Uri.parse(inputUri));
            if (cancelRequested) {
                throw new InterruptedException("Video upscale cancelled.");
            }

            File outputFile = new File(destination);
            VideoUpscaleProcessor engine = new VideoUpscaleProcessor(new File(getCacheDir(), "realsr"));
            processor = engine;
            engine.upscale(copiedInput, outputFile, modelFolder, outputScale, modelScale,
                    (message, processedFrames, estimatedFrames) -> {
                        status = message;
                        updateProgressNotification(message);
                        notifyListener(message, false, false, outputPath);
                    });

            successful = true;
            status = getString(R.string.video_done);
            android.media.MediaScannerConnection.scanFile(
                    this,
                    new String[] { outputFile.getAbsolutePath() },
                    new String[] { "video/mp4" },
                    (path, uri) -> Log.i(TAG, "Media scan completed: " + path)
            );
        } catch (Exception e) {
            successful = false;
            if (cancelRequested || e instanceof InterruptedException
                    || (e.getMessage() != null && e.getMessage().toLowerCase().contains("cancel"))) {
                status = getString(R.string.video_cancelled);
            } else {
                Log.e(TAG, "Video upscale failed", e);
                status = getString(R.string.video_failed) + ": "
                        + (e.getMessage() == null ? "Unknown error" : e.getMessage());
            }
        } finally {
            if (copiedInput != null) {
                copiedInput.delete();
            }
            processor = null;
            running = false;
            completed = true;
            stopForeground(false);
            publish(status, true, successful);
            stopSelf(startId);
        }
    }

    private File copyInputVideo(Uri uri) throws Exception {
        File inputDirectory = new File(getCacheDir(), "video-upscale-input");
        if (!inputDirectory.exists() && !inputDirectory.mkdirs()) {
            throw new IllegalStateException("Cannot create temporary video folder.");
        }

        String displayName = queryDisplayName(uri);
        String extension = ".mp4";
        if (displayName != null && displayName.lastIndexOf('.') >= 0) {
            String candidate = displayName.substring(displayName.lastIndexOf('.')).toLowerCase();
            if (candidate.matches("\\.(mp4|mkv|mov|webm|avi|m4v|3gp|ts|m2ts)")) {
                extension = candidate;
            }
        }

        File destination = new File(inputDirectory, "input-" + System.currentTimeMillis() + extension);
        ContentResolver resolver = getContentResolver();
        try (InputStream input = resolver.openInputStream(uri);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IllegalStateException("Cannot open the selected video.");
            }
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (cancelRequested || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Video upscale cancelled.");
                }
                output.write(buffer, 0, read);
            }
            output.flush();
        }
        if (destination.length() == 0) {
            destination.delete();
            throw new IllegalStateException("The selected video is empty.");
        }
        return destination;
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void publish(String message, boolean done, boolean success) {
        status = message;
        completed = done;
        successful = success;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(message, !done));
        notifyListener(message, done, success, outputPath);
    }

    private void notifyListener(String message, boolean done, boolean success, String path) {
        Listener current = listener;
        if (current != null) {
            mainHandler.post(() -> current.onStatus(message, done, success, path));
        }
    }

    private void updateProgressNotification(String message) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(message, true));
    }

    private Notification buildNotification(String message, boolean ongoing) {
        Intent activityIntent = new Intent(this, VideoUpscaleActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 44, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(message)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, ongoing)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Video upscaling", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows progress while a video is being upscaled.");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        VideoUpscaleProcessor current = processor;
        if (current != null) {
            current.cancel();
        }
        executor.shutdownNow();
        super.onDestroy();
    }
}
