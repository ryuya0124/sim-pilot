package dev.simpilot

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import cz.mroczis.netmonster.core.factory.NetMonsterFactory
import cz.mroczis.netmonster.core.model.cell.CellCdma
import cz.mroczis.netmonster.core.model.cell.CellGsm
import cz.mroczis.netmonster.core.model.cell.CellLte
import cz.mroczis.netmonster.core.model.cell.CellNr
import cz.mroczis.netmonster.core.model.cell.CellTdscdma
import cz.mroczis.netmonster.core.model.cell.CellWcdma
import cz.mroczis.netmonster.core.model.cell.ICell
import cz.mroczis.netmonster.core.model.connection.NoneConnection
import cz.mroczis.netmonster.core.model.connection.PrimaryConnection
import cz.mroczis.netmonster.core.model.connection.SecondaryConnection
import cz.mroczis.netmonster.core.model.band.BandGsm
import cz.mroczis.netmonster.core.model.band.BandLte
import cz.mroczis.netmonster.core.model.band.BandNr
import cz.mroczis.netmonster.core.model.band.BandTdscdma
import cz.mroczis.netmonster.core.model.band.BandWcdma
import kotlin.math.roundToInt

/** Reads NetMonster on a worker thread and converts its validated models into app-owned data. */
internal class NetMonsterRadioSource(context: Context) {
    private val netMonster by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NetMonsterFactory.get(context.applicationContext)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun read(activeSubIds: Set<Int>): Result<Map<Int, RadioMetrics>> = runCatching {
        if (activeSubIds.isEmpty()) return@runCatching emptyMap()
        val allCells = netMonster.getCells()
        val cells = allCells.filter { it.subscriptionId in activeSubIds }
        activeSubIds.mapNotNull { subId ->
            val forSubscription = cells.filter { it.subscriptionId == subId }
            toMetrics(forSubscription)?.let { subId to it }
        }.toMap()
    }

    private fun toMetrics(cells: List<ICell>): RadioMetrics? {
        if (cells.isEmpty()) return null
        val serving = cells.filterNot { it.connectionStatus is NoneConnection }
        val primary = serving.filter { it.connectionStatus is PrimaryConnection }
            .maxByOrNull(::technologyRank)
            ?: serving.maxByOrNull(::technologyRank)
            ?: cells.maxByOrNull(::technologyRank)
            ?: return null
        val secondary = serving.filter { it.connectionStatus is SecondaryConnection }
        val lte = primary as? CellLte
        val hasServingNr = serving.any { it is CellNr }
        val hasServingLte = serving.any { it is CellLte }
        val aggregatedBands = buildList {
            lte?.aggregatedBands?.forEach { band ->
                add(band.number?.let { "B$it" } ?: band.name)
            }
            secondary.forEach { cell ->
                cell.band?.let { band ->
                    add(band.number?.let { number -> if (cell is CellNr) "n$number" else "B$number" } ?: band.name.orEmpty())
                }
            }
        }.filter(String::isNotBlank).distinct()
        val technology = when {
            primary is CellNr && hasServingLte -> "5G NSA"
            primary is CellNr -> "5G SA"
            primary is CellLte && hasServingNr -> "5G NSA"
            primary is CellLte && (aggregatedBands.isNotEmpty() || secondary.any { it is CellLte }) -> "4G+"
            primary is CellLte -> "4G"
            primary is CellWcdma -> "3G"
            primary is CellTdscdma -> "3G TD-SCDMA"
            primary is CellGsm -> "2G"
            primary is CellCdma -> "CDMA"
            else -> "—"
        }
        val identity = identity(primary)
        return RadioMetrics(
            technology = technology,
            primaryConnected = primary.connectionStatus is PrimaryConnection,
            servingCellCount = serving.size,
            neighboringCellCount = cells.count { it.connectionStatus is NoneConnection },
            secondaryCellCount = secondary.size,
            bandNumber = primary.band?.number,
            bandName = primary.band?.name,
            channelNumber = primary.band?.channelNumber,
            aggregatedBands = aggregatedBands,
            bandwidthKhz = lte?.bandwidth,
            referenceDbm = referenceDbm(primary),
            rssiDbm = rssiDbm(primary),
            rsrpDbm = rsrpDbm(primary),
            rsrqDb = rsrqDb(primary),
            sinrDb = sinrDb(primary),
            cqi = lte?.signal?.cqi,
            timingAdvance = timingAdvance(primary),
            pci = identity.pci,
            areaCode = identity.areaCode,
            cellId = identity.cellId,
            cells = cells
                .sortedWith(compareBy<ICell>({ connectionRank(it) }, { -technologyRank(it) }))
                .map(::toCellObservation),
            observedAtElapsed = SystemClock.elapsedRealtime(),
        )
    }

    private fun connectionRank(cell: ICell): Int = when (cell.connectionStatus) {
        is PrimaryConnection -> 0
        is SecondaryConnection -> 1
        else -> 2
    }

    private fun toCellObservation(cell: ICell): RadioCellObservation {
        val connection = when (cell.connectionStatus) {
            is PrimaryConnection -> "Primary"
            is SecondaryConnection -> "Secondary"
            else -> "Neighbor"
        }
        return RadioCellObservation(
            technology = cellTechnology(cell),
            connection = connection,
            connectionInferred = (cell.connectionStatus as? SecondaryConnection)?.isGuess == true,
            network = fields {
                cell.network?.let { network ->
                    add("plmn", "PLMN", listOf(network.mcc, network.mnc).joinToString(""))
                    add("mcc", "MCC", network.mcc)
                    add("mnc", "MNC", network.mnc)
                    add("iso", "国コード", network.iso)
                }
            },
            band = bandFields(cell),
            identity = identityFields(cell),
            signal = signalFields(cell),
            sourceTimestamp = cell.timestamp,
        )
    }

    private fun cellTechnology(cell: ICell): String = when (cell) {
        is CellNr -> "NR"
        is CellLte -> "LTE"
        is CellWcdma -> "WCDMA"
        is CellTdscdma -> "TD-SCDMA"
        is CellGsm -> "GSM"
        is CellCdma -> "CDMA"
        else -> cell::class.java.simpleName
    }

    private fun bandFields(cell: ICell): List<RadioField> = fields {
        cell.band?.let { band ->
            val prefix = if (cell is CellNr) "n" else if (band.number != null) "B" else ""
            add("band", "バンド", band.number?.let { "$prefix$it" } ?: band.name)
            add("bandNumber", "バンド番号", band.number)
            add("bandName", "バンド名", band.name)
            add("channel", "チャンネル", band.channelNumber)
            when (band) {
                is BandNr -> {
                    add("downlinkArfcn", "NR-ARFCN", band.downlinkArfcn)
                    add("downlinkFrequencyKhz", "下り周波数 (kHz)", band.downlinkFrequency)
                }
                is BandLte -> add("downlinkEarfcn", "EARFCN", band.downlinkEarfcn)
                is BandWcdma -> add("downlinkUarfcn", "UARFCN", band.downlinkUarfcn)
                is BandTdscdma -> add("downlinkUarfcn", "UARFCN", band.downlinkUarfcn)
                is BandGsm -> add("arfcn", "ARFCN", band.arfcn)
            }
        }
        if (cell is CellLte) {
            add("bandwidthKhz", "帯域幅 (kHz)", cell.bandwidth)
            add(
                "aggregatedBands",
                "CAバンド",
                cell.aggregatedBands.mapNotNull { aggregated ->
                    aggregated.number?.let { "B$it" } ?: aggregated.name
                }.joinToString(" + ").ifBlank { null },
            )
        }
    }

    private fun identityFields(cell: ICell): List<RadioField> = fields {
        when (cell) {
            is CellLte -> {
                add("eci", "ECI", cell.eci)
                add("enb", "eNodeB", cell.enb)
                add("cid", "Cell ID", cell.cid)
                add("tac", "TAC", cell.tac)
                add("pci", "PCI", cell.pci)
                add("ecgi", "ECGI", cell.ecgi)
            }
            is CellNr -> {
                add("nci", "NCI", cell.nci)
                add("tac", "TAC", cell.tac)
                add("pci", "PCI", cell.pci)
            }
            is CellWcdma -> {
                add("ci", "CI", cell.ci)
                add("cid", "Cell ID", cell.cid)
                add("rnc", "RNC", cell.rnc)
                add("lac", "LAC", cell.lac)
                add("psc", "PSC", cell.psc)
                add("cgi", "CGI", cell.cgi)
            }
            is CellTdscdma -> {
                add("ci", "CI", cell.ci)
                add("cid", "Cell ID", cell.cid)
                add("rnc", "RNC", cell.rnc)
                add("lac", "LAC", cell.lac)
                add("cpid", "CPID", cell.cpid)
                add("cgi", "CGI", cell.cgi)
            }
            is CellGsm -> {
                add("cid", "Cell ID", cell.cid)
                add("lac", "LAC", cell.lac)
                add("bsic", "BSIC", cell.bsic)
                add("ncc", "NCC", cell.ncc)
                add("bcc", "BCC", cell.bcc)
                add("cgi", "CGI", cell.cgi)
            }
            is CellCdma -> {
                add("sid", "SID", cell.sid)
                add("nid", "NID", cell.nid)
                add("bid", "BID", cell.bid)
                add("latitude", "基地局緯度", cell.lat)
                add("longitude", "基地局経度", cell.lon)
            }
        }
    }

    private fun signalFields(cell: ICell): List<RadioField> = fields {
        when (cell) {
            is CellLte -> with(cell.signal) {
                add("dbm", "代表値", dbm, "dBm")
                add("rssi", "RSSI", rssi, "dBm")
                add("rsrp", "RSRP", rsrp, "dBm")
                add("rsrq", "RSRQ", rsrq, "dB")
                add("snr", "SNR / SINR", snr, "dB")
                add("cqi", "CQI", cqi)
                add("timingAdvance", "Timing Advance", timingAdvance)
                add("rssiAsu", "RSSI ASU", rssiAsu)
                add("rsrpAsu", "RSRP ASU", rsrpAsu)
            }
            is CellNr -> with(cell.signal) {
                add("dbm", "代表値", dbm, "dBm")
                add("ssRsrp", "SS-RSRP", ssRsrp, "dBm")
                add("ssRsrq", "SS-RSRQ", ssRsrq, "dB")
                add("ssSinr", "SS-SINR", ssSinr, "dB")
                add("csiRsrp", "CSI-RSRP", csiRsrp, "dBm")
                add("csiRsrq", "CSI-RSRQ", csiRsrq, "dB")
                add("csiSinr", "CSI-SINR", csiSinr, "dB")
                add("timingAdvance", "Timing Advance", timingAdvance)
                add("ssRsrpAsu", "SS-RSRP ASU", ssRsrpAsu)
                add("csiRsrpAsu", "CSI-RSRP ASU", csiRsrpAsu)
            }
            is CellWcdma -> with(cell.signal) {
                add("dbm", "代表値", dbm, "dBm")
                add("rssi", "RSSI", rssi, "dBm")
                add("rscp", "RSCP", rscp, "dBm")
                add("ecno", "Ec/No", ecno, "dB")
                add("ecio", "Ec/Io", ecio, "dB")
                add("bitErrorRate", "BER", bitErrorRate)
                add("rscpAsu", "RSCP ASU", rscpAsu)
                add("rssiAsu", "RSSI ASU", rssiAsu)
            }
            is CellTdscdma -> with(cell.signal) {
                add("dbm", "代表値", dbm, "dBm")
                add("rssi", "RSSI", rssi, "dBm")
                add("rscp", "RSCP", rscp, "dBm")
                add("bitErrorRate", "BER", bitErrorRate)
                add("rscpAsu", "RSCP ASU", rscpAsu)
                add("rssiAsu", "RSSI ASU", rssiAsu)
            }
            is CellGsm -> with(cell.signal) {
                add("dbm", "代表値", dbm, "dBm")
                add("rssi", "RSSI", rssi, "dBm")
                add("bitErrorRate", "BER", bitErrorRate)
                add("timingAdvance", "Timing Advance", timingAdvance)
                add("asu", "ASU", asu)
                add("distanceToCellMeters", "推定距離", getDistanceToCell(), "m")
            }
            is CellCdma -> with(cell.signal) {
                add("dbm", "代表値", dbm, "dBm")
                add("cdmaRssi", "CDMA RSSI", cdmaRssi, "dBm")
                add("cdmaEcio", "CDMA Ec/Io", cdmaEcio, "dB")
                add("evdoRssi", "EVDO RSSI", evdoRssi, "dBm")
                add("evdoEcio", "EVDO Ec/Io", evdoEcio, "dB")
                add("evdoSnr", "EVDO SNR", evdoSnr, "dB")
            }
        }
    }

    private fun fields(block: RadioFieldBuilder.() -> Unit): List<RadioField> =
        RadioFieldBuilder().apply(block).values

    private class RadioFieldBuilder {
        val values = mutableListOf<RadioField>()

        fun add(key: String, label: String, value: Any?, unit: String? = null) {
            if (value == null || value.toString().isBlank()) return
            values += RadioField(key, label, value.toString() + unit?.let { " $it" }.orEmpty())
        }
    }

    private fun technologyRank(cell: ICell): Int = when (cell) {
        is CellNr -> 6
        is CellLte -> 5
        is CellTdscdma -> 4
        is CellWcdma -> 3
        is CellCdma -> 2
        is CellGsm -> 1
        else -> 0
    }

    private fun referenceDbm(cell: ICell): Int? = when (cell) {
        is CellNr -> (cell.signal.ssRsrp ?: cell.signal.csiRsrp)
        is CellLte -> cell.signal.rsrp?.roundToInt() ?: cell.signal.rssi
        is CellWcdma -> cell.signal.rscp ?: cell.signal.rssi
        is CellTdscdma -> cell.signal.rscp ?: cell.signal.rssi
        else -> cell.signal?.dbm
    }

    private fun rssiDbm(cell: ICell): Int? = when (cell) {
        is CellLte -> cell.signal.rssi
        is CellWcdma -> cell.signal.rssi
        is CellTdscdma -> cell.signal.rssi
        is CellGsm -> cell.signal.rssi
        is CellCdma -> cell.signal.dbm
        else -> null
    }

    private fun rsrpDbm(cell: ICell): Double? = when (cell) {
        is CellLte -> cell.signal.rsrp
        is CellNr -> (cell.signal.ssRsrp ?: cell.signal.csiRsrp)?.toDouble()
        else -> null
    }

    private fun rsrqDb(cell: ICell): Double? = when (cell) {
        is CellLte -> cell.signal.rsrq
        is CellNr -> (cell.signal.ssRsrq ?: cell.signal.csiRsrq)?.toDouble()
        else -> null
    }

    private fun sinrDb(cell: ICell): Double? = when (cell) {
        is CellLte -> cell.signal.snr
        is CellNr -> (cell.signal.ssSinr ?: cell.signal.csiSinr)?.toDouble()
        is CellCdma -> cell.signal.evdoSnr?.toDouble()
        else -> null
    }

    private fun timingAdvance(cell: ICell): Int? = when (cell) {
        is CellLte -> cell.signal.timingAdvance
        is CellNr -> cell.signal.timingAdvance
        is CellGsm -> cell.signal.timingAdvance
        else -> null
    }

    private fun identity(cell: ICell): CellIdentity = when (cell) {
        is CellLte -> CellIdentity(cell.pci, cell.tac?.toLong(), cell.eci?.toLong())
        is CellNr -> CellIdentity(cell.pci, cell.tac?.toLong(), cell.nci)
        is CellWcdma -> CellIdentity(cell.psc, cell.lac?.toLong(), cell.ci?.toLong())
        is CellTdscdma -> CellIdentity(cell.cpid, cell.lac?.toLong(), cell.ci?.toLong())
        is CellGsm -> CellIdentity(cell.bsic, cell.lac?.toLong(), cell.cid?.toLong())
        is CellCdma -> CellIdentity(null, cell.nid?.toLong(), cell.bid?.toLong())
        else -> CellIdentity(null, null, null)
    }

    private data class CellIdentity(val pci: Int?, val areaCode: Long?, val cellId: Long?)

}
