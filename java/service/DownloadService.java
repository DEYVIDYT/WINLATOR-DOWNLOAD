// This is a partial representation of DownloadService.java focusing on DownloadTask
// and areas needing fixes. Assume the rest of DownloadService.java is as per
// the last successfully corrected version.

package com.winlator.Download.service;

// ... [All necessary imports from the previous complete version of DownloadService.java] ...
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


public class DownloadService extends Service {

    private static final String TAG = "DownloadService";
    // ... [All other constants and fields from the complete version] ...
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


    public class DownloadBinder extends Binder { /* ... */
        public DownloadService getService() {
            return DownloadService.this;
        }
    }
    @Override
    public void onCreate() { /* ... (ensure executor and mainThreadHandler are initialized) ... */
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
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { /* ... (as per last correct version) ... */
        if (intent == null) {
            Log.w(TAG, "onStartCommand received a null intent.");
            checkStopForeground();
            return START_STICKY;
        }

        if (activeDownloads.isEmpty() && !isForegroundServiceRunning()) { // Check if not already in foreground by a task
             Notification genericNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("Download Service")
                .setContentText("Serviço de download ativo...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
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
            } else if (intent.hasExtra(EXTRA_PIXELDRAIN_URL)) {
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

        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }

        switch (action) {
            case ACTION_START_DOWNLOAD:
                handleStartDownload(intent);
                break;
            case ACTION_RESOLVE_AND_START_GOFILE_DOWNLOAD:
                String gofileUrl = intent.getStringExtra(EXTRA_GOFILE_URL);
                String gofilePassword = intent.getStringExtra(EXTRA_GOFILE_PASSWORD);
                if (gofileUrl != null && !gofileUrl.isEmpty()) {
                    executor.execute(() -> handleResolveGofileUrl(gofileUrl, gofilePassword));
                } else { Log.e(TAG, "Gofile URL is missing for RESOLVE action."); }
                break;
            case ACTION_RESOLVE_AND_START_MEDIAFIRE_DOWNLOAD:
                String mediafireUrl = intent.getStringExtra(EXTRA_MEDIAFIRE_URL);
                if (mediafireUrl != null && !mediafireUrl.isEmpty()) {
                    executor.execute(() -> handleResolveMediafireUrl(mediafireUrl));
                } else { Log.e(TAG, "MediaFire URL is missing for RESOLVE action."); }
                break;
            case ACTION_RESOLVE_AND_START_GOOGLE_DRIVE_DOWNLOAD:
                String googleDriveUrl = intent.getStringExtra(EXTRA_GOOGLE_DRIVE_URL);
                if (googleDriveUrl != null && !googleDriveUrl.isEmpty()) {
                    executor.execute(() -> handleResolveGoogleDriveUrl(googleDriveUrl));
                } else { Log.e(TAG, "Google Drive URL is missing for RESOLVE action."); }
                break;
            case ACTION_RESOLVE_AND_START_PIXELDRAIN_DOWNLOAD:
                String pixeldrainUrl = intent.getStringExtra(EXTRA_PIXELDRAIN_URL);
                if (pixeldrainUrl != null && !pixeldrainUrl.isEmpty()) {
                    executor.execute(() -> handleResolvePixeldrainUrl(pixeldrainUrl));
                } else { Log.e(TAG, "PixelDrain URL is missing for RESOLVE action."); }
                break;
            case ACTION_PAUSE_DOWNLOAD: handlePauseDownload(intent); break;
            case ACTION_RESUME_DOWNLOAD: handleResumeDownload(intent); break;
            case ACTION_CANCEL_DOWNLOAD: handleCancelDownload(intent); break;
            case ACTION_RETRY_DOWNLOAD: handleRetryDownload(intent); break;
            default:
                Log.w(TAG, "onStartCommand: Received unknown or unhandled action: " + action);
                // ... default error handling
                break;
        }
        return START_STICKY;
    }

    // ... [All other methods like createPreparingNotification, verifyAndCorrectDownloadStatuses, handleStartDownload,
    // handlePauseDownload, handleResumeDownload, handleCancelDownload, handleRetryDownload, startDownload (both versions),
    // createOrUpdateNotificationBuilder, updateNotificationProgress, updateNotificationPaused,
    // updateNotificationComplete, updateNotificationError, checkStopForeground, DB operations, cursorToDownload,
    // createNotificationChannel, bytesToMB, onBind, handleResolveGofileUrl, handleResolveMediafireUrl,
    // handleResolveGoogleDriveUrl should be assumed to be present and correct from the previous full version,
    // *except* for the specific fixes targeted below within DownloadTask.]

    private boolean isForegroundServiceRunning() {
        // This is a conceptual check. Actual implementation might vary.
        // One way is to check if any of our specific download notifications are active.
        // Or if the GENERIC_SERVICE_NOTIFICATION_ID is active.
        // For simplicity, this is a placeholder. In a real scenario, you'd check NotificationManager.
        return !activeNotifications.isEmpty() || (notificationManager != null && notificationManager.getActiveNotifications() != null && notificationManager.getActiveNotifications().length > 0);
    }

    // Assume all resolver handlers are present and correct as per last full version
    private void handleResolveGofileUrl(String gofileUrl, String password) { /* ... */
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
    private void handleResolveMediafireUrl(String pageUrl) { /* ... */
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
    private void handleResolveGoogleDriveUrl(String pageUrl) { /* ... (ensure !item.directUrl.isEmpty()) ... */
        Log.i(TAG, "handleResolveGoogleDriveUrl: Starting resolution for " + pageUrl);
        GoogleDriveLinkResolver resolver = new GoogleDriveLinkResolver();
        DownloadItem resolvedItem = resolver.resolveDriveUrl(pageUrl);

        if (resolvedItem != null && resolvedItem.directUrl != null && !resolvedItem.directUrl.isEmpty()) { // Corrected Check
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
    private void handleResolvePixeldrainUrl(String pageUrl) { /* ... (ensure NotificationCompat.Builder chain and braces are correct) ... */
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

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID); // Initialize first
            builder.setSmallIcon(R.drawable.ic_cancel)
                   .setContentTitle("Erro no Link PixelDrain")
                   .setContentText("Não foi possível processar o link PixelDrain.")
                   .setAutoCancel(true); // Corrected chaining

            if (notificationManager != null) {
                 notificationManager.notify((int) (System.currentTimeMillis() % 10000) + 3, builder.build());
            }
        } // Corrected: This brace closes the else block
    } // Corrected: This brace closes the handleResolvePixeldrainUrl method


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

        @Override
        protected void onPreExecute() {
            super.onPreExecute(); // It's good practice to call super
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
                if (totalBytes <= 0) {
                    long cl = connection.getContentLength();
                    if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                         String contentRange = connection.getHeaderField("Content-Range");
                         if (contentRange != null) {
                             try { totalBytes = Long.parseLong(contentRange.substring(contentRange.lastIndexOf('/') + 1)); }
                             catch (Exception e) { totalBytes = downloadedBytes + cl; }
                         } else { totalBytes = downloadedBytes + cl; }
                    } else { totalBytes = cl; }
                    if (totalBytes <=0) totalBytes = -1;
                    updateDownloadTotalBytes(downloadId, totalBytes);
                }

                if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    randomAccessFile = new RandomAccessFile(downloadedFile, "rw"); randomAccessFile.seek(downloadedBytes);
                    output = new FileOutputStream(randomAccessFile.getFD());
                } else {
                    downloadedBytes = 0; output = new FileOutputStream(downloadedFile);
                }
                input = new BufferedInputStream(connection.getInputStream()); byte[] data = new byte[8192]; int count;
                long bytesSinceLastUpdate = 0;
                if(downloadedBytes == 0 && startTime == 0) startTime = System.currentTimeMillis(); // Ensure startTime is set

                while ((count = input.read(data)) != -1) {
                    if (isCancelled) return null;
                    if (isPaused) { updateDownloadProgress(downloadId, downloadedBytes, totalBytes); return null; }
                    downloadedBytes += count; bytesSinceLastUpdate += count; output.write(data, 0, count);
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastUpdateTime > 500 || bytesSinceLastUpdate > (1024 * 1024)) {
                        updateDownloadProgress(downloadId, downloadedBytes, totalBytes);

                        long elapsedTime = currentTime - startTime; // FIX: elapsedTime calculation
                        if (elapsedTime > 500) {
                            speed = (double) downloadedBytes / (elapsedTime / 1000.0);
                        } else {
                            speed = 0;
                        }
                        publishProgress(totalBytes > 0 ? (int) ((downloadedBytes * 100) / totalBytes) : -1);
                        Intent intent = new Intent(ACTION_DOWNLOAD_PROGRESS);
                        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
                        intent.putExtra("progress", totalBytes > 0 ? (int) ((downloadedBytes * 100) / totalBytes) : 0);
                        intent.putExtra("downloadedBytes", downloadedBytes);
                        intent.putExtra("totalBytes", totalBytes);
                        intent.putExtra("speed", speed);
                        broadcastManager.sendBroadcast(intent);
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
            if (isPaused) { updateNotificationPaused(downloadId); }
            else if (result != null) { updateNotificationComplete(downloadId, fileName, result); }
            else { updateNotificationError(downloadId, fileName); }
            if (!isPaused) { Intent intent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED); intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId); broadcastManager.sendBroadcast(intent); }
            checkStopForeground();
        }
        @Override
        protected void onCancelled(File result) {
            super.onCancelled(result);
            isCancelled = true;
            activeDownloads.remove(downloadId);
            Log.d(TAG, "Download cancelled: " + fileName);

            // If a status update is needed, it should be to a specific CANCELLED status if one exists,
            // or often FAILED if cancellation is treated as a type of failure.
            // The error report mentioned changing STATUS_CANCELLED to STATUS_FAILED.
            // Assuming the original intent was to mark it as failed on cancel for some reason.
            Download d = getDownloadById(downloadId);
            if (d != null && d.getStatus() != Download.STATUS_FAILED) { // Check current status before updating
                 updateDownloadStatus(downloadId, Download.STATUS_FAILED); // FIX: Use STATUS_FAILED
            }
            // Ensure notification is removed if not handled by updateNotificationError/Paused
             if (notificationManager != null) {
                notificationManager.cancel((int) (NOTIFICATION_ID_BASE + downloadId));
            }
            activeNotifications.remove(downloadId);

            Intent intent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED); intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId); broadcastManager.sendBroadcast(intent);
            checkStopForeground();
        }
    }

    // Other methods (DB operations, checkStopForeground, etc.) as before
    private void checkStopForeground() { if (activeDownloads.isEmpty() && !hasActiveResolutions()) stopForeground(true); }
    private boolean hasActiveResolutions() { /* TODO: Future enhancement: track active resolution tasks */ return false; }
    private long insertDownload(String url, String fileName) {
        SQLiteDatabase db = dbHelper.getWritableDatabase(); ContentValues values = new ContentValues();
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_URL, url);
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_FILE_NAME, fileName);
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_STATUS, Download.STATUS_PENDING);
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_TIMESTAMP, System.currentTimeMillis());
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_DOWNLOADED_BYTES, 0);
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_TOTAL_BYTES, -1);
        values.putNull(DownloadContract.DownloadEntry.COLUMN_NAME_LOCAL_PATH);
        return db.insert(DownloadContract.DownloadEntry.TABLE_NAME, null, values);
    }
    private void updateDownloadProgress(long id, long dBytes, long tBytes) {
        SQLiteDatabase db = dbHelper.getWritableDatabase(); ContentValues values = new ContentValues();
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_DOWNLOADED_BYTES, dBytes);
        if (tBytes > 0) values.put(DownloadContract.DownloadEntry.COLUMN_NAME_TOTAL_BYTES, tBytes);
        db.update(DownloadContract.DownloadEntry.TABLE_NAME, values, DownloadContract.DownloadEntry._ID + " = ?", new String[]{String.valueOf(id)});
    }
    private void updateDownloadTotalBytes(long id, long tBytes) {
        if (tBytes <= 0) return; SQLiteDatabase db = dbHelper.getWritableDatabase(); ContentValues values = new ContentValues();
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_TOTAL_BYTES, tBytes);
        db.update(DownloadContract.DownloadEntry.TABLE_NAME, values, DownloadContract.DownloadEntry._ID + " = ?", new String[]{String.valueOf(id)});
    }
    private void updateDownloadLocalPath(long id, String path) {
        SQLiteDatabase db = dbHelper.getWritableDatabase(); ContentValues values = new ContentValues();
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_LOCAL_PATH, path);
        db.update(DownloadContract.DownloadEntry.TABLE_NAME, values, DownloadContract.DownloadEntry._ID + " = ?", new String[]{String.valueOf(id)});
    }
    private void updateDownloadStatus(long id, int status) { updateDownloadStatus(id, status, null); }
    private void updateDownloadStatus(long id, int status, String path) {
        SQLiteDatabase db = dbHelper.getWritableDatabase(); ContentValues values = new ContentValues();
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_STATUS, status);
        if (path != null) values.put(DownloadContract.DownloadEntry.COLUMN_NAME_LOCAL_PATH, path);
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_TIMESTAMP, System.currentTimeMillis());
        db.update(DownloadContract.DownloadEntry.TABLE_NAME, values, DownloadContract.DownloadEntry._ID + " = ?", new String[]{String.valueOf(id)});
    }
    private long getDownloadIdByUrl(String url) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(DownloadContract.DownloadEntry.TABLE_NAME, new String[]{DownloadContract.DownloadEntry._ID}, DownloadContract.DownloadEntry.COLUMN_NAME_URL + " = ?", new String[]{url}, null, null, null);
        long id = -1; if (cursor != null && cursor.moveToFirst()) { id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadContract.DownloadEntry._ID)); cursor.close(); } return id;
    }
    public Download getDownloadById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(DownloadContract.DownloadEntry.TABLE_NAME, null, DownloadContract.DownloadEntry._ID + " = ?", new String[]{String.valueOf(id)}, null, null, null);
        Download download = null; if (cursor != null && cursor.moveToFirst()) { download = cursorToDownload(cursor); cursor.close(); } return download;
    }
    public List<Download> getAllDownloads() {
        List<Download> downloads = new ArrayList<>(); SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(DownloadContract.DownloadEntry.TABLE_NAME, null, null, null, null, null, DownloadContract.DownloadEntry.COLUMN_NAME_TIMESTAMP + " DESC");
        if (cursor != null) { while (cursor.moveToNext()) { downloads.add(cursorToDownload(cursor)); } cursor.close(); } return downloads;
    }
    public int clearCompletedDownloads() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int deletedRows = db.delete(DownloadContract.DownloadEntry.TABLE_NAME, DownloadContract.DownloadEntry.COLUMN_NAME_STATUS + " = ?", new String[]{String.valueOf(Download.STATUS_COMPLETED)});
        if (deletedRows > 0) broadcastManager.sendBroadcast(new Intent(ACTION_DOWNLOAD_STATUS_CHANGED)); return deletedRows;
    }
    public boolean deleteDownload(long id) {
        Download download = getDownloadById(id);
        if (download != null && download.getLocalPath() != null && !download.getLocalPath().isEmpty()) { try { new File(download.getLocalPath()).delete(); } catch (Exception e) {} }
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int deletedRows = db.delete(DownloadContract.DownloadEntry.TABLE_NAME, DownloadContract.DownloadEntry._ID + " = ?", new String[]{String.valueOf(id)});
        if (deletedRows > 0) { Intent intent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED); intent.putExtra(EXTRA_DOWNLOAD_ID, id); broadcastManager.sendBroadcast(intent); } return deletedRows > 0;
    }
    public int deleteDownloads(List<Long> ids) { int c=0; for(long id : ids) if(deleteDownload(id)) c++; return c; }
    private Download cursorToDownload(Cursor c) {
        return new Download( c.getLong(c.getColumnIndexOrThrow(DownloadContract.DownloadEntry._ID)), c.getString(c.getColumnIndexOrThrow(DownloadContract.DownloadEntry.COLUMN_NAME_URL)),
            c.getString(c.getColumnIndexOrThrow(DownloadContract.DownloadEntry.COLUMN_NAME_FILE_NAME)), c.getString(c.getColumnIndexOrThrow(DownloadContract.DownloadEntry.COLUMN_NAME_LOCAL_PATH)),
            c.getLong(c.getColumnIndexOrThrow(DownloadContract.DownloadEntry.COLUMN_NAME_TOTAL_BYTES)), c.getLong(c.getColumnIndexOrThrow(DownloadContract.DownloadEntry.COLUMN_NAME_DOWNLOADED_BYTES)),
            c.getInt(c.getColumnIndexOrThrow(DownloadContract.DownloadEntry.COLUMN_NAME_STATUS)), c.getLong(c.getColumnIndexOrThrow(DownloadContract.DownloadEntry.COLUMN_NAME_TIMESTAMP)) );
    }
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Download Channel"; String description = "Canal para notificações de download do Winlator";
            int importance = NotificationManager.IMPORTANCE_DEFAULT; NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description); if (notificationManager != null) notificationManager.createNotificationChannel(channel);
        }
    }
    private double bytesToMB(long bytes) { return bytes / (1024.0 * 1024.0); }
    @Nullable @Override public IBinder onBind(Intent intent) { verifyAndCorrectDownloadStatuses(); return binder; }
    @Override public void onDestroy() {
        super.onDestroy();
        for (DownloadTask task : activeDownloads.values()) { if (task != null && !task.isCancelled() && !task.isPaused) { task.pause(); updateDownloadStatus(task.downloadId, Download.STATUS_PAUSED); } }
        activeDownloads.clear(); activeNotifications.clear();
        if (executor != null && !executor.isShutdown()) executor.shutdown();
        if (dbHelper != null) dbHelper.close();
        Log.d(TAG, "DownloadService destroyed.");
    }
}
```
