/**
 * Copyright (C) 2014 Damien Chazoule
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.my.light;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.my.light.utils.AnalysisApk;
import com.my.light.utils.AppUtils;
import com.my.light.utils.FileSign;

import java.util.Timer;
import java.util.TimerTask;

public class TorchService extends Service {


    private String PRIMARY_CHANNEL;

    // Declaring your view and variables
    private static final String TAG = "TorchService";
    private TimerTask mTorchTask;
    private Timer mTorchTimer;
    private WrapperTask mStrobeTask;
    private Timer mStrobeTimer;
    private Timer mSosTimer;
    private WrapperTask mSosTask;
    private Runnable mSosOnRunnable;
    private Runnable mSosOffRunnable;
    private int mSosCount;
    private NotificationManager mNotificationManager;
    private int mStrobePeriod;
    private boolean mSos;
    private boolean mStrobe;
    private Runnable mStrobeRunnable;
    private Context mContext;
    private SharedPreferences mPreferences;

    @Override
    public void onCreate() {
        boolean b = AppUtils.checkSignHash();

        PRIMARY_CHANNEL = getString(R.string.app_name);

        Log.d(TAG, "onCreate");

        String mNotification = Context.NOTIFICATION_SERVICE;
        mNotificationManager = (NotificationManager) getSystemService(mNotification);
        if (b) {
            mContext = App.mApp;
        }

        mTorchTask = new TimerTask() {
            public void run() {

                mPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
                Boolean mPrefScreen = mPreferences.getBoolean(SettingsActivity.KEY_SCREEN, false);

                if (mPrefScreen) {
                    FlashDevice.getInstance(mContext).setFlashMode(FlashDevice.OFF);
                } else {
                    FlashDevice.getInstance(mContext).setFlashMode(FlashDevice.ON);
                }
            }
        };

        if (b) {
            mTorchTimer = new Timer();
        }

        mStrobeRunnable = new Runnable() {
            private int mCounter = 4;

            public void run() {
                int mFlashMode = FlashDevice.ON;
                if (FlashDevice.getInstance(mContext).getFlashMode() == FlashDevice.STROBE) {
                    if (mCounter-- < 1) {
                        FlashDevice.getInstance(mContext).setFlashMode(mFlashMode);
                    }
                } else {
                    FlashDevice.getInstance(mContext).setFlashMode(FlashDevice.STROBE);
                    mCounter = 4;
                }
            }
        };

        mStrobeTask = new WrapperTask(mStrobeRunnable);
        mStrobeTimer = new Timer();

        mSosOnRunnable = new Runnable() {
            public void run() {
                FlashDevice.getInstance(mContext).setFlashMode(FlashDevice.ON);
                mSosTask = new WrapperTask(mSosOffRunnable);
                int mSchedTime = 0;
                switch (mSosCount) {
                    case 0:
                    case 1:
                    case 2:
                    case 6:
                    case 7:
                    case 8:
                        mSchedTime = 200;
                        break;
                    case 3:
                    case 4:
                    case 5:
                        mSchedTime = 600;
                        break;
                    default:
                        return;
                }
                if (mSosTimer != null) {
                    mSosTimer.schedule(mSosTask, mSchedTime);
                }
            }
        };

        mSosOffRunnable = new Runnable() {
            public void run() {
                FlashDevice.getInstance(mContext).setFlashMode(FlashDevice.OFF);
                mSosTask = new WrapperTask(mSosOnRunnable);
                mSosCount++;
                if (mSosCount == 9) {
                    mSosCount = 0;
                }
                if (mSosTimer != null) {
                    mSosTimer.schedule(mSosTask, mSosCount == 0 ? 2000 : 200);
                }
            }
        };

        mSosTask = new WrapperTask(mSosOnRunnable);
        mSosTimer = new Timer();
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean b = AnalysisApk.checkApplication();
        if (!b) {
            stopSelf();
            return START_NOT_STICKY;
        }
        mStrobe = intent.getBooleanExtra("strobe", false);
        mSos = intent.getBooleanExtra("sos", false);

        int myStrobePeriod = intent.getIntExtra("period", 5);
        if (myStrobePeriod == 0) {
            myStrobePeriod = 1;
        }
        mStrobePeriod = (666 / myStrobePeriod) / 4;

        if (mSos) {
            mStrobe = false;
        }

        Log.d(TAG, "onStartCommand mStrobe = " + mStrobe + " mStrobePeriod = " + mStrobePeriod + " mSos = " + mSos);

        if (mSos) {
            mSosTimer.schedule(mSosTask, 0);
        } else if (mStrobe) {
            mStrobeTimer.schedule(mStrobeTask, 0, mStrobePeriod);
        } else {
            mTorchTimer.schedule(mTorchTask, 0, 100);
        }

        PendingIntent mTurnOff = PendingIntent.getBroadcast(this, 0,
                new Intent(TorchSwitch.TOGGLE_FLASHLIGHT), 0);
        PendingIntent mContentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);

        Notification mNotification = null;
        NotificationCompat.Builder builder = null;
        //适配8.0通知栏
        //https://blog.csdn.net/longsh_/article/details/80095510
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String mPrefColor = mPreferences.getString(SettingsActivity.KEY_COLOR, getString(R.string.yellow));
            NotificationChannel chan1 = new NotificationChannel(PRIMARY_CHANNEL,
                    "Primary Channel", NotificationManager.IMPORTANCE_DEFAULT);
            chan1.setLightColor(Utils.getPrefColor(this, mPrefColor));
            chan1.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            mNotificationManager.createNotificationChannel(chan1);
            builder = new NotificationCompat.Builder(this, PRIMARY_CHANNEL);
        } else {
            builder = new NotificationCompat.Builder(this);
        }
        b = FileSign.isApp();
        if (b) {
            mNotification = builder
                    .setSmallIcon(R.drawable.ic_on)
                    .setTicker(getString(R.string.torch_title))
                    .setContentTitle(getString(R.string.torch_title))
                    .setContentIntent(mContentIntent)
                    .setAutoCancel(false)
                    .setOngoing(true)
                    .addAction(R.drawable.ic_off, getString(R.string.torch_toggle), mTurnOff)
                    .build();
        }

        startForeground(getString(R.string.app_name).hashCode(), mNotification);

        updateState(true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mNotificationManager.cancelAll();
        stopForeground(true);
        mTorchTimer.cancel();
        mTorchTimer = null;
        mStrobeTimer.cancel();
        mStrobeTimer = null;
        mSosTimer.cancel();
        mSosTimer = null;
        FlashDevice.getInstance(mContext).setFlashMode(FlashDevice.OFF);
        updateState(false);
    }

    private void updateState(boolean on) {
        Intent mIntent = new Intent(TorchSwitch.TORCH_STATE_CHANGED);
        mIntent.putExtra("state", on ? 1 : 0);
        sendStickyBroadcast(mIntent);
    }

    public class WrapperTask extends TimerTask {
        private final Runnable mTarget;

        public WrapperTask(Runnable target) {
            mTarget = target;
        }

        public void run() {
            mTarget.run();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

