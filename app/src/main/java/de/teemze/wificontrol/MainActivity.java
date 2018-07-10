package de.teemze.wificontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.*;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.Channel;
import java.util.Dictionary;
import java.util.Hashtable;

public class MainActivity extends AppCompatActivity
{
    public static MainActivity Instance;
    public MetaLog Log;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        init();
    }

    private void init()
    {
        Instance = this;

        Intent bindingIntent = new Intent(this, MainService.class);
        startService(bindingIntent);

        Log = new MetaLog(findViewById(R.id.textView), this);

        Log.i("WifiControl", "Init");

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        Button refreshButton = findViewById(R.id.refreshButton);
        refreshButton.setOnClickListener(view -> {
            tearDown();
            try
            {
                Thread.sleep(100);
            } catch (InterruptedException e)
            {
                e.printStackTrace();
            }
            tearUp();

        });

        Button playButton = findViewById(R.id.playButton);
        Button pauseButton = findViewById(R.id.pauseButton);
        Button nextButton = findViewById(R.id.nextButton);
        Button previousButton = findViewById(R.id.previousButton);
        Button volumeUpButton = findViewById(R.id.volumeUpButton);
        Button volumeDownButton = findViewById(R.id.volumeDownButton);
        playButton.setOnClickListener(this::handleButtonClick);
        pauseButton.setOnClickListener(this::handleButtonClick);
        nextButton.setOnClickListener(this::handleButtonClick);
        previousButton.setOnClickListener(this::handleButtonClick);
        volumeUpButton.setOnClickListener(this::handleButtonClick);
        volumeDownButton.setOnClickListener(this::handleButtonClick);

        createNotification();
    }

    private void createNotification()
    {
        Intent playIntent = new Intent(MainService.MAIN_SERVICE_FILTER);
        playIntent.putExtra("action", MainService.PLAY_ACTION);
        PendingIntent pendingPlayIntent = PendingIntent.getBroadcast(
                this, 0, playIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        Intent pauseIntent = new Intent(MainService.MAIN_SERVICE_FILTER);
        pauseIntent.putExtra("action", MainService.PAUSE_ACTION);
        PendingIntent pendingPauseIntent = PendingIntent.getBroadcast(
                this, 1, pauseIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        Intent nextIntent = new Intent(MainService.MAIN_SERVICE_FILTER);
        nextIntent.putExtra("action", MainService.NEXT_ACTION);
        PendingIntent pendingNextIntent = PendingIntent.getBroadcast(
                this, 2, nextIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        Intent previousIntent = new Intent(MainService.MAIN_SERVICE_FILTER);
        previousIntent.putExtra("action", MainService.PREVIOUS_ACTION);
        PendingIntent pendingPreviousIntent = PendingIntent.getBroadcast(
                this, 3, previousIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        Intent volumeUpIntent = new Intent(MainService.MAIN_SERVICE_FILTER);
        volumeUpIntent.putExtra("action", MainService.VOLUME_UP_ACTION);
        PendingIntent pendingVolumeUpIntent = PendingIntent.getBroadcast(
                this, 4, volumeUpIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        Intent volumeDownIntent = new Intent(MainService.MAIN_SERVICE_FILTER);
        volumeDownIntent.putExtra("action", MainService.VOLUME_DOWN_ACTION);
        PendingIntent pendingVolumeDownIntent = PendingIntent.getBroadcast(
                this, 5, volumeDownIntent, 0);

        RemoteViews notificationMainLayout = new RemoteViews(getPackageName(), R.layout.notification_main);

        notificationMainLayout.setOnClickPendingIntent(R.id.notificationPlayButton, pendingPlayIntent);
        notificationMainLayout.setOnClickPendingIntent(R.id.notificationPauseButton, pendingPauseIntent);
        notificationMainLayout.setOnClickPendingIntent(R.id.notificationNextButton, pendingNextIntent);
        notificationMainLayout.setOnClickPendingIntent(R.id.notificationPreviousButton, pendingPreviousIntent);
        notificationMainLayout.setOnClickPendingIntent(R.id.notificationVolumeUpButton, pendingVolumeUpIntent);
        notificationMainLayout.setOnClickPendingIntent(R.id.notificationVolumeDownButton, pendingVolumeDownIntent);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "default")
                .setContentTitle("WifiControl")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(notificationMainLayout)
                .setOngoing(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            String name = "Main";
            String description = "Main channel";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel("default", name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            assert notificationManager != null;
            notificationManager.createNotificationChannel(channel);
        }

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(0, builder.build());
    }

    public void testBroadcast()
    {
        Intent testIntent = new Intent(MainService.MAIN_SERVICE_FILTER);
        sendBroadcast(testIntent);
    }

    private void handleButtonClick(@NonNull View view)
    {
        Integer code = Integer.decode((String) view.getTag());
        if (code == null)
            return;

        send(code);
    }

    private void send(int code)
    {
        MainService.Instance.send(code);
    }

    private void tearDown()
    {
        MainService.Instance.tearDown();
    }

    private void tearUp()
    {
        MainService.Instance.tearUp();
    }

    /*@Override
    protected void onPause()
    {
        tearDown();
        super.onPause();
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        if (!Interrupted)
            return;
        tearUp();
    }*/

    @Override
    protected void onDestroy()
    {
        Log.i("WifiControl", "Activity destroying");
        tearDown();
        super.onDestroy();
    }
}
