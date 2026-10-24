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
    private val devices = listOf(
        Device("411-light", "w411dp-h891dp-xhdpi", 1f, false),
        Device("360-light", "w360dp-h780dp-xhdpi", 1f, false),
        Device("411-light-font2", "w411dp-h891dp-xhdpi", 2f, false),
        Device("360-light-font2", "w360dp-h780dp-xhdpi", 2f, false),
        Device("411-dark", "w411dp-h891dp-night-xhdpi", 1f, true),
    )

    private val nop = object : ShopActions {
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

    private val states: List<Pair<String, @Composable () -> Unit>> = listOf(
        "01-welcome" to { WelcomeScreen(Perms(false, false, false, false), {}, {}) },
        "02-welcome-denied" to { WelcomeScreen(Perms(true, false, false, true, canAskAgain = false), {}, {}) },
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
        "14-welcome-approximate" to { WelcomeScreen(Perms(true, false, true, true, approximate = true), {}, {}) },
        "16-walkout-to-receipt" to { WalkOutFlip(ui(bay.copy(leaving = true), lines = filled.take(3)), receipt) },
        "15-bag-off-in-store-items" to { ShopScreen(ui(bay, BagState.Problem("Turn on Bluetooth to connect your bag."), lines = filled.take(3)), nop) },
        "12-receipt-due" to { ShopScreen(ui(StoreState.Away(null, null), paid = receipt.copy(status = "payment_due", payment = "", unpriced = 0)), nop) },
    )

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
