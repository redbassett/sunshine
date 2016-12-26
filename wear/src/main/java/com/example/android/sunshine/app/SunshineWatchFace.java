/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face without seconds. On devices with low-bit ambient mode, the text is drawn
 * without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final String LOG_TAG = SunshineWatchFace.class.getName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a minute since seconds
     * aren't displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mSeperatorPaint;
        Paint mMaxTempPaint;
        Paint mMinTempPaint;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mTimeYOffset;
        float mDateYOffset;
        float mSeperatorWidth;
        float mSeperatorHeight;
        float mSeperatorYOffset;
        float mWeatherYOffset;
        float mWeatherRowSpacing;

        String mTimeFormat;
        String mDateFormat;

        GoogleApiClient mGoogleApiClient;

        public static final String WEATHER_PATH = "/current_weather";
        public static final String MAX_TEMP_KEY = "HIGH_TEMP";
        public static final String MIN_TEMP_KEY = "LOW_TEMP";
        public static final String WEATHER_ID_KEY = "WEATHER_ID";

        String mMaxTemp;
        String mMinTemp;
        int mWeatherId;

        public static final String PREFS_KEY = "watchface_weather";
        public static final String PREFS_MAX_TEMP = "watchfach_weather_max";
        public static final String PREFS_MIN_TEMP = "watchface_weather_min";
        public static final String PREFS_WEATHER_ID = "watchface_weather_id";

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mTimeYOffset = resources.getDimension(R.dimen.digital_time_y_offset);
            mDateYOffset = resources.getDimension(R.dimen.digital_date_y_offset);
            mSeperatorWidth = resources.getDimension(R.dimen.digital_seperator_width);
            mSeperatorHeight = resources.getDimension(R.dimen.digital_seperator_height);
            mSeperatorYOffset = resources.getDimension(R.dimen.digital_seperator_y_offset);
            mWeatherYOffset = resources.getDimension(R.dimen.digital_weather_y_offset);
            mWeatherRowSpacing = resources.getDimension(R.dimen.digital_weather_row_spacing);

            mTimeFormat = resources.getString(R.string.time_format);
            mDateFormat = resources.getString(R.string.date_format);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTimePaint = createTextPaint(resources.getColor(R.color.digital_text_primary));
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text_secondary));

            mSeperatorPaint = new Paint();
            mSeperatorPaint.setColor(resources.getColor(R.color.digital_text_secondary));

            mMaxTempPaint = createTextPaint(resources.getColor(R.color.digital_text_primary));
            mMinTempPaint = createTextPaint(resources.getColor(R.color.digital_text_secondary));

            mCalendar = Calendar.getInstance();

            SharedPreferences prefs = getSharedPreferences(PREFS_KEY, 0);
            mMaxTemp = prefs.getString(PREFS_MAX_TEMP, null);
            mMinTemp = prefs.getString(PREFS_MIN_TEMP, null);
            mWeatherId = prefs.getInt(PREFS_WEATHER_ID, 0);

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                        @Override
                        public void onConnected(@Nullable Bundle connectionHint) {
                            Log.d(LOG_TAG, "onConnected: " + connectionHint);

                            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
                        }

                        @Override
                        public void onConnectionSuspended(int cause) {
                            Log.d(LOG_TAG, "onConnectionSuspended: " + cause);
                        }
                    })
                    .addOnConnectionFailedListener(
                            new GoogleApiClient.OnConnectionFailedListener() {
                        @Override
                        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                            Log.d(LOG_TAG, "onConnectionFailed: " + connectionResult);
                        }
                    })
                    .addApi(Wearable.API)
                    .build();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                Log.d(LOG_TAG, "Connecting");
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Log.d(LOG_TAG, "Disconnecting");
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_time_size_round
                    : R.dimen.digital_text_time_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_date_size_round
                    : R.dimen.digital_text_date_size);
            float weatherTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_weather_size_round
                    : R.dimen.digital_text_weather_size);

            mTimePaint.setTextSize(timeTextSize);
            mDatePaint.setTextSize(dateTextSize);
            mMaxTempPaint.setTextSize(weatherTextSize);
            mMinTempPaint.setTextSize(weatherTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mMaxTempPaint.setAntiAlias(!inAmbientMode);
                    mMinTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient and interactive modes.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String timeText = new SimpleDateFormat(mTimeFormat, Locale.getDefault())
                    .format(mCalendar.getTime());
            canvas.drawText(timeText, bounds.centerX() - (mTimePaint.measureText(timeText)/2),
                    mTimeYOffset, mTimePaint);

            String dateText = new SimpleDateFormat(mDateFormat, Locale.getDefault())
                    .format(mCalendar.getTime()).toUpperCase();
            canvas.drawText(dateText, bounds.centerX() - (mDatePaint.measureText(dateText)/2),
                    mDateYOffset, mDatePaint);

            canvas.drawRect(bounds.centerX() - (mSeperatorWidth/2), mSeperatorYOffset,
                    bounds.centerX() + (mSeperatorWidth/2), mSeperatorYOffset+mSeperatorHeight,
                    mSeperatorPaint);

            Bitmap weatherIcon = BitmapFactory.decodeResource(getResources(),
                    Utility.getIconResourceForWeatherCondition(mWeatherId));

            if (mMaxTemp != null && mMinTemp != null && weatherIcon != null) {
                float center = bounds.centerX();
                float maxTempWidth = mMaxTempPaint.measureText(mMaxTemp);
                float maxTempHeight = mMaxTempPaint.getFontSpacing();
                float offset = mMaxTempPaint.descent();

                canvas.drawText(mMaxTemp,
                        center - (maxTempWidth / 2),
                        mWeatherYOffset, mMaxTempPaint);

                canvas.drawText(mMinTemp,
                        center + (maxTempWidth / 2)
                                + mWeatherRowSpacing,
                        mWeatherYOffset, mMinTempPaint);

                canvas.drawBitmap(weatherIcon, null,
                        new RectF(center - (maxTempWidth/2) - mWeatherRowSpacing - maxTempHeight,
                                mWeatherYOffset - maxTempHeight + offset,
                                center - (maxTempWidth/2) - mWeatherRowSpacing,
                                mWeatherYOffset + offset),
                        null);
            } else {
                Log.d(LOG_TAG, "Not drawing weather. High: " + mMaxTemp + " Low: " + mMinTemp
                        + " Icon: " + String.valueOf(weatherIcon) + " with ID "
                        + String.valueOf(mWeatherId));
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        /**
         * Handle updated data from the handheld app
         * @param dataEventBuffer
         */
        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();
                    if (dataItem.getUri().getPath().compareTo(WEATHER_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                        mMaxTemp = dataMap.getString(MAX_TEMP_KEY);
                        mMinTemp = dataMap.getString(MIN_TEMP_KEY);
                        mWeatherId = dataMap.getInt(WEATHER_ID_KEY);

                        SharedPreferences prefs = getSharedPreferences(PREFS_KEY, 0);
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putString(PREFS_MAX_TEMP, mMaxTemp);
                        editor.putString(PREFS_MIN_TEMP, mMinTemp);
                        editor.putInt(PREFS_WEATHER_ID, mWeatherId);
                        editor.commit();

                        invalidate();
                    }
                }
            }
        }
    }
}
