package com.resonanse.hlwatchface

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.PassiveListenerConfig

/**
 * Live heart rate read straight from Health Services.
 *
 * The complication route cannot supply this. Samsung Health's heart rate
 * complication checks `ComplicationRequest.isForSafeWatchFace`, gets UNSAFE for a
 * side-loaded face, and answers with its own name instead of a BPM. Health
 * Services is a separate API with no such gate — it only needs BODY_SENSORS.
 *
 * Two sources feed one value:
 *
 *  - **Passive** ([ensureRegistered]) survives ambient, process death and reboot, and
 *    supplies a last-known reading the moment the face starts. On its own it updates
 *    far too rarely to feel live — four minutes of watching produced no batch at all.
 *  - **Live** ([startLive]) runs only while the face is awake and actually showing
 *    heart rate, refreshing within seconds of a wrist raise. It holds the optical
 *    sensor on, which is why it is stopped the moment the face goes ambient.
 */
object HeartRateSource {

    private const val PREFS   = "hl_heart_rate"
    private const val KEY_BPM = "bpm"
    private const val KEY_AT  = "at"

    /**
     * Backstop expiry for a reading. The primary signal is availability: the moment
     * the sensor reports the watch is off the wrist we drop the value outright, so the
     * face shows "--" instead of a number frozen from whenever it was last worn. This
     * window only covers the case where the sensor stops reporting without ever saying
     * why (ambient, for instance, where the live callback is deliberately stopped).
     */
    private const val STALE_AFTER_MS = 10 * 60_000L

    // -1 means "not yet loaded from disk"
    @Volatile private var lastBpm = -1
    @Volatile private var lastAt = 0L
    @Volatile private var registered = false

    private var measureCb: MeasureCallback? = null

    /**
     * Duty cycle for the optical sensor.
     *
     * Health Services exposes no sampling-rate control — `registerMeasureCallback`
     * takes only a data type — so the only way to sample less often is to run the
     * sensor in bursts. A burst has to outlast acquisition, measured on-watch at
     * 17-21 s, or it never produces a reading at all.
     *
     * While the face is being looked at the burst is held open, so once it locks on
     * the number ticks live in your hand. Once the screen goes dark the burst runs
     * down and the sensor sleeps until the next period.
     */
    private const val BURST_MS  = 30_000L
    private const val PERIOD_MS = 180_000L

    /**
     * How long to leave the sensor alone after the watch reports itself off the
     * wrist. Without this the renderer would re-register every frame against a wrist
     * that is not there — on a charger, all night.
     */
    private const val OFF_BODY_BACKOFF_MS = 60_000L

    @Volatile private var burstStartedAt = 0L
    @Volatile private var suppressUntil = 0L

    /**
     * Drives the duty cycle. Called every frame, so it must stay cheap and idempotent.
     * [faceVisible] is false in ambient.
     */
    fun tick(ctx: Context, faceVisible: Boolean) {
        val now = System.currentTimeMillis()
        if (now < suppressUntil) {
            stopLive(ctx)
            return
        }
        if (measureCb != null) {
            // Hold the burst open while the user is actually looking at the face.
            if (!faceVisible && now - burstStartedAt > BURST_MS) stopLive(ctx)
        } else if (faceVisible || now - burstStartedAt >= PERIOD_MS) {
            burstStartedAt = now
            startLive(ctx)
        }
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.BODY_SENSORS) ==
            PackageManager.PERMISSION_GRANTED

    /** Latest heart rate, or 0 when there is no usable reading. */
    fun bpm(ctx: Context): Int {
        if (lastBpm < 0) {
            // The listener can deliver while this process is dead, so the reading is
            // persisted and read back here on first use.
            val p = prefs(ctx)
            lastBpm = p.getInt(KEY_BPM, 0)
            lastAt = p.getLong(KEY_AT, 0L)
        }
        val usable = lastBpm > 0 && System.currentTimeMillis() - lastAt < STALE_AFTER_MS
        return if (usable) lastBpm else 0
    }

    internal fun record(ctx: Context, value: Int) {
        lastBpm = value
        lastAt = System.currentTimeMillis()
        prefs(ctx).edit().putInt(KEY_BPM, value).putLong(KEY_AT, lastAt).apply()
    }

    internal fun clear(ctx: Context) {
        lastBpm = 0
        lastAt = 0L
        prefs(ctx).edit().clear().apply()
    }

    /**
     * Idempotent. The subscription lives in Health Services, not in this process, so
     * registering once is enough — it outlives ambient, process death and reboot.
     */
    fun ensureRegistered(ctx: Context) {
        if (registered || !hasPermission(ctx)) return
        registered = true
        try {
            val config = PassiveListenerConfig.builder()
                .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                .build()
            HealthServices.getClient(ctx).passiveMonitoringClient
                .setPassiveListenerServiceAsync(HeartRateListenerService::class.java, config)
        } catch (e: Exception) {
            registered = false
        }
    }

    fun startLive(ctx: Context) {
        if (measureCb != null) return
        if (!hasPermission(ctx)) return
        val app = ctx.applicationContext
        val cb = object : MeasureCallback {
            override fun onAvailabilityChanged(
                dataType: DeltaDataType<*, *>,
                availability: Availability
            ) {
                if (availability !is DataTypeAvailability) return
                // Only OFF_BODY clears. Every fresh registration reports plain
                // UNAVAILABLE first, then ACQUIRING, before it ever produces a reading
                // — treating that as "not worn" wiped the value on every wrist raise
                // and left dashes behind whenever the screen slept before the sensor
                // re-locked. OFF_BODY is the only availability that actually means the
                // watch is not being worn; the staleness window covers everything else.
                if (availability == DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY) {
                    // Nothing to measure against a wrist that is not there: drop the
                    // reading and let the sensor sleep before trying again.
                    clear(app)
                    suppressUntil = System.currentTimeMillis() + OFF_BODY_BACKOFF_MS
                }
            }

            override fun onDataReceived(data: DataPointContainer) {
                // Health Services reports 0 when it cannot get a lock on a pulse.
                data.getData(DataType.HEART_RATE_BPM)
                    .lastOrNull { it.value > 0 }
                    ?.let { record(app, it.value.toInt()) }
            }
        }
        measureCb = cb
        try {
            HealthServices.getClient(ctx).measureClient
                .registerMeasureCallback(DataType.HEART_RATE_BPM, cb)
        } catch (e: Exception) {
            measureCb = null
        }
    }

    fun stopLive(ctx: Context) {
        val cb = measureCb ?: return
        measureCb = null
        try {
            HealthServices.getClient(ctx).measureClient
                .unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, cb)
        } catch (e: Exception) {
            // the callback reference is already dropped
        }
    }
}
