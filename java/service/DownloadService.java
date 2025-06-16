package com.winlator.Download.service;

import android.app.Notification; // Added
import android.app.NotificationChannel; // Added
import android.app.NotificationManager; // Added
import android.app.PendingIntent; // Added
import android.app.Service;
import android.content.ContentValues; // Added
import android.content.Context; // Added
import android.content.Intent;
import android.database.Cursor; // Added
import android.database.sqlite.SQLiteDatabase; // Added
import android.net.Uri; // Added
import android.os.AsyncTask; // Added
import android.os.Binder; // Added
import android.os.Build; // Added
import android.os.Environment; // Added
import android.os.Handler;
import android.os.IBinder; // Added
import android.os.Looper;
import android.util.Log;
import android.webkit.URLUtil;
import android.widget.Toast;

import androidx.annotation.Nullable; // Added
import androidx.core.app.NotificationCompat; // Added
import androidx.core.content.FileProvider; // Added
import androidx.localbroadcastmanager.content.LocalBroadcastManager; // Added

import com.winlator.Download.DownloadManagerActivity; // Added
import com.winlator.Download.R; // Added
import com.winlator.Download.db.DownloadContract; // Added
import com.winlator.Download.db.SQLiteHelper; // Added
import com.winlator.Download.model.Download; // Added

import java.io.BufferedInputStream; // Added
import java.io.File; // Added
import java.io.FileOutputStream; // Added
import java.io.IOException; // Added
import java.io.InputStream; // Added
import java.io.OutputStream; // Added
import java.io.RandomAccessFile; // Added
import java.net.HttpURLConnection; // Added
import java.net.URL; // Added
import java.util.ArrayList; // Added
import java.util.HashMap; // Added
import java.util.List;
import java.util.Map; // Added
import java.util.concurrent.ConcurrentHashMap; // Added
import java.util.concurrent.ExecutorService; // Added
import java.util.concurrent.Executors;


public class DownloadService extends Service {

    private static final String TAG = "DownloadService";
    public static final String EXTRA_URL = "com.winlator.Download.extra.URL";
    public static final String EXTRA_FILE_NAME = "com.winlator.Download.extra.FILE_NAME";
    public static final String EXTRA_DOWNLOAD_ID = "com.winlator.Download.extra.DOWNLOAD_ID";
    public static final String EXTRA_ACTION = "com.winlator.Download.extra.ACTION";

    public static final String ACTION_START_DOWNLOAD = "com.winlator.Download.action.START_DOWNLOAD";
    public static final String ACTION_PAUSE_DOWNLOAD = "com.winlator.Download.action.PAUSE_DOWNLOAD";
    public static final String ACTION_RESUME_DOWNLOAD = "com.winlator.Download.action.RESUME_DOWNLOAD";
    public static final String ACTION_CANCEL_DOWNLOAD = "com.winlator.Download.action.CANCEL_DOWNLOAD";
    public static final String ACTION_RETRY_DOWNLOAD = "com.winlator.Download.action.RETRY_DOWNLOAD";

    public static final String ACTION_RESOLVE_AND_START_GOFILE_DOWNLOAD = "com.winlator.Download.action.RESOLVE_AND_START_GOFILE_DOWNLOAD";
    public static final String EXTRA_GOFILE_URL = "com.winlator.Download.extra.GOFILE_URL";
    public static final String EXTRA_GOFILE_PASSWORD = "com.winlator.Download.extra.GOFILE_PASSWORD";
    public static final String EXTRA_AUTH_TOKEN = "com.winlator.Download.extra.AUTH_TOKEN";

    public static final String ACTION_RESOLVE_AND_START_MEDIAFIRE_DOWNLOAD = "com.winlator.Download.action.RESOLVE_MEDIAFIRE_DOWNLOAD";
    public static final String EXTRA_MEDIAFIRE_URL = "com.winlator.Download.extra.MEDIAFIRE_URL";

    public static final String ACTION_RESOLVE_AND_START_GOOGLE_DRIVE_DOWNLOAD = "com.winlator.Download.action.RESOLVE_GOOGLE_DRIVE_DOWNLOAD";
    public static final String EXTRA_GOOGLE_DRIVE_URL = "com.winlator.Download.extra.GOOGLE_DRIVE_URL";

    public static final String ACTION_RESOLVE_AND_START_PIXELDRAIN_DOWNLOAD = "com.winlator.Download.action.RESOLVE_PIXELDRAIN_DOWNLOAD";
    public static final String EXTRA_PIXELDRAIN_URL = "com.winlator.Download.extra.PIXELDRAIN_URL";

    public static final String ACTION_DOWNLOAD_PROGRESS = "com.winlator.Download.action.DOWNLOAD_PROGRESS";
    public static final String ACTION_DOWNLOAD_STATUS_CHANGED = "com.winlator.Download.action.DOWNLOAD_STATUS_CHANGED";

    private static final String CHANNEL_ID = "WinlatorDownloadChannel";
    private static final int NOTIFICATION_ID_BASE = 1000;
    private static final int GENERIC_SERVICE_NOTIFICATION_ID = NOTIFICATION_ID_BASE - 2;

    private NotificationManager notificationManager;
    private SQLiteHelper dbHelper;
    private final IBinder binder = new DownloadBinder();
    private LocalBroadcastManager broadcastManager;
    private ExecutorService executor;
    private final Map<Long, DownloadTask> activeDownloads = new ConcurrentHashMap<>();
    private final Map<Long, NotificationCompat.Builder> activeNotifications = new ConcurrentHashMap<>();
    private Handler mainThreadHandler;

    public class DownloadBinder extends Binder {
        public DownloadService getService() {
            return DownloadService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            dbHelper = new SQLiteHelper(this);
            broadcastManager = LocalBroadcastManager.getInstance(this);
            createNotificationChannel();
            if (executor == null || executor.isShutdown()) {
                executor = Executors.newSingleThreadExecutor();
            }
            if (mainThreadHandler == null) {
                mainThreadHandler = new Handler(Looper.getMainLooper());
            }
            verifyAndCorrectDownloadStatuses();
        } catch (Throwable t) {
            Log.e(TAG, "Critical error during DownloadService onCreate", t);
        }
    }

    private Notification createPreparingNotification(String fileName) {
        // ... (implementation as before)
        Intent notificationIntent = new Intent(this, DownloadManagerActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(fileName)
            .setContentText("Preparando download...")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.w(TAG, "onStartCommand received a null intent.");
            checkStopForeground();
            return START_STICKY;
        }

        // Ensure generic foreground notification is shown if service starts without a specific task
        // This logic might need adjustment based on how startForeground is managed by tasks
        if (activeDownloads.isEmpty()) {
             Notification genericNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download) // Ensure this drawable exists
                .setContentTitle("Download Service")
                .setContentText("Serviço de download ativo...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true) // Make it ongoing if it's for keeping service alive
                .build();
            startForeground(GENERIC_SERVICE_NOTIFICATION_ID, genericNotification);
            Log.d(TAG, "onStartCommand: Generic startForeground initiated.");
        }


        String action = intent.getStringExtra(EXTRA_ACTION);
        if (action == null) {
            if (intent.hasExtra(EXTRA_GOFILE_URL)) {
                action = ACTION_RESOLVE_AND_START_GOFILE_DOWNLOAD;
            } else if (intent.hasExtra(EXTRA_MEDIAFIRE_URL)) {
                action = ACTION_RESOLVE_AND_START_MEDIAFIRE_DOWNLOAD;
            } else if (intent.hasExtra(EXTRA_GOOGLE_DRIVE_URL)) {
                action = ACTION_RESOLVE_AND_START_GOOGLE_DRIVE_DOWNLOAD;
            } else if (intent.hasExtra(EXTRA_PIXELDRAIN_URL)) { // Added check for PixelDrain URL
                action = ACTION_RESOLVE_AND_START_PIXELDRAIN_DOWNLOAD;
            } else if (intent.hasExtra(EXTRA_URL)) {
                action = ACTION_START_DOWNLOAD;
            } else {
                Log.w(TAG, "onStartCommand: Intent has no action and no recognizable URL extra. Stopping.");
                checkStopForeground();
                stopSelf();
                return START_STICKY;
            }
        }
        Log.d(TAG, "onStartCommand: Effective action: " + action);

        if (executor == null || executor.isShutdown()) { // Ensure executor is always alive before use
            executor = Executors.newSingleThreadExecutor();
        }

        switch (action) {
            case ACTION_START_DOWNLOAD:
                handleStartDownload(intent);
                break;
            case ACTION_RESOLVE_AND_START_GOFILE_DOWNLOAD:
                // ... (Gofile handling remains the same)
                String gofileUrl = intent.getStringExtra(EXTRA_GOFILE_URL);
                String gofilePassword = intent.getStringExtra(EXTRA_GOFILE_PASSWORD);
                Log.d(TAG, "onStartCommand: ACTION_RESOLVE_AND_START_GOFILE_DOWNLOAD for URL: " + gofileUrl);
                if (gofileUrl != null && !gofileUrl.isEmpty()) {
                    if (executor == null || executor.isShutdown()) { executor = Executors.newSingleThreadExecutor(); }
                    executor.execute(() -> handleResolveGofileUrl(gofileUrl, gofilePassword));
                } else { Log.e(TAG, "Gofile URL is missing for RESOLVE action."); }
                break;
            case ACTION_RESOLVE_AND_START_MEDIAFIRE_DOWNLOAD:
                // ... (MediaFire handling remains the same)
                String mediafireUrl = intent.getStringExtra(EXTRA_MEDIAFIRE_URL);
                Log.d(TAG, "onStartCommand: ACTION_RESOLVE_AND_START_MEDIAFIRE_DOWNLOAD for URL: " + mediafireUrl);
                if (mediafireUrl != null && !mediafireUrl.isEmpty()) {
                    if (executor == null || executor.isShutdown()) { executor = Executors.newSingleThreadExecutor(); }
                    executor.execute(() -> handleResolveMediafireUrl(mediafireUrl));
                } else { Log.e(TAG, "MediaFire URL is missing for RESOLVE action."); }
                break;
            case ACTION_RESOLVE_AND_START_GOOGLE_DRIVE_DOWNLOAD:
                // ... (Google Drive handling remains the same)
                String googleDriveUrl = intent.getStringExtra(EXTRA_GOOGLE_DRIVE_URL);
                Log.d(TAG, "onStartCommand: ACTION_RESOLVE_AND_START_GOOGLE_DRIVE_DOWNLOAD for URL: " + googleDriveUrl);
                if (googleDriveUrl != null && !googleDriveUrl.isEmpty()) {
                     if (executor == null || executor.isShutdown()) { executor = Executors.newSingleThreadExecutor(); }
                    executor.execute(() -> handleResolveGoogleDriveUrl(googleDriveUrl));
                } else { Log.e(TAG, "Google Drive URL is missing for RESOLVE action."); }
                break;
            case ACTION_RESOLVE_AND_START_PIXELDRAIN_DOWNLOAD: // New case for PixelDrain
                String pixeldrainUrl = intent.getStringExtra(EXTRA_PIXELDRAIN_URL);
                Log.d(TAG, "onStartCommand: ACTION_RESOLVE_AND_START_PIXELDRAIN_DOWNLOAD for URL: " + pixeldrainUrl);
                if (pixeldrainUrl != null && !pixeldrainUrl.isEmpty()) {
                    if (executor == null || executor.isShutdown()) { executor = Executors.newSingleThreadExecutor(); }
                    executor.execute(() -> handleResolvePixeldrainUrl(pixeldrainUrl));
                } else {
                    Log.e(TAG, "PixelDrain URL is missing for RESOLVE action.");
                }
                break;
            // ... (other existing cases: PAUSE, RESUME, CANCEL, RETRY, default) ...
            case ACTION_PAUSE_DOWNLOAD: handlePauseDownload(intent); break;
            case ACTION_RESUME_DOWNLOAD: handleResumeDownload(intent); break;
            case ACTION_CANCEL_DOWNLOAD: handleCancelDownload(intent); break;
            case ACTION_RETRY_DOWNLOAD: handleRetryDownload(intent); break;
            default:
                // ... (default handling remains the same)
                Log.w(TAG, "onStartCommand: Received unknown or unhandled action: " + action);
                Notification unknownActionNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_cancel)
                    .setContentTitle("Download Service")
                    .setContentText("Ação de download desconhecida: " + action)
                    .setAutoCancel(true)
                    .build();
                if (notificationManager != null) {
                     notificationManager.notify(GENERIC_SERVICE_NOTIFICATION_ID, unknownActionNotification);
                }
                checkStopForeground();
                stopSelf();
                break;
        }
        return START_STICKY;
    }

    private void verifyAndCorrectDownloadStatuses() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String[] projection = { DownloadContract.DownloadEntry._ID };
        String selection = DownloadContract.DownloadEntry.COLUMN_NAME_STATUS + " = ?";
        String[] selectionArgs = { String.valueOf(Download.STATUS_DOWNLOADING) };

        Cursor cursor = db.query(
            DownloadContract.DownloadEntry.TABLE_NAME, projection, selection, selectionArgs, null, null, null );

        boolean statusChanged = false;
        if (cursor != null) {
            while (cursor.moveToNext()) {
                long downloadId = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadContract.DownloadEntry._ID));
                if (!activeDownloads.containsKey(downloadId)) {
                    Log.w(TAG, "Correcting status for orphaned download ID: " + downloadId);
                    updateDownloadStatus(downloadId, Download.STATUS_PAUSED);
                    statusChanged = true;
                }
            }
            cursor.close();
        }
        if (statusChanged) {
            Intent broadcastIntent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED);
            broadcastManager.sendBroadcast(broadcastIntent);
        }
    }

    private void handleStartDownload(Intent intent) {
        Log.i(TAG, "handleStartDownload: Entry. Action: " + intent.getAction());
        String urlString = intent.getStringExtra(EXTRA_URL);
        String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
        final String authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN);
        Log.d(TAG, "handleStartDownload: URL: '" + urlString + "', FileName: '" + fileName + "', AuthToken: " + (authToken != null ? "present" : "null"));

        final int PREPARING_NOTIFICATION_ID = NOTIFICATION_ID_BASE - 1;

        if (urlString == null || urlString.trim().isEmpty() || fileName == null || fileName.trim().isEmpty()) {
            Log.e(TAG, "handleStartDownload: Invalid or missing URL/FileName.");
            Notification invalidRequestNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cancel).setContentTitle("Download Falhou")
                .setContentText("Pedido de download inválido.").setAutoCancel(true).build();
            startForeground(PREPARING_NOTIFICATION_ID, invalidRequestNotification); // Use startForeground for this temp notification
            new Handler(Looper.getMainLooper()).postDelayed(() -> { // Auto-cancel after delay
                if (notificationManager != null) notificationManager.cancel(PREPARING_NOTIFICATION_ID);
                checkStopForeground(); // Then check if service can stop
            }, 5000);
            return; // Do not proceed
        }
        if (!URLUtil.isValidUrl(urlString)) {
             Log.e(TAG, "handleStartDownload: URL is syntactically invalid: '" + urlString);
             Notification invalidUrlNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cancel).setContentTitle("Download Falhou")
                .setContentText("URL de download inválida.").setAutoCancel(true).build();
            startForeground(PREPARING_NOTIFICATION_ID, invalidUrlNotification);
             new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (notificationManager != null) notificationManager.cancel(PREPARING_NOTIFICATION_ID);
                checkStopForeground();
            }, 5000);
            return;
        }

        Notification preparingNotification = createPreparingNotification(fileName);
        startForeground(PREPARING_NOTIFICATION_ID, preparingNotification);

        final String effectiveAuthToken = authToken;
        executor.execute(() -> {
            for (DownloadTask existingTask : activeDownloads.values()) {
                if (existingTask.urlString.equals(urlString)) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (notificationManager != null) notificationManager.cancel(PREPARING_NOTIFICATION_ID);
                        Toast.makeText(DownloadService.this, "Este arquivo já está sendo baixado", Toast.LENGTH_SHORT).show();
                         checkStopForeground(); // Important if this was the only "new" task
                    });
                    return;
                }
            }

            long downloadId = getDownloadIdByUrl(urlString);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (notificationManager != null) notificationManager.cancel(PREPARING_NOTIFICATION_ID);
                if (downloadId != -1) {
                    Download existingDownload = getDownloadById(downloadId);
                    if (existingDownload != null) {
                        if (existingDownload.getStatus() == Download.STATUS_COMPLETED) {
                            Toast.makeText(DownloadService.this, "Este arquivo já foi baixado", Toast.LENGTH_SHORT).show();
                        } else if (existingDownload.getStatus() == Download.STATUS_PAUSED || existingDownload.getStatus() == Download.STATUS_FAILED) {
                            startDownload(existingDownload.getId(), existingDownload.getUrl(), existingDownload.getFileName(), effectiveAuthToken);
                        } else if (existingDownload.getStatus() == Download.STATUS_DOWNLOADING) {
                            startDownload(existingDownload.getId(), existingDownload.getUrl(), existingDownload.getFileName(), effectiveAuthToken);
                        }
                    } else {
                        final long newDownloadIdAfterNull = insertDownload(urlString, fileName);
                        if (newDownloadIdAfterNull != -1) {
                            startDownload(newDownloadIdAfterNull, urlString, fileName, effectiveAuthToken);
                        } else { Toast.makeText(DownloadService.this, "Erro ao iniciar download.", Toast.LENGTH_SHORT).show(); }
                    }
                } else {
                    final long newDownloadId = insertDownload(urlString, fileName);
                    if (newDownloadId != -1) {
                        startDownload(newDownloadId, urlString, fileName, effectiveAuthToken);
                    } else { Toast.makeText(DownloadService.this, "Erro ao iniciar download.", Toast.LENGTH_SHORT).show(); }
                }
                checkStopForeground(); // Check after handling or starting a task
            });
        });
    }
    public void handlePauseDownload(long downloadId) {
        DownloadTask task = activeDownloads.get(downloadId);
        if (task != null) {
            task.pause();
            // Status and notification updates are handled by the task's onPostExecute or a direct call after pause confirmation
        }
    }
    private void handlePauseDownload(Intent intent) {
        long downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1);
        if (downloadId != -1) handlePauseDownload(downloadId);
    }
    public void handleResumeDownload(long downloadId) {
        Download download = getDownloadById(downloadId);
        if (activeDownloads.containsKey(downloadId)) { Log.w(TAG, "Download task already active for ID: " + downloadId); return; }
        if (download != null && (download.getStatus() == Download.STATUS_PAUSED || download.getStatus() == Download.STATUS_FAILED)) {
            startDownload(downloadId, download.getUrl(), download.getFileName(), null); // Auth token not persisted for resume yet
        }
    }
    private void handleResumeDownload(Intent intent) {
        long downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1);
        if (downloadId != -1) handleResumeDownload(downloadId);
    }
    public void handleCancelDownload(long downloadId) {
        DownloadTask task = activeDownloads.get(downloadId);
        if (task != null) {
            task.cancel(true);
        } else { // If task not active, ensure cleanup from DB and notification
            if (notificationManager != null) notificationManager.cancel((int) (NOTIFICATION_ID_BASE + downloadId));
            activeNotifications.remove(downloadId);
            Download download = getDownloadById(downloadId);
            if (download != null && download.getLocalPath() != null && !download.getLocalPath().isEmpty()) {
                try { new File(download.getLocalPath()).delete(); } catch (Exception e) { Log.e(TAG, "Error deleting file", e); }
            }
            deleteDownload(downloadId); // This also sends broadcast
            checkStopForeground();
        }
    }
    private void handleCancelDownload(Intent intent) {
        long downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1);
        if (downloadId != -1) handleCancelDownload(downloadId);
    }
    private void handleRetryDownload(long downloadId) {
        Download download = getDownloadById(downloadId);
        if (activeDownloads.containsKey(downloadId)) { Log.w(TAG, "Download task already active for ID: " + downloadId); return; }
        if (download != null && download.getStatus() == Download.STATUS_FAILED) {
            ContentValues values = new ContentValues();
            values.put(DownloadContract.DownloadEntry.COLUMN_NAME_DOWNLOADED_BYTES, 0);
            values.put(DownloadContract.DownloadEntry.COLUMN_NAME_STATUS, Download.STATUS_PENDING);
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.update(DownloadContract.DownloadEntry.TABLE_NAME, values, DownloadContract.DownloadEntry._ID + " = ?", new String[] { String.valueOf(downloadId) });
            startDownload(downloadId, download.getUrl(), download.getFileName(), null); // Auth token not persisted for retry
        }
    }
    private void handleRetryDownload(Intent intent) {
        long downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1);
        if (downloadId != -1) handleRetryDownload(downloadId);
    }
    private void startDownload(long downloadId, String urlString, String fileName, String authToken) {
        NotificationCompat.Builder builder = createOrUpdateNotificationBuilder(downloadId, fileName);
        activeNotifications.put(downloadId, builder);
        startForeground((int) (NOTIFICATION_ID_BASE + downloadId), builder.build());
        updateDownloadStatus(downloadId, Download.STATUS_DOWNLOADING);
        DownloadTask task = new DownloadTask(downloadId, urlString, fileName, builder, authToken);
        activeDownloads.put(downloadId, task);
        task.execute();
        Intent broadcastIntent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED);
        broadcastIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        broadcastManager.sendBroadcast(broadcastIntent);
    }
    private void startDownload(long downloadId, String urlString, String fileName) { startDownload(downloadId, urlString, fileName, null); }

    private NotificationCompat.Builder createOrUpdateNotificationBuilder(long downloadId, String fileName) {
        Intent notificationIntent = new Intent(this, DownloadManagerActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, (int) downloadId, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent pauseIntent = new Intent(this, DownloadService.class);
        pauseIntent.putExtra(EXTRA_ACTION, ACTION_PAUSE_DOWNLOAD);
        pauseIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        PendingIntent pausePendingIntent = PendingIntent.getService(this, (int) (downloadId + 100), pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        Intent cancelIntent = new Intent(this, DownloadService.class);
        cancelIntent.putExtra(EXTRA_ACTION, ACTION_CANCEL_DOWNLOAD);
        cancelIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        PendingIntent cancelPendingIntent = PendingIntent.getService(this, (int) (downloadId + 200), cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download).setContentTitle(fileName)
            .setContentText("Iniciando download...").setProgress(100, 0, true)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_pause, "Pausar", pausePendingIntent)
            .addAction(R.drawable.ic_cancel, "Cancelar", cancelPendingIntent);
    }
    private void updateNotificationProgress(long downloadId, int progress, long downloadedBytes, long totalBytes, double speed) {
        NotificationCompat.Builder builder = activeNotifications.get(downloadId);
        if (builder != null) {
            double speedMbps = bytesToMB((long)speed); String contentText;
            if (totalBytes > 0) {
                 contentText = String.format("%.1f / %.1f MB (%.2f Mbps)", bytesToMB(downloadedBytes), bytesToMB(totalBytes), speedMbps);
                 builder.setProgress(100, progress, false);
            } else {
                 contentText = String.format("%.1f MB (%.2f Mbps)", bytesToMB(downloadedBytes), speedMbps);
                 builder.setProgress(0, 0, true);
            }
            builder.setContentText(contentText);
            if (notificationManager != null) notificationManager.notify((int) (NOTIFICATION_ID_BASE + downloadId), builder.build());
        }
    }
    private void updateNotificationPaused(long downloadId) {
        NotificationCompat.Builder builder = activeNotifications.get(downloadId);
        if (builder != null) {
            Download download = getDownloadById(downloadId);
            if (download != null) {
                builder.mActions.clear();
                Intent resumeIntent = new Intent(this, DownloadService.class);
                resumeIntent.putExtra(EXTRA_ACTION, ACTION_RESUME_DOWNLOAD);
                resumeIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
                PendingIntent resumePendingIntent = PendingIntent.getService(this, (int) (downloadId + 300), resumeIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Intent cancelIntent = new Intent(this, DownloadService.class);
                cancelIntent.putExtra(EXTRA_ACTION, ACTION_CANCEL_DOWNLOAD);
                cancelIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
                PendingIntent cancelPendingIntent = PendingIntent.getService(this, (int) (downloadId + 200), cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                builder.setContentTitle(download.getFileName() + " (Pausado)")
                       .setContentText(download.getFormattedDownloadedSize() + " / " + download.getFormattedTotalSize())
                       .setOngoing(false).setOnlyAlertOnce(true).setProgress(100, download.getProgress(), false)
                       .addAction(R.drawable.ic_play, "Continuar", resumePendingIntent)
                       .addAction(R.drawable.ic_cancel, "Cancelar", cancelPendingIntent);
                if (notificationManager != null) notificationManager.notify((int) (NOTIFICATION_ID_BASE + downloadId), builder.build());
            }
        }
    }
    private void updateNotificationComplete(long downloadId, String fileName, File downloadedFile) {
        NotificationCompat.Builder builder = activeNotifications.get(downloadId);
        if (builder == null) { // Should ideally not happen if task was active, but handle defensively
             Intent nIntent = new Intent(this, DownloadManagerActivity.class);
             PendingIntent pIntent = PendingIntent.getActivity(this, (int) downloadId, nIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
             builder = new NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_download).setContentIntent(pIntent);
        }
        builder.mActions.clear(); PendingIntent contentIntent = null;
        if (downloadedFile != null && downloadedFile.exists()) {
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            Uri fileUri = FileProvider.getUriForFile(this, getApplicationContext().getPackageName() + ".provider", downloadedFile);
            installIntent.setDataAndType(fileUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            contentIntent = PendingIntent.getActivity(this, (int) (downloadId + 400), installIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
             Intent managerIntent = new Intent(this, DownloadManagerActivity.class);
             contentIntent = PendingIntent.getActivity(this, (int) downloadId, managerIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        builder.setContentTitle(fileName + " - Download Concluído").setContentText("Toque para instalar")
               .setProgress(0, 0, false).setOngoing(false).setAutoCancel(true).setOnlyAlertOnce(false);
        if (contentIntent != null) builder.setContentIntent(contentIntent);
        if (notificationManager != null) notificationManager.notify((int) (NOTIFICATION_ID_BASE + downloadId), builder.build());
        activeNotifications.remove(downloadId);
        checkStopForeground();
    }
    private void updateNotificationError(long downloadId, String fileName) {
        NotificationCompat.Builder builder = activeNotifications.get(downloadId);
         if (builder == null) {
             Intent nIntent = new Intent(this, DownloadManagerActivity.class);
             PendingIntent pIntent = PendingIntent.getActivity(this, (int) downloadId, nIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
             builder = new NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_download).setContentIntent(pIntent);
        }
        builder.mActions.clear();
        Intent retryIntent = new Intent(this, DownloadService.class);
        retryIntent.putExtra(EXTRA_ACTION, ACTION_RETRY_DOWNLOAD);
        retryIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        PendingIntent retryPendingIntent = PendingIntent.getService(this, (int) (downloadId + 500), retryIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancelIntent = new Intent(this, DownloadService.class);
        cancelIntent.putExtra(EXTRA_ACTION, ACTION_CANCEL_DOWNLOAD);
        cancelIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        PendingIntent cancelPendingIntent = PendingIntent.getService(this, (int) (downloadId + 200), cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setContentTitle(fileName + " - Download Falhou").setContentText("Ocorreu um erro durante o download")
               .setProgress(0, 0, false).setOngoing(false).setAutoCancel(true).setOnlyAlertOnce(false)
               .addAction(R.drawable.ic_play, "Tentar novamente", retryPendingIntent)
               .addAction(R.drawable.ic_cancel, "Remover", cancelPendingIntent);
        if (notificationManager != null) notificationManager.notify((int) (NOTIFICATION_ID_BASE + downloadId), builder.build());
        activeNotifications.remove(downloadId);
        checkStopForeground();
    }

    // Resolver Handlers
    private void handleResolveGofileUrl(String gofileUrl, String password) {
        Log.i(TAG, "handleResolveGofileUrl: Starting resolution for " + gofileUrl);
        GofileLinkResolver resolver = new GofileLinkResolver();
        GofileResolvedResult resolvedResult = resolver.resolveGofileUrl(gofileUrl, password);

        if (resolvedResult != null && resolvedResult.hasItems()) {
            Log.i(TAG, "Gofile resolution successful. Found " + resolvedResult.getItems().size() + " items.");
            for (DownloadItem item : resolvedResult.getItems()) {
                Intent downloadIntent = new Intent(this, DownloadService.class);
                downloadIntent.putExtra(EXTRA_ACTION, ACTION_START_DOWNLOAD);
                downloadIntent.putExtra(EXTRA_URL, item.directUrl);
                downloadIntent.putExtra(EXTRA_FILE_NAME, item.fileName);
                downloadIntent.putExtra(EXTRA_AUTH_TOKEN, resolvedResult.getAuthToken());
                mainThreadHandler.post(() -> handleStartDownload(downloadIntent));
            }
        } else {
            Log.e(TAG, "Gofile resolution failed or no items found for " + gofileUrl);
            final String userMessage = "Falha ao resolver link Gofile: " +
                (gofileUrl != null && gofileUrl.lastIndexOf('/') != -1 && gofileUrl.lastIndexOf('/') < gofileUrl.length() -1 ?
                gofileUrl.substring(gofileUrl.lastIndexOf('/') + 1) : "Desconhecido");
            mainThreadHandler.post(() -> Toast.makeText(DownloadService.this, userMessage, Toast.LENGTH_LONG).show());
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cancel)
                .setContentTitle("Erro no Link Gofile")
                .setContentText("Não foi possível resolver arquivos do link Gofile.")
                .setAutoCancel(true);
            if (notificationManager != null) {
                notificationManager.notify((int) (System.currentTimeMillis() % 10000), builder.build());
            }
        }
    }

    private void handleResolveMediafireUrl(String pageUrl) {
        Log.i(TAG, "handleResolveMediafireUrl: Starting resolution for " + pageUrl);
        MediafireLinkResolver resolver = new MediafireLinkResolver();
        DownloadItem resolvedItem = resolver.resolveMediafireUrl(pageUrl);

        if (resolvedItem != null && resolvedItem.directUrl != null && !resolvedItem.directUrl.isEmpty()) {
            Log.i(TAG, "MediaFire resolution successful. Filename: " + resolvedItem.fileName + ", URL: " + resolvedItem.directUrl);
            Intent downloadIntent = new Intent(this, DownloadService.class);
            downloadIntent.putExtra(EXTRA_ACTION, ACTION_START_DOWNLOAD);
            downloadIntent.putExtra(EXTRA_URL, resolvedItem.directUrl);
            downloadIntent.putExtra(EXTRA_FILE_NAME, resolvedItem.fileName);
            mainThreadHandler.post(() -> handleStartDownload(downloadIntent));
        } else {
            Log.e(TAG, "MediaFire resolution failed or no items found for " + pageUrl);
            final String userMessage = "Falha ao resolver link MediaFire: " +
                (pageUrl != null && pageUrl.lastIndexOf('/') != -1 && pageUrl.lastIndexOf('/') < pageUrl.length() -1 ?
                pageUrl.substring(pageUrl.lastIndexOf('/') + 1) : "Desconhecido");
            mainThreadHandler.post(() -> Toast.makeText(DownloadService.this, userMessage, Toast.LENGTH_LONG).show());
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cancel)
                .setContentTitle("Erro no Link MediaFire")
                .setContentText("Não foi possível resolver arquivos do link MediaFire.")
                .setAutoCancel(true);
            if (notificationManager != null) {
                 notificationManager.notify((int) (System.currentTimeMillis() % 10000) + 1, builder.build());
            }
        }
    }

    private void handleResolveGoogleDriveUrl(String pageUrl) {
        Log.i(TAG, "handleResolveGoogleDriveUrl: Starting resolution for " + pageUrl);
        GoogleDriveLinkResolver resolver = new GoogleDriveLinkResolver();
        DownloadItem resolvedItem = resolver.resolveDriveUrl(pageUrl);

        if (resolvedItem != null && resolvedItem.directUrl != null && !resolvedItem.directUrl.isEmpty()) { // Corrected check
            Log.i(TAG, "Google Drive resolution successful. Filename: " + resolvedItem.fileName + ", URL: " + resolvedItem.directUrl + ", Size: " + resolvedItem.size);
            Intent downloadIntent = new Intent(this, DownloadService.class);
            downloadIntent.putExtra(EXTRA_ACTION, ACTION_START_DOWNLOAD);
            downloadIntent.putExtra(EXTRA_URL, resolvedItem.directUrl);
            downloadIntent.putExtra(EXTRA_FILE_NAME, resolvedItem.fileName);
            mainThreadHandler.post(() -> handleStartDownload(downloadIntent));
        } else {
            Log.e(TAG, "Google Drive resolution failed or no items found for " + pageUrl);
            String displayUrl = pageUrl;
            if (pageUrl != null) {
                String id = GoogleDriveUtils.extractDriveId(pageUrl);
                if (id != null) displayUrl = "ID: " + id;
                else if (pageUrl.lastIndexOf('/') != -1 && pageUrl.lastIndexOf('/') < pageUrl.length() -1) {
                     displayUrl = pageUrl.substring(pageUrl.lastIndexOf('/') + 1);
                }
            } else { displayUrl = "Desconhecido"; }

            final String userMessage = "Falha ao resolver link Google Drive: " + displayUrl;
            mainThreadHandler.post(() -> Toast.makeText(DownloadService.this, userMessage, Toast.LENGTH_LONG).show());
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cancel)
                .setContentTitle("Erro no Link Google Drive")
                .setContentText("Não foi possível resolver arquivos do link Google Drive.")
                .setAutoCancel(true);
            if (notificationManager != null) {
                 notificationManager.notify((int) (System.currentTimeMillis() % 10000) + 2, builder.build());
            }
        }
    }

    private void handleResolvePixeldrainUrl(String pageUrl) {
        Log.i(TAG, "handleResolvePixeldrainUrl: Starting resolution for " + pageUrl);
        PixeldrainLinkResolver resolver = new PixeldrainLinkResolver();
        DownloadItem resolvedItem = resolver.resolvePixeldrainUrl(pageUrl);

        if (resolvedItem != null && resolvedItem.directUrl != null && !resolvedItem.directUrl.isEmpty()) {
            Log.i(TAG, "PixelDrain resolution successful. Filename: " + resolvedItem.fileName + ", URL: " + resolvedItem.directUrl);
            Intent downloadIntent = new Intent(this, DownloadService.class);
            downloadIntent.putExtra(EXTRA_ACTION, ACTION_START_DOWNLOAD);
            downloadIntent.putExtra(EXTRA_URL, resolvedItem.directUrl);
            downloadIntent.putExtra(EXTRA_FILE_NAME, resolvedItem.fileName);
            mainThreadHandler.post(() -> handleStartDownload(downloadIntent));
        } else {
            Log.e(TAG, "PixelDrain resolution failed for " + pageUrl);
            String displayUrl = pageUrl;
            if (pageUrl != null) {
                String id = PixeldrainLinkResolver.extractFileId(pageUrl);
                if (id != null && !id.isEmpty()) displayUrl = "ID: " + id;
                else if (pageUrl.lastIndexOf('/') != -1 && pageUrl.lastIndexOf('/') < pageUrl.length() -1) {
                     displayUrl = pageUrl.substring(pageUrl.lastIndexOf('/') + 1);
                }
            } else { displayUrl = "Desconhecido"; }

            final String userMessage = "Falha ao processar link PixelDrain: " + displayUrl;
            mainThreadHandler.post(() -> Toast.makeText(DownloadService.this, userMessage, Toast.LENGTH_LONG).show());

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID);
            builder.setSmallIcon(R.drawable.ic_cancel)
                   .setContentTitle("Erro no Link PixelDrain")
                   .setContentText("Não foi possível processar o link PixelDrain.")
                   .setAutoCancel(true);

            if (notificationManager != null) {
                 notificationManager.notify((int) (System.currentTimeMillis() % 10000) + 3, builder.build());
            }
        }
    }

    private class DownloadTask extends AsyncTask<Void, Integer, File> {
        private final long downloadId;
        private final String urlString;
        private final String fileName;
        private final NotificationCompat.Builder notificationBuilder;
        private final String authToken;
        private boolean isPaused = false;
        private boolean isCancelled = false;
        private long totalBytes = -1;
        private long downloadedBytes = 0;
        private long startTime;
        private long lastUpdateTime = 0;
        private double speed = 0;

        DownloadTask(long downloadId, String urlString, String fileName, NotificationCompat.Builder builder, String authToken) {
            this.downloadId = downloadId;
            this.urlString = urlString;
            this.fileName = fileName;
            this.notificationBuilder = builder;
            this.authToken = authToken;
        }

        DownloadTask(long downloadId, String urlString, String fileName, NotificationCompat.Builder builder) {
            this(downloadId, urlString, fileName, builder, null);
        }

        public void pause() { isPaused = true; }
        // onPreExecute, doInBackground, onProgressUpdate, onPostExecute, onCancelled as before
        @Override
        protected void onPreExecute() {
            startTime = System.currentTimeMillis();
            Download existingDownload = getDownloadById(downloadId);
            if (existingDownload != null && existingDownload.getDownloadedBytes() > 0) {
                downloadedBytes = existingDownload.getDownloadedBytes();
                totalBytes = existingDownload.getTotalBytes();
            }
        }
        @Override
        protected File doInBackground(Void... params) {
            InputStream input = null; OutputStream output = null; HttpURLConnection connection = null;
            File downloadedFile = null; RandomAccessFile randomAccessFile = null;
            try {
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists()) downloadDir.mkdirs();
                downloadedFile = new File(downloadDir, fileName);
                updateDownloadLocalPath(downloadId, downloadedFile.getAbsolutePath());
                
                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();
                if (this.authToken != null && !this.authToken.isEmpty()) {
                    connection.setRequestProperty("Cookie", "accountToken=" + this.authToken);
                }
                if (downloadedBytes > 0) {
                    connection.setRequestProperty("Range", "bytes=" + downloadedBytes + "-");
                }
                connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
                connection.connect();
                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    updateDownloadStatus(downloadId, Download.STATUS_FAILED); return null;
                }
                if (totalBytes <= 0) { /* ... (logic to get totalBytes from Content-Length/Content-Range) ... */
                    long cl = connection.getContentLength(); totalBytes = (responseCode == HttpURLConnection.HTTP_PARTIAL) ? downloadedBytes + cl : cl;
                    if (totalBytes <=0) totalBytes = -1; updateDownloadTotalBytes(downloadId, totalBytes);
                }

                if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    randomAccessFile = new RandomAccessFile(downloadedFile, "rw"); randomAccessFile.seek(downloadedBytes);
                    output = new FileOutputStream(randomAccessFile.getFD());
                } else {
                    downloadedBytes = 0; output = new FileOutputStream(downloadedFile);
                }
                input = new BufferedInputStream(connection.getInputStream()); byte[] data = new byte[8192]; int count;
                long bytesSinceLastUpdate = 0;
                while ((count = input.read(data)) != -1) {
                    if (isCancelled) return null;
                    if (isPaused) { updateDownloadProgress(downloadId, downloadedBytes, totalBytes); return null; }
                    downloadedBytes += count; bytesSinceLastUpdate += count; output.write(data, 0, count);
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastUpdateTime > 500 || bytesSinceLastUpdate > (1024 * 1024)) {
                        updateDownloadProgress(downloadId, downloadedBytes, totalBytes);
                        if (elapsedTime > 500) speed = (double) downloadedBytes / ((currentTime - startTime) / 1000.0);
                        publishProgress(totalBytes > 0 ? (int) ((downloadedBytes * 100) / totalBytes) : -1);
                        Intent intent = new Intent(ACTION_DOWNLOAD_PROGRESS); /* ... (put extras) ... */ broadcastManager.sendBroadcast(intent);
                        bytesSinceLastUpdate = 0; lastUpdateTime = currentTime;
                    }
                }
                updateDownloadStatus(downloadId, Download.STATUS_COMPLETED); return downloadedFile;
            } catch (Exception e) {
                Log.e(TAG, "DownloadTask Exception for " + urlString, e);
                updateDownloadStatus(downloadId, Download.STATUS_FAILED); return null;
            } finally {
                try { if (output != null) output.close(); if (input != null) input.close();
                      if (randomAccessFile != null) randomAccessFile.close(); if (connection != null) connection.disconnect();
                } catch (IOException e) { Log.e(TAG, "Error closing streams", e); }
            }
        }
        @Override
        protected void onProgressUpdate(Integer... values) {
            updateNotificationProgress(downloadId, values[0], downloadedBytes, totalBytes, speed);
        }
        @Override
        protected void onPostExecute(File result) {
            activeDownloads.remove(downloadId);
            if (isPaused) { updateNotificationPaused(downloadId); } // Update notification on pause
            else if (result != null) { updateNotificationComplete(downloadId, fileName, result); }
            else { updateNotificationError(downloadId, fileName); }
            if (!isPaused) { Intent intent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED); intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId); broadcastManager.sendBroadcast(intent); }
            checkStopForeground();
        }
        @Override
        protected void onCancelled(File result) {
            activeDownloads.remove(downloadId);
            // Cleanup (notification, file, DB) is typically handled by handleCancelDownload, which should be called prior to task.cancel(true)
            // For safety, ensure notification is removed if handleCancelDownload wasn't robust enough
            if (notificationManager != null) notificationManager.cancel((int) (NOTIFICATION_ID_BASE + downloadId));
            activeNotifications.remove(downloadId);
            // Ensure DB status reflects cancellation if not already done
            Download d = getDownloadById(downloadId);
            if (d != null && d.getStatus() != Download.STATUS_CANCELLED) {
                updateDownloadStatus(downloadId, Download.STATUS_CANCELLED);
                // File deletion should be in handleCancelDownload
            }
            Intent intent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED); intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId); broadcastManager.sendBroadcast(intent);
            checkStopForeground();
        }
    }

    // Other methods (DB operations, checkStopForeground, etc.) as before
    private void checkStopForeground() { if (activeDownloads.isEmpty() && !hasActiveResolutions()) stopForeground(true); }
    private boolean hasActiveResolutions() { /* TODO: Future enhancement: track active resolution tasks */ return false; }
    private long insertDownload(String url, String fileName) { /* ... */ return 0; }
    private void updateDownloadProgress(long id, long dBytes, long tBytes) { /* ... */ }
    private void updateDownloadTotalBytes(long id, long tBytes) { /* ... */ }
    private void updateDownloadLocalPath(long id, String path) { /* ... */ }
    private void updateDownloadStatus(long id, int status) { /* ... */ }
    private void updateDownloadStatus(long id, int status, String path) { /* ... */ }
    private long getDownloadIdByUrl(String url) { /* ... */ return -1; }
    public Download getDownloadById(long id) { /* ... */ return null; }
    public List<Download> getAllDownloads() { /* ... */ return new ArrayList<>(); }
    public int clearCompletedDownloads() { /* ... */ return 0; }
    public boolean deleteDownload(long id) { /* ... */ return false; }
    public int deleteDownloads(List<Long> ids) { /* ... */ return 0; }
    private Download cursorToDownload(Cursor c) { /* ... */ return null; }
    private void createNotificationChannel() { /* ... */ }
    private double bytesToMB(long bytes) { /* ... */ return 0.0; }
    @Nullable @Override public IBinder onBind(Intent intent) { verifyAndCorrectDownloadStatuses(); return binder; }
    @Override public void onDestroy() { /* ... */ }
}
```
