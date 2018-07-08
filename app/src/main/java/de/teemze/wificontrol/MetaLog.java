package de.teemze.wificontrol;

import android.util.Log;
import android.widget.TextView;
import org.w3c.dom.Text;

public final class MetaLog
{
    private android.widget.TextView TextView;
    private de.teemze.wificontrol.MainActivity MainActivity;

    MetaLog(TextView textView, MainActivity mainActivity)
    {
        TextView = textView;
        MainActivity = mainActivity;
    }

    public void i(String tag, String msg)
    {
        Log.i(tag, msg);
        MainActivity.runOnUiThread(() -> TextView.append("I/" + tag + ": " + msg + "\n"));
    }

    public void d(String tag, String msg)
    {
        Log.d(tag, msg);
        MainActivity.runOnUiThread(() -> {
            if (TextView.getText().length() >= 1000)
                TextView.setText(TextView.getText().subSequence(100, TextView.getText().length()));

            TextView.append("D/" + tag + ": " + msg + "\n");
        });
    }

    public void e(String tag, String msg)
    {
        Log.e(tag, msg);
        MainActivity.runOnUiThread(() -> TextView.append("E/" + tag + ": " + msg + "\n"));
    }
}
