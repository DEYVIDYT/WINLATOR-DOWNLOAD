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
import java.net.MalformedURLException; // Added import
import java.net.URL;
import java.io.BufferedReader; // Added for DownloadTask helper
import java.io.BufferedWriter; // Added for DownloadTask helper
import java.io.FileReader; // Added for DownloadTask helper
import java.io.FileWriter; // Added for DownloadTask helper
import java.io.FileInputStream; // Added for DownloadTask helper
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

    // Pixeldrain specific actions and extras
    public static final String ACTION_RESOLVE_AND_START_PIXELDRAIN_DOWNLOAD = "com.winlator.Download.action.RESOLVE_PIXELDRAIN_DOWNLOAD";
    public static final String EXTRA_PIXELDRAIN_URL = "com.winlator.Download.extra.PIXELDRAIN_URL";

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
            case ACTION_RESOLVE_AND_START_PIXELDRAIN_DOWNLOAD:
                String pixeldrainUrl = intent.getStringExtra(EXTRA_PIXELDRAIN_URL);
                Log.d(TAG, "onStartCommand: ACTION_RESOLVE_AND_START_PIXELDRAIN_DOWNLOAD for URL: " + pixeldrainUrl);
                if (pixeldrainUrl != null && !pixeldrainUrl.isEmpty()) {
                    if (executor == null || executor.isShutdown()) {
                        executor = Executors.newSingleThreadExecutor();
                    }
                    executor.execute(() -> handleResolvePixeldrainUrl(pixeldrainUrl));
                } else {
                    Log.e(TAG, "Pixeldrain URL is missing for RESOLVE action.");
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
        GofileLinkResolver resolver = new GofileLinkResolver(this); // Error 4: Corrected GofileLinkResolver instantiation
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

    private void handleResolvePixeldrainUrl(String pageUrl) {
        Log.i(TAG, "handleResolvePixeldrainUrl: Starting resolution for " + pageUrl);
        // Ensure PixeldrainLinkResolver is correctly implemented and imported
        PixeldrainLinkResolver resolver = new PixeldrainLinkResolver();
        DownloadItem resolvedItem = resolver.resolvePixeldrainUrl(pageUrl);

        if (resolvedItem != null && resolvedItem.directUrl != null && !resolvedItem.directUrl.isEmpty()) {
            Log.i(TAG, "Pixeldrain resolution successful. Filename: " + resolvedItem.fileName + ", URL: " + resolvedItem.directUrl + ", Size: " + resolvedItem.size);

            Intent downloadIntent = new Intent(this, DownloadService.class);
            downloadIntent.putExtra(EXTRA_ACTION, ACTION_START_DOWNLOAD);
            downloadIntent.putExtra(EXTRA_URL, resolvedItem.directUrl);
            downloadIntent.putExtra(EXTRA_FILE_NAME, resolvedItem.fileName);
            // Pass gofileContentId if it's relevant, though typically not for Pixeldrain direct links
            // If size is available and useful for insertDownload, pass it too.
            // downloadIntent.putExtra(EXTRA_GOFILE_CONTENT_ID, resolvedItem.gofileContentId); // Example if grouping by original link matters

            Log.d(TAG, "Dispatching new download task for resolved Pixeldrain item: " + resolvedItem.fileName);
            mainThreadHandler.post(() -> handleStartDownload(downloadIntent));

        } else {
            Log.e(TAG, "Pixeldrain resolution failed or no items found for " + pageUrl);
            String displayUrl = pageUrl;
            if (pageUrl != null) {
                // Basic extraction for display, similar to Google Drive
                if (pageUrl.lastIndexOf('/') != -1 && pageUrl.lastIndexOf('/') < pageUrl.length() -1) {
                     displayUrl = pageUrl.substring(pageUrl.lastIndexOf('/') + 1);
                }
            } else {
                displayUrl = "Desconhecido";
            }

            final String userMessage = "Falha ao resolver link Pixeldrain: " + displayUrl;
            mainThreadHandler.post(() -> Toast.makeText(DownloadService.this, userMessage, Toast.LENGTH_LONG).show());

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cancel)
                .setContentTitle("Erro no Link Pixeldrain")
                .setContentText("Não foi possível resolver arquivos do link Pixeldrain.")
                .setAutoCancel(true);
            if (notificationManager != null) {
                 notificationManager.notify((int) (System.currentTimeMillis() % 10000) + 3, builder.build()); // Use different ID
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
                            // Error 5, 6, 7, 8: Corrected startDownload call (using 4-arg legacy for now, which will be fixed)
                            startDownload(existingDownload.getId(), existingDownload.getUrl(), existingDownload.getFileName(), effectiveAuthToken);
                        } else if (existingDownload.getStatus() == Download.STATUS_DOWNLOADING) {
                            Log.w(TAG, "DB indicates downloading, but no active task found for " + existingDownload.getFileName() + ". Attempting to restart.");
                            Log.i(TAG, "handleStartDownload (MainThread): About to call startDownload for existing downloading (but no task) download. ID: " + existingDownload.getId() + ", URL: '" + existingDownload.getUrl() + "', FileName: '" + existingDownload.getFileName() + "'");
                            // Error 5, 6, 7, 8: Corrected startDownload call
                            startDownload(existingDownload.getId(), existingDownload.getUrl(), existingDownload.getFileName(), effectiveAuthToken);
                        }
                    } else {
                        // DB had an ID, but we couldn't fetch the Download object. This is an inconsistent state.
                        Log.w(TAG, "Could not fetch existing download with ID: " + downloadId + ". Treating as new.");
                        final long newDownloadIdAfterNull = insertDownload(urlString, fileName);
                        Log.d(TAG, "handleStartDownload (Executor): Existing download object was null for ID " + downloadId + ". Attempted insert, new ID: " + newDownloadIdAfterNull);
                        if (newDownloadIdAfterNull != -1) {
                            Log.i(TAG, "handleStartDownload (MainThread): About to call startDownload for new download (after null existing). ID: " + newDownloadIdAfterNull + ", URL: '" + urlString + "', FileName: '" + fileName + "'");
                            // Error 5, 6, 7, 8: Corrected startDownload call
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
                    // Error 5, 6, 7, 8: Corrected startDownload call
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
            // Error 5, 6, 7, 8: Corrected startDownload call for resume
            String localPath = download.getLocalPath();
            if (localPath == null || localPath.isEmpty()) { // Fallback if localPath is somehow not set
                File baseAppDownloadDir = new File(AppSettings.getDownloadPath(this));
                localPath = new File(baseAppDownloadDir, download.getFileName()).getAbsolutePath();
            }
            boolean mtEnabled = AppSettings.isMultithreadDownloadEnabled(this);
            int mtThreads = AppSettings.getDownloadThreadsCount(this);
            // For now, resume will not have the Gofile token unless we store it in DB.
            startDownload(downloadId, download.getUrl(), download.getFileName(), null, localPath, mtEnabled, mtThreads);
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
            // Error 5, 6, 7, 8: Corrected startDownload call for retry
            String localPath = download.getLocalPath();
            if (localPath == null || localPath.isEmpty()) { // Fallback
                File baseAppDownloadDir = new File(AppSettings.getDownloadPath(this));
                localPath = new File(baseAppDownloadDir, download.getFileName()).getAbsolutePath();
            }
            boolean mtEnabled = AppSettings.isMultithreadDownloadEnabled(this);
            int mtThreads = AppSettings.getDownloadThreadsCount(this);
            // Retry will also not use a token unless persisted.
            startDownload(downloadId, download.getUrl(), download.getFileName(), null, localPath, mtEnabled, mtThreads);
        }
    }

    // Primary startDownload signature (Error 5, 6, 7, 8)
    private void startDownload(long downloadId, String urlString, String displayFileName, String authToken, String targetLocalPath, boolean multithreadEnabled, int threadsCount) {
        Log.i(TAG, "startDownload: Entry. ID: " + downloadId + ", URL: '" + urlString + "', FileName: '" + displayFileName + "', AuthToken: " + (authToken != null ? "present" : "null") + ", Path: " + targetLocalPath + ", MT: " + multithreadEnabled + ", Threads: " + threadsCount);

        if (targetLocalPath == null || targetLocalPath.isEmpty()) {
            Log.e(TAG, "Target local path is null or empty for download ID " + downloadId + ". Cannot start download.");
            // Consider posting an error notification or toast here
            return;
        }

        // Criar ou atualizar a notificação
        NotificationCompat.Builder builder = createOrUpdateNotificationBuilder(downloadId, displayFileName);
        activeNotifications.put(downloadId, builder);
        
        // Iniciar o serviço em primeiro plano
        startForeground((int) (NOTIFICATION_ID_BASE + downloadId), builder.build());
        
        updateDownloadStatus(downloadId, Download.STATUS_DOWNLOADING, targetLocalPath);
        
        DownloadTask task = new DownloadTask(downloadId, urlString, displayFileName, builder, authToken, targetLocalPath, multithreadEnabled, threadsCount);
        activeDownloads.put(downloadId, task);
        Log.i(TAG, "startDownload: About to execute DownloadTask for ID: " + downloadId);
        task.execute();
        
        Intent broadcastIntent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED);
        broadcastIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        broadcastManager.sendBroadcast(broadcastIntent);
    }

    // Legacy overload (4 args) - Corrected to call the primary 7-arg signature (Error 5, 6, 7, 8)
    private void startDownload(long downloadId, String urlString, String displayFileName, String authToken) {
        Log.w(TAG, "Legacy startDownload (4 args) called for ID: " + downloadId + ". Constructing default path and fetching MT settings.");
        // Determine default local path (this might need to be smarter if Gofile downloads use this legacy path)
        // For non-Gofile, displayFileName is usually the final file name.
        // AppSettings.getDownloadPath(this) gives the base directory.
        String downloadPathSetting = AppSettings.getDownloadPath(this);
        File baseAppDownloadDir;
        if (AppSettings.DEFAULT_DOWNLOAD_PATH.equals(downloadPathSetting) || downloadPathSetting == null || downloadPathSetting.isEmpty()) {
            baseAppDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        } else {
            baseAppDownloadDir = new File(downloadPathSetting);
        }
        if (!baseAppDownloadDir.exists() && !baseAppDownloadDir.mkdirs()) {
            Log.e(TAG, "Failed to create download directory: " + baseAppDownloadDir.getAbsolutePath() + ". Falling back to public Downloads dir.");
            baseAppDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            // Ensure this fallback directory also exists
            if (!baseAppDownloadDir.exists() && !baseAppDownloadDir.mkdirs()) {
                 Log.e(TAG, "Fallback download directory also failed to be created: " + baseAppDownloadDir.getAbsolutePath());
                 // Handle this critical error, perhaps by notifying user and not starting download
                 return;
            }
        }
        String defaultLocalPath = new File(baseAppDownloadDir, displayFileName).getAbsolutePath();

        boolean mtEnabled = AppSettings.isMultithreadDownloadEnabled(this);
        int mtThreads = AppSettings.getDownloadThreadsCount(this);
        startDownload(downloadId, urlString, displayFileName, authToken, defaultLocalPath, mtEnabled, mtThreads);
    }

    // Removed the 3-arg startDownload overload as it's ambiguous and not directly called by corrected logic.
    // If any old code path still uses it, it would need similar correction or removal.

    private NotificationCompat.Builder createOrUpdateNotificationBuilder(long downloadId, String displayFileName) { // Parameter renamed
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
                .setContentTitle(displayFileName) // Use displayFileName
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

    private class DownloadTask extends AsyncTask<Void, Integer, File> { // Error 9: displayFileName already correct here from previous step
        private final long downloadId;
        private final String urlString;
        private final String displayFileName;
        private final NotificationCompat.Builder notificationBuilder;
        private final String localPath;
        private final String authToken;
        private final boolean multithreadEnabled;
        private final int threadsCount;

        private boolean isPaused = false;
        // isCancelled from AsyncTask already exists, but we use it for our logic checks.
        // Let's ensure AsyncTask's isCancelled() is used where appropriate or a local flag if needed for specific timing.
        // For now, assuming isCancelled() from AsyncTask is the primary check for cancellation state.
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
            this.displayFileName = displayFileName; // Error 9: Initializes this.displayFileName
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
            // If using segmentExecutor, you might want to interrupt its threads or cancel futures here
            if (segmentExecutor != null && !segmentExecutor.isShutdown()) {
                Log.d(TAG, "DownloadTask (" + this.downloadId + "): Pausing - shutting down segmentExecutor.");
                for (Future<?> future : segmentFutures) {
                    if (!future.isDone() && !future.isCancelled()) {
                        future.cancel(true); // Attempt to interrupt segment threads
                    }
                }
                segmentExecutor.shutdownNow();
            }
        }

        @Override
        protected void onPreExecute() {
            Log.d(TAG, "DownloadTask (" + this.downloadId + "): onPreExecute. URL: '" + this.urlString + "'");
            super.onPreExecute();
            startTime = System.currentTimeMillis();
            
            Download existingDownload = getDownloadById(downloadId);
            if (existingDownload != null) { // Check if download exists in DB
                 if (existingDownload.getDownloadedBytes() > 0 && existingDownload.getLocalPath() != null && new File(existingDownload.getLocalPath()).exists()) {
                    // Only restore downloadedBytes if the file exists and is not a directory.
                    // For multi-part, this simple check is not enough; parts need to be checked.
                    // For single-thread, this is okay.
                    if (!this.multithreadEnabled || this.threadsCount <= 1) {
                         downloadedBytes = existingDownload.getDownloadedBytes();
                    }
                 }
                 if (existingDownload.getTotalBytes() > 0) {
                    totalBytes = existingDownload.getTotalBytes();
                 }
            }
        }

        // Error 10-17: Define missing helper methods and SegmentDownloader class
        private boolean performHeadRequest() {
            HttpURLConnection headConnection = null;
            try {
                URL headUrl = new URL(this.urlString);
                headConnection = (HttpURLConnection) headUrl.openConnection();
                headConnection.setRequestMethod("HEAD");
                if (this.authToken != null && !this.authToken.isEmpty()) {
                    headConnection.setRequestProperty("Cookie", "accountToken=" + this.authToken);
                }
                headConnection.setConnectTimeout(10000); // 10 seconds
                headConnection.setReadTimeout(10000); // 10 seconds
                headConnection.connect();
                int responseCode = headConnection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    this.totalBytes = headConnection.getContentLengthLong();
                    if (this.totalBytes > 0) {
                        updateDownloadTotalBytes(this.downloadId, this.totalBytes);
                    }
                    String acceptRanges = headConnection.getHeaderField("Accept-Ranges");
                    Log.d(TAG, "HEAD Request for " + this.displayFileName + ": TotalBytes=" + this.totalBytes + ", Accept-Ranges=" + acceptRanges); // Error 18-23: displayFileName
                    return this.totalBytes > 0 && "bytes".equalsIgnoreCase(acceptRanges);
                } else {
                    Log.w(TAG, "HEAD request failed for " + this.displayFileName + " with code: " + responseCode); // Error 18-23: displayFileName
                    return false;
                }
            } catch (IOException e) {
                Log.e(TAG, "HEAD request IOException for " + this.displayFileName + ": " + e.getMessage(), e); // Error 18-23: displayFileName
                return false;
            } finally {
                if (headConnection != null) headConnection.disconnect();
            }
        }

        private File manageTempPartsDirectory(File targetFile, int numThreads) {
            File partsDir = new File(targetFile.getParentFile(), targetFile.getName() + ".parts");
            File infoFile = new File(partsDir, "info.dat");
            if (partsDir.exists()) {
                boolean clearParts = true;
                if (infoFile.exists()) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(infoFile))) {
                        String line = reader.readLine();
                        if (line != null && Integer.parseInt(line) == numThreads) {
                            clearParts = false; // Config matches, assume parts are resumable
                        }
                    } catch (IOException | NumberFormatException e) {
                        Log.w(TAG, "Could not read/parse info file: " + infoFile.getPath() + ". Clearing parts.", e);
                    }
                }
                if (clearParts) {
                    Log.i(TAG, "Clearing existing parts directory: " + partsDir.getAbsolutePath());
                    deleteRecursive(partsDir); // Existing parts dir but info is bad or missing
                }
            }
            if (!partsDir.exists()) {
                if (!partsDir.mkdirs()) {
                     Log.e(TAG, "Failed to create parts directory: " + partsDir.getAbsolutePath());
                     return null; // Indicate failure
                }
            }
            // Write/update info file only if directory creation/check was successful
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(infoFile))) {
                writer.write(String.valueOf(numThreads));
            } catch (IOException e) {
                Log.e(TAG, "Could not write info file: " + infoFile.getPath(), e);
                // This might not be fatal if parts can still be downloaded.
            }
            return partsDir;
        }

        private void deleteRecursive(File fileOrDirectory) {
            if (fileOrDirectory.isDirectory()) {
                File[] children = fileOrDirectory.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursive(child);
                    }
                }
            }
            if (!fileOrDirectory.delete()) {
                Log.w(TAG, "Failed to delete: " + fileOrDirectory.getAbsolutePath());
            }
        }

        // Corrected signature from previous version, removing basePartSize as it's calculated inside or not needed
        private boolean mergePartFiles(File partsDir, File targetFile, int numThreads) {
            Log.i(TAG, "Merging " + numThreads + " part files into " + targetFile.getName());
            long totalMergedBytes = 0;
            try (OutputStream fos = new FileOutputStream(targetFile)) {
                for (int i = 0; i < numThreads; i++) {
                    if (isCancelled()) { Log.w(TAG, "Merging cancelled for " + this.displayFileName); return false; } // Error 18-23: displayFileName
                    File partFile = new File(partsDir, "part_" + i);

                    // Basic check: if a part file is missing and we expected a multi-part download, it's an error.
                    // More sophisticated checks might involve expected part sizes if they were stored.
                    if (!partFile.exists() && this.totalBytes > 0) {
                         Log.e(TAG, "Part file missing: " + partFile.getAbsolutePath() + " for " + this.displayFileName); // Error 18-23: displayFileName
                         return false;
                    }

                    if (partFile.exists()) { // Check again if it exists (could be a 0-byte part if totalBytes was 0)
                        totalMergedBytes += partFile.length();
                        try (InputStream fis = new FileInputStream(partFile)) {
                            byte[] buffer = new byte[8192]; // 8KB buffer
                            int bytesRead;
                            while ((bytesRead = fis.read(buffer)) != -1) {
                                if (isCancelled()) { Log.w(TAG, "Merging cancelled during write for " + this.displayFileName); return false; } // Error 18-23: displayFileName
                                fos.write(buffer, 0, bytesRead);
                            }
                        }
                    }
                }
                Log.i(TAG, "Successfully merged part files for " + this.displayFileName + ". Total merged bytes: " + totalMergedBytes); // Error 18-23: displayFileName
                if (this.totalBytes > 0 && totalMergedBytes != this.totalBytes) {
                    Log.e(TAG, "Merged file size (" + totalMergedBytes + ") does not match expected total size (" + this.totalBytes + ") for " + this.displayFileName); // Error 18-23: displayFileName
                    return false;
                }
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Error merging part files for " + this.displayFileName + ": " + e.getMessage(), e); // Error 18-23: displayFileName
                return false;
            }
        }

        private class SegmentDownloader implements Runnable {
            private final URL url;
            private final long startByte;
            private final long endByte;
            private final File partFile;
            private final String authToken;
            private final int segmentIndex; // For logging/debugging

            SegmentDownloader(URL url, long startByte, long endByte, File partFile, String authToken, int segmentIndex) {
                this.url = url;
                this.startByte = startByte;
                this.endByte = endByte;
                this.partFile = partFile;
                this.authToken = authToken;
                this.segmentIndex = segmentIndex;
            }

            @Override
            public void run() {
                HttpURLConnection conn = null;
                InputStream input = null;
                RandomAccessFile output = null; // Use RandomAccessFile to seek and write
                long currentDownloaded = 0;

                try {
                    if (partFile.exists()) {
                        currentDownloaded = partFile.length();
                    }

                    // If part is already complete (e.g. from a previous attempt)
                    if (currentDownloaded >= (endByte - startByte + 1)) {
                        Log.i(TAG, "Segment " + segmentIndex + " for " + DownloadTask.this.displayFileName + " already complete. Size: " + currentDownloaded); // Error 18-23: displayFileName
                        DownloadTask.this.incrementOverallProgress(currentDownloaded); // Ensure this part contributes to total
                        return;
                    }

                    // If resuming, adjust startByte for the range header
                    long actualStartByte = startByte + currentDownloaded;
                    if (actualStartByte > endByte && endByte != -1) { // Check if overshot (can happen if endByte is calculated)
                         Log.i(TAG, "Segment " + segmentIndex + " for " + DownloadTask.this.displayFileName + " overshot. Start: " + actualStartByte + ", End: " + endByte);
                         DownloadTask.this.incrementOverallProgress(currentDownloaded); // Still count what was there
                         return; // Effectively complete or invalid range
                    }


                    Log.d(TAG, "Segment " + segmentIndex + " for " + DownloadTask.this.displayFileName + ": Connecting. Range: " + actualStartByte + "-" + endByte + ". Part file: " + partFile.getName()); // Error 18-23: displayFileName
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("Range", "bytes=" + actualStartByte + "-" + endByte);
                    if (authToken != null && !authToken.isEmpty()) {
                        conn.setRequestProperty("Cookie", "accountToken=" + authToken);
                    }
                    conn.setConnectTimeout(15000); // 15s
                    conn.setReadTimeout(15000);   // 15s
                    conn.connect();

                    int responseCode = conn.getResponseCode();
                    // HTTP_PARTIAL (206) is expected for range requests
                    if (responseCode != HttpURLConnection.HTTP_PARTIAL && responseCode != HttpURLConnection.HTTP_OK) {
                        Log.e(TAG, "Segment " + segmentIndex + " for " + DownloadTask.this.displayFileName + " failed. Server returned HTTP " + responseCode + " " + conn.getResponseMessage()); // Error 18-23: displayFileName
                        throw new IOException("Server error: " + responseCode);
                    }

                    input = conn.getInputStream();
                    output = new RandomAccessFile(partFile, "rw");
                    output.seek(currentDownloaded); // Seek to the end of already downloaded content for this part

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = input.read(buffer)) != -1) {
                        if (DownloadTask.this.isPaused || DownloadTask.this.isCancelled()) { // Check outer task's status
                            Log.d(TAG, "Segment " + segmentIndex + " for " + DownloadTask.this.displayFileName + ": Download interrupted (paused/cancelled)."); // Error 18-23: displayFileName
                            if (DownloadTask.this.isCancelled()) throw new InterruptedException("Download cancelled");
                            return; // Paused, stop but don't throw error
                        }
                        output.write(buffer, 0, bytesRead);
                        currentDownloaded += bytesRead;
                        DownloadTask.this.incrementOverallProgress(bytesRead);
                    }
                     Log.i(TAG, "Segment " + segmentIndex + " for " + DownloadTask.this.displayFileName + " completed successfully. Bytes read in this run: " + (currentDownloaded - partFile.length() + bytesRead > 0 ? bytesRead : 0) ) ;


                } catch (MalformedURLException e) { // Should not happen if URL was validated before
                    Log.e(TAG, "Segment " + segmentIndex + " MalformedURLException: " + e.getMessage(), e);
                    // Consider how to signal failure to the main DownloadTask
                } catch (IOException | InterruptedException e) {
                    if (DownloadTask.this.isPaused) {
                        Log.i(TAG, "Segment " + segmentIndex + " for " + DownloadTask.this.displayFileName + " paused: " + e.getMessage()); // Error 18-23: displayFileName
                    } else if (DownloadTask.this.isCancelled()) {
                        Log.i(TAG, "Segment " + segmentIndex + " for " + DownloadTask.this.displayFileName + " cancelled: " + e.getMessage()); // Error 18-23: displayFileName
                    } else {
                        Log.e(TAG, "Segment " + segmentIndex + " for " + DownloadTask.this.displayFileName + " error: " + e.getMessage(), e); // Error 18-23: displayFileName
                    }
                    // Propagate exception to be caught by Future.get() in main task
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt(); // Restore interrupt status
                    throw new RuntimeException("Segment " + segmentIndex + " failed or was interrupted.", e);
                } finally {
                    try {
                        if (input != null) input.close();
                        if (output != null) output.close();
                        if (conn != null) conn.disconnect();
                    } catch (IOException e) {
                        Log.e(TAG, "Segment " + segmentIndex + " error closing resources: " + e.getMessage(), e);
                    }
                }
            }
        }


        @Override
        protected File doInBackground(Void... params) {
            Log.i(TAG, "DownloadTask (" + this.downloadId + ") doInBackground. Multithread: " + this.multithreadEnabled + ", Threads: " + this.threadsCount + ", URL: " + this.urlString);

            if (this.multithreadEnabled && this.threadsCount > 1 && this.localPath != null && performHeadRequest()) {
                Log.i(TAG, "Proceeding with multi-threaded download for " + this.displayFileName + ". Total size: " + this.totalBytes + " bytes. Threads: " + this.threadsCount); // Error 18-23: displayFileName

                File partsDir = manageTempPartsDirectory(new File(this.localPath), this.threadsCount);
                if (partsDir == null) {
                    Log.e(TAG, "Failed to manage parts directory for " + this.displayFileName + ". Falling back to single-threaded download."); // Error 18-23: displayFileName
                    return singleThreadDownloadLogic();
                }

                this.segmentExecutor = Executors.newFixedThreadPool(this.threadsCount);
                this.segmentFutures.clear();

                this.totalBytesDownloadedAcrossSegments = 0; // Recalculate based on existing parts
                for (int i = 0; i < this.threadsCount; i++) {
                    File partFileCheck = new File(partsDir, "part_" + i);
                    if (partFileCheck.exists()) {
                        this.totalBytesDownloadedAcrossSegments += partFileCheck.length();
                    }
                }
                Log.i(TAG, "Initial total downloaded across segments (from existing parts) for " + this.displayFileName + ": " + this.totalBytesDownloadedAcrossSegments); // Error 18-23: displayFileName
                incrementOverallProgress(0); // Initialize progress display

                long segmentSize = this.totalBytes / this.threadsCount;

                boolean setupError = false;
                for (int i = 0; i < this.threadsCount; i++) {
                    if (isCancelled() || isPaused) { setupError = true; break; }
                    long startByte = i * segmentSize;
                    long endByte = (i == this.threadsCount - 1) ? this.totalBytes - 1 : startByte + segmentSize - 1;
                    File partFile = new File(partsDir, "part_" + i);
                    Log.d(TAG, "Segment " + i + " for " + this.displayFileName + ": Calculated range " + startByte + "-" + endByte + " for part file " + partFile.getName()); // Error 18-23: displayFileName
                    try {
                        SegmentDownloader segmentTask = new SegmentDownloader(new URL(this.urlString), startByte, endByte, partFile, this.authToken, i);
                        this.segmentFutures.add(this.segmentExecutor.submit(segmentTask));
                    } catch (MalformedURLException e) { // Should not happen if URL was validated earlier
                        Log.e(TAG, "MalformedURLException for segment " + i + ", URL: " + this.urlString, e);
                        setupError = true;
                        break;
                    }
                }

                boolean allSegmentsSuccess = !setupError;
                if (allSegmentsSuccess && !isCancelled() && !isPaused) {
                    for (Future<?> future : this.segmentFutures) {
                        if (isCancelled() || isPaused) { allSegmentsSuccess = false; break; }
                        try {
                            future.get(); // Wait for segment to complete
                        } catch (Exception e) {
                            Log.e(TAG, "A segment download for " + this.displayFileName + " failed or was cancelled during future.get().", e); // Error 18-23: displayFileName
                            allSegmentsSuccess = false;
                            // Cancel remaining futures if one fails
                            for(Future<?> f : this.segmentFutures) { if (!f.isDone() && !f.isCancelled()) f.cancel(true); }
                            break;
                        }
                    }
                } else if (!setupError) { // If setup was fine, but then paused or cancelled
                    allSegmentsSuccess = false; // Mark as not all successful if paused or cancelled during setup or waiting
                    Log.i(TAG, "Download for " + this.displayFileName + " was cancelled, paused, or setup error before all segment futures could be processed."); // Error 18-23: displayFileName
                }

                if (this.segmentExecutor != null) { // Shutdown executor
                     if (!allSegmentsSuccess || isCancelled() || isPaused) { this.segmentExecutor.shutdownNow(); }
                     else { this.segmentExecutor.shutdown(); }
                }

                if (isCancelled()) {
                    Log.i(TAG, "Download cancelled, cleaning up parts for " + this.displayFileName); // Error 18-23: displayFileName
                    deleteRecursive(partsDir);
                    return null;
                }
                if (isPaused) { // Check local isPaused flag for tasks that were paused by user action
                    Log.i(TAG, "Download paused for " + this.displayFileName + ". Parts retained for potential resume."); // Error 18-23: displayFileName
                    return null;
                }

                if (allSegmentsSuccess) {
                     // Sanity check total downloaded bytes after all segments attempt completion
                    if (this.totalBytesDownloadedAcrossSegments < this.totalBytes && this.totalBytes > 0) {
                        Log.e(TAG, "Total downloaded bytes mismatch before merge for " + this.displayFileName + ". Expected: " + this.totalBytes + ", Got: " + this.totalBytesDownloadedAcrossSegments + ". Marking as failed."); // Error 18-23: displayFileName
                        updateDownloadStatus(this.downloadId, Download.STATUS_FAILED); // Update DB status
                        deleteRecursive(partsDir); // Clean up inconsistent parts
                        return null;
                    }

                    Log.i(TAG, "All segments downloaded successfully for " + this.displayFileName + ". Starting merge."); // Error 18-23: displayFileName
                    if (notificationBuilder != null) {
                        notificationBuilder.setContentText("Merging parts...").setProgress(0, 0, true); // Indeterminate progress for merge
                        if (DownloadService.this.notificationManager != null) {
                             DownloadService.this.notificationManager.notify((int) (NOTIFICATION_ID_BASE + downloadId), notificationBuilder.build());
                        } else { Log.w(TAG, "notificationManager is null, cannot update notification for merging state."); }
                    } else { Log.w(TAG, "notificationBuilder is null, cannot update notification for merging state."); }

                    boolean mergeSuccess = mergePartFiles(partsDir, new File(this.localPath), this.threadsCount); // Pass correct number of threads
                    if (mergeSuccess) {
                        updateDownloadStatus(this.downloadId, Download.STATUS_COMPLETED); // Update DB status
                        deleteRecursive(partsDir); // Clean up parts directory after successful merge
                        return new File(this.localPath);
                    } else {
                        Log.e(TAG, "Failed to merge part files for " + this.displayFileName); // Error 18-23: displayFileName
                        updateDownloadStatus(this.downloadId, Download.STATUS_FAILED); // Update DB status
                        deleteRecursive(partsDir); // Clean up parts even if merge failed to avoid issues on retry
                        return null;
                    }
                } else { // Segments did not all succeed (and not paused/cancelled before completion of loop)
                    Log.e(TAG, "One or more segments failed or task was interrupted for " + this.displayFileName + "."); // Error 18-23: displayFileName
                    if (!isPaused && !isCancelled()) { // If not paused or cancelled explicitly by user/task logic
                        updateDownloadStatus(this.downloadId, Download.STATUS_FAILED); // Update DB status
                        // Parts are kept for potential resume if paused, otherwise consider cleanup based on failure type.
                        // For now, if not paused/cancelled, and segments fail, parts are kept.
                    }
                    return null;
                }
            } else { // Fallback to single-threaded download
                 if (this.multithreadEnabled && this.threadsCount > 1) {
                    Log.w(TAG, "Falling back to single-threaded download for " + this.displayFileName + " (HEAD failed, no range support/size, or localPath null)."); // Error 18-23: displayFileName
                } else {
                    Log.d(TAG, "Proceeding with single-threaded download for " + this.displayFileName); // Error 18-23: displayFileName
                }
                return singleThreadDownloadLogic();
            }
        }

        private void incrementOverallProgress(long delta) { // Renamed from synchronized void for clarity, lock explicit
            synchronized (progressLock) {
            synchronized (progressLock) { // Ensure thread-safe update to shared progress variables
                if (delta > 0) { // Only add if actual bytes were downloaded
                    this.totalBytesDownloadedAcrossSegments += delta;
                }
                // Update DB progress regardless of delta, as totalBytes might have changed or initial call
                updateDownloadProgress(downloadId, this.totalBytesDownloadedAcrossSegments, this.totalBytes);

                if (this.totalBytes > 0) {
                    int progress = (int) ((this.totalBytesDownloadedAcrossSegments * 100) / this.totalBytes);
                    publishProgress(progress); // Update notification via onProgressUpdate
                } else {
                    publishProgress(-1); // Indeterminate progress
                }
            }

            long currentTime = System.currentTimeMillis();
            // Throttle broadcast updates for performance
            if (delta >= 0 && (currentTime - lastUpdateTime > 500 || (this.totalBytes > 0 && this.totalBytesDownloadedAcrossSegments == this.totalBytes) ) ) {
                synchronized(progressLock) { // Also protect speed calculation with shared variables
                    long elapsedTime = currentTime - startTime; // startTime is from onPreExecute
                    if (elapsedTime > 0) {
                         // Calculate speed based on total downloaded by this task across all its segments
                         this.speed = (double) this.totalBytesDownloadedAcrossSegments / (elapsedTime / 1000.0); // bytes/sec
                    } else {
                        this.speed = 0; // Avoid division by zero if no time has passed
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

        private File singleThreadDownloadLogic() { // Error 18-23: Usages of this.fileName to be this.displayFileName
            InputStream input = null;
            OutputStream output = null;
            HttpURLConnection connection = null;
            File downloadedFile = null;
            RandomAccessFile randomAccessFile = null;

            try {
                Log.d(TAG, "DownloadTask (" + this.downloadId + "): singleThreadDownloadLogic starting for " + this.displayFileName + ". URL: '" + this.urlString + "'. LocalPath: '" + this.localPath + "'. AuthToken: " + (this.authToken != null ? "present" : "null"));

                if (this.localPath == null || this.localPath.isEmpty()) {
                    Log.e(TAG, "DownloadTask (" + this.downloadId + ") for " + this.displayFileName + ": Local path is null or empty. Cannot proceed with singleThreadDownloadLogic.");
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
                byte[] data = new byte[8192]; // 8KB buffer
                int count;
                long bytesSinceLastUpdate = 0; // For throttling UI updates
                
                while ((count = input.read(data)) != -1) {
                    if (isCancelled()) { // Use AsyncTask's isCancelled()
                        Log.d(TAG, "Single-thread download cancelled for " + this.displayFileName);
                        return null;
                    }
                    if (isPaused) { // Check local isPaused flag
                        Log.d(TAG, "Single-thread download paused for " + this.displayFileName);
                        updateDownloadProgress(downloadId, downloadedBytes, totalBytes); // Save current progress
                        return null;
                    }
                    
                    downloadedBytes += count;
                    bytesSinceLastUpdate += count; // Accumulate bytes since last UI update
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
            Log.d(TAG, "DownloadTask (" + this.downloadId + "): onPostExecute for " + this.displayFileName + ". Result is null: " + (result == null) + ". Task was paused: " + isPaused + ". URL: '" + this.urlString + "'");
            activeDownloads.remove(downloadId); // Remove from active tasks
            
            if (isPaused) {
                Log.d(TAG, "Download paused: " + this.displayFileName); // Error 18-23: displayFileName
                // Status already updated in handlePauseDownload or pause() method of task
            } else if (isCancelled()) { // Check if task was cancelled
                Log.d(TAG, "Download cancelled during execution: " + this.displayFileName); // Error 18-23: displayFileName
                // Cleanup is handled by handleCancelDownload or onCancelled
            } else if (result != null) {
                Log.d(TAG, "Download completed: " + this.displayFileName); // Error 18-23: displayFileName
                updateNotificationComplete(downloadId, this.displayFileName, result); // Error 18-23: displayFileName
            } else { // Download failed (result is null and not paused/cancelled)
                Log.d(TAG, "Download failed: " + this.displayFileName); // Error 18-23: displayFileName
                updateNotificationError(downloadId, this.displayFileName); // Error 18-23: displayFileName
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
        protected void onCancelled(File result) {
            super.onCancelled(result); // Call super
            // isCancelled = true; // No longer needed, use isCancelled() from AsyncTask
            activeDownloads.remove(downloadId);
            Log.d(TAG, "Download explicitly cancelled via onCancelled for: " + this.displayFileName); // Error 18-23: displayFileName
            // Actual cleanup (file deletion, DB update to CANCELLED if needed) is typically handled
            // by the method that called task.cancel(true), e.g., handleCancelDownload.
            // Here, we just ensure the task is removed from activeDownloads and service foreground state is checked.
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

