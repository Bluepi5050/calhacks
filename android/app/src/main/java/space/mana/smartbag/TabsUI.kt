package space.mana.smartbag

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

// ======================================================================================
// App structure (3.2): signed out (Welcome / Create account / Log in) -> setup steps (Permissions,
// Pair your bag, Add a card) -> four tabs (Shop · Trips · Wallet · Account). Everything is driven by
// an AppUi snapshot so every screen renders with fake state in the screenshot tests.
// ======================================================================================

data class AppUi(
    val shop: ShopUi, val account: AccountState, val perms: Perms,
    val trips: List<Receipt>? = null, val tripsLoading: Boolean = false, val tripsError: String? = null,
    val pairing: PairUi? = null, val connectedBag: String? = null, val version: String = "",
    val demoActive: Boolean = false,      // the demo bag is standing in for a real one (counts as connected)
)

interface AppActions : ShopActions {
    fun signUp(name: String, email: String, password: String, done: (String?, AccountState?) -> Unit) {}
    fun signIn(email: String, password: String, done: (String?, AccountState?) -> Unit) {}
    fun signOut(done: (String?) -> Unit) {}
    fun deleteAccount(password: String, done: (String?) -> Unit) {}
    fun addCard(c: NewCard, done: (String?) -> Unit) {}
    fun setDefaultCard(id: Long, done: (String?) -> Unit) {}
    fun removeCard(id: Long, done: (String?) -> Unit) {}
    fun renameBag(id: Long, name: String, done: (String?) -> Unit) {}
    fun unpairBag(id: Long, done: (String?) -> Unit) {}
    fun loadTrips() {}
    fun askPermissions() {}
    fun appSettings() {}
    fun bluetoothOn() {}
    fun startPairing() {}
    fun probe(b: FoundBag) {}
    fun confirmProbe() {}
    fun rejectProbe() {}
    fun pair(bleName: String, name: String, done: (String?) -> Unit) {}
    fun pairDemo(done: (String?) -> Unit) {}
    fun endPairing() {}
}

enum class Step { Perms, Pair, Card }

/** Navigation that isn't data: the setup queue and full-screen overlays over the tabs. Survives recreation. */
class Nav {
    var signedOutScreen by mutableStateOf("welcome")          // welcome | signup | login
    val setup = mutableStateListOf<Step>()
    var setupTotal by mutableStateOf(0)                        // steps queued at sign-in (for "Step i of n")
    var overlay by mutableStateOf<String?>(null)               // pair | card
    fun reset() { signedOutScreen = "welcome"; setup.clear(); setupTotal = 0; overlay = null }
    fun startSetup(steps: List<Step>) { setup.clear(); setup.addAll(steps); setupTotal = steps.size }

    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<Nav, String>(
            save = { listOf(it.signedOutScreen, it.overlay ?: "", it.setupTotal.toString()) + it.setup.map { s -> s.name } },
            restore = { l -> Nav().apply {
                signedOutScreen = l[0]; overlay = l[1].ifEmpty { null }; setupTotal = l[2].toInt()
                setup.addAll(l.drop(3).map { Step.valueOf(it) })
            } },
        )
    }
}

/** Which setup steps a newly signed-in phone still needs (spec: log-in skips what the account already has). */
fun setupSteps(a: AccountState, perms: Perms, signUp: Boolean): List<Step> = buildList {
    if (!perms.ready) add(Step.Perms)
    if (signUp || a.bags.isEmpty()) add(Step.Pair)
    if (signUp || a.cards.isEmpty()) add(Step.Card)
}

@Composable
fun AppRoot(ui: AppUi, actions: AppActions, nav: Nav = rememberSaveable(saver = Nav.Saver) { Nav() }) {
    val sheets = remember { Sheets() }
    val signedIn = ui.account.signedIn
    LaunchedEffect(signedIn) { if (!signedIn) { nav.reset(); sheets.hide() } }
    // A walk-out charge always wins: close overlays so the receipt (on Shop) is what the shopper sees.
    LaunchedEffect(ui.shop.paid?.id) { if (ui.shop.paid != null) { nav.overlay = null; sheets.hide() } }
    // Navigation actions the screens ask for (open pairing, add a card, card picker) are handled here.
    val a = remember(actions, nav, sheets) {
        object : AppActions by actions {
            override fun openPairing() { nav.overlay = "pair" }
            override fun openAddCard() { sheets.hide(); nav.overlay = "card" }
            override fun chooseCard() { sheets.show { CardPickerSheet(LocalAppUi.current!!.account, onPick = { c, done -> actions.setDefaultCard(c.id, done) },
                onAdd = { sheets.hide(); nav.overlay = "card" }, onClose = sheets::hide) } }
        }
    }
    CompositionLocalProvider(LocalSheets provides sheets, LocalAppUi provides ui) {
        Box(Modifier.fillMaxSize().background(LocalPalette.current.bg)) {
            when {
                !signedIn -> SignedOut(nav, a, ui)
                nav.setup.isNotEmpty() && ui.shop.paid == null -> SetupStep(nav, ui, a)
                !ui.perms.ready -> PermissionsScreen(ui.perms, onContinue = a::askPermissions, onSettings = a::appSettings)
                nav.overlay == "pair" -> PairFlow(ui, a, setup = false) { nav.overlay = null }
                nav.overlay == "card" -> {
                    BackHandler { nav.overlay = null }
                    AddCardScreen(ui.account.cards.isEmpty(), null, onBack = { nav.overlay = null }, onLater = null,
                        onSubmit = { c, done -> a.addCard(c, done) }, onDone = { nav.overlay = null })
                }
                else -> MainTabs(ui, a)
            }
            SheetLayer(sheets)
        }
    }
}

/** Latest snapshot, for sheets: they are opened from a click but render (and must update) with the current state. */
val LocalAppUi = compositionLocalOf<AppUi?> { null }

@Composable
private fun SignedOut(nav: Nav, a: AppActions, ui: AppUi) {
    when (nav.signedOutScreen) {
        "signup", "login" -> {
            val signUp = nav.signedOutScreen == "signup"
            BackHandler { nav.signedOutScreen = "welcome" }
            key(signUp) {
                AuthScreen(signUp, onBack = { nav.signedOutScreen = "welcome" }, onSwitch = { nav.signedOutScreen = if (signUp) "login" else "signup" },
                    onSubmit = { name, email, pw, done ->
                        val after: (String?, AccountState?) -> Unit = { err, acct ->
                            if (err == null && acct != null && acct.signedIn) nav.startSetup(setupSteps(acct, ui.perms, signUp))
                            done(err)
                        }
                        if (signUp) a.signUp(name, email, pw, after) else a.signIn(email, pw, after)
                    })
            }
        }
        else -> WelcomeScreen(onCreate = { nav.signedOutScreen = "signup" }, onLogIn = { nav.signedOutScreen = "login" })
    }
}

@Composable
private fun SetupStep(nav: Nav, ui: AppUi, a: AppActions) {
    val next = { if (nav.setup.isNotEmpty()) nav.setup.removeAt(0) }
    val total = maxOf(nav.setupTotal, nav.setup.size)
    CompositionLocalProvider(LocalSetupProgress provides ((total - nav.setup.size + 1) to total)) {
    when (nav.setup.first()) {
        Step.Perms -> {
            LaunchedEffect(ui.perms.ready) { if (ui.perms.ready) next() }
            PermissionsScreen(ui.perms, onContinue = a::askPermissions, onSettings = a::appSettings, step = 1)
        }
        Step.Pair -> key("pair") { PairFlow(ui, a, setup = true) { next() } }
        Step.Card -> AddCardScreen(ui.account.cards.isEmpty(), 3, onBack = null, onLater = { next() },
            onSubmit = { c, done -> a.addCard(c, done) }, onDone = { next() })
    }
    }
}

// ---------------------------------------------------------------- tabs

private data class Tab(val label: String, val icon: ImageVector)
private val TABS = listOf(Tab("Shop", Glyph.bag), Tab("Trips", Glyph.receipt), Tab("Wallet", Glyph.card), Tab("Account", Glyph.person))
val TabBarHeight = 64.dp

@Composable
fun MainTabs(ui: AppUi, a: AppActions, initialTab: Int = 0, initialReview: Receipt? = null) {
    val p = LocalPalette.current
    val sheets = LocalSheets.current
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var shopFull by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf(initialReview) }
    // A walk-out charge always lands on Shop, where the receipt peak lives.
    LaunchedEffect(ui.shop.paid?.id) { if (ui.shop.paid != null) { tab = 0; review = null; sheets.hide() } }
    val navBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val inset = TabBarHeight + navBottom
    val full = (tab == 0 && shopFull) || review != null
    Box(Modifier.fillMaxSize().background(p.bg)) {
        when (tab) {
            0 -> CompositionLocalProvider(LocalNavPad provides false) {
                ShopScreen(ui.shop, a, bottomInset = inset, onFullScreen = { shopFull = it })
            }
            1 -> TripsScreen(ui, a, inset) { review = it }
            2 -> WalletScreen(ui.account, inset, onCard = { c ->
                    sheets.show { val acct = LocalAppUi.current?.account ?: ui.account; CardSheet(acct, acct.cards.firstOrNull { it.id == c.id } ?: c,
                        onDefault = { cc, done -> a.setDefaultCard(cc.id, done) }, onRemove = { cc, done -> a.removeCard(cc.id, done) }, onClose = sheets::hide) }
                }, onAdd = a::openAddCard)
            else -> AccountScreen(ui, a, inset)
        }
        AnimatedVisibility(!full, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(if (LocalReduceMotion.current) 0 else 200)), exit = fadeOut(tween(if (LocalReduceMotion.current) 0 else 160))) {
            TabBar(tab) { tab = it }
        }
        review?.let { r ->
            BackHandler { review = null }
            ReceiptScreen(r, peak = false, onDone = { review = null })
        }
    }
}

@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    // Labels scale with the font size up to 1.5x, then stop (as tab bars do), so four always fit.
    val labelSize = (13f * minOf(density.fontScale, 1.5f) / density.fontScale).sp
    Column(Modifier.fillMaxWidth().background(p.surface)) {
        Hairline()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(TabBarHeight - 1.dp)) {
            TABS.forEachIndexed { i, t ->
                val on = i == selected
                val c = if (on) p.ink else p.faint
                Column(
                    Modifier.weight(1f).fillMaxHeight().selectable(on, role = Role.Tab) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    GlyphImage(t.icon, c)
                    Spacer(Modifier.height(2.dp))
                    androidx.compose.material3.Text(t.label, color = c, fontSize = labelSize, lineHeight = labelSize * 1.3f,
                        fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Trips

private fun dayLabel(ms: Long): String {
    val c = Calendar.getInstance(); val y = c.get(Calendar.YEAR); val d = c.get(Calendar.DAY_OF_YEAR)
    c.timeInMillis = ms
    val sameYear = c.get(Calendar.YEAR) == y
    return when {
        sameYear && c.get(Calendar.DAY_OF_YEAR) == d -> "Today"
        (sameYear && c.get(Calendar.DAY_OF_YEAR) == d - 1) -> "Yesterday"
        else -> java.text.SimpleDateFormat(if (sameYear) "EEEE, MMM d" else "MMM d, yyyy", java.util.Locale.getDefault()).format(Date(ms))
    }
}
private fun timeOnly(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms)).replace(' ', NBSP).replace('\u202F', NBSP)

@Composable
fun TripsScreen(ui: AppUi, a: AppActions, bottomInset: Dp, onOpen: (Receipt) -> Unit) {
    val p = LocalPalette.current
    LaunchedEffect(Unit) { a.loadTrips() }
    val trips = ui.trips
    Column(Modifier.fillMaxSize().background(p.bg)) {
        TabHeader("Trips")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            when {
                trips == null && ui.tripsError != null -> {
                    Spacer(Modifier.height(24.dp))
                    Txt(ui.tripsError, Type.body, p.bad)
                    Spacer(Modifier.height(16.dp))
                    Pill("Try again", Modifier.fillMaxWidth(), primary = false, onClick = a::loadTrips)
                }
                trips == null -> { Spacer(Modifier.height(24.dp)); BusyLine("Loading your trips…") }
                trips.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Mark(48.dp, slot = null, ink = p.strong)
                    Spacer(Modifier.height(16.dp))
                    Txt("No trips yet", Type.body, bold = true, align = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Txt("Your receipts show up here after you walk out.", Type.caption, p.muted, align = TextAlign.Center, balanced = true)
                }
                else -> {
                    if (ui.tripsError != null) { Spacer(Modifier.height(16.dp)); Txt(ui.tripsError, Type.caption, p.bad) }
                    trips.groupBy { dayLabel(it.paidAt) }.forEach { (day, list) ->
                        Spacer(Modifier.height(24.dp))
                        Eyebrow(day)
                        Spacer(Modifier.height(8.dp))
                        Hairline()
                        list.forEach { r -> key(r.id) { TripRow(r) { onOpen(r) }; Hairline() } }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.height(bottomInset))
        }
    }
}

@Composable
private fun TripRow(r: Receipt, onOpen: () -> Unit) {
    val p = LocalPalette.current
    val due = r.status != "paid"
    val stacked = big
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClickLabel = "Open receipt", onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = "${r.storeName}, ${timeOnly(r.paidAt)}, ${items(r.count)}, ${money(r.total)}" + if (due) ", payment due" else ""
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(r.storeName, Type.body, bold = true)
            Txt("${timeOnly(r.paidAt)} · ${items(r.count)}", Type.caption, p.muted, num = true)
            if (stacked) {
                Txt(money(r.total), Type.body, num = true)
                if (due) Txt("Payment due", Type.caption, p.bad, bold = true)
            }
        }
        if (!stacked) {
            Spacer(Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.End) {
                Txt(money(r.total), Type.body, num = true)
                if (due) Txt("Payment due", Type.caption, p.bad, bold = true)
            }
        }
    }
}

// ---------------------------------------------------------------- Account

@Composable
fun AccountScreen(ui: AppUi, a: AppActions, bottomInset: Dp) {
    val p = LocalPalette.current
    val sheets = LocalSheets.current
    val acct = ui.account
    Column(Modifier.fillMaxSize().background(p.bg)) {
        TabHeader(acct.name.ifEmpty { "Account" }, acct.email)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(32.dp))
            Eyebrow("Your bags")
            Spacer(Modifier.height(8.dp))
            Hairline()
            acct.bags.forEach { b ->
                key(b.id) {
                    // The demo bag has no hardware: it is "Connected" while it stands in for a real bag, otherwise just "Demo".
                    val connected = if (b.isDemo) ui.demoActive else ui.connectedBag == b.bagId
                    val word = if (connected) "Connected" else if (b.isDemo) "Demo" else "Not nearby"
                    ListRow(b.name, if (b.isDemo) "Demo bag, no hardware" else bagDisplayName(b.bagId), icon = Glyph.bag,
                        trailing = word, trailingColor = if (connected) p.good else null, chevron = true,
                        description = "${b.name}, ${word.lowercase()}") {
                        sheets.show { BagSheet(b, onRename = { n, done -> a.renameBag(b.id, n, done) }, onUnpair = { done -> a.unpairBag(b.id, done) }, onClose = sheets::hide) }
                    }
                    Hairline()
                }
            }
            ListRow("Pair a bag", icon = Glyph.plus, onClick = a::openPairing)
            Hairline()
            Spacer(Modifier.height(32.dp))
            Eyebrow("Permissions")
            Spacer(Modifier.height(8.dp))
            Hairline()
            val perm = @Composable { title: String, icon: ImageVector, ok: Boolean, word: String ->
                ListRow(title, icon = icon, trailing = word, trailingColor = if (ok) null else p.bad, chevron = true,
                    description = "$title, $word. Opens settings.", onClick = a::appSettings)
                Hairline()
            }
            perm("Nearby devices", Glyph.nearby, ui.perms.nearby, if (ui.perms.nearby) "Allowed" else "Off")
            perm("Location", Glyph.location, ui.perms.location, if (ui.perms.location) "Precise" else if (ui.perms.approximate) "Approximate" else "Off")
            perm("Notifications", Glyph.bell, true, if (ui.perms.notifications) "Allowed" else "Off")
            Spacer(Modifier.height(32.dp))
            Hairline()
            ListRow("Sign out", bold = false) { sheets.show { SignOutSheet(a, sheets::hide) } }
            Hairline()
            ListRow("Delete account", titleColor = p.bad, bold = false) { sheets.show { DeleteSheet(a, sheets::hide) } }
            Hairline()
            Spacer(Modifier.height(24.dp))
            Txt("Bag It ${ui.version}", Type.caption, p.faint)
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.height(bottomInset))
        }
    }
}

@Composable
fun BagSheet(b: BagInfo, onRename: (String, (String?) -> Unit) -> Unit, onUnpair: ((String?) -> Unit) -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    var name by remember { mutableStateOf(b.name) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Txt(b.name, Type.title, bold = true, modifier = Modifier.semantics { heading() })
    Txt(if (b.isDemo) "Demo bag, no hardware" else bagDisplayName(b.bagId), Type.caption, p.muted)
    Spacer(Modifier.height(24.dp))
    if (!confirm) {
        Field("Name", name, { name = it.take(30) }, enabled = !busy, capitalization = KeyboardCapitalization.Sentences, ime = ImeAction.Done)
        Spacer(Modifier.height(16.dp))
        ErrorLine(err)
        BusyPill("Save name", busy) { busy = true; err = null; onRename(name.trim()) { e -> busy = false; err = e; if (e == null) onClose() } }
        Spacer(Modifier.height(8.dp))
        TextAction("Unpair this bag", Modifier.fillMaxWidth(), color = p.bad, enabled = !busy) { confirm = true }
    } else {
        Txt("Unpair ${b.name}?", Type.body, bold = true)
        Spacer(Modifier.height(4.dp))
        Txt("This phone stops listening to it. You can pair it again any time.", Type.body, p.muted)
        Spacer(Modifier.height(24.dp))
        ErrorLine(err)
        BusyPill("Unpair", busy, primary = false, danger = true) { busy = true; err = null; onUnpair { e -> busy = false; err = e; if (e == null) onClose() } }
        Spacer(Modifier.height(8.dp))
        TextAction("Cancel", Modifier.fillMaxWidth(), enabled = !busy) { confirm = false }
    }
}

@Composable
private fun SignOutSheet(a: AppActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Txt("Sign out of Bag It?", Type.title, bold = true, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
    Txt("Your cards and bags stay with your account. This phone stops paying when you walk out.", Type.body, p.muted)
    Spacer(Modifier.height(24.dp))
    ErrorLine(err)
    BusyPill("Sign out", busy) { busy = true; err = null; a.signOut { e -> busy = false; err = e; if (e == null) onClose() } }
    Spacer(Modifier.height(8.dp))
    TextAction("Cancel", Modifier.fillMaxWidth(), enabled = !busy, onClick = onClose)
}

@Composable
fun DeleteSheet(a: AppActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    fun go() { if (busy || pw.isEmpty()) return; busy = true; err = null; a.deleteAccount(pw) { e -> busy = false; err = e; pw = ""; if (e == null) onClose() } }
    SecureScreen()
    Txt("Delete your account?", Type.title, bold = true, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
    Txt("Your saved cards, paired bags and account are removed. Receipts stay with the stores you shopped at.", Type.body, p.muted)
    Spacer(Modifier.height(24.dp))
    Field("Password", pw, { pw = it.take(128) }, enabled = !busy, keyboard = KeyboardType.Password, ime = ImeAction.Done, onIme = { go() },
        visual = PasswordVisualTransformation(), hint = "To confirm it's you")
    Spacer(Modifier.height(16.dp))
    ErrorLine(err)
    BusyPill("Delete account", busy, primary = false, danger = true) { go() }
    Spacer(Modifier.height(8.dp))
    TextAction("Cancel", Modifier.fillMaxWidth(), enabled = !busy, onClick = onClose)
}
