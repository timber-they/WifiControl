package de.teemze.wificontrol;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Calendar;

public class MainActivity extends AppCompatActivity
{
    private final String SERVICE_TYPE = "_nsdwificontrol._tcp";
    private ServerSocket ServerSocket;
    private int LocalPort;
    private NsdManager.RegistrationListener RegistrationListener;
    private String ServiceName;
    private NsdManager NsdManager;
    private android.net.nsd.NsdManager.DiscoveryListener DiscoveryListener;
    private android.net.nsd.NsdManager.ResolveListener ResolveListener;
    private NsdServiceInfo OtherServiceInfo;
    private int OtherServicePort;
    private InetAddress OtherHost;
    private Socket OtherSocket;
    private boolean Interrupted;
    private AudioManager AudioManager;
    private Thread InputWaiter;
    private Socket OtherSocketConnector;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        init();
    }

    private void init()
    {
        Log.i("WifiControl", "Init");
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        Button playButton = findViewById(R.id.playButton);
        playButton.setOnClickListener(this::handleButtonClick);

        AudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        initializeServerSocket();
        initializeRegistrationListener();
        registerService(LocalPort);

        ResolveListener = new NsdManager.ResolveListener()
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
                try
                {
                    OtherSocket = new Socket(OtherHost.getHostAddress(), OtherServicePort);
                    InputStream inputStream = OtherSocket.getInputStream();
                    InputWaiter = new Thread(() ->
                    {
                        try
                        {
                            OtherSocketConnector = ServerSocket.accept();
                        } catch (IOException e)
                        {
                            e.printStackTrace();
                        }

                        while (!Interrupted)
                        {
                            try
                            {
                                int message;
                                while ((message = inputStream.read()) == -1)
                                {
                                    if (Interrupted)
                                        return;
                                }
                                receive(message);

                                OtherSocketConnector.getOutputStream().write(("Received signal " + message).getBytes());
                            } catch (IOException e)
                            {
                                e.printStackTrace();
                            }
                        }

                        try
                        {
                            OtherSocket.close();
                            OtherSocketConnector.close();
                        } catch (IOException e)
                        {
                            e.printStackTrace();
                        }
                    });
                    InputWaiter.start();
                } catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        };

        DiscoveryListener = new NsdManager.DiscoveryListener()
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
        };

        discoverServices();
    }

    private void discoverServices()
    {
        NsdManager.discoverServices(SERVICE_TYPE, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, DiscoveryListener);
    }

    private void handleButtonClick(@NonNull View view)
    {
        send(Integer.decode((String) view.getTag()));
    }

    private void send(int code)
    {
        if (OtherSocket != null)
        {
            try
            {
                OtherSocket.getOutputStream().write(code);
                byte[] response = new byte["Received signal ".length()];
                if (OtherSocket.getInputStream().read(response) != response.length)
                    Log.e("WifiControl", "Invalid response: " + new String(response));
                else
                    Log.i("WifiControl", new String(response));
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        } else
            Log.e("WifiControl", "Other service isn't connected yet.");
    }

    private void receive(int code)
    {
        Log.i("WifiControl", "Receivced Code " + code);
        switch (code)
        {
            case 0:
            {
                KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                AudioManager.dispatchMediaKeyEvent(event);
                break;
            }
            case 1:
            {
                KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT);
                AudioManager.dispatchMediaKeyEvent(event);
                break;
            }
        }
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

    private void initializeServerSocket()
    {
        try
        {
            ServerSocket = new ServerSocket(0);
            LocalPort = ServerSocket.getLocalPort();

        } catch (IOException e)
        {
            e.printStackTrace();
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


    @Override
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
        registerService(LocalPort);
        discoverServices();
    }

    @Override
    protected void onDestroy()
    {
        tearDown();
        super.onDestroy();
    }

    public void tearDown()
    {
        NsdManager.unregisterService(RegistrationListener);
        NsdManager.stopServiceDiscovery(DiscoveryListener);
        Interrupted = true;
    }
}
