package de.teemze.wificontrol;

import android.app.IntentService;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.StrictMode;
import android.support.annotation.Nullable;
import android.view.KeyEvent;
import android.widget.Toast;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Dictionary;
import java.util.Hashtable;

public class MainService extends Service
{
    public static final String INIT_ACTION = "init";
    public static final String PLAY_ACTION = "play";
    public static final String PAUSE_ACTION = "pause";
    public static final String NEXT_ACTION = "next";
    public static final String PREVIOUS_ACTION = "previous";
    public static final String VOLUME_UP_ACTION = "volumeUp";
    public static final String VOLUME_DOWN_ACTION = "volumeDown";
    public static final String MAIN_SERVICE_FILTER = "WifiControl main";
    public static MainService Instance;
    private final String SERVICE_TYPE = "_nsdwificontrol._tcp";
    private final android.content.BroadcastReceiver BroadcastReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            Log.d("WifiControl", "Received broadcast");

            assert intent != null;
            final String action = intent.getStringExtra("action");
            if (action == null)
                return;
            switch (action)
            {
                case PLAY_ACTION:
                    send(0);
                    break;
                case PAUSE_ACTION:
                    send(1);
                    break;
                case NEXT_ACTION:
                    send(2);
                    break;
                case PREVIOUS_ACTION:
                    send(3);
                    break;
                case VOLUME_UP_ACTION:
                    send(4);
                    break;
                case VOLUME_DOWN_ACTION:
                    send(5);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported action: " + action);
            }
        }
    };
    private Dictionary<Integer, Integer> InputOutputCodes;
    private android.media.AudioManager AudioManager;
    private android.net.nsd.NsdManager.DiscoveryListener DiscoveryListener;
    private android.net.nsd.NsdManager.ResolveListener ResolveListener;
    private ServerSocket ServerSocket;
    private int LocalPort;
    private NsdManager.RegistrationListener RegistrationListener;
    private String ServiceName;
    private NsdManager NsdManager;
    private NsdServiceInfo OtherServiceInfo;
    private int OtherServicePort;
    private InetAddress OtherHost;
    private Socket OtherSocket;
    private boolean Interrupted;
    private Thread InputWaiter;
    private Socket OtherSocketConnector;
    private MainActivity MainActivity;
    private MetaLog Log;

    public MainService()
    {
        Instance = this;
    }

    public void init()
    {
        MainActivity = de.teemze.wificontrol.MainActivity.Instance;
        Log = MainActivity.Log;
        Log.d("WifiControl", "Initializing service");

        registerReceiver(BroadcastReceiver, new IntentFilter(MAIN_SERVICE_FILTER));
        MainActivity.testBroadcast();

        InputOutputCodes = new Hashtable<>();
        InputOutputCodes.put(0, KeyEvent.KEYCODE_MEDIA_PLAY);
        InputOutputCodes.put(1, KeyEvent.KEYCODE_MEDIA_PAUSE);
        InputOutputCodes.put(2, KeyEvent.KEYCODE_MEDIA_NEXT);
        InputOutputCodes.put(3, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        InputOutputCodes.put(4, KeyEvent.KEYCODE_VOLUME_UP);
        InputOutputCodes.put(5, KeyEvent.KEYCODE_VOLUME_DOWN);
        InputOutputCodes.put(42, KeyEvent.KEYCODE_UNKNOWN);

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        AudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        assert AudioManager != null;
        AudioManager.setMode(android.media.AudioManager.MODE_NORMAL);

        initializeServerSocket();
        initializeRegistrationListener();
        registerService(LocalPort);

        ResolveListener = new MyResolveListener();
        DiscoveryListener = new MyDiscoveryListener();

        discoverServices();
    }

    private void registerService(int port)
    {
        Interrupted = false;

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName("NsdWifiControl" + Build.MODEL);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);

        NsdManager = (android.net.nsd.NsdManager) this.getSystemService(Context.NSD_SERVICE);
        assert NsdManager != null;
        NsdManager.registerService(serviceInfo, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, RegistrationListener);
    }

    private void discoverServices()
    {
        Log.d("WifiControl", "Discovering services");
        NsdManager.discoverServices(SERVICE_TYPE, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, DiscoveryListener);
    }

    public void send(int code)
    {
        Thread sendingThread = new Thread(() -> {
            if (OtherSocket != null)
            {
                Log.i("WifiControl", "Connected: \nOtherSocket: " + OtherSocket.isConnected() +
                        "\nOtherSocketConnector: " + (OtherSocketConnector == null ? "Null" : OtherSocketConnector
                        .isConnected()));
                try
                {
                    Log.d("WifiControl", "Sending " + code);
                    OtherSocket.getOutputStream().write(code);
                    OtherSocket.getOutputStream().flush();
                    byte[] response = new byte["Received signal ..".length()];
                    if (OtherSocket.getInputStream().read(response) != response.length)
                        Log.e("WifiControl", "Invalid response: " + new String(response));
                    else
                        Log.i("WifiControl", "Response: " + new String(response));
                } catch (IOException e)
                {
                    e.printStackTrace();
                }
            } else
                Log.e("WifiControl", "Other service isn't connected yet.");
        });

        sendingThread.start();
    }

    private void receive(int code)
    {
        Log.i("WifiControl", "Receivced Code " + code);

        if (code == 42)
        {
            MainActivity.runOnUiThread(() -> {
                Toast.makeText(this, "Found by other device.", Toast.LENGTH_SHORT).show();
            });
        }

        Integer keyCode = InputOutputCodes.get(code);
        if (keyCode == null)
        {
            Log.e("WifiControl", "Unknown code");
            return;
        }

        switch (keyCode)
        {
            case KeyEvent.KEYCODE_VOLUME_UP:
                AudioManager.adjustVolume(android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI);
                break;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                AudioManager.adjustVolume(android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI);
                break;
            default:
                KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
                KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, keyCode);

                AudioManager.dispatchMediaKeyEvent(down);
                AudioManager.dispatchMediaKeyEvent(up);

                Intent spotifyButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                spotifyButtonIntent.setPackage("com.spotify.music");
                synchronized (this)
                {
                    spotifyButtonIntent.putExtra(Intent.EXTRA_KEY_EVENT, down);
                    sendOrderedBroadcast(spotifyButtonIntent, null);

                    spotifyButtonIntent.putExtra(Intent.EXTRA_KEY_EVENT, up);
                    sendOrderedBroadcast(spotifyButtonIntent, null);
                }
                break;
        }
    }

    private void initializeRegistrationListener()
    {
        RegistrationListener = new NsdManager.RegistrationListener()
        {
            @Override
            public void onRegistrationFailed(NsdServiceInfo nsdServiceInfo, int i)
            {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo nsdServiceInfo, int i)
            {
                Log.e("WifiControl", "NSD registration failed. Error code: " + i);
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo nsdServiceInfo)
            {
                ServiceName = nsdServiceInfo.getServiceName();
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo nsdServiceInfo)
            {
            }
        };
    }

    private void initializeServerSocket()
    {
        try
        {
            ServerSocket = new ServerSocket(0);
            LocalPort = ServerSocket.getLocalPort();
            Log.i("WifiControl", "Server socket bound: " + ServerSocket.isBound());

        } catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public void tearDown()
    {
        try
        {
            NsdManager.unregisterService(RegistrationListener);
            NsdManager.stopServiceDiscovery(DiscoveryListener);
        } catch (Exception e)
        {
            e.printStackTrace();
        }
        Interrupted = true;

        Log.i("WifiControl", "TearDown. OtherSocket: " + (OtherSocket == null ? "Null" : OtherSocket.isConnected()));
    }

    public void tearUp()
    {
        registerService(LocalPort);
        discoverServices();
    }

    @Override
    public void onDestroy()
    {
        Log.i("WifiControl", "Service destroying");
        tearDown();

        unregisterReceiver(BroadcastReceiver);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent)
    {
        return null;
    }

    @Override
    public void onCreate ()
    {
        init();
    }

    class MyResolveListener implements android.net.nsd.NsdManager.ResolveListener
    {
        @Override
        public void onResolveFailed(NsdServiceInfo nsdServiceInfo, int i)
        {
            Log.e("WifiControl", "Service resolving failed. Error code: " + i);
        }

        @Override
        public void onServiceResolved(NsdServiceInfo nsdServiceInfo)
        {
            Log.d("WifiControl", "Service resolved: " + nsdServiceInfo.getServiceName());

            if (nsdServiceInfo.getServiceName().equals(ServiceName))
            {
                Log.e("WifiControl", "Same IP.");
                return;
            }

            if (InputWaiter != null && InputWaiter.isAlive())
            {
                Interrupted = true;
                InputWaiter.interrupt();
                Interrupted = false;
            }

            OtherServiceInfo = nsdServiceInfo;
            OtherServicePort = nsdServiceInfo.getPort();
            OtherHost = nsdServiceInfo.getHost();
            InputWaiter = new Thread(() ->
            {
                try
                {
                    OtherSocket = new Socket(OtherHost.getHostAddress(), OtherServicePort);

                    OtherSocketConnector = ServerSocket.accept();

                    send(42);

                    while (!Interrupted)
                    {
                        try
                        {
                            int message;
                            while ((message = OtherSocketConnector.getInputStream().read()) == -1)
                            {
                                if (Interrupted)
                                    return;
                            }
                            receive(message);

                            OtherSocketConnector.getOutputStream().write(("Received signal " + message).getBytes());
                            OtherSocketConnector.getOutputStream().flush();
                        } catch (IOException e)
                        {
                            e.printStackTrace();
                        }
                    }
                    OtherSocket.close();
                    OtherSocketConnector.close();

                } catch (IOException e)
                {
                    e.printStackTrace();
                }
            });
            InputWaiter.start();
        }
    }

    class MyDiscoveryListener implements android.net.nsd.NsdManager.DiscoveryListener
    {
        @Override
        public void onStartDiscoveryFailed(String s, int i)
        {
            Log.e("WifiControl", "Discovery failed. Error code: " + i);
            NsdManager.stopServiceDiscovery(this);
        }

        @Override
        public void onStopDiscoveryFailed(String s, int i)
        {
            Log.e("WifiControl", "Discovery failed. Error code: " + i);
            NsdManager.stopServiceDiscovery(this);
        }

        @Override
        public void onDiscoveryStarted(String s)
        {
            Log.d("WifiControl", "Service discovery started");
        }

        @Override
        public void onDiscoveryStopped(String s)
        {
            Log.i("WifiControl", "Discovery stopped: " + s);
        }

        @Override
        public void onServiceFound(NsdServiceInfo nsdServiceInfo)
        {
            Log.d("WifiControl", "Service discovery success: " + nsdServiceInfo);
            String type = nsdServiceInfo.getServiceType().trim();
            if (type.toCharArray()[type.length() - 1] == '.')
                type = type.substring(0, type.length() - 1);
            if (!type.equals(SERVICE_TYPE))
                Log.d("WifiControl", "Unknown Service Type: " + nsdServiceInfo.getServiceType());
            else if (nsdServiceInfo.getServiceName().equals(ServiceName))
                Log.d("WifiControls", "Same machine: " + ServiceName);
            else if (nsdServiceInfo.getServiceName().contains("NsdWifiControl"))
                NsdManager.resolveService(nsdServiceInfo, ResolveListener);
        }

        @Override
        public void onServiceLost(NsdServiceInfo nsdServiceInfo)
        {
            Log.e("WifiControl", "Service lost: " + nsdServiceInfo);

        }
    }
}
