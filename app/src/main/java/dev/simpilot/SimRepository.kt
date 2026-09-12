package dev.simpilot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

class SimRepository(
    private val context: Context,
    private val executor: Executor,
    private val onRadioChanged: ((event: String, subId: Int) -> Unit)? = null,
) {
    private val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private val callbacks = ConcurrentHashMap<Int, LineCallback>()
    private val observations = ConcurrentHashMap<Int, Observation>()

    private data class Observation(
        val level: Int = -1,
        val dbm: Int? = null,
        val inService: Boolean = false,
        val serviceStateKnown: Boolean = false,
        val networkType: String = "—",
        val dataConnectionState: Int = -1,
        val callState: Int = -1,
    )

    private inner class LineCallback(private val subId: Int) : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.DisplayInfoListener,
        TelephonyCallback.DataConnectionStateListener,
        TelephonyCallback.CallStateListener {

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            val dbm = if (Build.VERSION.SDK_INT >= 29) {
                signalStrength.cellSignalStrengths.firstOrNull()?.dbm
            } else null
            var levelChanged = false
            observations.compute(subId) { _, old ->
                val next = (old ?: Observation()).copy(level = signalStrength.level, dbm = dbm)
                levelChanged = old == null || old.level != next.level
                next
            }
            publish()
            if (levelChanged) onRadioChanged?.invoke("signal_level", subId)
        }

        override fun onServiceStateChanged(serviceState: ServiceState) {
            var stateChanged = false
            observations.compute(subId) { _, old ->
                val next = (old ?: Observation()).copy(
                    inService = serviceState.state == ServiceState.STATE_IN_SERVICE,
                    serviceStateKnown = true,
                )
                stateChanged = old == null || old.inService != next.inService || !old.serviceStateKnown
                next
            }
            publish()
            if (stateChanged) onRadioChanged?.invoke("service_state", subId)
        }

        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
            var typeChanged = false
            observations.compute(subId) { _, old ->
                val next = (old ?: Observation()).copy(networkType = networkLabel(info))
                typeChanged = old == null || old.networkType != next.networkType
                next
            }
            publish()
            if (typeChanged) onRadioChanged?.invoke("network_type", subId)
        }

        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            var stateChanged = false
            observations.compute(subId) { _, old ->
                val next = (old ?: Observation()).copy(dataConnectionState = state)
                stateChanged = old == null || old.dataConnectionState != next.dataConnectionState
                next
            }
            if (stateChanged) onRadioChanged?.invoke("data_connection", subId)
        }

        override fun onCallStateChanged(state: Int) {
            var stateChanged = false
            observations.compute(subId) { _, old ->
                val next = (old ?: Observation()).copy(callState = state)
                stateChanged = old == null || old.callState != next.callState
                next
            }
            if (stateChanged) onRadioChanged?.invoke("call_state", subId)
        }
    }

    fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun refresh(): List<SimLine> {
        if (!hasPhonePermission()) {
            AppState.update { it.copy(lines = emptyList(), status = "電話の権限が必要です") }
            return emptyList()
        }
        val infos = runCatching { subscriptionManager.activeSubscriptionInfoList }.getOrNull().orEmpty()
            .sortedBy { it.simSlotIndex }
        val activeIds = infos.map { it.subscriptionId }.toSet()
        callbacks.keys.filterNot(activeIds::contains).forEach { subId ->
            callbacks.remove(subId)?.let { telephonyManager.createForSubscriptionId(subId).unregisterTelephonyCallback(it) }
            observations.remove(subId)
        }
        infos.forEach { info ->
            callbacks.computeIfAbsent(info.subscriptionId) { subId ->
                LineCallback(subId).also {
                    runCatching {
                        telephonyManager.createForSubscriptionId(subId).registerTelephonyCallback(executor, it)
                    }
                }
            }
        }
        val lines = infos.map { info ->
            val o = observations[info.subscriptionId] ?: Observation()
            SimLine(
                subId = info.subscriptionId,
                slotIndex = info.simSlotIndex,
                displayName = info.displayName?.toString().orEmpty(),
                carrierName = info.carrierName?.toString().orEmpty(),
                signalLevel = o.level,
                dbm = o.dbm,
                inService = o.inService,
                serviceStateKnown = o.serviceStateKnown,
                networkType = o.networkType,
            )
        }
        AppState.update {
            it.copy(
                lines = lines,
                dataSubId = SubscriptionManager.getDefaultDataSubscriptionId(),
                voiceSubId = SubscriptionManager.getDefaultVoiceSubscriptionId(),
                smsSubId = SubscriptionManager.getDefaultSmsSubscriptionId(),
                shizukuReady = ShizukuBridge.isReady(),
                shizukuGranted = ShizukuBridge.isGranted(),
            )
        }
        return lines
    }

    fun unregister() {
        callbacks.forEach { (subId, callback) ->
            runCatching { telephonyManager.createForSubscriptionId(subId).unregisterTelephonyCallback(callback) }
        }
        callbacks.clear()
    }

    private fun publish() = refresh()

    private fun networkLabel(info: TelephonyDisplayInfo): String = when (info.overrideNetworkType) {
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G+"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE -> "5G"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "4G+"
        else -> when (info.networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            else -> "—"
        }
    }
}
