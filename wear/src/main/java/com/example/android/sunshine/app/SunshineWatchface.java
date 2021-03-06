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
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;


/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchface extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchface.Engine> mWeakReference;

        public EngineHandler(SunshineWatchface.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchface.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {


        private static final String TAG = "EngineWatchFace";
        private static final String WEATHER_PATH = "/weather";
        private static final String WEATHER_TEMP_HIGH_KEY = "weather_temp_high_key";
        private static final String WEATHER_TEMP_LOW_KEY = "weather_temp_low_key";
        private static final String WEATHER_TEMP_ICON_KEY = "weather_temp_icon_key";
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        String weatherTempHigh;
        String weatherTempLow;
        Bitmap weatherTempIcon = null;
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint linePaint;
        Paint textPaintTime;
        Paint textPaintTimeBold;
        Paint textPaintDate;
        Paint textPaintTemp;
        Paint textPaintTempBold;
        Rect textBounds = new Rect();
        boolean mAmbient;
        SimpleDateFormat mDateFormat;
        float mXOffset;
        float mYOffset;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                mDateFormat = new SimpleDateFormat("ccc, MMM d yyyy", Locale.getDefault());
                mDateFormat.setCalendar(mCalendar);
                invalidate();
            }
        };
        Date mDate;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private GoogleApiClient mGoogleApiClient;
        DataApi.DataListener dataListener = new DataApi.DataListener() {
            @Override
            public void onDataChanged(DataEventBuffer dataEvents) {
                Log.e(TAG, "onDataChanged(): " + dataEvents);

                for (DataEvent event : dataEvents) {
                    if (event.getType() == DataEvent.TYPE_CHANGED) {
                        String path = event.getDataItem().getUri().getPath();
                        if (WEATHER_PATH.equals(path)) {
                            Log.e(TAG, "Data Changed for " + WEATHER_PATH);
                            try {
                                DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                                weatherTempHigh = dataMapItem.getDataMap().getString(WEATHER_TEMP_HIGH_KEY);
                                weatherTempLow = dataMapItem.getDataMap().getString(WEATHER_TEMP_LOW_KEY);
                                final Asset photo = dataMapItem.getDataMap().getAsset(WEATHER_TEMP_ICON_KEY);
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        weatherTempIcon = bitmapFromAsset(mGoogleApiClient, photo);
                                    }
                                }).start();

                            } catch (Exception e) {
                                Log.e(TAG, "Exception   ", e);
                                weatherTempIcon = null;
                            }

                        } else {

                            Log.e(TAG, "Unrecognized path:  \"" + path + "\"  \"" + WEATHER_PATH + "\"");
                        }

                    } else {
                        Log.e(TAG, "Unknown data event type   " + event.getType());
                    }
                }
            }

            private Bitmap bitmapFromAsset(GoogleApiClient apiClient, Asset asset) {
                if (asset == null) {
                    throw new IllegalArgumentException("Asset must be non-null");
                }
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(apiClient, asset).await().getInputStream();

                if (assetInputStream == null) {
                    Log.w(TAG, "Requested an unknown Asset.");
                    return null;
                }
                return BitmapFactory.decodeStream(assetInputStream);
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mCalendar = Calendar.getInstance();
            mDate = new Date();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchface.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchface.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.digital_background));

            textPaintTime = new Paint();
            textPaintTime = createTextPaint(resources.getColor(R.color.main_text));

            textPaintTimeBold = new Paint();
            textPaintTimeBold = createTextPaint(resources.getColor(R.color.main_text));

            textPaintDate = new Paint();
            textPaintDate = createTextPaint(resources.getColor(R.color.main_text));

            textPaintTemp = new Paint();
            textPaintTemp = createTextPaint(resources.getColor(R.color.second_text));
            textPaintTempBold = new Paint();
            textPaintTempBold = createTextPaint(resources.getColor(R.color.second_text));

            linePaint = new Paint();
            linePaint.setColor(resources.getColor(android.R.color.secondary_text_light));
            linePaint.setStrokeWidth(0.8f);
            linePaint.setAntiAlias(true);

            mDateFormat = new SimpleDateFormat("ccc, MMM d yyyy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);

            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                        @Override
                        public void onConnected(Bundle bundle) {
                            Log.e(TAG, "onConnected: Successfully connected to Google API client");
                            Wearable.DataApi.addListener(mGoogleApiClient, dataListener);
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    NodeApi.GetConnectedNodesResult nodes = Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();
                                    for (final Node node : nodes.getNodes()) {
                                        MessageApi.SendMessageResult messageResult = Wearable.MessageApi.sendMessage(mGoogleApiClient, node.getId(), "/run_sync", null).await();
                                        if (messageResult.getStatus().isSuccess()) {
                                            Log.d("Sent to", node.getDisplayName());
                                        }
                                    }
                                }
                            }).start();

                        }

                        @Override
                        public void onConnectionSuspended(int i) {
                            Log.e(TAG, "onConnectionSuspended");
                        }
                    })
                    .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                        @Override
                        public void onConnectionFailed(ConnectionResult connectionResult) {
                            Log.e(TAG, "onConnectionFailed(): Failed to connect, with result : " + connectionResult);
                        }
                    })
                    .build();
            mGoogleApiClient.connect();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
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
                registerReceiver();
                mCalendar.setTimeZone(TimeZone.getDefault());
                mDateFormat = new SimpleDateFormat("ccc, MMM d yyyy", Locale.getDefault());
                mDateFormat.setCalendar(mCalendar);
            } else {
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
            SunshineWatchface.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchface.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchface.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);

            textPaintTime.setTextSize(resources.getDimension(R.dimen.time_text_size));
            textPaintTimeBold.setTextSize(resources.getDimension(R.dimen.time_text_size));
            textPaintDate.setTextSize(resources.getDimension(R.dimen.date_text_size));
            textPaintTemp.setTextSize(resources.getDimension(R.dimen.temp_text_size));
            textPaintTempBold.setTextSize(resources.getDimension(R.dimen.temp_text_size));
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
                mBackgroundPaint.setColor(inAmbientMode ? getResources().getColor(R.color.digital_background_ambient) : getResources().getColor(R.color.digital_background));
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    textPaintTime.setAntiAlias(!inAmbientMode);
                    textPaintDate.setAntiAlias(!inAmbientMode);
                    textPaintTemp.setAntiAlias(!inAmbientMode);
                    linePaint.setAntiAlias(!inAmbientMode);
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
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            Log.e("onDraw", "Watch on Draw");

            int spaceY = 20;
            int spaceX = 10;
            int spaceYTemp;

            String text;

            int centerX = bounds.width() / 2;
            int centerY = bounds.height() / 2;

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            text = mDateFormat.format(mDate).toUpperCase();
            textPaintDate.getTextBounds(text, 0, text.length(), textBounds);
            canvas.drawText(text, centerX - textBounds.width() / 2, centerY, textPaintDate);
            spaceYTemp = textBounds.height();
            String ampm = mCalendar.get(Calendar.AM_PM) == 0 ? "AM" : "PM";
            int hour = mCalendar.get(Calendar.HOUR) == 0 ? 12 : mCalendar.get(Calendar.HOUR);
            text = String.format(Locale.ENGLISH, "%02d", hour)
                    + ":" + String.format(Locale.ENGLISH, "%02d", mCalendar.get(Calendar.MINUTE))
                    + ":" + String.format(Locale.ENGLISH, "%02d", mCalendar.get(Calendar.SECOND))
                    + " " + String.format(Locale.ENGLISH, "%s", ampm);
            textPaintTimeBold.getTextBounds(text, 0, text.length(), textBounds);
            canvas.drawText(text, centerX - textBounds.width() / 2, centerY - spaceY + 4 - spaceYTemp, textPaintTimeBold);

            if (!mAmbient) {
                spaceYTemp = spaceY;
                canvas.drawLine(centerX - 20, centerY + spaceY, centerX + 20, centerY + spaceYTemp, linePaint);
                if (weatherTempHigh != null && weatherTempLow != null) {

                    text = weatherTempHigh;
                    textPaintTempBold.getTextBounds(text, 0, text.length(), textBounds);
                    spaceYTemp = textBounds.height() + spaceY + spaceYTemp;
                    canvas.drawText(text, centerX - textBounds.width() / 2, centerY + spaceYTemp, textPaintTempBold);

                    text = weatherTempLow;
                    canvas.drawText(text, centerX + textBounds.width() / 2 + spaceX, centerY + spaceYTemp, textPaintTemp);

                    if (weatherTempIcon != null) {
                        // draw weather icon
                        canvas.drawBitmap(weatherTempIcon,
                                centerX - textBounds.width() / 2 - spaceX - weatherTempIcon.getWidth(),
                                centerY + spaceYTemp - weatherTempIcon.getHeight() / 2 - textBounds.height() / 2, null);
                    }
                } else {
                    // draw temperature high
                    text = getString(R.string.info_not_available);
                    textPaintDate.getTextBounds(text, 0, text.length(), textBounds);
                    spaceYTemp = textBounds.height() + spaceY + spaceYTemp;
                    canvas.drawText(text, centerX - textBounds.width() / 2, centerY + spaceYTemp, textPaintDate);

                }
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
    }
}