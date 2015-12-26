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

package com.example.android.wearsunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
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

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener,
            MessageApi.MessageListener {

        private static final String WEATHER_ID = "WeatherID";
        private static final String MIN_TEMP = "MinTemp";
        private static final String MAX_TEMP = "MaxTemp";

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mHourTextPaint;
        Paint mMinuteTextPaint;
        Paint mDateTextPaint;
        Paint mLinePaint;
        Paint mMaxTempPaint;
        Paint mMinTempPaint;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private GoogleApiClient mApiClient;
        private Bitmap mWeatherIcon;
        private String mMaxTemp = "99°";
        private String mMinTemp = "2°";
        private float mLineExtends = 50;
        private boolean mDrawCenterLine = false;
        private Rect mBounds = new Rect();
        private int textColor;
        private int mGridLinesMultiplier = 4;
        private float mXSpacing;
        private float mYSpacing;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.background));

            textColor = ContextCompat.getColor(getApplicationContext(), R.color.digital_text);
            mHourTextPaint = createTextPaint(textColor, true);
            mMinuteTextPaint = createTextPaint(textColor, false);
            mDateTextPaint = createTextPaint(textColor, false);
            mMaxTempPaint = createTextPaint(textColor, true);
            mMinTempPaint = createTextPaint(textColor, false);
            mLinePaint = createLinePaint(
                    ContextCompat.getColor(getApplicationContext(), R.color.digital_text));

            createWeatherBitmap(215);

            mTime = new Time();
        }

        private void initApiClient() {
            if (mApiClient == null) {
                mApiClient = new GoogleApiClient.Builder(getApplicationContext())
                        .addApi(Wearable.API)
                        .addConnectionCallbacks(this)
                        .addOnConnectionFailedListener(this)
                        .build();
            }

            if (!mApiClient.isConnected()) {
                mApiClient.connect();
            }
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, boolean bold) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setAntiAlias(true);

            if (bold) {
                paint.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                paint.setTypeface(Typeface.DEFAULT);
            }

            return paint;
        }

        private Paint createLinePaint(int color) {
            Paint p = new Paint();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(getResources().getDimension(R.dimen.line_stroke_width));
            p.setColor(color);
            p.setAlpha(128);
            return p;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
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
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);

            initApiClient();
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);

            mApiClient.disconnect();
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_text_size_round : R.dimen.digital_date_text_size);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_textSize_round : R.dimen.digital_temp_text_size);

            mHourTextPaint.setTextSize(textSize);
            mMinuteTextPaint.setTextSize(textSize);
            mDateTextPaint.setTextSize(dateTextSize);
            mMaxTempPaint.setTextSize(tempTextSize);
            mMinTempPaint.setTextSize(tempTextSize);

            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mXSpacing = resources.getDimension(R.dimen.digital_text_x_spacing);
            mXSpacing = resources.getDimension(R.dimen.digital_text_y_spacing);
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
                    mHourTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.

                    if (x > 250 && y > 250) {
                        mDrawCenterLine = !mDrawCenterLine;
                    } else {
                        if (x < 150) {
                            if (mGridLinesMultiplier > 1) {
                                mGridLinesMultiplier--;
                            }
                        } else {
                            if (mGridLinesMultiplier < 5) {
                                mGridLinesMultiplier++;
                            }
                        }
                    }

                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            float centerX = bounds.exactCenterX();

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            mTime.setToNow();

            float currentOffsetY = mYOffset;

            String hour = String.format("%02d", mTime.hour);
            String minute = String.format(":%02d", mTime.minute);
            mHourTextPaint.getTextBounds(hour, 0, hour.length(), mBounds);
            float hourLength = mHourTextPaint.measureText(hour);
            float hourHeight = mBounds.height();

            float totalTextLength = hourLength + mMinuteTextPaint.measureText(minute);
            float offsetX = centerX - (totalTextLength / 2);
            canvas.drawText(hour, offsetX, mYOffset, mHourTextPaint);
            canvas.drawText(minute, offsetX + hourLength, mYOffset, mMinuteTextPaint);
            currentOffsetY += hourHeight;

            // date
            String date = WatchUtility.getFormattedDate();
            mDateTextPaint.getTextBounds(date, 0, date.length(), mBounds);
            float dateLength = mDateTextPaint.measureText(date);
            float dateHeight = mBounds.height();
            offsetX = centerX - dateLength / 2;
            canvas.drawText(date, offsetX, currentOffsetY, mDateTextPaint);
            currentOffsetY += dateHeight - mYSpacing; // add an additional spacing

            // separation line
            canvas.drawLine(centerX - mLineExtends, currentOffsetY,
                    centerX + mLineExtends, currentOffsetY, mLinePaint);
            currentOffsetY += mLinePaint.getStrokeWidth() + 5;

            // prep: calculate length of all elements of the weather line
            // needed to draw centered to the screen
            float totalLength = mWeatherIcon.getScaledWidth(canvas);
            totalLength += mMaxTempPaint.measureText(mMaxTemp);
            totalLength += mMinTempPaint.measureText(mMinTemp);
            totalLength += 2 * mXSpacing; // spacing between elements
            float currentOffsetX = centerX - totalLength / 2;

            // draw the icon with the correct offset
            canvas.drawBitmap(mWeatherIcon, currentOffsetX, currentOffsetY, new Paint());
            currentOffsetX += mWeatherIcon.getScaledWidth(canvas) + mXSpacing;
            currentOffsetY += mWeatherIcon.getScaledHeight(canvas) / 2;

            // Max Temp
            mMaxTempPaint.getTextBounds(mMaxTemp, 0, mMaxTemp.length(), mBounds);
            float tempHeight = mBounds.height() / 2;
            canvas.drawText(mMaxTemp, currentOffsetX, currentOffsetY + tempHeight, mMaxTempPaint);
            currentOffsetX += mMaxTempPaint.measureText(mMaxTemp) + mXSpacing;

            // Min Temp
            mMinTempPaint.getTextBounds(mMinTemp, 0, mMinTemp.length(), mBounds);
            tempHeight = mBounds.height() / 2;
            canvas.drawText(mMinTemp, currentOffsetX, currentOffsetY + tempHeight, mMinTempPaint);

            if (mDrawCenterLine) {
                drawGrid(canvas, bounds);
                canvas.drawLine(centerX, 0, centerX, bounds.height(), mLinePaint);
            }
        }

        private void drawGrid(Canvas c, Rect bounds) {
            // some simple hacky debug grid
            // toggle on / off by tapping lower right corner
            // tapping left side of the screen to decrease line count
            // tap right side to increase line count

            int gridLines = (int) Math.pow(2, mGridLinesMultiplier);

            float spacingX = bounds.width() / gridLines;
            float spacingY = bounds.height() / gridLines;

            for (int x = 0; x < gridLines + 1; x++) {
                c.drawLine(spacingX * x, 0, spacingX * x, bounds.height(), mLinePaint);
            }
            for (int y = 0; y < gridLines + 1; y++) {
                c.drawLine(0, spacingY * y, bounds.width(), spacingY * y, mLinePaint);
            }

            c.drawText(String.valueOf(gridLines), 250, bounds.height() - 5, mMinuteTextPaint);
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

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.MessageApi.addListener(mApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
        }

        @Override
        public void onMessageReceived(MessageEvent messageEvent) {
            if (messageEvent.getPath().equals("/dataUpdated")) {
                DataMap dm = DataMap.fromByteArray(messageEvent.getData());
                int weatherId = dm.getInt(WEATHER_ID);
                mMaxTemp = dm.getString(MAX_TEMP);
                mMinTemp = dm.getString(MIN_TEMP);

                createWeatherBitmap(weatherId);
            }
        }

        private void createWeatherBitmap(int weatherId) {
            Resources resources = getResources();
            int iconSize = resources.getDimensionPixelSize(R.dimen.weather_icon_size);
            Bitmap bmp = BitmapFactory.decodeResource(resources, WatchUtility.getArtResourceForWeatherCondition(weatherId));
            bmp = Bitmap.createScaledBitmap(bmp, iconSize, iconSize, false);
            if (bmp != null) {
                mWeatherIcon = bmp;
            }
        }
    }
}
