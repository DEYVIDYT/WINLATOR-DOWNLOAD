package com.winlator.Download.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

// jLibtorrent imports
import com.frostwire.jlibtorrent.SessionManager;
import com.frostwire.jlibtorrent.SettingsPack;
import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.AddTorrentParams;
import com.frostwire.jlibtorrent.alerts.Alert;
import com.frostwire.jlibtorrent.alerts.AlertType;
import com.frostwire.jlibtorrent.listener.AlertListener;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.TorrentFlags; // For SEED_MODE

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.winlator.Download.MainActivity; // Assuming MainActivity is the entry point
import com.winlator.Download.R; // For notification icon

public class TorrentSeedingService extends Service {

    private static final String TAG = "TorrentSeedingService";
    private static final String CHANNEL_ID = "torrent_seeding_channel";
    private static final int NOTIFICATION_ID = 1002; // Different from UploadService

    public static final String ACTION_START_SEEDING = "com.winlator.Download.ACTION_START_SEEDING";
    public static final String EXTRA_TORRENT_FILE_PATH = "com.winlator.Download.EXTRA_TORRENT_FILE_PATH";
    public static final String EXTRA_SAVE_PATH = "com.winlator.Download.EXTRA_SAVE_PATH";

    private SessionManager sessionManager;
    private PowerManager.WakeLock wakeLock;
    private ScheduledExecutorService alertExecutor;
    private ExecutorService torrentCommandsExecutor; // Executor for handling torrent commands


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // We don't provide binding
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: TorrentSeedingService is being created.");
        createNotificationChannel();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TorrentSeedingService::WakeLock");
            wakeLock.setReferenceCounted(false);
        } else {
            Log.e(TAG, "PowerManager not available.");
        }

        torrentCommandsExecutor = Executors.newSingleThreadExecutor(); // Initialize the command executor

        try {
            sessionManager = new SessionManager();
            setupSessionSettings();
            sessionManager.start(); // Start the session
            Log.i(TAG, "jLibtorrent session started.");

            // Setup alert listener
            alertExecutor = Executors.newSingleThreadScheduledExecutor();
            alertExecutor.scheduleAtFixedRate(() -> {
                if (sessionManager != null && sessionManager.isRunning()) {
                    sessionManager.popAlerts(); // Process alerts
                }
            }, 0, 1, TimeUnit.SECONDS); // Check for alerts every second

            sessionManager.addListener(new AlertListener() {
                @Override
                public int[] types() {
                    // Listen to all alerts for now, or specify interesting ones
                    return null; // null means all alerts
                }

                @Override
                public void alert(Alert<?> alert) {
                    Log.d(TAG, "jLibtorrent Alert: " + alert.type() + " - " + alert.message());
                    // Handle specific alerts if needed, e.g., error alerts, torrent finished alerts etc.
                     if (alert.type().equals(AlertType.TORRENT_ERROR)) {
                        Log.e(TAG, "Torrent error alert: " + alert.message());
                        // Potentially stop seeding this torrent or notify user.
                    }
                }
            });

        } catch (Throwable e) { // Catch broader errors like UnsatisfiedLinkError
            Log.e(TAG, "Failed to initialize jLibtorrent session: " + e.getMessage(), e);
            // Potentially stop the service if session fails to start
            stopSelf();
        }
    }

    private void setupSessionSettings() {
        if (sessionManager == null) return;

        SettingsPack settings = new SettingsPack();
        settings.setBoolean(SettingsPack.BooleanKey.ENABLE_DHT, true);
        settings.setBoolean(SettingsPack.BooleanKey.ENABLE_UPNP, true);
        settings.setBoolean(SettingsPack.BooleanKey.ENABLE_NATPMP, true);
        settings.setBoolean(SettingsPack.BooleanKey.ENABLE_LSD, true); // Local Service Discovery
        settings.setBoolean(SettingsPack.BooleanKey.ENABLE_PEX, true); // Peer Exchange

        settings.setString(SettingsPack.StringKey.USER_AGENT, "WinlatorDownloadApp/1.0");
        settings.setString(SettingsPack.StringKey.LISTEN_INTERFACES, "0.0.0.0:6881,[::]:6881"); // Listen on IPv4 and IPv6

        // More settings can be configured here, e.g., connection limits, alert masks etc.
        // settings.setInteger(SettingsPack.IntKey.ALERT_MASK, AlertType.ERROR.swig() | AlertType.STORAGE_NOTIFICATION.swig());


        sessionManager.applySettings(settings);
        Log.i(TAG, "jLibtorrent session settings applied.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand received.");
        if (intent == null || !ACTION_START_SEEDING.equals(intent.getAction())) {
            Log.w(TAG, "No action or invalid action received.");
            return START_NOT_STICKY;
        }

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(60 * 60 * 1000L /* 1 hour timeout, adjust as needed */); // Acquire wake lock with timeout
            Log.d(TAG, "WakeLock acquired.");
        }

        String torrentFilePath = intent.getStringExtra(EXTRA_TORRENT_FILE_PATH);
        String savePath = intent.getStringExtra(EXTRA_SAVE_PATH);

        if (torrentFilePath == null || savePath == null) {
            Log.e(TAG, "Torrent file path or save path is null. Stopping service.");
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, createNotification("Starting torrent seeding..."));
        Log.i(TAG, "Service moved to foreground.");

        if (sessionManager == null || !sessionManager.isRunning()) {
            Log.e(TAG, "Session manager not running. Cannot add torrent.");
            stopSelf();
            return START_NOT_STICKY;
        }

        torrentCommandsExecutor.execute(() -> { // Use the member executor
            try {
                File torrentFile = new File(torrentFilePath);
                if (!torrentFile.exists()) {
                    Log.e(TAG, "Torrent file does not exist: " + torrentFilePath);
                    // Notify UI or log, then stop or handle error
                    return;
                }
                TorrentInfo ti = new TorrentInfo(torrentFile);
                Log.i(TAG, "TorrentInfo loaded for: " + ti.name());

                AddTorrentParams params = new AddTorrentParams();
                params.torrentInfo(ti);
                params.savePath(new File(savePath));

                // Assuming files are complete and we want to start in seed mode.
                // This requires verification that files at savePath are indeed complete.
                // For now, we'll set the flag. If files are not complete, jlibtorrent will check and re-download.
                // A better approach would be to check files first or let jlibtorrent handle it.
                // params.flags(params.flags().and_(TorrentFlags.SEED_MODE)); // This syntax is incorrect for setting
                // params.flags(params.flags().or_(TorrentFlags.SEED_MODE)); // This is how you'd add a flag usually
                // However, for just seeding, ensuring files are checked and then letting it seed is often default.
                // If we want to force recheck: params.flags(params.flags().or_(TorrentFlags.OVERWRITE_EXISTING));
                // For now, let's assume files are there and jlibtorrent will figure out the state.
                // If files are already there and complete, it should start seeding.

                sessionManager.download(params); // `download` is the general method to add torrents.
                                                 // It handles both downloading and seeding.
                Log.i(TAG, "Torrent added to session for seeding: " + ti.name());
                // Update notification to reflect active seeding
                startForeground(NOTIFICATION_ID, createNotification("Seeding: " + ti.name()));

            } catch (Exception e) {
                Log.e(TAG, "Error adding torrent for seeding: " + e.getMessage(), e);
                // Notify UI or log, then stop or handle error
            }
        });

        return START_STICKY; // Or START_REDELIVER_INTENT if you want to reprocess the last intent
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: TorrentSeedingService is being destroyed.");
        if (sessionManager != null && sessionManager.isRunning()) {
            Log.i(TAG, "Stopping jLibtorrent session.");
            sessionManager.stop();
        }
        if (alertExecutor != null && !alertExecutor.isShutdown()) {
            alertExecutor.shutdown();
        }
        if (torrentCommandsExecutor != null && !torrentCommandsExecutor.isShutdown()) {
            torrentCommandsExecutor.shutdown();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released.");
        }
        stopForeground(true); // Ensure notification is removed
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Torrent Seeding Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Notification for active torrent seeding");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class); // Or a specific seeding UI
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Torrent Seeding Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_upload) // Replace with a proper seeding icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
}
