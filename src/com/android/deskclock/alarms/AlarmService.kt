/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.deskclock.alarms

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

import com.android.deskclock.AlarmAlertWakeLock
import com.android.deskclock.LogUtils
import com.android.deskclock.R
import com.android.deskclock.data.DataModel
import com.android.deskclock.events.Events
import com.android.deskclock.provider.AlarmInstance
import com.android.deskclock.provider.ClockContract.InstancesColumns

/**
 * This service is in charge of starting/stopping the alarm. It will bring up and manage the
 * [AlarmActivity] as well as [AlarmKlaxon].
 *
 * Registers a broadcast receiver to listen for snooze/dismiss intents. The broadcast receiver
 * exits early if AlarmActivity is bound to prevent double-processing of the snooze/dismiss intents.
 */
class AlarmService : Service() {
    /** Binder given to AlarmActivity.  */
    private val mBinder: IBinder = Binder()

    /** Whether the service is currently bound to AlarmActivity  */
    private var mIsBound = false

    /** Listener for changes in phone state.  */
    private val mPhoneStateListener = PhoneStateChangeListener()

    /** Whether the receiver is currently registered  */
    private var mIsRegistered = false

    override fun onBind(intent: Intent?): IBinder {
        mIsBound = true
        return mBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        mIsBound = false
        return super.onUnbind(intent)
    }

    private lateinit var mTelephonyManager: TelephonyManager
    private var mCurrentAlarm: AlarmInstance? = null
    private lateinit var mSensorManager: SensorManager
    private var mFlipAction: Int = 0
    private var mShakeAction: Int = 0
    
    private fun startAlarm(instance: AlarmInstance) {
        LogUtils.v("AlarmService.start with instance: " + instance.mId)
        if (mCurrentAlarm != null) {
            AlarmStateManager.setMissedState(this, mCurrentAlarm!!)
            stopCurrentAlarm()
        }

        AlarmAlertWakeLock.acquireCpuWakeLock(this)

        mCurrentAlarm = instance
        AlarmNotifications.showAlarmNotification(this, mCurrentAlarm!!)
        mTelephonyManager.listen(mPhoneStateListener.init(), PhoneStateListener.LISTEN_CALL_STATE)
        AlarmKlaxon.start(this, mCurrentAlarm!!)
        sendBroadcast(Intent(ALARM_ALERT_ACTION))
        attachListeners()
    }

    private fun stopCurrentAlarm() {
        if (mCurrentAlarm == null) {
            LogUtils.v("There is no current alarm to stop")
            return
        }

        val instanceId = mCurrentAlarm!!.mId
        LogUtils.v("AlarmService.stop with instance: %s", instanceId)

        AlarmKlaxon.stop(this)
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE)
        sendBroadcast(Intent(ALARM_DONE_ACTION))

        stopForeground(true /* removeNotification */)

        mCurrentAlarm = null
        detachListeners()
        AlarmAlertWakeLock.releaseCpuLock()
    }

    private val mActionsReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.getAction()
            LogUtils.i("AlarmService received intent %s", action)
            if (mCurrentAlarm == null ||
                    mCurrentAlarm!!.mAlarmState != InstancesColumns.FIRED_STATE) {
                LogUtils.i("No valid firing alarm")
                return
            }

            if (mIsBound) {
                LogUtils.i("AlarmActivity bound; AlarmService no-op")
                return
            }

            when (action) {
                ALARM_SNOOZE_ACTION -> {
                    // Set the alarm state to snoozed.
                    // If this broadcast receiver is handling the snooze intent then AlarmActivity
                    // must not be showing, so always show snooze toast.
                    AlarmStateManager.setSnoozeState(context, mCurrentAlarm!!, true /* showToast */)
                    Events.sendAlarmEvent(R.string.action_snooze, R.string.label_intent)
                }
                ALARM_DISMISS_ACTION -> {
                    // Set the alarm state to dismissed.
                    AlarmStateManager.deleteInstanceAndUpdateParent(context, mCurrentAlarm!!)
                    Events.sendAlarmEvent(R.string.action_dismiss, R.string.label_intent)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        mTelephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        // Register the broadcast receiver
        val filter = IntentFilter(ALARM_SNOOZE_ACTION)
        filter.addAction(ALARM_DISMISS_ACTION)
        registerReceiver(mActionsReceiver, filter)
        mIsRegistered = true
        
        // set up for flip and shake actions
        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        mFlipAction = DataModel.dataModel.flipAction
        mShakeAction = DataModel.dataModel.shakeAction
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtils.v("AlarmService.onStartCommand() with %s", intent)
        if (intent == null) {
            return Service.START_NOT_STICKY
        }

        val instanceId = AlarmInstance.getId(intent.getData()!!)
        when (intent.getAction()) {
            AlarmStateManager.CHANGE_STATE_ACTION -> {
                AlarmStateManager.handleIntent(this, intent)

                // If state is changed to firing, actually fire the alarm!
                val alarmState: Int = intent.getIntExtra(AlarmStateManager.ALARM_STATE_EXTRA, -1)
                if (alarmState == InstancesColumns.FIRED_STATE) {
                    val cr: ContentResolver = this.getContentResolver()
                    val instance: AlarmInstance? = AlarmInstance.getInstance(cr, instanceId)
                    if (instance == null) {
                        LogUtils.e("No instance found to start alarm: %d", instanceId)
                        if (mCurrentAlarm != null) {
                            // Only release lock if we are not firing alarm
                            AlarmAlertWakeLock.releaseCpuLock()
                        }
                    } else if (mCurrentAlarm != null && mCurrentAlarm!!.mId == instanceId) {
                        LogUtils.e("Alarm already started for instance: %d", instanceId)
                    } else {
                        startAlarm(instance)
                    }
                }
            }
            STOP_ALARM_ACTION -> {
                if (mCurrentAlarm != null && mCurrentAlarm!!.mId != instanceId) {
                    LogUtils.e("Can't stop alarm for instance: %d because current alarm is: %d",
                            instanceId, mCurrentAlarm!!.mId)
                } else {
                    stopCurrentAlarm()
                    stopSelf()
                }
            }
        }

        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        LogUtils.v("AlarmService.onDestroy() called")
        super.onDestroy()
        if (mCurrentAlarm != null) {
            stopCurrentAlarm()
        }

        if (mIsRegistered) {
            unregisterReceiver(mActionsReceiver)
            mIsRegistered = false
        }
    }

    private inner class PhoneStateChangeListener : PhoneStateListener() {
        private var mPhoneCallState = 0

        fun init(): PhoneStateChangeListener {
            mPhoneCallState = -1
            return this
        }

        override fun onCallStateChanged(state: Int, ignored: String?) {
            if (mPhoneCallState == -1) {
                mPhoneCallState = state
            }

            if (state != TelephonyManager.CALL_STATE_IDLE && state != mPhoneCallState) {
                startService(AlarmStateManager.createStateChangeIntent(this@AlarmService,
                        "AlarmService", mCurrentAlarm!!, InstancesColumns.MISSED_STATE))
            }
        }
    }
    
    private interface ResettableSensorEventListener : SensorEventListener {
        fun reset()
    }

    private val mFlipListener: ResettableSensorEventListener = object : ResettableSensorEventListener {
        // Accelerometers are not quite accurate.
	private val GRAVITY_UPPER_THRESHOLD: Float = 1.3f * SensorManager.STANDARD_GRAVITY
        private val GRAVITY_LOWER_THRESHOLD: Float = 0.7f * SensorManager.STANDARD_GRAVITY
        private val SENSOR_SAMPLES = 3
        private var mStopped = false
        private var mWasFaceUp = false
        private val mSamples = BooleanArray(SENSOR_SAMPLES)
        private var mSampleIndex = 0

        @Override
        override fun onAccuracyChanged(sensor: Sensor?, acc: Int) {
        }

        @Override
        override fun reset() {
            mWasFaceUp = false
            mStopped = false
            for (i in 0 until SENSOR_SAMPLES) {
                mSamples[i] = false
            }
        }

        private fun filterSamples(): Boolean {
            var allPass = true
            for (sample in mSamples) {
                allPass = allPass && sample
            }
            return allPass
        }

        @Override
        override fun onSensorChanged(event: SensorEvent) {
            // Add a sample overwriting the oldest one. Several samples
            // are used to avoid the erroneous values the sensor sometimes
            // returns.
            val z: Float = event.values.get(2)
            if (mStopped) {
                return
            }
            if (!mWasFaceUp) {
                // Check if its face up enough.
                mSamples[mSampleIndex] = z > GRAVITY_LOWER_THRESHOLD &&
                        z < GRAVITY_UPPER_THRESHOLD

                // face up
                if (filterSamples()) {
                    mWasFaceUp = true
                    for (i in 0 until SENSOR_SAMPLES) {
                        mSamples[i] = false
                    }
                }
            } else {
                // Check if its face down enough.
                mSamples[mSampleIndex] = z < -GRAVITY_LOWER_THRESHOLD &&
                        z > -GRAVITY_UPPER_THRESHOLD

                // face down
                if (filterSamples()) {
                    mStopped = true
                    handleAction(mFlipAction)
                }
            }
            mSampleIndex = (mSampleIndex + 1) % SENSOR_SAMPLES
        }
    };

    private val mShakeListener: SensorEventListener = object : SensorEventListener {
        private val SENSITIVITY = 16f
        private val BUFFER = 5
        private val gravity = FloatArray(3)
        private var average = 0f
        private var fill = 0

        @Override
        override fun onAccuracyChanged(sensor: Sensor?, acc: Int) {
        }

        override fun onSensorChanged(event: SensorEvent) {
            val alpha = 0.8F
	    for (i in 0..2) {
                gravity[i] = alpha * gravity[i] + (1 - alpha) * event.values.get(i)
            }
            val x: Float = event.values.get(0) - gravity[0]
            val y: Float = event.values.get(1) - gravity[1]
            val z: Float = event.values.get(2) - gravity[2]
            if (fill <= BUFFER) {
                average += Math.abs(x) + Math.abs(y) + Math.abs(z)
                fill++
            } else {
                if (average / BUFFER >= SENSITIVITY) {
                    handleAction(mShakeAction)
                }
                average = 0f
                fill = 0
            }
        }
    }

    private fun attachListeners() {
	if (mFlipAction !== ALARM_NO_ACTION) {
            mFlipListener.reset()
            mSensorManager.registerListener(mFlipListener,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                    SensorManager.SENSOR_DELAY_NORMAL,
                    300 * 1000) //batch every 300 milliseconds
        }
        if (mShakeAction !== ALARM_NO_ACTION) {
            mSensorManager.registerListener(mShakeListener,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                    SensorManager.SENSOR_DELAY_GAME,
                    50 * 1000) //batch every 50 milliseconds
        }
    }

    private fun detachListeners() {
        if (mFlipAction !== ALARM_NO_ACTION) {
            mSensorManager.unregisterListener(mFlipListener)
        }
        if (mShakeAction !== ALARM_NO_ACTION) {
            mSensorManager.unregisterListener(mShakeListener)
        }
    }

    private fun handleAction(action: Int) {
        when (action) {
            ALARM_SNOOZE ->                 // Setup Snooze Action
                startService(AlarmStateManager.createStateChangeIntent(this,
                        AlarmStateManager.ALARM_SNOOZE_TAG, mCurrentAlarm!!,
                        InstancesColumns.SNOOZE_STATE))
            ALARM_DISMISS ->                 // Setup Dismiss Action
                startService(AlarmStateManager.createStateChangeIntent(this,
                        AlarmStateManager.ALARM_DISMISS_TAG, mCurrentAlarm!!,
                        InstancesColumns.DISMISSED_STATE))
            ALARM_NO_ACTION -> {
            }
            else -> {
            }
        }
    }

    companion object {
        /**
         * AlarmActivity and AlarmService (when unbound) listen for this broadcast intent
         * so that other applications can snooze the alarm (after ALARM_ALERT_ACTION and before
         * ALARM_DONE_ACTION).
         */
        const val ALARM_SNOOZE_ACTION = "com.android.deskclock.ALARM_SNOOZE"

        /**
         * AlarmActivity and AlarmService listen for this broadcast intent so that other
         * applications can dismiss the alarm (after ALARM_ALERT_ACTION and before ALARM_DONE_ACTION).
         */
        const val ALARM_DISMISS_ACTION = "com.android.deskclock.ALARM_DISMISS"

        /** A public action sent by AlarmService when the alarm has started.  */
        const val ALARM_ALERT_ACTION = "com.android.deskclock.ALARM_ALERT"

        /** A public action sent by AlarmService when the alarm has stopped for any reason.  */
        const val ALARM_DONE_ACTION = "com.android.deskclock.ALARM_DONE"

        /** Private action used to stop an alarm with this service.  */
        const val STOP_ALARM_ACTION = "STOP_ALARM"

        // constants for no action/snooze/dismiss
        const val ALARM_NO_ACTION = 0
        const val ALARM_SNOOZE = 1
        const val ALARM_DISMISS = 2

        // default action for flip and shake
        const val DEFAULT_ACTION = ALARM_NO_ACTION.toString()
    
        /**
         * Utility method to help stop an alarm properly. Nothing will happen, if alarm is not firing
         * or using a different instance.
         *
         * @param context application context
         * @param instance you are trying to stop
         */
        @JvmStatic
        fun stopAlarm(context: Context, instance: AlarmInstance) {
            val intent: Intent =
                    AlarmInstance.createIntent(context, AlarmService::class.java, instance.mId)
                            .setAction(STOP_ALARM_ACTION)

            // We don't need a wake lock here, since we are trying to kill an alarm
            context.startService(intent)
        }
    }
}
