package com.resonanse.hlwatchface

import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType

/**
 * Receives passive heart rate updates from Health Services and parks the newest
 * reading in [HeartRateSource]. Bound by the platform, so it is declared exported
 * and guarded with the Health Services binding permission.
 */
class HeartRateListenerService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        // Health Services reports 0 when it could not get a lock on a pulse.
        dataPoints.getData(DataType.HEART_RATE_BPM)
            .lastOrNull { it.value > 0 }
            ?.let { HeartRateSource.record(applicationContext, it.value.toInt()) }
    }

    override fun onPermissionLost() {
        HeartRateSource.clear(applicationContext)
    }
}
