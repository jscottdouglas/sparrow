package com.sparrowwallet.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- formatting (shared by all screens) ----

internal fun formatLtc(litoshis: Long): String = "%.8f LTC".format(litoshis / 1e8)

internal fun formatUsd(litoshis: Long, price: Double?): String =
    price?.let { "$%,.2f".format(kotlin.math.abs(litoshis) / 1e8 * it) } ?: "—"

internal fun formatDate(unixSeconds: Long?): String =
    unixSeconds?.let { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US).format(Date(it * 1000)) } ?: "pending"

internal fun formatDay(unixSeconds: Long?): String =
    unixSeconds?.let { SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(it * 1000)) } ?: "pending"

internal val MonoFont = FontFamily.Monospace

// ---- transaction anatomy ----

internal enum class TxKind(val chip: String) {
    RECEIVED("RECEIVED"),
    SENT("SENT"),
    MOVE_PRIVATE("MOVE → PRIVATE"),
    MOVE_PUBLIC("MOVE → PUBLIC")
}

/** Everything the activity list and detail screen need about one transaction. */
internal data class TxInfo(
    val kind: TxKind,
    val label: String?,
    val timestamp: Long?,       // unix seconds, null/0 = pending
    val height: Int,            // <= 0 = unconfirmed
    val confirmations: Int?,    // null when unknown (mweb entries)
    val txid: String,           // txid, or outputId for live mweb coins
    val delta: Long,            // signed litoshis
    val mweb: Boolean
)

internal fun kindFor(delta: Long, label: String?): TxKind = when {
    label?.startsWith("Move to Private", ignoreCase = true) == true -> TxKind.MOVE_PRIVATE
    label?.startsWith("Move to Public", ignoreCase = true) == true -> TxKind.MOVE_PUBLIC
    delta >= 0 -> TxKind.RECEIVED
    else -> TxKind.SENT
}

@Composable
internal fun kindColor(kind: TxKind): Color {
    val dark = isSystemInDarkTheme()
    return when(kind) {
        TxKind.RECEIVED -> if(dark) LtcPalette.green else LtcPalette.greenLight
        TxKind.SENT -> MaterialTheme.colorScheme.onSurfaceVariant
        TxKind.MOVE_PRIVATE -> if(dark) LtcPalette.copperDark else LtcPalette.copperLight
        TxKind.MOVE_PUBLIC -> if(dark) LtcPalette.blueDark else LtcPalette.blueLight
    }
}

@Composable
internal fun TypeChip(kind: TxKind) {
    val color = kindColor(kind)
    Text(
        kind.chip,
        fontFamily = MonoFont, fontSize = 8.5.sp, letterSpacing = 0.8.sp,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** Small layer tag for wallet cards: PUBLIC (blue) or PRIVATE · MWEB (copper). */
@Composable
internal fun LayerTag(privateLayer: Boolean) {
    val dark = isSystemInDarkTheme()
    val color = if(privateLayer) (if(dark) LtcPalette.copperDark else LtcPalette.copperLight)
                else (if(dark) LtcPalette.blueDark else LtcPalette.blueLight)
    Text(
        if(privateLayer) "PRIVATE · MWEB" else "PUBLIC",
        fontFamily = MonoFont, fontSize = 8.5.sp, letterSpacing = 0.8.sp,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

/** The standard Back action: a real button, full width, same on every screen. */
@Composable
internal fun BackButton(enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) { Text("← Back") }
}

// ---- status pill ----

internal enum class PillState { OK, SCAN, IDLE }

@Composable
internal fun StatusPill(state: PillState, text: String) {
    val dot = when(state) {
        PillState.OK -> if(isSystemInDarkTheme()) LtcPalette.green else LtcPalette.greenLight
        PillState.SCAN -> LtcPalette.amber
        PillState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(text, fontFamily = MonoFont, fontSize = 10.sp,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A fixed-height slot for [StatusPill] so status changes never shift the layout. */
@Composable
internal fun StatusSlot(status: String?, state: PillState) {
    Box(modifier = Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) {
        status?.let { StatusPill(state, it) }
    }
}

// ---- layer switch (the core gesture) ----

@Composable
internal fun LayerSwitch(isPrivate: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(11.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        @Composable
        fun seg(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if(selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable(onClick = onClick)
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if(selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        seg("Public", !isPrivate, { onSelect(false) }, Modifier.weight(1f))
        seg("Private", isPrivate, { onSelect(true) }, Modifier.weight(1f))
    }
}

// ---- balance header with split meter ----

@Composable
internal fun BalanceHeader(
    eyebrow: String,
    total: Long?,
    usdNow: Double?,
    publicPart: Long? = null,
    privatePart: Long? = null,
    pending: Long? = null,
    fiatNote: String? = null
) {
    val dark = isSystemInDarkTheme()
    val blue = if(dark) LtcPalette.blueDark else LtcPalette.blueLight
    val copper = if(dark) LtcPalette.copperDark else LtcPalette.copperLight
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        Text(eyebrow.uppercase(), fontFamily = MonoFont, fontSize = 9.5.sp, letterSpacing = 1.3.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            total?.let { "%.8f".format(it / 1e8) } ?: "—",
            fontFamily = MonoFont, fontSize = 27.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            modifier = Modifier.padding(top = 3.dp)
        )
        Text(
            listOfNotNull(
                total?.let { "≈ ${formatUsd(it, usdNow)}" },
                usdNow?.let { "$%,.2f / LTC".format(it) },
                fiatNote
            ).joinToString(" · ").ifEmpty { " " },
            fontFamily = MonoFont, fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        if(publicPart != null && privatePart != null && publicPart + privatePart > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 9.dp).height(6.dp)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                if(publicPart > 0) Box(Modifier.weight(publicPart.toFloat()).height(6.dp).background(blue))
                if(privatePart > 0) Box(Modifier.weight(privatePart.toFloat()).height(6.dp).background(copper))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                @Composable
                fun legend(dot: Color, name: String, amount: Long, alignEnd: Boolean) {
                    Column(horizontalAlignment = if(alignEnd) Alignment.End else Alignment.Start) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                            Text("$name %.8f".format(amount / 1e8), fontFamily = MonoFont, fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        usdNow?.let {
                            Text(formatUsd(amount, it), fontFamily = MonoFont, fontSize = 9.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 1.dp))
                        }
                    }
                }
                legend(blue, "Public", publicPart, alignEnd = false)
                legend(copper, "Private", privatePart, alignEnd = true)
            }
        }
        pending?.takeIf { it != 0L }?.let {
            Text("${formatLtc(it)} pending", fontFamily = MonoFont, fontSize = 10.sp,
                color = LtcPalette.amber, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

// ---- activity row: banking-statement style — direction avatar, readable title,
// date line, prominent trimmed amount with fiat beneath; tap for full detail ----

internal fun kindTitle(kind: TxKind): String = when(kind) {
    TxKind.RECEIVED -> "Received"
    TxKind.SENT -> "Sent"
    TxKind.MOVE_PRIVATE -> "Moved to Private"
    TxKind.MOVE_PUBLIC -> "Moved to Public"
}

/** LTC amount without the trailing-zero noise: 0.02412456 stays, 0.50000000 → 0.5 */
internal fun formatLtcShort(litoshis: Long): String {
    val s = "%.8f".format(kotlin.math.abs(litoshis) / 1e8).trimEnd('0')
    return if(s.endsWith('.')) s + "0" else s
}

@Composable
internal fun ActivityRow(tx: TxInfo, usdNow: Double?, onClick: () -> Unit) {
    val color = kindColor(tx.kind)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                when(tx.kind) {
                    TxKind.RECEIVED -> "↓"
                    TxKind.SENT -> "↑"
                    else -> "⇄"
                },
                color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                tx.label?.takeIf { it.isNotEmpty() } ?: kindTitle(tx.kind),
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(
                    if(tx.height <= 0) "Pending" else formatDay(tx.timestamp),
                    kindTitle(tx.kind).takeIf { !tx.label.isNullOrEmpty() }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if(tx.delta >= 0) "+" else "−") + formatLtcShort(tx.delta),
                fontFamily = MonoFont, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if(tx.delta >= 0) kindColor(TxKind.RECEIVED)
                        else MaterialTheme.colorScheme.onSurface
            )
            Text(
                formatUsd(tx.delta, usdNow),
                fontFamily = MonoFont, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
    }
}
