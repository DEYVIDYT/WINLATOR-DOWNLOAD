package com.winlator.Download.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.webkit.URLUtil;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.winlator.Download.DownloadManagerActivity;
import com.winlator.Download.R;
import com.winlator.Download.db.DownloadContract;
import com.winlator.Download.db.SQLiteHelper;
import com.winlator.Download.model.Download;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future; // Added
import java.util.List; // Added
import java.util.ArrayList; // Added
import com.winlator.Download.utils.AppSettings; // Ensure this is imported

public class DownloadService extends Service {

    private static final String TAG = "DownloadService";
    public static final String EXTRA_URL = "com.winlator.Download.extra.URL";
    public static final String EXTRA_FILE_NAME = "com.winlator.Download.extra.FILE_NAME";
    public static final String EXTRA_DOWNLOAD_ID = "com.winlator.Download.extra.DOWNLOAD_ID";
    public static final String EXTRA_ACTION = "com.winlator.Download.extra.ACTION";

    // Ações para o serviço
    public static final String ACTION_START_DOWNLOAD = "com.winlator.Download.action.START_DOWNLOAD";
    public static final String ACTION_PAUSE_DOWNLOAD = "com.winlator.Download.action.PAUSE_DOWNLOAD";
    public static final String ACTION_RESUME_DOWNLOAD = "com.winlator.Download.action.RESUME_DOWNLOAD";
    public static final String ACTION_CANCEL_DOWNLOAD = "com.winlator.Download.action.CANCEL_DOWNLOAD";
    public static final String ACTION_RETRY_DOWNLOAD = "com.winlator.Download.action.RETRY_DOWNLOAD";

    // Gofile specific actions and extras
    public static final String ACTION_RESOLVE_AND_START_GOFILE_DOWNLOAD = "com.winlator.Download.action.RESOLVE_AND_START_GOFILE_DOWNLOAD";
    public static final String EXTRA_GOFILE_URL = "com.winlator.Download.extra.GOFILE_URL";
    public static final String EXTRA_GOFILE_PASSWORD = "com.winlator.Download.extra.GOFILE_PASSWORD"; // Optional
    public static final String EXTRA_AUTH_TOKEN = "com.winlator.Download.extra.AUTH_TOKEN";

    // MediaFire specific actions and extras
    public static final String ACTION_RESOLVE_AND_START_MEDIAFIRE_DOWNLOAD = "com.winlator.Download.action.RESOLVE_MEDIAFIRE_DOWNLOAD";
    public static final String EXTRA_MEDIAFIRE_URL = "com.winlator.Download.extra.MEDIAFIRE_URL";

    // Google Drive specific actions and extras
    public static final String ACTION_RESOLVE_AND_START_GOOGLE_DRIVE_DOWNLOAD = "com.winlator.Download.action.RESOLVE_GOOGLE_DRIVE_DOWNLOAD";
    public static final String EXTRA_GOOGLE_DRIVE_URL = "com.winlator.Download.extra.GOOGLE_DRIVE_URL";

    // Broadcast actions
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
    private Handler mainThreadHandler;
    
    // Mapa para armazenar as tarefas de download ativas
    private final Map<Long, DownloadTask> activeDownloads = new ConcurrentHashMap<>();
    // Mapa para armazenar as notificações ativas
    private final Map<Long, NotificationCompat.Builder> activeNotifications = new ConcurrentHashMap<>();

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
            if (executor == null || executor.isShutdown()) { // Ensure executor is initialized
                executor = Executors.newSingleThreadExecutor();
            }
            if (mainThreadHandler == null) { // Ensure initialized
                mainThreadHandler = new Handler(Looper.getMainLooper());
            }
            // Verificar e corrigir status de downloads ao iniciar o serviço
            verifyAndCorrectDownloadStatuses();
        } catch (Throwable t) {
            Log.e(TAG, "Critical error during DownloadService onCreate", t);
            // Depending on the severity, you might want to stop the service from continuing
            // if critical initializations failed. For now, just logging.
            // If dbHelper is null, for example, subsequent operations will fail.
        }
    }

    private Notification createPreparingNotification(String fileName) {
        Intent notificationIntent = new Intent(this, DownloadManagerActivity.class);
        // Use a unique request code for the PendingIntent if it might conflict with others.
        // For a temporary notification, request code 0 might be fine if not expecting updates.
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download) // Consider a distinct icon for "preparing" if available
            .setContentTitle(fileName)
            .setContentText("Preparando download...")
            .setProgress(0, 0, true) // Indeterminate progress
            .setOngoing(true) // Make it ongoing so user knows something is happening
            .setContentIntent(pendingIntent) // Optional: action when user taps
            .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.w(TAG, "onStartCommand received a null intent. Stopping service if no active tasks.");
            checkStopForeground();
            return START_STICKY;
        }

        Notification genericNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Download Service")
            .setContentText("Serviço de download ativo...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build();
        startForeground(GENERIC_SERVICE_NOTIFICATION_ID, genericNotification);
        Log.d(TAG, "onStartCommand: Initial generic startForeground completed.");

        String action = intent.getStringExtra(EXTRA_ACTION);
        if (action == null) {
            if (intent.hasExtra(EXTRA_GOFILE_URL)) {
                 action = ACTION_RESOLVE_AND_START_GOFILE_DOWNLOAD;
            } else if (intent.hasExtra(EXTRA_MEDIAFIRE_URL)) {
                 action = ACTION_RESOLVE_AND_START_MEDIAFIRE_DOWNLOAD;
            } else if (intent.hasExtra(EXTRA_GOOGLE_DRIVE_URL)) { // Added check for Google Drive URL
                 action = ACTION_RESOLVE_AND_START_GOOGLE_DRIVE_DOWNLOAD;
            } else if (intent.hasExtra(EXTRA_URL)) {
                 action = ACTION_START_DOWNLOAD;
            } else {
                 Log.w(TAG, "onStartCommand: Intent has no action and no recognizable URL extra. Stopping.");
                 checkStopForeground();
                 stopSelf(); // Stop if intent is not understood
                 return START_STICKY;
            }
        }
        Log.d(TAG, "onStartCommand: Effective action: " + action);


        switch (action) {
            case ACTION_START_DOWNLOAD:
                handleStartDownload(intent);
                break;
            case ACTION_RESOLVE_AND_START_GOFILE_DOWNLOAD:
                String gofileUrl = intent.getStringExtra(EXTRA_GOFILE_URL);
                String gofilePassword = intent.getStringExtra(EXTRA_GOFILE_PASSWORD);
                Log.d(TAG, "onStartCommand: ACTION_RESOLVE_AND_START_GOFILE_DOWNLOAD for URL: " + gofileUrl);
                if (gofileUrl != null && !gofileUrl.isEmpty()) {
                    if (executor == null || executor.isShutdown()) {
                         executor = Executors.newSingleThreadExecutor();
                    }
                    executor.execute(() -> handleResolveGofileUrl(gofileUrl, gofilePassword));
                } else {
                    Log.e(TAG, "Gofile URL is missing for RESOLVE action.");
                }
                break;
            case ACTION_RESOLVE_AND_START_MEDIAFIRE_DOWNLOAD: // New case for MediaFire
                String mediafireUrl = intent.getStringExtra(EXTRA_MEDIAFIRE_URL);
                Log.d(TAG, "onStartCommand: ACTION_RESOLVE_AND_START_MEDIAFIRE_DOWNLOAD for URL: " + mediafireUrl);
                if (mediafireUrl != null && !mediafireUrl.isEmpty()) {
                    if (executor == null || executor.isShutdown()) {
                         executor = Executors.newSingleThreadExecutor();
                    }
                    executor.execute(() -> handleResolveMediafireUrl(mediafireUrl));
                } else {
                    Log.e(TAG, "MediaFire URL is missing for RESOLVE action.");
                }
                break;
            case ACTION_RESOLVE_AND_START_GOOGLE_DRIVE_DOWNLOAD: // New case for Google Drive
                String googleDriveUrl = intent.getStringExtra(EXTRA_GOOGLE_DRIVE_URL);
                Log.d(TAG, "onStartCommand: ACTION_RESOLVE_AND_START_GOOGLE_DRIVE_DOWNLOAD for URL: " + googleDriveUrl);
                if (googleDriveUrl != null && !googleDriveUrl.isEmpty()) {
                     if (executor == null || executor.isShutdown()) {
                         executor = Executors.newSingleThreadExecutor();
                    }
                    executor.execute(() -> handleResolveGoogleDriveUrl(googleDriveUrl));
                } else {
                    Log.e(TAG, "Google Drive URL is missing for RESOLVE action.");
                }
                break;
            // ... (other existing cases: PAUSE, RESUME, CANCEL, RETRY, default) ...
            case ACTION_PAUSE_DOWNLOAD:
                handlePauseDownload(intent);
                break;
            case ACTION_RESUME_DOWNLOAD:
                handleResumeDownload(intent);
                break;
            case ACTION_CANCEL_DOWNLOAD:
                handleCancelDownload(intent);
                break;
            case ACTION_RETRY_DOWNLOAD:
                handleRetryDownload(intent);
                break;
            default:
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

    private void handleResolveGofileUrl(String gofileUrl, String password) {
        Log.i(TAG, "handleResolveGofileUrl: Starting resolution for " + gofileUrl);
        GofileLinkResolver resolver = new GofileLinkResolver();
        GofileResolvedResult resolvedResult = resolver.resolveGofileUrl(gofileUrl, password);

        if (resolvedResult != null && resolvedResult.hasItems()) {
            Log.i(TAG, "Gofile resolution successful. Found " + resolvedResult.getItems().size() + " items. Token: " + resolvedResult.getAuthToken());
            for (DownloadItem item : resolvedResult.getItems()) {
                Intent downloadIntent = new Intent(this, DownloadService.class);
                downloadIntent.putExtra(EXTRA_ACTION, ACTION_START_DOWNLOAD);
                downloadIntent.putExtra(EXTRA_URL, item.directUrl);
                downloadIntent.putExtra(EXTRA_FILE_NAME, item.fileName);
                downloadIntent.putExtra(EXTRA_AUTH_TOKEN, resolvedResult.getAuthToken());
                Log.d(TAG, "Dispatching new download task for resolved Gofile item: " + item.fileName);
                mainThreadHandler.post(() -> handleStartDownload(downloadIntent));
            }
        } else {
            Log.e(TAG, "Gofile resolution failed or no items found for " + gofileUrl);
            final String userMessage = "Falha ao resolver link Gofile: " + (gofileUrl != null && gofileUrl.lastIndexOf('/') != -1 && gofileUrl.lastIndexOf('/') < gofileUrl.length() -1 ? gofileUrl.substring(gofileUrl.lastIndexOf('/') + 1) : "Desconhecido");
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


    // New method for MediaFire resolution
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
            // No EXTRA_AUTH_TOKEN needed for MediaFire direct links typically

            Log.d(TAG, "Dispatching new download task for resolved MediaFire item: " + resolvedItem.fileName);
            mainThreadHandler.post(() -> handleStartDownload(downloadIntent));

        } else {
            Log.e(TAG, "MediaFire resolution failed or no items found for " + pageUrl);
            final String userMessage = "Falha ao resolver link MediaFire: " + (pageUrl != null ? pageUrl.substring(pageUrl.lastIndexOf('/') + 1) : "Desconhecido");
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

    // New method for Google Drive resolution
    private void handleResolveGoogleDriveUrl(String pageUrl) {
        Log.i(TAG, "handleResolveGoogleDriveUrl: Starting resolution for " + pageUrl);
        GoogleDriveLinkResolver resolver = new GoogleDriveLinkResolver(); // Ensure GoogleDriveUtils is available
        DownloadItem resolvedItem = resolver.resolveDriveUrl(pageUrl);

        if (resolvedItem != null && resolvedItem.directUrl != null && !resolvedItem.directUrl.isEmpty()) {
            Log.i(TAG, "Google Drive resolution successful. Filename: " + resolvedItem.fileName + ", URL: " + resolvedItem.directUrl + ", Size: " + resolvedItem.size);

            Intent downloadIntent = new Intent(this, DownloadService.class);
            downloadIntent.putExtra(EXTRA_ACTION, ACTION_START_DOWNLOAD);
            downloadIntent.putExtra(EXTRA_URL, resolvedItem.directUrl);
            downloadIntent.putExtra(EXTRA_FILE_NAME, resolvedItem.fileName);
            // If file size is available from resolver, it could be passed to insertDownload or startDownload
            // For now, relying on DownloadTask to get size from Content-Length of direct URL.

            Log.d(TAG, "Dispatching new download task for resolved Google Drive item: " + resolvedItem.fileName);
            mainThreadHandler.post(() -> handleStartDownload(downloadIntent));

        } else {
            Log.e(TAG, "Google Drive resolution failed or no items found for " + pageUrl);
            // Extract a more user-friendly part of the URL for the message
            String displayUrl = pageUrl;
            if (pageUrl != null) {
                String id = GoogleDriveUtils.extractDriveId(pageUrl); // Use the new util
                if (id != null) displayUrl = "ID: " + id;
                else if (pageUrl.lastIndexOf('/') != -1 && pageUrl.lastIndexOf('/') < pageUrl.length() -1) {
                     displayUrl = pageUrl.substring(pageUrl.lastIndexOf('/') + 1);
                }
            } else {
                displayUrl = "Desconhecido";
            }

            final String userMessage = "Falha ao resolver link Google Drive: " + displayUrl;
            mainThreadHandler.post(() -> Toast.makeText(DownloadService.this, userMessage, Toast.LENGTH_LONG).show());

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cancel)
                .setContentTitle("Erro no Link Google Drive")
                .setContentText("Não foi possível resolver arquivos do link Google Drive.")
                .setAutoCancel(true);
            if (notificationManager != null) {
                 notificationManager.notify((int) (System.currentTimeMillis() % 10000) + 2, builder.build()); // Use different ID
            }
        }
    }
    
    // Método para verificar e corrigir status de downloads fantasmas
    private void verifyAndCorrectDownloadStatuses() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String[] projection = { DownloadContract.DownloadEntry._ID };
        String selection = DownloadContract.DownloadEntry.COLUMN_NAME_STATUS + " = ?";
        String[] selectionArgs = { String.valueOf(Download.STATUS_DOWNLOADING) };

        Cursor cursor = db.query(
            DownloadContract.DownloadEntry.TABLE_NAME,
            projection,
            selection,
            selectionArgs,
            null,
            null,
            null
        );

        boolean statusChanged = false;
        if (cursor != null) {
            while (cursor.moveToNext()) {
                long downloadId = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadContract.DownloadEntry._ID));
                // Se um download está como DOWNLOADING no DB mas não está ativo no Service, muda para PAUSED
                if (!activeDownloads.containsKey(downloadId)) {
                    Log.w(TAG, "Correcting status for orphaned download ID: " + downloadId);
                    updateDownloadStatus(downloadId, Download.STATUS_PAUSED);
                    statusChanged = true;
                }
            }
            cursor.close();
        }

        // Notificar a UI se algum status foi alterado
        if (statusChanged) {
            Intent broadcastIntent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED);
            broadcastManager.sendBroadcast(broadcastIntent);
        }
    }

    private void handleStartDownload(Intent intent) {
        Log.i(TAG, "handleStartDownload: Entry. Action: " + intent.getAction());
        Log.d(TAG, "handleStartDownload: URL from intent: '" + intent.getStringExtra(EXTRA_URL) + "'");
        Log.d(TAG, "handleStartDownload: FileName from intent: '" + intent.getStringExtra(EXTRA_FILE_NAME) + "'");
        String urlString = intent.getStringExtra(EXTRA_URL);
        String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
        // Retrieve the auth token. It might be null if not a Gofile download.
        final String authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN);
        Log.d(TAG, "handleStartDownload: AuthToken from intent: " + (authToken != null ? "present" : "null"));

        final int PREPARING_NOTIFICATION_ID = NOTIFICATION_ID_BASE - 1; // Unique ID for preparing/error notification

        if (urlString == null || urlString.trim().isEmpty() || fileName == null || fileName.trim().isEmpty()) {
            Log.e(TAG, "handleStartDownload: Invalid or missing URL/FileName. URL: '" + urlString + "', FileName: '" + fileName + "'. Stopping service.");

            Notification invalidRequestNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cancel) // Ensure ic_cancel exists or use a default error icon
                .setContentTitle("Download Falhou")
                .setContentText("Pedido de download inválido.")
                .setAutoCancel(true)
                .build();
            startForeground(PREPARING_NOTIFICATION_ID, invalidRequestNotification);

            // Stop the service as it cannot proceed.
            // The notification tied to startForeground will be removed by stopSelf.
            stopSelf();
            return;
        }

        // New check for URL syntax validity
        if (!URLUtil.isValidUrl(urlString)) {
            Log.e(TAG, "handleStartDownload: URL is syntactically invalid: '" + urlString + "'. Stopping service.");

            Notification invalidUrlNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cancel) // Ensure ic_cancel exists
                .setContentTitle("Download Falhou")
                .setContentText("URL de download inválida.")
                .setAutoCancel(true)
                .build();
            // Use the same PREPARING_NOTIFICATION_ID or a new unique one if PREPARING_NOTIFICATION_ID could still be active
            // For this specific early exit, using PREPARING_NOTIFICATION_ID is fine as it's before real work.
            final int INVALID_URL_NOTIFICATION_ID = NOTIFICATION_ID_BASE - 1; // Same as PREPARING_NOTIFICATION_ID
            startForeground(INVALID_URL_NOTIFICATION_ID, invalidUrlNotification);

            stopSelf();
            return;
        }

        // If parameters are valid (including URL syntax), proceed with the "Preparing download..." notification
        Notification preparingNotification = createPreparingNotification(fileName);
        startForeground(PREPARING_NOTIFICATION_ID, preparingNotification);

        if (executor == null || executor.isShutdown()) { // Ensure executor is alive
            Log.w(TAG, "Executor was null/shutdown in handleStartDownload, re-initializing.");
            executor = Executors.newSingleThreadExecutor();
        }

        // Capture authToken for use in the lambda
        final String effectiveAuthToken = authToken;

        executor.execute(() -> {
            Log.d(TAG, "handleStartDownload (Executor): Background processing started for URL: '" + urlString + "', FileName: '" + fileName + "' AuthToken: " + (effectiveAuthToken != null ? "present" : "null"));
            // Check if a download task for this URL is already in activeDownloads
            for (DownloadTask existingTask : activeDownloads.values()) {
                if (existingTask.urlString.equals(urlString)) {
                    Log.i(TAG, "Download task for URL already active: " + urlString);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (notificationManager != null) { // Check notificationManager
                            notificationManager.cancel(PREPARING_NOTIFICATION_ID);
                        }
                        Toast.makeText(DownloadService.this, "Este arquivo já está sendo baixado", Toast.LENGTH_SHORT).show();
                    });
                    // No need to call checkStopForeground here as the service was just started with a new notification.
                    // If this task wasn't truly new, the original foreground notification for that task should still be active.
                    return;
                }
            }

            long downloadId = getDownloadIdByUrl(urlString);
            Log.d(TAG, "handleStartDownload (Executor): getDownloadIdByUrl for '" + urlString + "' returned ID: " + downloadId);

            if (downloadId != -1) {
                Download existingDownload = getDownloadById(downloadId);
                Log.d(TAG, "handleStartDownload (Executor): Found existing DB entry for ID " + downloadId + ". Status: " + (existingDownload != null ? existingDownload.getStatus() : "null object") + ", Path: " + (existingDownload != null ? existingDownload.getLocalPath() : "null object"));
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (notificationManager != null) {
                         notificationManager.cancel(PREPARING_NOTIFICATION_ID);
                    }
                    if (existingDownload != null) {
                        if (existingDownload.getStatus() == Download.STATUS_COMPLETED) {
                            Toast.makeText(DownloadService.this, "Este arquivo já foi baixado", Toast.LENGTH_SHORT).show();
                        } else if (existingDownload.getStatus() == Download.STATUS_PAUSED || existingDownload.getStatus() == Download.STATUS_FAILED) {
                            Log.i(TAG, "handleStartDownload (MainThread): About to call startDownload for existing paused/failed download. ID: " + existingDownload.getId() + ", URL: '" + existingDownload.getUrl() + "', FileName: '" + existingDownload.getFileName() + "'");
                            startDownload(existingDownload.getId(), existingDownload.getUrl(), existingDownload.getFileName(), effectiveAuthToken);
                        } else if (existingDownload.getStatus() == Download.STATUS_DOWNLOADING) {
                            Log.w(TAG, "DB indicates downloading, but no active task found for " + existingDownload.getFileName() + ". Attempting to restart.");
                            Log.i(TAG, "handleStartDownload (MainThread): About to call startDownload for existing downloading (but no task) download. ID: " + existingDownload.getId() + ", URL: '" + existingDownload.getUrl() + "', FileName: '" + existingDownload.getFileName() + "'");
                            startDownload(existingDownload.getId(), existingDownload.getUrl(), existingDownload.getFileName(), effectiveAuthToken);
                        }
                    } else {
                        // DB had an ID, but we couldn't fetch the Download object. This is an inconsistent state.
                        Log.w(TAG, "Could not fetch existing download with ID: " + downloadId + ". Treating as new.");
                        final long newDownloadIdAfterNull = insertDownload(urlString, fileName);
                        Log.d(TAG, "handleStartDownload (Executor): Existing download object was null for ID " + downloadId + ". Attempted insert, new ID: " + newDownloadIdAfterNull);
                        if (newDownloadIdAfterNull != -1) {
                            Log.i(TAG, "handleStartDownload (MainThread): About to call startDownload for new download (after null existing). ID: " + newDownloadIdAfterNull + ", URL: '" + urlString + "', FileName: '" + fileName + "'");
                            startDownload(newDownloadIdAfterNull, urlString, fileName, effectiveAuthToken);
                        } else {
                            Log.e(TAG, "Failed to insert new download record for: " + urlString);
                            Toast.makeText(DownloadService.this, "Erro ao iniciar download.", Toast.LENGTH_SHORT).show();
                        }
                    }
                    checkStopForeground();
                });
                return;
            }

            // If no existing download ID was found by URL, this is a new download.
            final long newDownloadId = insertDownload(urlString, fileName);
            Log.d(TAG, "handleStartDownload (Executor): No existing DB entry. Inserted new record with ID: " + newDownloadId + " for URL: '" + urlString + "'");

            new Handler(Looper.getMainLooper()).post(() -> {
                if (notificationManager != null) {
                    notificationManager.cancel(PREPARING_NOTIFICATION_ID);
                }
                if (newDownloadId != -1) {
                    Log.i(TAG, "handleStartDownload (MainThread): About to call startDownload for brand new download. ID: " + newDownloadId + ", URL: '" + urlString + "', FileName: '" + fileName + "'");
                    startDownload(newDownloadId, urlString, fileName, effectiveAuthToken);
                } else {
                    Log.e(TAG, "Failed to insert new download record for: " + urlString);
                    Toast.makeText(DownloadService.this, "Erro ao iniciar download.", Toast.LENGTH_SHORT).show();
                }
                // checkStopForeground is important here: if startDownload fails to post its own fg notification
                // or if the download is immediately found to be complete/invalid before a real task starts,
                // this ensures the "preparing" notification is cleared and service stops if appropriate.
                checkStopForeground();
            });
        });
    }

    private void handlePauseDownload(Intent intent) {
        long downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1);
        if (downloadId != -1) {
            handlePauseDownload(downloadId);
        }
    }

    public void handlePauseDownload(long downloadId) {
        DownloadTask task = activeDownloads.get(downloadId);
        if (task != null) {
            task.pause();
            updateDownloadStatus(downloadId, Download.STATUS_PAUSED);
            updateNotificationPaused(downloadId);
            
            // Enviar broadcast
            Intent broadcastIntent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED);
            broadcastIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
            broadcastManager.sendBroadcast(broadcastIntent);
        }
    }

    private void handleResumeDownload(Intent intent) {
        long downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1);
        if (downloadId != -1) {
            handleResumeDownload(downloadId);
        }
    }

    public void handleResumeDownload(long downloadId) {
        Download download = getDownloadById(downloadId);
        // Verificar se já não está baixando
        if (activeDownloads.containsKey(downloadId)) {
             Log.w(TAG, "Download task already active for ID: " + downloadId);
             return;
        }
        if (download != null && (download.getStatus() == Download.STATUS_PAUSED || download.getStatus() == Download.STATUS_FAILED)) {
            // For now, resume will not have the Gofile token unless we store it in DB.
            startDownload(downloadId, download.getUrl(), download.getFileName(), null);
        }
    }

    private void handleCancelDownload(Intent intent) {
        long downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1);
        if (downloadId != -1) {
            handleCancelDownload(downloadId);
        }
    }

    public void handleCancelDownload(long downloadId) {
        DownloadTask task = activeDownloads.get(downloadId);
        if (task != null) {
            task.cancel(true);
            // A remoção de activeDownloads é feita no onPostExecute/onCancelled da AsyncTask
        }
        
        // Remover a notificação
        notificationManager.cancel((int) (NOTIFICATION_ID_BASE + downloadId));
        activeNotifications.remove(downloadId);
        
        // Deletar o arquivo parcial se existir
        Download download = getDownloadById(downloadId);
        if (download != null && download.getLocalPath() != null && !download.getLocalPath().isEmpty()) {
             try {
                 File fileToDelete = new File(download.getLocalPath());
                 if (fileToDelete.exists()) {
                     fileToDelete.delete();
                 }
             } catch (Exception e) {
                 Log.e(TAG, "Error deleting partial file for download ID: " + downloadId, e);
             }
        }

        // Remover do banco de dados
        deleteDownload(downloadId);
        
        // Enviar broadcast
        Intent broadcastIntent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED);
        broadcastIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        broadcastManager.sendBroadcast(broadcastIntent);
    }

    private void handleRetryDownload(Intent intent) {
        long downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1);
        if (downloadId != -1) {
            handleRetryDownload(downloadId);
        }
    }

    private void handleRetryDownload(long downloadId) {
        Download download = getDownloadById(downloadId);
         // Verificar se já não está baixando
        if (activeDownloads.containsKey(downloadId)) {
             Log.w(TAG, "Download task already active for ID: " + downloadId);
             return;
        }
        if (download != null && download.getStatus() == Download.STATUS_FAILED) {
            // Resetar o progresso e status
            ContentValues values = new ContentValues();
            values.put(DownloadContract.DownloadEntry.COLUMN_NAME_DOWNLOADED_BYTES, 0);
            values.put(DownloadContract.DownloadEntry.COLUMN_NAME_STATUS, Download.STATUS_PENDING);
            
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.update(
                DownloadContract.DownloadEntry.TABLE_NAME,
                values,
                DownloadContract.DownloadEntry._ID + " = ?",
                new String[] { String.valueOf(downloadId) }
            );
            
            // Iniciar o download novamente
            // Retry will also not use a token unless persisted.
            startDownload(downloadId, download.getUrl(), download.getFileName(), null);
        }
    }

    // Modified startDownload signature to include authToken
    private void startDownload(long downloadId, String urlString, String fileName, String authToken) {
        Log.i(TAG, "startDownload: Entry. ID: " + downloadId + ", URL: '" + urlString + "', FileName: '" + fileName + "', AuthToken: " + (authToken != null ? "present" : "null"));
        // Criar ou atualizar a notificação
        NotificationCompat.Builder builder = createOrUpdateNotificationBuilder(downloadId, fileName);
        activeNotifications.put(downloadId, builder);
        
        // Iniciar o serviço em primeiro plano
        startForeground((int) (NOTIFICATION_ID_BASE + downloadId), builder.build());
        
        // Atualizar o status no banco de dados
        // For Gofile, fileName might be a relative path. targetLocalPath is the full path.
        // actualDisplayFileName (derived from targetLocalPath.getName()) is best for DB.
        // This part is now handled in handleStartDownload before calling this startDownload method.
        // The call to updateDownloadStatus here will use the targetLocalPath.
        updateDownloadStatus(downloadId, Download.STATUS_DOWNLOADING, targetLocalPath);

        // Retrieve multi-thread settings
        boolean multithreadEnabled = AppSettings.isMultithreadDownloadEnabled(this);
        int threadsCount = AppSettings.getDownloadThreadsCount(this);
        Log.d(TAG, "startDownload for ID " + downloadId + " - Multithread Enabled: " + multithreadEnabled + ", Threads: " + threadsCount);
        
        // Iniciar a tarefa de download, passing the authToken, targetLocalPath, and multi-thread settings
        // displayFileName is passed to DownloadTask, which is correct.
        DownloadTask task = new DownloadTask(downloadId, urlString, displayFileName, builder, authToken, targetLocalPath, multithreadEnabled, threadsCount);
        activeDownloads.put(downloadId, task);
        Log.i(TAG, "startDownload: About to execute DownloadTask for ID: " + downloadId);
        task.execute();
        
        // Enviar broadcast
        Intent broadcastIntent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED);
        broadcastIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        broadcastManager.sendBroadcast(broadcastIntent);
    }

    // Overload for existing calls that don't have authToken (e.g., resume of non-Gofile)
    // This overload needs to be updated to fit the new main signature, or removed if all calls are updated.
    // For now, it will call the new main signature with default pathing.
    private void startDownload(long downloadId, String urlString, String displayFileName) {
        Log.w(TAG, "startDownload (legacy overload without authToken/localPath) called for ID: " + downloadId);
        File baseAppDownloadDir = new File(AppSettings.getDownloadPath(this));
        String defaultLocalPath = new File(baseAppDownloadDir, displayFileName).getAbsolutePath();
        boolean multithreadEnabled = AppSettings.isMultithreadDownloadEnabled(this);
        int threadsCount = AppSettings.getDownloadThreadsCount(this);
        startDownload(downloadId, urlString, displayFileName, null, defaultLocalPath, multithreadEnabled, threadsCount);
    }

    private NotificationCompat.Builder createOrUpdateNotificationBuilder(long downloadId, String displayFileName) {
        // Verificar se já existe uma notificação para este download
        NotificationCompat.Builder builder = activeNotifications.get(downloadId);
        // Sempre criar uma nova instância para garantir que os PendingIntents estejam atualizados
        // if (builder == null) { 
            // Criar intent para abrir o gerenciador de downloads
            Intent notificationIntent = new Intent(this, DownloadManagerActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 
                (int) downloadId, 
                notificationIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            
            // Criar intent para pausar o download
            Intent pauseIntent = new Intent(this, DownloadService.class);
            pauseIntent.putExtra(EXTRA_ACTION, ACTION_PAUSE_DOWNLOAD);
            pauseIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
            PendingIntent pausePendingIntent = PendingIntent.getService(
                this, 
                (int) (downloadId + 100), // Usar IDs diferentes para cada PendingIntent
                pauseIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            
            // Criar intent para cancelar o download
            Intent cancelIntent = new Intent(this, DownloadService.class);
            cancelIntent.putExtra(EXTRA_ACTION, ACTION_CANCEL_DOWNLOAD);
            cancelIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
            PendingIntent cancelPendingIntent = PendingIntent.getService(
                this, 
                (int) (downloadId + 200), // Usar IDs diferentes
                cancelIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            
            // Criar a notificação
            builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(fileName)
                .setContentText("Iniciando download...")
                .setProgress(100, 0, true) // Indeterminado inicialmente
                .setOngoing(true) // Não pode ser dispensada pelo usuário
                .setOnlyAlertOnce(true) // Alertar apenas uma vez
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_pause, "Pausar", pausePendingIntent)
                .addAction(R.drawable.ic_cancel, "Cancelar", cancelPendingIntent);
        // }
        
        return builder;
    }

    private void updateNotificationProgress(long downloadId, int progress, long downloadedBytes, long totalBytes, double speed) {
        NotificationCompat.Builder builder = activeNotifications.get(downloadId);
        if (builder != null) {
            // Converter para MB e Mbps para exibição
            double downloadedMB = bytesToMB(downloadedBytes);
            double totalMB = bytesToMB(totalBytes);
            double speedMbps = bytesToMB((long)speed);
            
            String contentText;
            if (totalBytes > 0) {
                 contentText = String.format("%.1f / %.1f MB (%.2f Mbps)",
                    bytesToMB(downloadedBytes),
                    bytesToMB(totalBytes),
                    speedMbps);
                 builder.setProgress(100, progress, false);
            } else {
                 contentText = String.format("%.1f MB (%.2f Mbps)",
                    bytesToMB(downloadedBytes),
                    speedMbps);
                 builder.setProgress(0, 0, true); // Indeterminado se o total é desconhecido
            }
           
            builder.setContentText(contentText);
            notificationManager.notify((int) (NOTIFICATION_ID_BASE + downloadId), builder.build());
        }
    }

    private void updateNotificationPaused(long downloadId) {
        NotificationCompat.Builder builder = activeNotifications.get(downloadId);
        if (builder != null) {
            Download download = getDownloadById(downloadId);
            if (download != null) {
                // Remover ações existentes
                builder.mActions.clear();
                
                // Criar intent para retomar o download
                Intent resumeIntent = new Intent(this, DownloadService.class);
                resumeIntent.putExtra(EXTRA_ACTION, ACTION_RESUME_DOWNLOAD);
                resumeIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
                PendingIntent resumePendingIntent = PendingIntent.getService(
                    this, 
                    (int) (downloadId + 300), // ID diferente
                    resumeIntent, 
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
                
                // Criar intent para cancelar o download
                Intent cancelIntent = new Intent(this, DownloadService.class);
                cancelIntent.putExtra(EXTRA_ACTION, ACTION_CANCEL_DOWNLOAD);
                cancelIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
                PendingIntent cancelPendingIntent = PendingIntent.getService(
                    this, 
                    (int) (downloadId + 200), // ID consistente com o de cancelamento
                    cancelIntent, 
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
                
                builder.setContentTitle(download.getFileName() + " (Pausado)")
                       .setContentText(download.getFormattedDownloadedSize() + " / " + download.getFormattedTotalSize())
                       .setOngoing(false)
                       .setOnlyAlertOnce(true)
                       .setProgress(100, download.getProgress(), false) // Mostrar progresso atual
                       .addAction(R.drawable.ic_play, "Continuar", resumePendingIntent)
                       .addAction(R.drawable.ic_cancel, "Cancelar", cancelPendingIntent);
                
                notificationManager.notify((int) (NOTIFICATION_ID_BASE + downloadId), builder.build());
            }
        }
    }

    private void updateNotificationComplete(long downloadId, String fileName, File downloadedFile) {
        NotificationCompat.Builder builder = activeNotifications.get(downloadId);
        // Se não houver builder ativo, criar um novo para a notificação de conclusão
        if (builder == null) {
             Intent notificationIntent = new Intent(this, DownloadManagerActivity.class);
             PendingIntent pendingIntent = PendingIntent.getActivity(
                 this, 
                 (int) downloadId, 
                 notificationIntent, 
                 PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
             );
             builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentIntent(pendingIntent);
             activeNotifications.put(downloadId, builder); // Adicionar ao mapa para consistência
        }

        // Remover ações existentes
        builder.mActions.clear();
        
        PendingIntent contentIntent = null;
        if (downloadedFile != null && downloadedFile.exists()) {
            // Criar intent para instalar o APK
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            Uri fileUri = FileProvider.getUriForFile(
                this, 
                getApplicationContext().getPackageName() + ".provider", 
                downloadedFile
            );
            installIntent.setDataAndType(fileUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            contentIntent = PendingIntent.getActivity(
                this, 
                (int) (downloadId + 400), // ID diferente
                installIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
        } else {
             // Se o arquivo não existe, apenas abrir o gerenciador
             Intent managerIntent = new Intent(this, DownloadManagerActivity.class);
             contentIntent = PendingIntent.getActivity(
                 this, 
                 (int) downloadId, 
                 managerIntent, 
                 PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
             );
        }

        builder.setContentTitle(fileName + " - Download Concluído")
               .setContentText("Toque para instalar")
               .setProgress(0, 0, false)
               .setOngoing(false)
               .setAutoCancel(true)
               .setOnlyAlertOnce(false); // Permitir alerta para conclusão
               
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent);
        }
        
        notificationManager.notify((int) (NOTIFICATION_ID_BASE + downloadId), builder.build());
        activeNotifications.remove(downloadId); // Remover notificação ativa após conclusão
        stopForeground(false); // Parar foreground se for o último download
    }

    private void updateNotificationError(long downloadId, String fileName) {
        NotificationCompat.Builder builder = activeNotifications.get(downloadId);
        // Se não houver builder ativo, criar um novo para a notificação de erro
         if (builder == null) {
             Intent notificationIntent = new Intent(this, DownloadManagerActivity.class);
             PendingIntent pendingIntent = PendingIntent.getActivity(
                 this, 
                 (int) downloadId, 
                 notificationIntent, 
                 PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
             );
             builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentIntent(pendingIntent);
             activeNotifications.put(downloadId, builder); // Adicionar ao mapa para consistência
        }

        // Remover ações existentes
        builder.mActions.clear();
        
        // Criar intent para tentar novamente
        Intent retryIntent = new Intent(this, DownloadService.class);
        retryIntent.putExtra(EXTRA_ACTION, ACTION_RETRY_DOWNLOAD);
        retryIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        PendingIntent retryPendingIntent = PendingIntent.getService(
            this, 
            (int) (downloadId + 500), // ID diferente
            retryIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // Criar intent para cancelar (remover) o download falho
        Intent cancelIntent = new Intent(this, DownloadService.class);
        cancelIntent.putExtra(EXTRA_ACTION, ACTION_CANCEL_DOWNLOAD);
        cancelIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        PendingIntent cancelPendingIntent = PendingIntent.getService(
            this, 
            (int) (downloadId + 200), // ID consistente
            cancelIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        builder.setContentTitle(fileName + " - Download Falhou")
               .setContentText("Ocorreu um erro durante o download")
               .setProgress(0, 0, false)
               .setOngoing(false)
               .setAutoCancel(true)
               .setOnlyAlertOnce(false) // Permitir alerta para erro
               .addAction(R.drawable.ic_play, "Tentar novamente", retryPendingIntent)
               .addAction(R.drawable.ic_cancel, "Remover", cancelPendingIntent);
        
        notificationManager.notify((int) (NOTIFICATION_ID_BASE + downloadId), builder.build());
        activeNotifications.remove(downloadId); // Remover notificação ativa após erro
        stopForeground(false); // Parar foreground se for o último download
    }

    private class DownloadTask extends AsyncTask<Void, Integer, File> {
        private final long downloadId;
        private final String urlString;
        private final String displayFileName; // Renamed
        private final NotificationCompat.Builder notificationBuilder;
        private final String localPath; // New
        private final String authToken;
        private final boolean multithreadEnabled; // New
        private final int threadsCount; // New

        private boolean isPaused = false;
        private boolean isCancelled = false;
        private long totalBytes = -1;
        private long downloadedBytes = 0;
        private long startTime;
        private long lastUpdateTime = 0;
        private double speed = 0;

        // For multi-threaded download
        private ExecutorService segmentExecutor;
        private final List<Future<?>> segmentFutures = new ArrayList<>();
        private long totalBytesDownloadedAcrossSegments = 0;
        private final Object progressLock = new Object();

        // Main Constructor
        DownloadTask(long downloadId, String urlString, String displayFileName,
                     NotificationCompat.Builder builder, String authToken, String localPath,
                     boolean multithreadEnabled, int threadsCount) {
            this.downloadId = downloadId;
            this.urlString = urlString;
            this.displayFileName = displayFileName;
            this.notificationBuilder = builder;
            this.authToken = authToken;
            this.localPath = localPath;
            this.multithreadEnabled = multithreadEnabled;
            this.threadsCount = Math.max(1, threadsCount); // Ensure at least 1 thread
             if(localPath == null && multithreadEnabled && threadsCount > 1){
                Log.e(TAG, "DownloadTask ID " + downloadId + ": localPath is null but multithreading is enabled. This is problematic.");
                // Potentially fall back to single-threaded or throw an error
            }
            Log.d(TAG, "DownloadTask " + downloadId + " constructed. MultiThread: " + this.multithreadEnabled + ", Threads: " + this.threadsCount + ", Path: " + this.localPath);
        }

        // Legacy Overload (localPath provided, multi-thread settings from AppSettings)
        DownloadTask(long downloadId, String urlString, String displayFileName,
                     NotificationCompat.Builder builder, String authToken, String localPath) {
            this(downloadId, urlString, displayFileName, builder, authToken, localPath,
                 AppSettings.isMultithreadDownloadEnabled(getApplicationContext()),
                 AppSettings.getDownloadThreadsCount(getApplicationContext()));
        }

        // Very Legacy Overload (no localPath, multi-thread settings from AppSettings)
         DownloadTask(long downloadId, String urlString, String displayFileName, NotificationCompat.Builder builder, String authToken) {
            this(downloadId, urlString, displayFileName, builder, authToken, null,
                 AppSettings.isMultithreadDownloadEnabled(getApplicationContext()),
                 AppSettings.getDownloadThreadsCount(getApplicationContext()));
        }

        public void pause() {
            isPaused = true;
        }

        @Override
        protected void onPreExecute() {
            Log.d(TAG, "DownloadTask (" + this.downloadId + "): onPreExecute. URL: '" + this.urlString + "'");
            super.onPreExecute();
            startTime = System.currentTimeMillis();
            
            // Verificar se já existe um download parcial
            Download existingDownload = getDownloadById(downloadId);
            if (existingDownload != null && existingDownload.getDownloadedBytes() > 0) {
                downloadedBytes = existingDownload.getDownloadedBytes();
                totalBytes = existingDownload.getTotalBytes();
            }
        }

        @Override
        protected File doInBackground(Void... params) {
            Log.i(TAG, "DownloadTask (" + this.downloadId + ") doInBackground. Multithread: " + this.multithreadEnabled + ", Threads: " + this.threadsCount + ", URL: " + this.urlString);

            if (this.multithreadEnabled && this.threadsCount > 1 && performHeadRequest()) {
                Log.i(TAG, "Proceeding with multi-threaded download for " + displayFileName + ". Total size: " + this.totalBytes + " bytes. Threads: " + this.threadsCount);

                File partsDir = manageTempPartsDirectory(new File(this.localPath), this.threadsCount);
                if (partsDir == null) {
                    Log.e(TAG, "Failed to manage parts directory. Falling back to single-threaded download.");
                    return singleThreadDownloadLogic();
                }

                this.segmentExecutor = Executors.newFixedThreadPool(this.threadsCount);
                this.segmentFutures.clear();

                // Initialize totalBytesDownloadedAcrossSegments by summing lengths of existing part files
                this.totalBytesDownloadedAcrossSegments = 0;
                for (int i = 0; i < this.threadsCount; i++) {
                    File partFileCheck = new File(partsDir, "part_" + i);
                    if (partFileCheck.exists()) {
                        this.totalBytesDownloadedAcrossSegments += partFileCheck.length();
                    }
                }
                Log.i(TAG, "Initial total downloaded across segments (from existing parts): " + this.totalBytesDownloadedAcrossSegments);
                incrementOverallProgress(0); // Update UI with current sum

                long basePartSize = this.totalBytes / this.threadsCount;

                boolean setupError = false;
                for (int i = 0; i < this.threadsCount; i++) {
                    if (isCancelled || isPaused) { setupError = true; break; }
                    long startByte = i * basePartSize;
                    long endByte = (i == this.threadsCount - 1) ? this.totalBytes - 1 : startByte + basePartSize - 1;
                    File partFile = new File(partsDir, "part_" + i);
                    Log.d(TAG, "Segment " + i + ": Calculated range " + startByte + "-" + endByte + " for part file " + partFile.getName());
                    try {
                        SegmentDownloader segmentTask = new SegmentDownloader(new URL(this.urlString), startByte, endByte, partFile, this.authToken, i);
                        this.segmentFutures.add(this.segmentExecutor.submit(segmentTask));
                    } catch (MalformedURLException e) {
                        Log.e(TAG, "MalformedURLException for segment " + i + ", URL: " + this.urlString, e);
                        setupError = true;
                        break;
                    }
                }

                boolean allSegmentsSuccess = !setupError;
                if (allSegmentsSuccess && !isCancelled && !isPaused) {
                    for (Future<?> future : this.segmentFutures) {
                        if (isCancelled || isPaused) { allSegmentsSuccess = false; break; }
                        try {
                            future.get();
                        } catch (Exception e) {
                            Log.e(TAG, "A segment download failed or was cancelled during future.get().", e);
                            allSegmentsSuccess = false;
                            for(Future<?> f : this.segmentFutures) { if (!f.isDone() && !f.isCancelled()) f.cancel(true); }
                            break;
                        }
                    }
                } else if (!setupError) {
                    allSegmentsSuccess = false;
                    Log.i(TAG, "Download was cancelled, paused, or setup error before all segment futures could be processed.");
                }

                if (this.segmentExecutor != null) {
                     if (!allSegmentsSuccess || isCancelled || isPaused) { this.segmentExecutor.shutdownNow(); }
                     else { this.segmentExecutor.shutdown(); }
                }

                if (isCancelled) {
                    Log.i(TAG, "Download cancelled, cleaning up parts for " + displayFileName);
                    deleteRecursive(partsDir);
                    return null;
                }
                if (isPaused) {
                    Log.i(TAG, "Download paused for " + displayFileName + ". Parts retained for potential resume.");
                    return null;
                }

                if (allSegmentsSuccess) {
                    if (this.totalBytesDownloadedAcrossSegments < this.totalBytes && this.totalBytes > 0) {
                        Log.e(TAG, "Total downloaded bytes mismatch before merge. Expected: " + this.totalBytes + ", Got: " + this.totalBytesDownloadedAcrossSegments + ". Marking as failed.");
                        updateDownloadStatus(this.downloadId, Download.STATUS_FAILED);
                        return null;
                    }

                    Log.i(TAG, "All segments downloaded successfully for " + displayFileName + ". Starting merge.");
                    if (notificationBuilder != null) {
                        notificationBuilder.setContentText("Merging parts...").setProgress(0, 0, true);
                        if (DownloadService.this.notificationManager != null) {
                             DownloadService.this.notificationManager.notify((int) (NOTIFICATION_ID_BASE + downloadId), notificationBuilder.build());
                        } else { Log.w(TAG, "notificationManager is null, cannot update notification for merging state."); }
                    } else { Log.w(TAG, "notificationBuilder is null, cannot update notification for merging state."); }

                    boolean mergeSuccess = mergePartFiles(partsDir, new File(this.localPath), this.threadsCount, basePartSize);
                    if (mergeSuccess) {
                        updateDownloadStatus(this.downloadId, Download.STATUS_COMPLETED);
                        deleteRecursive(partsDir);
                        return new File(this.localPath);
                    } else {
                        Log.e(TAG, "Failed to merge part files for " + displayFileName);
                        updateDownloadStatus(this.downloadId, Download.STATUS_FAILED);
                        deleteRecursive(partsDir);
                        return null;
                    }
                } else {
                    Log.e(TAG, "One or more segments failed or task was interrupted for " + displayFileName + ".");
                    if (!isPaused && !isCancelled) { updateDownloadStatus(this.downloadId, Download.STATUS_FAILED); }
                    return null;
                }
            } else {
                 if (this.multithreadEnabled && this.threadsCount > 1) {
                    Log.w(TAG, "Falling back to single-threaded download for " + displayFileName + " (HEAD failed or no range support/size).");
                } else {
                    Log.d(TAG, "Proceeding with single-threaded download for " + displayFileName);
                }
                return singleThreadDownloadLogic();
            }
        }

        private synchronized void incrementOverallProgress(long delta) {
            synchronized (progressLock) {
                this.totalBytesDownloadedAcrossSegments += delta;
                updateDownloadProgress(downloadId, this.totalBytesDownloadedAcrossSegments, this.totalBytes);

                if (this.totalBytes > 0) {
                    int progress = (int) ((this.totalBytesDownloadedAcrossSegments * 100) / this.totalBytes);
                    publishProgress(progress);
                } else {
                    publishProgress(-1);
                }
            }

            long currentTime = System.currentTimeMillis();
            if (delta >= 0 && (currentTime - lastUpdateTime > 500 || this.totalBytesDownloadedAcrossSegments == this.totalBytes )) {
                synchronized(progressLock) {
                    long elapsedTime = currentTime - startTime;
                    if (elapsedTime > 0) {
                         this.speed = (double) this.totalBytesDownloadedAcrossSegments / (elapsedTime / 1000.0);
                    } else {
                        this.speed = (delta == 0 && elapsedTime == 0 && this.totalBytesDownloadedAcrossSegments == 0) ? 0 : this.speed;
                    }
                }
                Intent intent = new Intent(ACTION_DOWNLOAD_PROGRESS);
                intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
                intent.putExtra("progress", totalBytes > 0 ? (int) ((this.totalBytesDownloadedAcrossSegments * 100) / totalBytes) : 0);
                intent.putExtra("downloadedBytes", this.totalBytesDownloadedAcrossSegments);
                intent.putExtra("totalBytes", this.totalBytes);
                intent.putExtra("speed", this.speed);
                broadcastManager.sendBroadcast(intent);
                lastUpdateTime = currentTime;
            }
        }

        private boolean mergePartFiles(File partsDir, File targetFile, int numThreads, long segmentBaseSize) {
            Log.i(TAG, "Merging " + numThreads + " part files into " + targetFile.getName());
            long currentExpectedPartSize;

            try (OutputStream fos = new FileOutputStream(targetFile)) {
                for (int i = 0; i < numThreads; i++) {
                    if (isCancelled) { Log.w(TAG, "Merging cancelled for " + targetFile.getName()); return false; }
                    File partFile = new File(partsDir, "part_" + i);

                    if (i == numThreads - 1 && totalBytes > 0) {
                        currentExpectedPartSize = totalBytes - (segmentBaseSize * i);
                    } else {
                        currentExpectedPartSize = segmentBaseSize;
                    }

                    if (!partFile.exists() && currentExpectedPartSize > 0) {
                         Log.e(TAG, "Part file missing: " + partFile.getAbsolutePath() + " for segment " + i + ", expected length " + currentExpectedPartSize);
                         return false;
                    }
                    if (partFile.exists() && partFile.length() < currentExpectedPartSize) {
                         Log.e(TAG, "Part file " + partFile.getAbsolutePath() + " is incomplete. Actual: " + partFile.length() + ", Expected: " + currentExpectedPartSize);
                         return false;
                    }

                    if (partFile.exists() && partFile.length() > 0) {
                        try (InputStream fis = new java.io.FileInputStream(partFile)) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = fis.read(buffer)) != -1) {
                                if (isCancelled) { Log.w(TAG, "Merging cancelled during write for " + targetFile.getName()); return false; }
                                fos.write(buffer, 0, bytesRead);
                            }
                        }
                    } else if (partFile.exists() && partFile.length() == 0 && currentExpectedPartSize > 0) {
                         Log.w(TAG, "Part file is empty but was expected to have content: " + partFile.getAbsolutePath());
                         return false;
                    }
                }
                Log.i(TAG, "Successfully merged part files for " + targetFile.getName());
                if (totalBytes > 0 && targetFile.length() != totalBytes) {
                    Log.e(TAG, "Merged file size (" + targetFile.length() + ") does not match expected total size (" + totalBytes + ").");
                    return false;
                }
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Error merging part files for " + targetFile.getName() + ": " + e.getMessage(), e);
                return false;
            }
        }


        private File singleThreadDownloadLogic() {
            InputStream input = null;
            OutputStream output = null;
            HttpURLConnection connection = null;
            File downloadedFile = null;
            RandomAccessFile randomAccessFile = null;

            try {
                // Note: this.fileName is now displayFileName. this.localPath is the full path.
                Log.d(TAG, "DownloadTask (" + this.downloadId + "): singleThreadDownloadLogic starting. URL: '" + this.urlString + "'. LocalPath: '" + this.localPath + "'. AuthToken: " + (this.authToken != null ? "present" : "null"));

                if (this.localPath == null || this.localPath.isEmpty()) {
                    Log.e(TAG, "DownloadTask (" + this.downloadId + "): Local path is null or empty. Cannot proceed with singleThreadDownloadLogic.");
                    updateDownloadStatus(downloadId, Download.STATUS_FAILED);
                    return null;
                }

                downloadedFile = new File(this.localPath);
                File parentDir = downloadedFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    if (!parentDir.mkdirs()) {
                        Log.e(TAG, "DownloadTask (" + this.downloadId + "): Failed to create parent directory: " + parentDir.getAbsolutePath());
                        updateDownloadStatus(downloadId, Download.STATUS_FAILED);
                        return null;
                    }
                }
                // Ensure local path is in DB, could have been null if legacy constructor was used by an old call.
                updateDownloadLocalPath(downloadId, this.localPath);

                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();

                if (this.authToken != null && !this.authToken.isEmpty()) {
                    connection.setRequestProperty("Cookie", "accountToken=" + this.authToken);
                }
                
                if (downloadedBytes > 0) {
                    connection.setRequestProperty("Range", "bytes=" + downloadedBytes + "-");
                }
                
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.connect();
                
                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    Log.e(TAG, "Server returned HTTP " + responseCode + " " + connection.getResponseMessage());
                    updateDownloadStatus(downloadId, Download.STATUS_FAILED);
                    return null;
                }
                
                if (totalBytes <= 0) { // totalBytes might be pre-set from HEAD request or DB
                    long contentLengthHeader = connection.getContentLengthLong(); // Use Long for safety
                    if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                         String contentRange = connection.getHeaderField("Content-Range");
                         if (contentRange != null) {
                             try {
                                 totalBytes = Long.parseLong(contentRange.substring(contentRange.lastIndexOf('/') + 1));
                             } catch (Exception e) {
                                 Log.w(TAG, "Could not parse Content-Range: " + contentRange);
                                 totalBytes = downloadedBytes + contentLengthHeader;
                             }
                         } else {
                             totalBytes = downloadedBytes + contentLengthHeader;
                         }
                    } else {
                         totalBytes = contentLengthHeader;
                    }
                    if (totalBytes <= 0) totalBytes = -1;
                    updateDownloadTotalBytes(downloadId, totalBytes);
                }
                
                if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    randomAccessFile = new RandomAccessFile(downloadedFile, "rw");
                    randomAccessFile.seek(downloadedBytes);
                    output = new FileOutputStream(randomAccessFile.getFD());
                } else {
                    downloadedBytes = 0;
                    output = new FileOutputStream(downloadedFile);
                }
                
                input = new BufferedInputStream(connection.getInputStream());
                byte[] data = new byte[8192];
                int count;
                long bytesSinceLastUpdate = 0;
                
                while ((count = input.read(data)) != -1) {
                    if (isCancelled) return null;
                    if (isPaused) {
                        updateDownloadProgress(downloadId, downloadedBytes, totalBytes);
                        return null;
                    }
                    
                    downloadedBytes += count;
                    bytesSinceLastUpdate += count;
                    output.write(data, 0, count);
                    
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastUpdateTime > 500 || bytesSinceLastUpdate > (1024 * 1024)) {
                        updateDownloadProgress(downloadId, downloadedBytes, totalBytes);
                        long elapsedTime = currentTime - startTime;
                        if (elapsedTime > 500) speed = (double) downloadedBytes / (elapsedTime / 1000.0);
                        
                        if (totalBytes > 0) publishProgress((int) ((downloadedBytes * 100) / totalBytes));
                        else publishProgress(-1);
                        
                        Intent progressIntent = new Intent(ACTION_DOWNLOAD_PROGRESS);
                        progressIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
                        progressIntent.putExtra("progress", totalBytes > 0 ? (int) ((downloadedBytes * 100) / totalBytes) : 0);
                        progressIntent.putExtra("downloadedBytes", downloadedBytes);
                        progressIntent.putExtra("totalBytes", totalBytes);
                        progressIntent.putExtra("speed", speed);
                        broadcastManager.sendBroadcast(progressIntent);
                        
                        bytesSinceLastUpdate = 0;
                        lastUpdateTime = currentTime;
                    }
                }
                updateDownloadStatus(downloadId, Download.STATUS_COMPLETED);
                return downloadedFile;
            } catch (Exception e) {
                Log.e(TAG, "DownloadTask (" + this.downloadId + "): Exception during singleThreadDownloadLogic: " + e.getMessage(), e);
                updateDownloadStatus(downloadId, Download.STATUS_FAILED);
                return null;
            } finally {
                try {
                    if (output != null) output.close();
                    if (input != null) input.close();
                    if (randomAccessFile != null) randomAccessFile.close();
                    if (connection != null) connection.disconnect();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing streams in singleThreadDownloadLogic: " + e.getMessage(), e);
                }
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            int progress = values[0];
            if (progress >= 0) {
                updateNotificationProgress(downloadId, progress, downloadedBytes, totalBytes, speed);
            } else {
                // Progresso indeterminado
                updateNotificationProgress(downloadId, 0, downloadedBytes, -1, speed);
            }
        }

        @Override
        protected void onPostExecute(File result) {
            Log.d(TAG, "DownloadTask (" + this.downloadId + "): onPostExecute. Result is null: " + (result == null) + ". Task was paused: " + isPaused + ". URL: '" + this.urlString + "'");
            activeDownloads.remove(downloadId);
            
            if (isPaused) {
                Log.d(TAG, "Download paused: " + fileName);
                // Já tratado em handlePauseDownload
            } else if (result != null) {
                Log.d(TAG, "Download completed: " + fileName);
                updateNotificationComplete(downloadId, fileName, result);
            } else {
                Log.d(TAG, "Download failed: " + fileName);
                updateNotificationError(downloadId, fileName);
            }
            
            // Enviar broadcast para atualizar a UI
            if (!isPaused) {
                Intent intent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED);
                intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
                broadcastManager.sendBroadcast(intent);
            }
            
            // Se não houver mais downloads ativos, parar o serviço em primeiro plano
            checkStopForeground();
        }

        @Override
        protected void onCancelled(File result) { // onCancelled pode receber o resultado também
            super.onCancelled(result);
            isCancelled = true;
            activeDownloads.remove(downloadId);
            Log.d(TAG, "Download cancelled: " + fileName);
            // A limpeza (DB, notificação, arquivo) é feita em handleCancelDownload
            // Enviar broadcast para garantir que a UI atualize
            Intent intent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED);
            intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
            broadcastManager.sendBroadcast(intent);
            checkStopForeground();
        }
    }
    
    private void checkStopForeground() {
         if (activeDownloads.isEmpty()) {
             stopForeground(true); // true para remover a última notificação se ainda existir
         }
    }

    // --- Database Operations ---

    private long insertDownload(String url, String fileName) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_URL, url);
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_FILE_NAME, fileName);
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_STATUS, Download.STATUS_PENDING);
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_TIMESTAMP, System.currentTimeMillis());
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_DOWNLOADED_BYTES, 0);
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_TOTAL_BYTES, -1);
        // Inicialmente não há caminho local
        values.putNull(DownloadContract.DownloadEntry.COLUMN_NAME_LOCAL_PATH);

        long id = db.insert(DownloadContract.DownloadEntry.TABLE_NAME, null, values);
        if (id == -1) {
            Log.e(TAG, "Error inserting download record for: " + url);
        }
        return id;
    }

    private void updateDownloadProgress(long downloadId, long downloadedBytes, long totalBytes) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_DOWNLOADED_BYTES, downloadedBytes);
        if (totalBytes > 0) {
            values.put(DownloadContract.DownloadEntry.COLUMN_NAME_TOTAL_BYTES, totalBytes);
        }

        db.update(
            DownloadContract.DownloadEntry.TABLE_NAME,
            values,
            DownloadContract.DownloadEntry._ID + " = ?",
            new String[] { String.valueOf(downloadId) }
        );
    }

    private void updateDownloadTotalBytes(long downloadId, long totalBytes) {
        if (totalBytes <= 0) return;
        
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_TOTAL_BYTES, totalBytes);

        db.update(
            DownloadContract.DownloadEntry.TABLE_NAME,
            values,
            DownloadContract.DownloadEntry._ID + " = ?",
            new String[] { String.valueOf(downloadId) }
        );
    }
    
    private void updateDownloadLocalPath(long downloadId, String localPath) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_LOCAL_PATH, localPath);

        db.update(
            DownloadContract.DownloadEntry.TABLE_NAME,
            values,
            DownloadContract.DownloadEntry._ID + " = ?",
            new String[] { String.valueOf(downloadId) }
        );
    }

    private void updateDownloadStatus(long downloadId, int status) {
        updateDownloadStatus(downloadId, status, null);
    }

    private void updateDownloadStatus(long downloadId, int status, String localPath) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_STATUS, status);
        if (localPath != null) {
            values.put(DownloadContract.DownloadEntry.COLUMN_NAME_LOCAL_PATH, localPath);
        }
        // Atualizar o timestamp sempre que o status mudar
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_TIMESTAMP, System.currentTimeMillis());

        int rowsAffected = db.update(
            DownloadContract.DownloadEntry.TABLE_NAME,
            values,
            DownloadContract.DownloadEntry._ID + " = ?",
            new String[] { String.valueOf(downloadId) }
        );
        Log.d(TAG, "Updated status for download " + downloadId + " to " + status + ". Rows affected: " + rowsAffected);
    }

    private long getDownloadIdByUrl(String url) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String[] projection = { DownloadContract.DownloadEntry._ID };
        String selection = DownloadContract.DownloadEntry.COLUMN_NAME_URL + " = ?";
        String[] selectionArgs = { url };

        Cursor cursor = db.query(
            DownloadContract.DownloadEntry.TABLE_NAME,
            projection,
            selection,
            selectionArgs,
            null,
            null,
            null
        );

        long id = -1;
        if (cursor != null && cursor.moveToFirst()) {
            id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadContract.DownloadEntry._ID));
            cursor.close();
        }
        return id;
    }

    public Download getDownloadById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String[] projection = {
            DownloadContract.DownloadEntry._ID,
            DownloadContract.DownloadEntry.COLUMN_NAME_URL,
            DownloadContract.DownloadEntry.COLUMN_NAME_FILE_NAME,
            DownloadContract.DownloadEntry.COLUMN_NAME_LOCAL_PATH,
            DownloadContract.DownloadEntry.COLUMN_NAME_TOTAL_BYTES,
            DownloadContract.DownloadEntry.COLUMN_NAME_DOWNLOADED_BYTES,
            DownloadContract.DownloadEntry.COLUMN_NAME_STATUS,
            DownloadContract.DownloadEntry.COLUMN_NAME_TIMESTAMP
        };
        String selection = DownloadContract.DownloadEntry._ID + " = ?";
        String[] selectionArgs = { String.valueOf(id) };

        Cursor cursor = db.query(
            DownloadContract.DownloadEntry.TABLE_NAME,
            projection,
            selection,
            selectionArgs,
            null,
            null,
            null
        );

        Download download = null;
        if (cursor != null && cursor.moveToFirst()) {
            download = cursorToDownload(cursor);
            cursor.close();
        }
        return download;
    }

    public List<Download> getAllDownloads() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String[] projection = {
            DownloadContract.DownloadEntry._ID,
            DownloadContract.DownloadEntry.COLUMN_NAME_URL,
            DownloadContract.DownloadEntry.COLUMN_NAME_FILE_NAME,
            DownloadContract.DownloadEntry.COLUMN_NAME_LOCAL_PATH,
            DownloadContract.DownloadEntry.COLUMN_NAME_TOTAL_BYTES,
            DownloadContract.DownloadEntry.COLUMN_NAME_DOWNLOADED_BYTES,
            DownloadContract.DownloadEntry.COLUMN_NAME_STATUS,
            DownloadContract.DownloadEntry.COLUMN_NAME_TIMESTAMP
        };
        String sortOrder = DownloadContract.DownloadEntry.COLUMN_NAME_TIMESTAMP + " DESC";

        Cursor cursor = db.query(
            DownloadContract.DownloadEntry.TABLE_NAME,
            projection,
            null,
            null,
            null,
            null,
            sortOrder
        );

        List<Download> downloads = new ArrayList<>();
        if (cursor != null) {
             while (cursor.moveToNext()) {
                 Download download = cursorToDownload(cursor);
                 
                 // Adicionar informações de velocidade para downloads ativos
                 DownloadTask task = activeDownloads.get(download.getId());
                 if (task != null && task.speed > 0 && download.getStatus() == Download.STATUS_DOWNLOADING) {
                     download.setSpeed(task.speed);
                 }
                 
                 downloads.add(download);
             }
             cursor.close();
        }
        
        return downloads;
    }

    public int clearCompletedDownloads() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        // Apenas remover do DB, não deletar arquivos
        int deletedRows = db.delete(
            DownloadContract.DownloadEntry.TABLE_NAME,
            DownloadContract.DownloadEntry.COLUMN_NAME_STATUS + " = ?",
            new String[] { String.valueOf(Download.STATUS_COMPLETED) }
        );
        if (deletedRows > 0) {
            // Notificar UI
            Intent broadcastIntent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED);
            broadcastManager.sendBroadcast(broadcastIntent);
        }
        return deletedRows;
    }
    
    // Método para deletar um download específico (e seu arquivo)
    public boolean deleteDownload(long downloadId) {
        Download download = getDownloadById(downloadId);
        boolean fileDeleted = false;
        if (download != null && download.getLocalPath() != null && !download.getLocalPath().isEmpty()) {
            try {
                File fileToDelete = new File(download.getLocalPath());
                if (fileToDelete.exists()) {
                    fileDeleted = fileToDelete.delete();
                    if (!fileDeleted) {
                         Log.w(TAG, "Failed to delete file: " + download.getLocalPath());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error deleting file for download ID: " + downloadId, e);
            }
        }
        
        // Deletar do banco de dados
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int deletedRows = db.delete(
            DownloadContract.DownloadEntry.TABLE_NAME,
            DownloadContract.DownloadEntry._ID + " = ?",
            new String[] { String.valueOf(downloadId) }
        );
        
        if (deletedRows > 0) {
            // Notificar UI
            Intent broadcastIntent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED);
            broadcastIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId); // Informar qual foi removido
            broadcastManager.sendBroadcast(broadcastIntent);
        }
        
        return deletedRows > 0;
    }
    
    // Método para deletar múltiplos downloads
    public int deleteDownloads(List<Long> downloadIds) {
        int deletedCount = 0;
        for (long id : downloadIds) {
            if (deleteDownload(id)) {
                deletedCount++;
            }
        }
        // A notificação da UI já é feita dentro de deleteDownload
        return deletedCount;
    }

    // --- End Database Operations ---
    
    private Download cursorToDownload(Cursor cursor) {
         return new Download(
             cursor.getLong(cursor.getColumnIndexOrThrow(DownloadContract.DownloadEntry._ID)),
             cursor.getString(cursor.getColumnIndexOrThrow(DownloadContract.DownloadEntry.COLUMN_NAME_URL)),
             cursor.getString(cursor.getColumnIndexOrThrow(DownloadContract.DownloadEntry.COLUMN_NAME_FILE_NAME)),
             cursor.getString(cursor.getColumnIndexOrThrow(DownloadContract.DownloadEntry.COLUMN_NAME_LOCAL_PATH)),
             cursor.getLong(cursor.getColumnIndexOrThrow(DownloadContract.DownloadEntry.COLUMN_NAME_TOTAL_BYTES)),
             cursor.getLong(cursor.getColumnIndexOrThrow(DownloadContract.DownloadEntry.COLUMN_NAME_DOWNLOADED_BYTES)),
             cursor.getInt(cursor.getColumnIndexOrThrow(DownloadContract.DownloadEntry.COLUMN_NAME_STATUS)),
             cursor.getLong(cursor.getColumnIndexOrThrow(DownloadContract.DownloadEntry.COLUMN_NAME_TIMESTAMP))
         );
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Download Channel";
            String description = "Canal para notificações de download do Winlator";
            int importance = NotificationManager.IMPORTANCE_DEFAULT; // Usar LOW para evitar som/vibração constante
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // Configurações adicionais (opcional)
            // channel.enableLights(true);
            // channel.setLightColor(Color.BLUE);
            // channel.enableVibration(false);
            // channel.setVibrationPattern(new long[]{0});
            notificationManager.createNotificationChannel(channel);
        }
    }

    private double bytesToMB(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // Verificar status ao ser vinculado também pode ser útil
        verifyAndCorrectDownloadStatuses();
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Tentar pausar downloads ativos ao destruir o serviço (melhor esforço)
        for (DownloadTask task : activeDownloads.values()) {
             if (task != null && !task.isCancelled() && !task.isPaused) {
                 task.pause();
                 updateDownloadStatus(task.downloadId, Download.STATUS_PAUSED);
             }
        }
        activeDownloads.clear();
        activeNotifications.clear();

        if (executor != null && !executor.isShutdown()) {
            executor.shutdown(); // Properly shutdown the executor
        }

        if (dbHelper != null) {
            dbHelper.close();
        }
        Log.d(TAG, "DownloadService destroyed.");
    }
}

