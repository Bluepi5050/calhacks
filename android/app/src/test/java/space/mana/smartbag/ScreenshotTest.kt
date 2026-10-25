package space.mana.smartbag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every key screen/state of the app to PNG with Robolectric native graphics (real Compose + Skia
 * rendering, no emulator). Each screen is drawn under a fake 24dp status bar and a 24dp gesture bar, because
 * Robolectric reports zero system-bar insets. Motion is rendered at its end state (reduced-motion path),
 * except the item flash, which shows its first frame.
 * Run: ./gradlew testDebugUnitTest --tests '*ScreenshotTest*' -PshotsDir=/path
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class ScreenshotTest {
    private val out = File(System.getProperty("shots.dir") ?: "/tmp/smartbag-shots")

    data class Device(val name: String, val qualifiers: String, val font: Float, val dark: Boolean)
    // 3.2: the four configs Scott asked for (3.1's 360 font 2.0 is still rendered with -Pshots.all=true).
    private val devices = listOf(
        Device("411-light", "w411dp-h891dp-xhdpi", 1f, false),
        Device("360-light", "w360dp-h780dp-xhdpi", 1f, false),
        Device("411-light-font2", "w411dp-h891dp-xhdpi", 2f, false),
        Device("411-dark", "w411dp-h891dp-night-xhdpi", 1f, true),
    ) + if (System.getProperty("shots.all") == "true") listOf(Device("360-light-font2", "w360dp-h780dp-xhdpi", 2f, false)) else emptyList()

    private val nop = object : AppActions {
        override fun connectBag() {}
        override fun locationSettings() {}
        override fun retry() {}
        override fun remove(sku: String) {}
        override fun finishReceipt() {}
        override fun demoDrop() {}
        override fun demoWalkOut() {}
    }

    private val bay = StoreState.InStore("bayside-grocer", "Bayside Grocer", "201 Spear St, San Francisco", false)
    private val bag = BagState.Connected("SmartBag-01")
    private fun line(sku: String, name: String, qty: Int, price: Int, sold: Boolean = true, t: Long = 0) =
        Line(sku, name, qty, price, price * qty, sold, t)
    private val filled = listOf(
        line("1001", "Organic bananas", 1, 129, t = 1), line("1005", "Whole milk", 2, 469, t = 2),
        line("1008", "Sourdough loaf", 1, 649, t = 3), line("9999", "", 1, 0, sold = false, t = 4),
    )
    private val filledTotal = 129 + 2 * 469 + 649
    private val receipt = Receipt(
        1042, "K7Q2PM", "Bayside Grocer", filledTotal, 4, 1, "paid", "Demo Visa •••• 4242", 1792878840000L,
        listOf(ReceiptLine("1001", "Organic bananas", 129, 1), ReceiptLine("1005", "Whole milk", 469, 2), ReceiptLine("1008", "Sourdough loaf", 649, 1)),
    )
    private fun ui(store: StoreState, b: BagState = bag, lines: List<Line> = emptyList(), paid: Receipt? = null, flash: Pair<String, Int>? = null, err: String? = null, last: Receipt? = null) =
        ShopUi(b, store, "Demo Visa •••• 4242", lines.filter { it.sold }.sumOf { it.lineCents }, lines.filter { it.sold }.sumOf { it.qty }, lines, paid, flash, err, last)

    // ---- 3.2 account fixtures
    private val visa = CardInfo(11, "Visa", "4242", 12, 2029, "Demo", true)
    private val mc = CardInfo(12, "Mastercard", "4444", 1, 2031, "", false)
    private val amex = CardInfo(13, "Amex", "0005", 6, 2028, "Work", false)
    private val myBag = BagInfo(21, "SmartBag-3F2A", "Scott's bag", 0)
    private val acct = AccountState(true, "Scott Chiang", "scott@bagitwith.us", listOf(visa, mc, amex), listOf(myBag), visa.label)
    private val perms = Perms(true, true, true, true)
    private fun app(shop: ShopUi, a: AccountState = acct, trips: List<Receipt>? = null, pairing: PairUi? = null) =
        AppUi(shop, a, perms, trips = trips, pairing = pairing, connectedBag = "SmartBag-3F2A", version = "3.2")
    private val now = System.currentTimeMillis()
    private val trips = listOf(
        receipt.copy(id = 1051, code = "M4XQ7T", paidAt = now - 20 * 60_000L),
        receipt.copy(id = 1049, code = "H2KD9P", storeName = "Mission Fresh", total = 1147, count = 3, paidAt = now - 3 * 3600_000L),
        receipt.copy(id = 1040, code = "Q8ZR2C", total = 598, count = 2, status = "payment_due", payment = "", paidAt = now - 26 * 3600_000L),
        receipt.copy(id = 1033, code = "B7NW4E", storeName = "Mission Fresh", total = 2210, count = 6, paidAt = now - 4 * 86_400_000L),
    )
    private val found = listOf(
        FoundBag("A", "SmartBag-3F2A", -48, now), FoundBag("B", "SmartBag-91C0", -68, now), FoundBag("C", "SmartBag-01", -84, now),
    )

    /** Tabs (or any screen) with a sheet already open, the way AppRoot hosts them. */
    @Composable
    private fun Host(ui: AppUi, sheet: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
        val sheets = androidx.compose.runtime.remember { Sheets().also { s -> if (sheet != null) s.show(sheet) } }
        androidx.compose.runtime.CompositionLocalProvider(LocalSheets provides sheets, LocalAppUi provides ui) {
            Box(Modifier.fillMaxSize()) { content(); SheetLayer(sheets) }
        }
    }

    private val cartLines = listOf(
        line("1003", "Honeycrisp apples", 2, 249, t = 3), line("1001", "Organic bananas", 1, 129, t = 2),
        line("cell-phone", "cell-phone", 1, 0, sold = false, t = 4), line("1010", "Sparkling water", 1, 199, t = 1))
    private val catalog = listOf(Recommendation("1001", "Organic bananas", 129, "banana"), Recommendation("1017", "Oranges", 189, "orange"),
        Recommendation("1010", "Sparkling water", 199, "bottle"), Recommendation("1018", "Carrots", 149, "carrot"), Recommendation("1005", "Whole milk", 469, ""))
    private fun cartShop(budget: Int = 2000) = ui(bay, BagState.Connected("SmartCart-3F2A"), cartLines).copy(budget = budget, editable = true,
        recs = listOf(Recommendation("1017", "Oranges", 189, "Goes with what's in your bag"), Recommendation("1018", "Carrots", 149, "Fits your budget"),
            Recommendation("1019", "Broccoli", 229, "Popular at Bayside Grocer")))

    private val states: List<Pair<String, @Composable () -> Unit>> = listOf(
        "01-permissions" to { PermissionsScreen(Perms(false, false, false, false), {}, {}) },
        "02-permissions-denied" to { PermissionsScreen(Perms(true, false, false, true, canAskAgain = false), {}, {}) },
        "03-finding-store" to { ShopScreen(ui(StoreState.Locating, BagState.Searching), nop) },
        "04-location-off" to { ShopScreen(ui(StoreState.NoLocation), nop) },
        "05-offline" to { ShopScreen(ui(StoreState.Offline("No internet connection."), lines = filled.take(2)), nop) },
        "06-not-in-store" to { ShopScreen(ui(StoreState.Away("Bayside Grocer", 240)), nop) },
        "07-bag-problem" to { ShopScreen(ui(bay, BagState.Problem("Couldn't find your bag. Make sure it's on and nearby.")), nop) },
        "08-in-store-empty" to { ShopScreen(ui(bay), nop) },
        "09-filling" to { ShopScreen(ui(bay, lines = filled, flash = "1008" to 1), nop) },
        "10-leaving" to { ShopScreen(ui(bay.copy(leaving = true), lines = filled.take(3)), nop) },
        "11-receipt-paid" to { ShopScreen(ui(StoreState.Away(null, null), paid = receipt), nop) },
        "13-last-trip" to { ShopScreen(ui(StoreState.Away("Bayside Grocer", 240), last = receipt.copy(paidAt = System.currentTimeMillis() - 5 * 60_000L)), nop) },
        "14-permissions-approximate" to { PermissionsScreen(Perms(true, false, true, true, approximate = true), {}, {}) },
        "16-walkout-to-receipt" to { WalkOutFlip(ui(bay.copy(leaving = true), lines = filled.take(3)), receipt) },
        "15-bag-off-in-store-items" to { ShopScreen(ui(bay, BagState.Problem("Turn on Bluetooth to connect your bag."), lines = filled.take(3)), nop) },
        "12-receipt-due" to { ShopScreen(ui(StoreState.Away(null, null), paid = receipt.copy(status = "payment_due", payment = "", unpriced = 0)), nop) },

        // ---- 3.2: accounts, setup, pairing, wallet, tabs
        "20-welcome" to { WelcomeScreen({}, {}) },
        "21-create-account" to { AuthScreen(true, {}, {}, { _, _, _, _ -> }) },
        "22-create-account-errors" to { AuthScreen(true, {}, {}, { _, _, _, _ -> }, preview = Triple("", "scott@", "short"), previewTried = true) },
        "23-log-in-error" to { AuthScreen(false, {}, {}, { _, _, _, _ -> }, preview = Triple("", "scott@bagitwith.us", ""), previewError = "Email or password is wrong.") },
        "24-setup-permissions" to { PermissionsScreen(Perms(false, false, false, false), {}, {}, step = 1) },
        "25-pair-scan" to { PairScanScreen(PairUi("scanning", found, now - 4000), setOf("SmartBag-3F2A"), 2, null, {}, {}, {}, {}, {}, onLater = {}, now = now) },
        "26-pair-none-found" to { PairScanScreen(PairUi("scanning", emptyList(), now - 20_000), emptySet(), null, {}, {}, {}, {}, {}, {}, onLater = null, now = now) },
        "27-pair-bluetooth-off" to { PairScanScreen(PairUi("off", emptyList(), now), emptySet(), 2, null, {}, {}, {}, {}, {}, onLater = {}, now = now) },
        "28-pair-check" to { PairCheckScreen(Probe("A", "SmartBag-3F2A", connected = true, helloCount = 1), 2, {}, {}, {}) },
        "29-pair-connecting" to { PairCheckScreen(Probe("A", "SmartBag-3F2A"), null, {}, {}, {}) },
        "30-pair-name" to { PairNameScreen("SmartBag-3F2A", "Scott's bag", 2, { _, _ -> }, {}) },
        "31-pair-done" to { PairDoneScreen("Scott's bag", false, 2, "Continue") {} },
        "32-add-card-setup" to { AddCardScreen(true, 3, null, {}, { _, _ -> }, {}) },
        "33-add-card-errors" to { AddCardScreen(false, null, {}, null, { _, _ -> }, {}, preview = listOf("4242424242424241", "0924", "12", ""), previewTried = true) },
        "34-tabs-shop-filling" to { Host(app(ui(bay, lines = filled))) { MainTabs(app(ui(bay, lines = filled)), nop) } },
        "35-tabs-shop-no-card" to { val a = acct.copy(cards = emptyList(), payment = ""); val s = ui(bay, lines = filled.take(3)).copy(payment = "", noCard = true); Host(app(s, a)) { MainTabs(app(s, a), nop) } },
        "36-tabs-shop-no-bag" to { val a = acct.copy(bags = emptyList()); val s = ui(StoreState.Away("Bayside Grocer", 240), BagState.Problem("No bag found nearby.")).copy(noBag = true); Host(app(s, a)) { MainTabs(app(s, a), nop) } },
        "37-card-picker" to { val u = app(ui(bay, lines = filled.take(3))); Host(u, { CardPickerSheet(acct, { _, _ -> }, {}) }) { MainTabs(u, nop) } },
        "38-trips" to { val u = app(ui(bay), trips = trips); Host(u) { MainTabs(u, nop, initialTab = 1) } },
        "39-trips-empty" to { val u = app(ui(bay), trips = emptyList()); Host(u) { MainTabs(u, nop, initialTab = 1) } },
        "40-wallet" to { val u = app(ui(bay)); Host(u) { MainTabs(u, nop, initialTab = 2) } },
        "41-wallet-empty" to { val a = acct.copy(cards = emptyList(), payment = ""); val u = app(ui(bay).copy(noCard = true), a); Host(u) { MainTabs(u, nop, initialTab = 2) } },
        "42-card-remove-confirm" to { val u = app(ui(bay)); Host(u, { CardSheet(acct, visa, { _, _ -> }, { _, _ -> }, {}, initialConfirm = true) }) { MainTabs(u, nop, initialTab = 2) } },
        "43-account" to { val a = acct.copy(bags = listOf(myBag, BagInfo(22, "DEMO", "Demo bag", 0))); val u = app(ui(bay), a); Host(u) { MainTabs(u, nop, initialTab = 3) } },
        "44-bag-sheet" to { val u = app(ui(bay)); Host(u, { BagSheet(myBag, { _, _ -> }, { }, {}) }) { MainTabs(u, nop, initialTab = 3) } },
        "45-delete-account" to { val u = app(ui(bay)); Host(u, { DeleteSheet(nop, {}) }) { MainTabs(u, nop, initialTab = 3) } },
        "46-tabs-walkout-to-receipt" to { TabsFlip() },
        // ---- 3.4: SmartCart
        "52-cart-budget-recs" to { val s = cartShop(); Host(app(s)) { MainTabs(app(s), nop) } },
        "53-cart-over-budget" to { val s = cartShop(budget = 700); Host(app(s)) { MainTabs(app(s), nop) } },
        "54-cart-correct-row" to { val s = cartShop().copy(editingPreview = "1003"); Host(app(s)) { MainTabs(app(s), nop) } },
        "55-change-item" to { val s = cartShop(); Host(app(s), { ChangeItemSheet(cartLines[0], { }, { _, _ -> }, {}, preview = catalog) }) { MainTabs(app(s), nop) } },
        "56-budget-sheet" to { val s = cartShop(); Host(app(s), { BudgetSheet(2000, { _, _ -> }, {}) }) { MainTabs(app(s), nop) } },
        "57-receipt-summary" to { ShopScreen(ui(StoreState.Away(null, null), paid = receipt).copy(summary = TripSummary(receipt, "s1k9", 0, 0, 6, 2000, 2000L - receipt.total,
            "You spent ${money(receipt.total)} on ${receipt.count} items at Bayside Grocer. Your trip took 12 minutes.")), nop) },
        "58-hardware-test" to { HardwareTestScreen(HwUi("connected SmartCart-TEST", "S|s1k9|ACTIVE|3|0|SmartCart-TEST", "s1k9 @ bayside-grocer", "s1k9", 0,
            "SmartCart procedures available", "accepted", true, listOf("17:02:11 → START|s1k9|bayside-grocer", "17:02:11 ← OK|START|s1k9", "17:02:14 ← 1|A|apple|93",
            "17:02:14 backend accepted for s1k9:1", "17:02:14 → ACK|s1k9|1", "17:02:20 ← 2|A|cell phone|71", "17:02:20 backend unknown_product for s1k9:2", "17:02:20 → ACK|s1k9|2")), nop, {}) },
        "59-pair-check-cart" to { PairCheckScreen(Probe("A", "SmartCart-3F2A", connected = true, hello = "SmartCart-3F2A"), 2, {}, {}, {}) },
        "60-cart-reconnecting" to { val s = cartShop().copy(bag = BagState.Connecting("SmartCart-3F2A", retry = true)); Host(app(s)) { MainTabs(app(s), nop) } },
        "61-visit-summary" to { ShopScreen(ui(StoreState.Away("Bayside Grocer", 240)).copy(summary = TripSummary(receipt.copy(id = 0), "s1k9", 0, 0, 2, 0, 0,
            "You left Bayside Grocer with nothing to pay for. Your trip took 4 minutes.")), nop) },
        // ---- 3.3: notifications
        "49-notifications" to { NotificationsScreen(app(ui(bay)).copy(perms = perms.copy(background = true), push = PushState(false, false, "")), nop, {}) },
        "50-notifications-off" to { NotificationsScreen(app(ui(bay)).copy(notifSystem = false,
            notif = NotifKind.values().associateWith { it != NotifKind.Store }), nop, {}) },
        "51-notifications-server-push" to { NotificationsScreen(app(ui(bay)).copy(perms = perms.copy(background = true), pushBuild = true,
            push = PushState(true, true, "trips,security")), nop, {}, previewResult = "Sent. It should arrive in a few seconds.") },
        "48-add-card-only-step" to { androidx.compose.runtime.CompositionLocalProvider(LocalSetupProgress provides (1 to 1)) { AddCardScreen(true, 3, null, {}, { _, _ -> }, {}) } },
        "47-trip-receipt" to { val u = app(ui(bay), trips = trips); Host(u) { MainTabs(u, nop, initialTab = 1, initialReview = trips[2]) } },
    )

    /** The walk-out flip inside the tab host: the receipt replaces shop + tab bar, the line completes in place. */
    @Composable
    private fun TabsFlip() {
        var paid by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Receipt?>(null) }
        androidx.compose.runtime.LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { }; paid = receipt }
        val leaving = ui(bay.copy(leaving = true), lines = filled.take(3))
        val s = if (paid == null) leaving else leaving.copy(store = StoreState.Away(null, null), lines = emptyList(), total = 0, count = 0, paid = paid, lastTrip = paid)
        Host(app(s)) { MainTabs(app(s), nop) }
    }

    /** The real path: the shop (leaving) renders first, then the charge arrives and the shop hands over to the receipt. */
    @Composable
    private fun WalkOutFlip(leaving: ShopUi, r: Receipt) {
        var paid by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Receipt?>(null) }
        androidx.compose.runtime.LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { }; paid = r }
        ShopScreen(if (paid == null) leaving else leaving.copy(store = StoreState.Away(null, null), lines = emptyList(), total = 0, count = 0, paid = paid, lastTrip = paid), nop)
    }

    @Composable
    private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
        BagItTheme(dark = dark, reduceMotion = true) {
            val p = LocalPalette.current
            Column(Modifier.fillMaxSize().background(p.bg)) {
                Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("9:41", color = p.ink, fontSize = (12f / androidx.compose.ui.platform.LocalDensity.current.fontScale).sp)
                }
                Box(Modifier.weight(1f)) { content() }
                Box(Modifier.fillMaxWidth().height(24.dp).background(p.surface), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(108.dp).height(4.dp).background(p.ink.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(50)))
                }
            }
        }
    }

    @Test fun renderAll() {
        out.mkdirs()
        val only = System.getProperty("shots.only")
        for (dv in devices) {
            RuntimeEnvironment.setQualifiers(dv.qualifiers)
            RuntimeEnvironment.setFontScale(dv.font)
            for ((name, content) in states) {
                if (only != null && !name.contains(only)) continue
                captureRoboImage(File(out, "${dv.name}/$name.png").absolutePath) { Frame(dv.dark) { content() } }
            }
        }
        RuntimeEnvironment.setFontScale(1f)
    }
}
