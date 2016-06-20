package com.example.android.sunshine.app.gcm;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import com.example.android.sunshine.app.MainActivity;
import com.example.android.sunshine.app.R;
import com.google.android.gms.gcm.GcmListenerService;

public class MyGcmListenerService extends GcmListenerService {
    private final static String TAG = "MyGcmListenerService";

    private final static String EXTRA_DATA = "data";
    private final static String EXTRA_WEATHER = "weather";
    private final static String EXTRA_LOCATION = "location";

    public static final int NOTIFICATION_ID = 1;

    /**
     * Called when a message is recieved.
     *
     * @param from senderID of the sender
     * @param data Data bundle containing message data as key/value pairs
     */
    @Override
    public void onMessageReceived(String from, Bundle data) {
        if (!data.isEmpty()) {
            String senderId = getString(R.string.gcm_defaultSenderId);
            if (senderId.length() == 0) {
                Toast.makeText(this, "SenderID string needs to be set.", Toast.LENGTH_LONG).show();
            }

            if (senderId.equals(from)) {
                String weather = data.getString(EXTRA_WEATHER);
                    String location = data.getString(EXTRA_LOCATION);
                    String alert = String.format(getString(R.string.gcm_weather_alert), weather,
                            location);
                    sendNotification(alert);
            }
            Log.i(TAG, "Received: " + data.toString());
        }
    }

    /**
     * Put the message into a notification and post it.
     *
     * @param message The alert message to be posted.
     */
    private void sendNotification(String message) {
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        PendingIntent contentIntent =
                PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);

        Bitmap largeIcon = BitmapFactory.decodeResource(this.getResources(), R.drawable.art_storm);
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this)
                    .setSmallIcon(R.drawable.art_clear)
                    .setLargeIcon(largeIcon)
                    .setContentTitle("Weather alert!")
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                    .setContentText(message)
                    .setPriority(NotificationCompat.PRIORITY_HIGH);
        builder.setContentIntent(contentIntent);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }
}
