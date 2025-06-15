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
            executor = Executors.newSingleThreadExecutor(); // Initialize executor
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
            action = ACTION_START_DOWNLOAD; // Ação padrão
        }

        switch (action) {
            case ACTION_START_DOWNLOAD:
                handleStartDownload(intent);
                break;
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
                Log.w(TAG, "onStartCommand: Received unknown action: " + action);
                Notification unknownActionNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_cancel)
                    .setContentTitle("Download Service")
                    .setContentText("Ação desconhecida no serviço de download.")
                    .setAutoCancel(true)
                    .build();
                notificationManager.notify(GENERIC_SERVICE_NOTIFICATION_ID, unknownActionNotification);
                stopSelf();
                break;
        }

        return START_STICKY;
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

        if (executor == null) {
            Log.w(TAG, "Executor was null in handleStartDownload, re-initializing.");
            executor = Executors.newSingleThreadExecutor();
        }

        executor.execute(() -> {
            Log.d(TAG, "handleStartDownload (Executor): Background processing started for URL: '" + urlString + "', FileName: '" + fileName + "'");
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
                            startDownload(existingDownload.getId(), existingDownload.getUrl(), existingDownload.getFileName());
                        } else if (existingDownload.getStatus() == Download.STATUS_DOWNLOADING) {
                            Log.w(TAG, "DB indicates downloading, but no active task found for " + existingDownload.getFileName() + ". Attempting to restart.");
                            Log.i(TAG, "handleStartDownload (MainThread): About to call startDownload for existing downloading (but no task) download. ID: " + existingDownload.getId() + ", URL: '" + existingDownload.getUrl() + "', FileName: '" + existingDownload.getFileName() + "'");
                            startDownload(existingDownload.getId(), existingDownload.getUrl(), existingDownload.getFileName());
                        }
                    } else {
                        // DB had an ID, but we couldn't fetch the Download object. This is an inconsistent state.
                        Log.w(TAG, "Could not fetch existing download with ID: " + downloadId + ". Treating as new.");
                        final long newDownloadIdAfterNull = insertDownload(urlString, fileName);
                        Log.d(TAG, "handleStartDownload (Executor): Existing download object was null for ID " + downloadId + ". Attempted insert, new ID: " + newDownloadIdAfterNull);
                        if (newDownloadIdAfterNull != -1) {
                            Log.i(TAG, "handleStartDownload (MainThread): About to call startDownload for new download (after null existing). ID: " + newDownloadIdAfterNull + ", URL: '" + urlString + "', FileName: '" + fileName + "'");
                            startDownload(newDownloadIdAfterNull, urlString, fileName);
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
                    startDownload(newDownloadId, urlString, fileName);
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
            startDownload(downloadId, download.getUrl(), download.getFileName());
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
            startDownload(downloadId, download.getUrl(), download.getFileName());
        }
    }

    private void startDownload(long downloadId, String urlString, String fileName) {
        Log.i(TAG, "startDownload: Entry. ID: " + downloadId + ", URL: '" + urlString + "', FileName: '" + fileName + "'");
        // Criar ou atualizar a notificação
        NotificationCompat.Builder builder = createOrUpdateNotificationBuilder(downloadId, fileName);
        activeNotifications.put(downloadId, builder);
        
        // Iniciar o serviço em primeiro plano
        startForeground((int) (NOTIFICATION_ID_BASE + downloadId), builder.build());
        
        // Atualizar o status no banco de dados
        updateDownloadStatus(downloadId, Download.STATUS_DOWNLOADING);
        
        // Iniciar a tarefa de download
        DownloadTask task = new DownloadTask(downloadId, urlString, fileName, builder);
        activeDownloads.put(downloadId, task);
        Log.i(TAG, "startDownload: About to execute DownloadTask for ID: " + downloadId);
        task.execute();
        
        // Enviar broadcast
        Intent broadcastIntent = new Intent(ACTION_DOWNLOAD_STATUS_CHANGED);
        broadcastIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        broadcastManager.sendBroadcast(broadcastIntent);
    }

    private NotificationCompat.Builder createOrUpdateNotificationBuilder(long downloadId, String fileName) {
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
        private final String fileName;
        private final NotificationCompat.Builder notificationBuilder;
        private boolean isPaused = false;
        private boolean isCancelled = false;
        private long totalBytes = -1;
        private long downloadedBytes = 0;
        private long startTime;
        private long lastUpdateTime = 0;
        private double speed = 0;

        DownloadTask(long downloadId, String urlString, String fileName, NotificationCompat.Builder builder) {
            this.downloadId = downloadId;
            this.urlString = urlString;
            this.fileName = fileName;
            this.notificationBuilder = builder;
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
            InputStream input = null;
            OutputStream output = null;
            HttpURLConnection connection = null;
            File downloadedFile = null;
            RandomAccessFile randomAccessFile = null;

            try {
                Log.d(TAG, "DownloadTask (" + this.downloadId + "): doInBackground starting. URL: '" + this.urlString + "'");
                // Preparar o diretório de download
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs();
                }
                downloadedFile = new File(downloadDir, fileName);
                String localPath = downloadedFile.getAbsolutePath();
                updateDownloadLocalPath(downloadId, localPath); // Salvar o caminho local no DB
                
                // Configurar a conexão
                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();
                
                // Se já temos bytes baixados, configurar o cabeçalho Range
                if (downloadedBytes > 0) {
                    connection.setRequestProperty("Range", "bytes=" + downloadedBytes + "-");
                }
                
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.connect();
                
                int responseCode = connection.getResponseCode();
                // Tratar resposta parcial (206) ou OK (200)
                if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    Log.e(TAG, "Server returned HTTP " + responseCode + " " + connection.getResponseMessage());
                    updateDownloadStatus(downloadId, Download.STATUS_FAILED);
                    return null;
                }
                
                // Obter o tamanho total do arquivo (considerando Range)
                if (totalBytes <= 0) {
                    long contentLengthHeader = connection.getContentLength();
                    if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                         // Se for parcial, o Content-Length é o restante, precisamos do tamanho total
                         String contentRange = connection.getHeaderField("Content-Range");
                         if (contentRange != null) {
                             try {
                                 totalBytes = Long.parseLong(contentRange.substring(contentRange.lastIndexOf('/') + 1));
                             } catch (Exception e) {
                                 Log.w(TAG, "Could not parse Content-Range: " + contentRange);
                                 totalBytes = downloadedBytes + contentLengthHeader; // Estimativa
                             }
                         } else {
                             totalBytes = downloadedBytes + contentLengthHeader; // Estimativa
                         }
                    } else {
                         totalBytes = contentLengthHeader;
                    }

                    if (totalBytes <= 0) {
                        totalBytes = -1; // Desconhecido
                    }
                    updateDownloadTotalBytes(downloadId, totalBytes);
                }
                
                // Abrir o arquivo para escrita
                if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    randomAccessFile = new RandomAccessFile(downloadedFile, "rw");
                    randomAccessFile.seek(downloadedBytes);
                    output = new FileOutputStream(randomAccessFile.getFD());
                } else {
                    // Se for 200 OK, começar do início (ou sobrescrever)
                    downloadedBytes = 0;
                    output = new FileOutputStream(downloadedFile);
                }
                
                input = new BufferedInputStream(connection.getInputStream());
                
                byte[] data = new byte[8192]; // Buffer maior
                int count;
                long bytesSinceLastUpdate = 0;
                
                while ((count = input.read(data)) != -1) {
                    if (isCancelled) {
                        return null;
                    }
                    if (isPaused) {
                        // Salvar o progresso atual antes de pausar
                        updateDownloadProgress(downloadId, downloadedBytes, totalBytes);
                        return null; // Indica que foi pausado, não concluído
                    }
                    
                    downloadedBytes += count;
                    bytesSinceLastUpdate += count;
                    output.write(data, 0, count);
                    
                    // Atualizar o progresso (limitado a cada 500ms ou 1MB)
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastUpdateTime > 500 || bytesSinceLastUpdate > (1024 * 1024)) {
                        updateDownloadProgress(downloadId, downloadedBytes, totalBytes);
                        
                        // Calcular a velocidade
                        long elapsedTime = currentTime - startTime;
                        if (elapsedTime > 500) { // Evitar divisão por zero ou valores irreais no início
                            speed = (double) downloadedBytes / (elapsedTime / 1000.0);
                        }
                        
                        // Publicar o progresso
                        if (totalBytes > 0) {
                            int progress = (int) ((downloadedBytes * 100) / totalBytes);
                            publishProgress(progress);
                        } else {
                            publishProgress(-1); // Indeterminado
                        }
                        
                        // Enviar broadcast para atualizar a UI
                        Intent intent = new Intent(ACTION_DOWNLOAD_PROGRESS);
                        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
                        intent.putExtra("progress", totalBytes > 0 ? (int) ((downloadedBytes * 100) / totalBytes) : 0);
                        intent.putExtra("downloadedBytes", downloadedBytes);
                        intent.putExtra("totalBytes", totalBytes);
                        intent.putExtra("speed", speed);
                        broadcastManager.sendBroadcast(intent);
                        
                        // Resetar contador e atualizar timestamp
                        bytesSinceLastUpdate = 0;
                        lastUpdateTime = currentTime;
                    }
                }
                
                // Download concluído
                updateDownloadStatus(downloadId, Download.STATUS_COMPLETED);
                return downloadedFile;
                
            } catch (Exception e) {
                Log.e(TAG, "DownloadTask (" + this.downloadId + "): Exception during download: " + e.getMessage(), e);
                updateDownloadStatus(downloadId, Download.STATUS_FAILED);
                return null;
            } finally {
                try {
                    if (output != null) output.close();
                    if (input != null) input.close();
                    if (randomAccessFile != null) randomAccessFile.close();
                    if (connection != null) connection.disconnect();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing streams: " + e.getMessage(), e);
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

