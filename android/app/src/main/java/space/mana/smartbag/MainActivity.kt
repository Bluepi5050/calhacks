package space.mana.smartbag

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.text.DateFormat
import java.util.Date
import java.util.Locale

val Bg = Color(0xFFFAFAF9)
val Ink = Color(0xFF18181B)
val Muted = Color(0xFF71717A)
val Faint = Color(0xFFA1A1AA)
val Hair = Color(0xFFE4E4E7)
val Accent = Color(0xFFE8654A)
val Good = Color(0xFF1F9D63)
val Bad = Color(0xFFD64545)
val Mono = FontFamily.Monospace

fun money(c: Int): String = "\$" + String.format(Locale.US, "%.2f", c / 100.0)
fun distance(m: Int): String = if (m < 1000) "$m m" else String.format(Locale.US, if (m < 10_000) "%.1f km" else "%.0f km", m / 1000.0)

class MainActivity : ComponentActivity() {
    private val model: ShopModel get() = (application as SmartBagApp).shop
    private var granted by mutableStateOf(false)

    private val permissionAsk = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions()
        if (granted) { startLocation(); connectBag() }
    }

    // Notifications are optional (receipts still show in the app), so they are asked for but not required.
    private fun askList(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) permissions() + Manifest.permission.POST_NOTIFICATIONS else permissions()
    private val bluetoothAsk = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (model.link.isEnabled) model.link.connect()
    }

    private fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasPermissions() = permissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun startLocation() {
        if (!model.location.enabled) model.locationUnavailable()
        model.location.start()
        WatchService.start(this)   // keeps reporting with the screen off: walking out is what pays
    }

    private fun connectBag() {
        if (!model.link.hasBluetooth) { model.link.connect(); return }
        if (!model.link.isEnabled) {
            try { bluetoothAsk.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) { model.link.connect() }
        } else model.link.connect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        granted = hasPermissions()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Bg, surface = Color.White, onSurface = Ink, onBackground = Ink)) {
                if (!granted) Welcome { permissionAsk.launch(askList()) }
                else Shop(model, onConnect = ::connectBag, onLocationSettings = {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                })
            }
        }
        if (granted && savedInstanceState == null) { startLocation(); connectBag() }
    }

    override fun onResume() {
        super.onResume()
        if (granted) {
            if (model.location.enabled && model.store is StoreState.NoLocation) model.refreshStore()
            startLocation()
        }
    }
}

// ---------- shared pieces

@Composable
fun BagMark(size: Int = 28) {
    Image(painterResource(R.drawable.bag_mark), contentDescription = null, modifier = Modifier.size(size.dp))
}

@Composable
fun Caps(text: String, color: Color = Muted) {
    Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 1.8.sp, color = color, fontWeight = FontWeight.Medium)
}

@Composable
fun Pill(text: String, enabled: Boolean = true, filled: Boolean = true, modifier: Modifier = Modifier, busy: Boolean = false, onClick: () -> Unit) {
    val bg = if (filled) (if (enabled) Accent else Accent.copy(alpha = 0.35f)) else Color.White
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (filled) Modifier else Modifier.border(1.dp, Hair, RoundedCornerShape(50)))
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center
    ) {
        if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        else Text(text, color = if (filled) Color.White else Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Dashed() {
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(Hair, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
    }
}

// ---------- welcome

@Composable
fun Welcome(onStart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Ink), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(96.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text("Skip the checkout line", fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(12.dp))
        Text("Drop groceries in your SmartBag and just walk out. Your card is charged the moment you leave the store.", fontSize = 16.sp, lineHeight = 23.sp, color = Muted)
        Spacer(Modifier.height(32.dp))
        Need("Bluetooth", "so your bag can tell us what's inside")
        Spacer(Modifier.height(14.dp))
        Need("Location", "so we know which store you're in, and when you've left")
        Spacer(Modifier.height(40.dp))
        Pill("Get started", modifier = Modifier.fillMaxWidth(), onClick = onStart)
    }
}

@Composable
fun Need(title: String, why: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(Accent))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
            Text(why, fontSize = 14.sp, color = Muted)
        }
    }
}

// ---------- shop

@Composable
fun Shop(m: ShopModel, onConnect: () -> Unit, onLocationSettings: () -> Unit) {
    val paid = m.paid
    if (paid != null) {
        BackHandler { m.finishReceipt() }
        ReceiptScreen(paid) { m.finishReceipt() }
        return
    }
    val inStore = m.store as? StoreState.InStore
    val lines = m.lines.toList()

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
                TopRow(m, onConnect)
                Spacer(Modifier.height(26.dp))
                StoreHeader(m, onLocationSettings)
                Spacer(Modifier.height(22.dp))
                Receipt(m, lines, inStore, m.total, m.count)
                Spacer(Modifier.height(24.dp))
            }
            if (lines.isNotEmpty() || inStore != null) WalkOutBar(m, inStore, lines)
        }
    }
}

/** There is no Pay button: the bag is charged by the database when the phone leaves the store. */
@Composable
fun WalkOutBar(m: ShopModel, inStore: StoreState.InStore?, lines: List<Line>) {
    val leaving = inStore?.leaving == true
    val unsold = if (inStore != null) lines.filter { !it.sold }.sumOf { it.qty } else 0
    Column(Modifier.fillMaxWidth().background(Color.White).border(width = 1.dp, color = Hair).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (leaving) Accent else if (inStore != null) Good else Faint))
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    leaving -> "Leaving ${inStore!!.name}…"
                    inStore != null && m.count > 0 -> "Just walk out"
                    inStore != null -> "Fill your bag, then just walk out"
                    else -> "Walk into a SmartBag store to shop"
                },
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f)
            )
            if (m.count > 0) Text(money(m.total), fontFamily = Mono, fontSize = 20.sp, color = Ink)
        }
        Spacer(Modifier.height(6.dp))
        val pay = m.payment.ifEmpty { "your card" }
        Text(
            when {
                leaving -> "Charging ${money(m.total)} to $pay as you leave."
                inStore != null && m.count > 0 -> "We'll charge $pay when you leave ${inStore.name}." + if (unsold > 0) " Items this store doesn't sell aren't charged." else ""
                inStore != null -> "Paying with $pay. No checkout, no scanning."
                else -> "Items in your bag are priced by the store you're in."
            },
            fontSize = 13.sp, lineHeight = 18.sp, color = Muted
        )
        m.syncError?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 13.sp, color = Bad) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopRow(m: ShopModel, onConnect: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val b = m.bag
    val (label, dot) = when (b) {
        is BagState.Connected -> b.name to Good
        is BagState.Connecting -> "Connecting…" to Accent
        BagState.Searching -> "Looking for your bag…" to Accent
        is BagState.Problem -> "Connect bag" to Bad
        BagState.Off -> "Connect bag" to Faint
    }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BagMark(24)
            Spacer(Modifier.width(8.dp))
            Text("SmartBag", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, Hair, RoundedCornerShape(50))
                    .background(Color.White)
                    .combinedClickable(
                        onClick = { if (b !is BagState.Connected && b !is BagState.Connecting && b != BagState.Searching) onConnect() },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); m.demoDrop() }
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(label, fontSize = 13.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
            }
        }
        if (b is BagState.Problem) {
            Spacer(Modifier.height(8.dp))
            Text(b.message, fontSize = 13.sp, color = Bad, modifier = Modifier.align(Alignment.End))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StoreHeader(m: ShopModel, onLocationSettings: () -> Unit) {
    when (val s = m.store) {
        StoreState.Locating -> {
            Caps("Finding your store")
            Spacer(Modifier.height(6.dp))
            Text("One moment…", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Faint)
        }
        StoreState.NoLocation -> {
            Caps("Location is off", Bad)
            Spacer(Modifier.height(6.dp))
            Text("Turn on location so we can find your store", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(10.dp))
            Text("Open settings", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onLocationSettings))
        }
        is StoreState.InStore -> {
            val haptic = LocalHapticFeedback.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (s.leaving) Accent else Good))
                Spacer(Modifier.width(8.dp))
                Caps(if (s.leaving) "Leaving" else "You're shopping at", if (s.leaving) Accent else Good)
            }
            Spacer(Modifier.height(6.dp))
            // Hidden demo aid: long-press the store name to simulate walking out.
            Text(s.name, fontSize = 28.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold, color = Ink,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); m.demoWalkOut() }))
            if (s.address.isNotBlank()) Text(s.address, fontSize = 14.sp, color = Muted)
        }
        is StoreState.Away -> {
            Caps("Not in a SmartBag store")
            Spacer(Modifier.height(6.dp))
            Text("Walk into a SmartBag store to start shopping", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            if (s.nearestName != null && s.nearestDistance != null) {
                Spacer(Modifier.height(6.dp))
                Text("Nearest: ${s.nearestName} · ${distance(s.nearestDistance)}", fontSize = 14.sp, color = Muted)
            }
        }
        is StoreState.Offline -> {
            Caps("Offline", Bad)
            Spacer(Modifier.height(6.dp))
            Text("Can't reach SmartBag", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(s.message, fontSize = 14.sp, color = Muted)
            Spacer(Modifier.height(10.dp))
            Text("Try again", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { m.refreshStore() })
        }
    }
}

@Composable
fun Receipt(m: ShopModel, lines: List<Line>, inStore: StoreState.InStore?, total: Int, count: Int) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).border(1.dp, Hair, RoundedCornerShape(20.dp))
    ) {
        if (lines.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BagMark(44)
                Spacer(Modifier.height(14.dp))
                Text("Your bag is empty", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink)
                Spacer(Modifier.height(4.dp))
                Text("Drop groceries in. They show up here with this store's prices, and you pay by walking out.", fontSize = 14.sp, lineHeight = 20.sp, color = Muted, textAlign = TextAlign.Center)
            }
            return@Column
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            lines.forEach { l -> key(l.sku) { LineRow(m, l, inStore) } }
        }
        Box(Modifier.padding(horizontal = 20.dp)) { Dashed() }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.Bottom) {
            Caps("$count item" + if (count == 1) "" else "s")
            Spacer(Modifier.weight(1f))
            Text(money(total), fontFamily = Mono, fontSize = 24.sp, color = Ink)
        }
    }
}

@Composable
fun LineRow(m: ShopModel, l: Line, inStore: StoreState.InStore?) {
    val glow = remember { Animatable(0f) }
    val f = m.flash
    val flashKey = if (f != null && f.first == l.sku) f.second else -1
    LaunchedEffect(flashKey) {
        if (flashKey >= 0) { glow.snapTo(1f); glow.animateTo(0f, tween(1300)) }
    }
    val missing = inStore != null && !l.sold
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Accent.copy(alpha = 0.16f * glow.value)).padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (l.sold) l.name else "Item ${l.sku}", fontFamily = Mono, fontSize = 15.sp, color = if (missing) Faint else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                l.sold -> "${l.qty} × ${money(l.priceCents)}"
                missing -> "Not sold here · won't be charged"
                else -> "${l.qty} in bag · waiting for store prices"
            }
            Text(sub, fontFamily = Mono, fontSize = 12.sp, color = if (missing) Bad else Faint)
        }
        Spacer(Modifier.width(12.dp))
        if (l.sold) Text(money(l.lineCents), fontFamily = Mono, fontSize = 15.sp, color = Ink)
        else if (missing) Text("Remove", fontSize = 13.sp, color = Accent, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { m.dismissLine(l.sku) })
    }
}

// ---------- receipt

fun qr(text: String, size: Int = 512): ImageBitmap {
    val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (bits.get(i % size, i / size)) 0xFF18181B.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
fun ReceiptScreen(r: Receipt, onDone: () -> Unit) {
    val code = remember(r.id) { qr("SMARTBAG-EXIT:${r.code}:${r.id}:${r.total}") }
    val due = r.status != "paid"
    val drawn = remember { Animatable(0f) }
    LaunchedEffect(r.id) { drawn.animateTo(1f, tween(650)) }
    Column(
        Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background((if (due) Bad else Good).copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(26.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.18f, size.height * 0.52f)
                    lineTo(size.width * 0.40f, size.height * 0.72f)
                    lineTo(size.width * 0.82f, size.height * 0.30f)
                }
                val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, false) }
                val part = Path()
                measure.getSegment(0f, measure.length * drawn.value, part, true)
                drawPath(part, if (due) Bad else Good, style = Stroke(width = 5f, cap = StrokeCap.Round))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(if (due) "You left ${r.storeName}. Payment due" else "Paid as you left ${r.storeName}", fontSize = 15.sp, color = Muted)
        Text(money(r.total), fontFamily = Mono, fontSize = 40.sp, color = Ink)
        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.paidAt)) + (if (r.payment.isNotEmpty()) " · ${r.payment}" else ""), fontSize = 13.sp, color = Faint)
        if (r.unpriced > 0) Text("${r.unpriced} item" + (if (r.unpriced == 1) "" else "s") + " not sold there, not charged", fontSize = 13.sp, color = Faint)
        Spacer(Modifier.height(24.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).border(1.dp, Hair, RoundedCornerShape(20.dp)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Caps("Receipt code")
            Spacer(Modifier.height(14.dp))
            Image(code, contentDescription = "Exit pass ${r.code}", modifier = Modifier.size(200.dp))
            Spacer(Modifier.height(12.dp))
            Text(r.code, fontFamily = Mono, fontSize = 26.sp, letterSpacing = 7.sp, color = Ink)
            Text("Staff can scan this to check your bag.", fontSize = 13.sp, color = Muted)
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).border(1.dp, Hair, RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 8.dp)) {
            r.items.forEachIndexed { i, it ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Hair))
                Row(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
                    Text((if (it.qty > 1) "${it.qty} × " else "") + it.name, fontFamily = Mono, fontSize = 14.sp, color = Ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(money(it.price * it.qty), fontFamily = Mono, fontSize = 14.sp, color = Ink)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Pill("Done", filled = false, modifier = Modifier.fillMaxWidth(), onClick = onDone)
    }
}
