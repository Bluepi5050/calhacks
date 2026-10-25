package space.mana.smartbag

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

// SmartCart additions to the shop (Android 3.4): budget, manual corrections, suggestions, trip summary, hardware test.
// Same visual system: hairlines, ink pills, coral only for the bag's light and money moving, red only when over budget.

/** Parse "12", "12.5", "$12.50" into cents; null if it isn't an amount. */
fun parseCents(s: String): Int? {
    val t = s.trim().removePrefix("$").replace(",", "")
    if (t.isEmpty()) return 0
    val v = t.toBigDecimalOrNull() ?: return null
    if (v.signum() < 0 || v.scale() > 2 || v > java.math.BigDecimal(100_000)) return null
    return v.movePointRight(2).toInt()
}

/** Budget line under the dock total. Red only when over. */
fun budgetText(budget: Int, total: Int): Pair<String, Boolean>? {
    if (budget <= 0) return null
    val left = budget - total
    return if (left >= 0) "${money(left)} left of your ${money(budget)} budget" to false
    else "${money(-left)} over your ${money(budget)} budget" to true
}

/** Receipt: how the trip went against the budget, from the backend's TripSummary. */
fun summaryBudgetText(s: TripSummary): Pair<String, Boolean>? {
    if (s.budgetCents <= 0) return null
    return if (s.underBudgetCents >= 0) "Under your ${money(s.budgetCents)} budget by ${money(s.underBudgetCents.toInt())}" to false
    else "Over your ${money(s.budgetCents)} budget by ${money((-s.underBudgetCents).toInt())}" to true
}

@Composable
fun BudgetLine(budget: Int, total: Int, onClick: () -> Unit) {
    val p = LocalPalette.current
    val (text, over) = budgetText(budget, total) ?: return
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Change budget", onClick = onClick).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Txt(text, Type.caption, if (over) p.bad else p.muted, num = true, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(4.dp))
        GlyphImage(Glyph.chevron, p.faint, 16.dp)
    }
}

@Composable
fun BudgetSheet(current: Int, onSave: (Int, (String?) -> Unit) -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf(if (current > 0) String.format(Locale.US, "%.2f", current / 100.0) else "") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val cents = parseCents(text)
    fun save(v: Int) { if (busy) return; busy = true; err = null; onSave(v) { e -> busy = false; err = e; if (e == null) onClose() } }
    Txt("Budget", Type.title, bold = true, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(4.dp))
    Txt("Bag It shows what's left as you shop and keeps suggestions within it. It stays set for your next trips.", Type.caption, p.muted)
    Spacer(Modifier.height(24.dp))
    Field("Budget per trip", text, { text = it.take(9) }, error = if (cents == null) "Enter an amount like 25 or 25.50." else null,
        enabled = !busy, keyboard = KeyboardType.Decimal, ime = ImeAction.Done, onIme = { cents?.let(::save) }, num = true, placeholder = "$0.00")
    Spacer(Modifier.height(16.dp))
    ErrorLine(err)
    BusyPill("Save budget", busy, Modifier.fillMaxWidth()) { cents?.let(::save) }
    if (current > 0) { Spacer(Modifier.height(8.dp)); TextAction("Remove budget", Modifier.fillMaxWidth(), enabled = !busy) { save(0) } }
}

/** Under a tapped bag row: − qty + and "Change item" (the cart saw the wrong thing). */
@Composable
fun LineEditStrip(l: Line, onQty: (Int) -> Unit, onChange: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", "One fewer ${l.name}") { onQty(l.qty - 1) }
        Txt("${l.qty}", Type.body, num = true, bold = true, modifier = Modifier.widthIn(min = 40.dp).padding(horizontal = 8.dp)
            .semantics { contentDescription = "Quantity ${l.qty}" }, align = androidx.compose.ui.text.style.TextAlign.Center)
        StepButton("+", "One more ${l.name}") { onQty(l.qty + 1) }
        Spacer(Modifier.weight(1f))
        TextAction("Change item", color = p.ink, onClick = onChange)
    }
}

@Composable
private fun StepButton(text: String, description: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(50)).border(1.dp, p.hair, RoundedCornerShape(50))
        .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Txt(text, Type.body, bold = true)
    }
}

/** "Change item": the store's catalog; tapping one moves one unit of [from] to it. */
@Composable
fun ChangeItemSheet(from: Line, load: ((List<Recommendation>?, String?) -> Unit) -> Unit, onPick: (Recommendation, (String?) -> Unit) -> Unit,
                    onClose: () -> Unit, preview: List<Recommendation>? = null) {
    val p = LocalPalette.current
    var items by remember { mutableStateOf(preview) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (preview == null) load { list, e -> items = list; err = e } }
    Txt("Change item", Type.title, bold = true, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(4.dp))
    Txt("Your cart saw ${if (from.sold) from.name else "an item it doesn't know"}. Pick what you actually put in; one is changed.", Type.caption, p.muted)
    Spacer(Modifier.height(16.dp))
    ErrorLine(err)
    val list = items
    if (list == null && err == null) { BusyLine("Loading this store's items…"); return }
    Hairline()
    list.orEmpty().filter { it.sku != from.sku }.forEach { r ->
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) {
            if (busy != null) return@clickable
            busy = r.sku; err = null
            onPick(r) { e -> busy = null; err = e; if (e == null) onClose() }
        }.semantics(mergeDescendants = true) { contentDescription = "${r.name}, ${money(r.priceCents)}" }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(r.name, Type.body)
                if (r.reason.isNotEmpty()) Txt("Cart label: ${r.reason}", Type.caption, p.faint)
            }
            if (busy == r.sku) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), color = p.ink, strokeWidth = 2.dp)
            else Txt(money(r.priceCents), Type.body, num = true)
        }
        Hairline()
    }
    if (list != null && list.isEmpty()) Txt("This store hasn't listed its items yet.", Type.caption, p.muted, modifier = Modifier.padding(vertical = 16.dp))
}

/** Up to three quiet suggestions with their reasons. No buttons: they're hints, not ads. */
@Composable
fun RecsBlock(recs: List<Recommendation>) {
    if (recs.isEmpty()) return
    val p = LocalPalette.current
    Spacer(Modifier.height(32.dp))
    Column(Modifier.padding(horizontal = 24.dp)) {
        Eyebrow("You might also want")
        Spacer(Modifier.height(8.dp))
        Hairline()
        recs.take(3).forEach { r ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 12.dp)
                .semantics(mergeDescendants = true) { contentDescription = "${r.name}, ${money(r.priceCents)}. ${r.reason}" },
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(r.name, Type.body, p.muted)
                    if (r.reason.isNotEmpty()) Txt(r.reason, Type.caption, p.faint)
                }
                Spacer(Modifier.width(16.dp))
                Txt(money(r.priceCents), Type.body, p.muted, num = true)
            }
            Hairline()
        }
    }
}

/** On the receipt: the backend's trip summary (text + budget comparison). */
@Composable
fun TripSummaryBlock(s: TripSummary) {
    val p = LocalPalette.current
    Eyebrow("This trip")
    Spacer(Modifier.height(8.dp))
    summaryBudgetText(s)?.let { (t, over) -> Txt(t, Type.body, if (over) p.bad else p.ink, num = true); Spacer(Modifier.height(4.dp)) }
    if (s.text.isNotBlank()) Txt(s.text, Type.body, p.muted)
}

/** Hidden (long-press the version in Account): the cart link as it is, for testing with the real Jetson or a simulated one. */
@Composable
fun HardwareTestScreen(h: HwUi, a: HwActions, onBack: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().background(p.bg)) {
        TopBar(onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            ScreenTitle("Hardware test", "The SmartCart link and its session, as the phone sees them.")
            Spacer(Modifier.height(24.dp))
            Hairline()
            listOf(
                "Cart" to h.bag,
                "STATUS" to (h.status ?: "–"),
                "Session" to (h.session ?: "none"),
                "Confirmed" to (h.confirmed ?: "no"),
                "Queue" to "${h.queued} waiting for the backend",
                "Backend" to (h.backend ?: "–"),
                "Last result" to (h.last ?: "–"),
            ).forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Txt(k, Type.caption, p.muted, modifier = Modifier.width(96.dp))
                    Txt(v, Type.caption, mono = true)
                }
                Hairline()
            }
            Spacer(Modifier.height(16.dp))
            InkSwitch(h.simulated, "Simulated cart", "Answers like the Jetson (contract v1.1), through the same phone code. Turning it on stops and disconnects a real cart.") { a.simulateCart(it) }
            if (h.simulated) Txt("In a store, simulated items go into your real trip and are charged when you walk out.", Type.caption, p.bad, modifier = Modifier.padding(top = 8.dp))
            Hairline()
            Spacer(Modifier.height(16.dp))
            Eyebrow("Send")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallPill("START", "Send START for the current session") { a.hwStart() }
                SmallPill("STOP", "Send STOP") { a.hwStop() }
                SmallPill("SYNC", "Ask the cart for STATUS") { a.hwSync() }
            }
            if (h.simulated) {
                Spacer(Modifier.height(16.dp))
                Eyebrow("Simulate a crossing")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallPill("+ apple", "Apple goes in") { a.simulate("A", "apple") }
                    SmallPill("− apple", "Apple comes out") { a.simulate("R", "apple") }
                    SmallPill("+ banana", "Banana goes in") { a.simulate("A", "banana") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallPill("+ bottle", "Bottle goes in") { a.simulate("A", "bottle") }
                    SmallPill("+ carrot", "Carrot goes in") { a.simulate("A", "carrot") }
                    SmallPill("− orange", "Orange comes out (none in the bag)") { a.simulate("R", "orange") }
                }
            }
            Spacer(Modifier.height(24.dp))
            Eyebrow("Log")
            Spacer(Modifier.height(8.dp))
            h.log.takeLast(80).reversed().forEach { Txt(it, Type.caption, p.muted, mono = true) }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

data class HwUi(val bag: String, val status: String?, val session: String?, val confirmed: String?, val queued: Int,
                val backend: String?, val last: String?, val simulated: Boolean, val log: List<String>)

interface HwActions {
    fun simulateCart(on: Boolean) {}
    fun hwStart() {}
    fun hwStop() {}
    fun hwSync() {}
    fun simulate(action: String, label: String) {}
}
