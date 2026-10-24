package space.mana.smartbag

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ======================================================================================
// Pair your bag: scan (live list with signal words) -> check the light (the bag blinks with the mark)
// -> name it -> paired (the slot lights steady). Coral appears only as the bag's light.
// ======================================================================================

fun signalWords(rssi: Int) = when { rssi >= -55 -> "Right here"; rssi >= -75 -> "Nearby"; else -> "Farther away" }

@Composable
fun PairScanScreen(
    pairing: PairUi, yours: Set<String>, step: Int?, onBack: (() -> Unit)?, onPick: (FoundBag) -> Unit,
    onRescan: () -> Unit, onBluetooth: () -> Unit, onSettings: () -> Unit, onDemo: () -> Unit, demoBusy: Boolean = false,
    demoError: String? = null, onLater: (() -> Unit)?, now: Long = System.currentTimeMillis(),
) {
    val p = LocalPalette.current
    // Bags drop off the list once they haven't been heard for 10 s; strongest signal first.
    val bags = pairing.found.filter { now - it.seenAt < 10_000 }.sortedByDescending { it.rssi }
    val searching = pairing.scan == "scanning"
    val noneFound = searching && bags.isEmpty() && now - pairing.startedAt > 15_000
    Column(Modifier.fillMaxSize().background(p.bg)) {
        if (step != null) { Spacer(Modifier.statusBarsPadding()); StepBar(step); Spacer(Modifier.height(8.dp)) } else TopBar(onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(if (step != null) 16.dp else 8.dp))
            when (pairing.scan) {
                "off" -> ScreenTitle("Bluetooth is off", "Turn it on to find your bag.")
                "denied" -> ScreenTitle("Nearby devices is off", "Bag It needs it to find your bag. Turn it on in Settings.")
                "scanning" -> ScreenTitle("Pair your bag", "Turn your bag on and hold it near your phone.")
                else -> ScreenTitle("Pair your bag", pairing.scan, p.bad)
            }
            if (searching) {
                Spacer(Modifier.height(32.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Bags nearby")
                    Spacer(Modifier.weight(1f))
                    if (!noneFound) BusyLine("Looking…")
                }
                Spacer(Modifier.height(8.dp))
                Hairline()
                if (noneFound) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Mark(48.dp, slot = null, ink = p.strong)
                        Spacer(Modifier.height(16.dp))
                        Txt("No bags found", Type.body, bold = true, align = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Txt("Make sure it's switched on and close to your phone.", Type.caption, p.muted, align = TextAlign.Center, balanced = true)
                    }
                } else if (bags.isEmpty()) {
                    Txt("Looking…", Type.caption, p.muted, modifier = Modifier.padding(vertical = 20.dp).semantics { liveRegion = LiveRegionMode.Polite })
                } else bags.forEach { b ->
                    key(b.address) {
                        val mine = b.bleName in yours
                        ListRow(
                            bagDisplayName(b.bleName), signalWords(b.rssi), icon = Glyph.bag, trailing = if (mine) "Yours" else null,
                            chevron = true, description = "${bagDisplayName(b.bleName)}, ${signalWords(b.rssi)}" + if (mine) ", already yours" else "",
                        ) { onPick(b) }
                        Hairline()
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomArea {
            when {
                pairing.scan == "off" -> { Pill("Turn on Bluetooth", Modifier.fillMaxWidth(), onClick = onBluetooth); Spacer(Modifier.height(8.dp)) }
                pairing.scan == "denied" -> { Pill("Open Settings", Modifier.fillMaxWidth(), onClick = onSettings); Spacer(Modifier.height(8.dp)) }
                noneFound || (pairing.scan != "scanning") -> { Pill("Scan again", Modifier.fillMaxWidth(), onClick = onRescan); Spacer(Modifier.height(8.dp)) }
            }
            ErrorLine(demoError)
            TextAction(if (demoBusy) "Setting up a demo bag…" else "No bag with you? Use a demo bag", Modifier.fillMaxWidth(), enabled = !demoBusy, onClick = onDemo)
            if (onLater != null) TextAction("Set up later", Modifier.fillMaxWidth(), color = p.muted, onClick = onLater)
        }
    }
}

/** The bag mark whose slot blinks three times each time the phone says HELLO (the bag blinks with it). */
@Composable
private fun BlinkingMark(beat: Int, steady: Boolean, size: androidx.compose.ui.unit.Dp = 96.dp) {
    val p = LocalPalette.current
    val reduce = LocalReduceMotion.current
    var on by remember { mutableStateOf(steady || (reduce && beat > 0)) }
    LaunchedEffect(beat, steady, reduce) {
        if (steady) { on = true; return@LaunchedEffect }
        if (beat <= 0) { on = false; return@LaunchedEffect }
        if (reduce) { on = true; return@LaunchedEffect }        // reduced motion: the light simply shows as on
        repeat(3) { on = true; delay(180); on = false; delay(180) }       // firmware: 3 x (180 on, 180 off)
    }
    Mark(size, slot = if (on) p.coral else p.bg, ink = p.ink)
}

@Composable
fun PairCheckScreen(probe: Probe, step: Int?, onYes: () -> Unit, onNo: () -> Unit, onBack: () -> Unit) {
    val p = LocalPalette.current
    val name = bagDisplayName(probe.bleName)
    Column(Modifier.fillMaxSize().background(p.bg)) {
        if (step != null) { Spacer(Modifier.statusBarsPadding()); StepBar(step); Spacer(Modifier.height(8.dp)) } else TopBar(onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(if (step != null) 16.dp else 8.dp))
            when {
                probe.error != null -> ScreenTitle("Couldn't reach $name", probe.error, p.bad)
                !probe.connected -> {
                    ScreenTitle("Connecting to $name")
                    Spacer(Modifier.height(16.dp)); BusyLine("Connecting…")
                }
                else -> ScreenTitle("Is the light on your bag blinking?",
                    "$name should be blinking now, three times in a row." + if (probe.hello == null && probe.helloCount > 2) " Older bags don't blink: if it's the one in your hand, it's yours." else "")
            }
            Spacer(Modifier.height(48.dp))
            Box(Modifier.fillMaxWidth().semantics { contentDescription = "Your bag's light" }, contentAlignment = Alignment.Center) {
                BlinkingMark(if (probe.connected && probe.error == null) maxOf(probe.helloCount, 1) else 0, steady = false)
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomArea {
            if (probe.error != null) Pill("Try another", Modifier.fillMaxWidth(), onClick = onNo)
            else {
                if (probe.connected) { Pill("Yes, that's my bag", Modifier.fillMaxWidth(), onClick = onYes); Spacer(Modifier.height(8.dp)) }
                Pill(if (probe.connected) "No, try another" else "Try another bag", Modifier.fillMaxWidth(), primary = false, onClick = onNo)
            }
        }
    }
}

@Composable
fun PairNameScreen(bleName: String, initial: String, step: Int?, onPair: (String, (String?) -> Unit) -> Unit, onBack: () -> Unit,
                   previewBusy: Boolean = false, previewError: String? = null) {
    val p = LocalPalette.current
    var name by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(previewBusy) }
    var err by remember { mutableStateOf(previewError) }
    fun go() { if (busy) return; busy = true; err = null; onPair(name.trim().ifEmpty { initial }) { e -> busy = false; err = e } }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        if (step != null) { Spacer(Modifier.statusBarsPadding()); StepBar(step); Spacer(Modifier.height(8.dp)) } else TopBar(if (busy) null else onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(if (step != null) 16.dp else 8.dp))
            ScreenTitle("Name your bag", "So you can tell your bags apart. You can change it later.")
            Spacer(Modifier.height(32.dp))
            Field("Name", name, { name = it.take(30) }, enabled = !busy, hint = bagDisplayName(bleName),
                capitalization = KeyboardCapitalization.Sentences, ime = ImeAction.Done, onIme = { go() })
            Spacer(Modifier.height(24.dp))
        }
        BottomArea {
            ErrorLine(err)
            BusyPill("Pair bag", busy) { go() }
        }
    }
}

@Composable
fun PairDoneScreen(name: String, demo: Boolean, step: Int?, cta: String, onDone: () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    LaunchedEffect(Unit) { view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS) }
    BackHandler { onDone() }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        if (step != null) { Spacer(Modifier.statusBarsPadding()); StepBar(step); Spacer(Modifier.height(8.dp)) } else Spacer(Modifier.statusBarsPadding().height(56.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(if (step != null) 16.dp else 8.dp))
            ScreenTitle("Bag paired",
                if (demo) "Your demo bag is ready. Press and hold Bag connected at the top of Shop to drop in a random item."
                else "Your bag is ready. Drop something in and it shows up here.")
            Spacer(Modifier.height(48.dp))
            Box(Modifier.fillMaxWidth().semantics { contentDescription = "$name, light on" }, contentAlignment = Alignment.Center) {
                Mark(96.dp, slot = p.coral, ink = p.ink)
            }
            Spacer(Modifier.height(16.dp))
            Txt(name, Type.body, p.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
        BottomArea { Pill(cta, Modifier.fillMaxWidth(), onClick = onDone) }
    }
}

/**
 * The whole pairing flow. [setup] = step 2 of first-run setup (step bar, "Set up later", "Continue").
 * Starts the pairing scan on entry and hands Bluetooth back to normal auto-connect on exit.
 */
@Composable
fun PairFlow(ui: AppUi, a: AppActions, setup: Boolean, onFinish: () -> Unit) {
    var stage by remember { mutableStateOf("scan") }
    var chosen by remember { mutableStateOf<FoundBag?>(null) }
    var pairedName by remember { mutableStateOf("") }
    var demo by remember { mutableStateOf(false) }
    var demoBusy by remember { mutableStateOf(false) }
    var demoErr by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    DisposableEffect(Unit) { a.startPairing(); onDispose { a.endPairing() } }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val step = if (setup) 2 else null
    val pairing = ui.pairing ?: PairUi("scanning", emptyList(), now)
    val yours = ui.account.bags.map { it.bagId }.toSet()
    when (stage) {
        "scan" -> {
            if (!setup) BackHandler { onFinish() }
            PairScanScreen(pairing, yours, step, onBack = onFinish, onPick = { chosen = it; a.probe(it); stage = "check" },
                onRescan = a::startPairing, onBluetooth = a::bluetoothOn, onSettings = a::appSettings,
                onDemo = { demoBusy = true; demoErr = null; a.pairDemo { e -> demoBusy = false; demoErr = e; if (e == null) { demo = true; pairedName = "Demo bag"; stage = "done" } } },
                demoBusy = demoBusy, demoError = demoErr, onLater = if (setup) onFinish else null, now = now)
        }
        "check" -> {
            val probe = pairing.probe ?: chosen?.let { Probe(it.address, it.bleName) } ?: Probe("", "SmartBag")
            val back = { a.rejectProbe(); stage = "scan" }
            BackHandler { back() }
            PairCheckScreen(probe, step, onYes = { a.confirmProbe(); stage = "name" }, onNo = back, onBack = back)
        }
        "name" -> {
            val b = chosen
            BackHandler { a.rejectProbe(); stage = "scan" }
            val first = ui.account.firstName
            PairNameScreen(b?.bleName ?: "", if (first.isNotEmpty()) "$first's bag" else "My bag", step,
                onPair = { name, done -> a.pair(b?.bleName ?: "", name) { e -> done(e); if (e == null) { pairedName = name; stage = "done" } } },
                onBack = { a.rejectProbe(); stage = "scan" })
        }
        else -> PairDoneScreen(pairedName, demo, step, if (setup) "Continue" else "Done", onFinish)
    }
}
