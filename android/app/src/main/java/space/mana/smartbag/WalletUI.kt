package space.mana.smartbag

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
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
import java.util.Calendar

// ======================================================================================
// Wallet: add a card (number + CVC checked on the phone, never sent), the Wallet tab, card sheets.
// ======================================================================================

/** Card label that never wraps inside "•••• 4242" (full server label, with nickname: dock and receipt use this). */
fun CardInfo.shown() = card(label)
/** Wallet and picker: "<Brand> •••• 4242" as the title, "Expires 12/29 · <nickname>" as the caption. */
fun CardInfo.title() = card("$brand •••• $last4")
fun CardInfo.caption() = "Expires $expiry" + if (nickname.isNotEmpty()) " · $nickname" else ""

/**
 * Add a card. The number and CVC live only in this screen's memory (remember, not rememberSaveable, so they
 * never reach the saved-state bundle) and are cleared the moment the form is submitted.
 */
@Composable
fun AddCardScreen(
    first: Boolean, step: Int?, onBack: (() -> Unit)?, onLater: (() -> Unit)?,
    onSubmit: (NewCard, (String?) -> Unit) -> Unit, onDone: () -> Unit,
    preview: List<String>? = null, previewTried: Boolean = false, previewError: String? = null,
) {
    val p = LocalPalette.current
    val focus = LocalFocusManager.current
    var number by remember { mutableStateOf(preview?.getOrNull(0) ?: "") }
    var exp by remember { mutableStateOf(preview?.getOrNull(1) ?: "") }
    var cvc by remember { mutableStateOf(preview?.getOrNull(2) ?: "") }
    var nick by remember { mutableStateOf(preview?.getOrNull(3) ?: "") }
    var makeDefault by remember { mutableStateOf(true) }
    var tried by remember { mutableStateOf(previewTried) }
    var busy by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(previewError) }
    val brand = Cards.brand(number)
    val now = remember { Calendar.getInstance() }
    val nowY = now.get(Calendar.YEAR); val nowM = now.get(Calendar.MONTH) + 1
    val numErr = if (tried) Cards.numberError(number) else null
    val expErr = if (tried) Cards.expiryError(exp, nowY, nowM) else null
    val cvcErr = if (tried) Cards.cvcError(cvc, brand) else null
    val expF = remember { FocusRequester() }; val cvcF = remember { FocusRequester() }; val nickF = remember { FocusRequester() }

    fun submit() {
        tried = true
        if (busy || Cards.numberError(number) != null || Cards.expiryError(exp, nowY, nowM) != null || Cards.cvcError(cvc, brand) != null) return
        val (m, y) = Cards.expiry(exp)!!
        val req = NewCard(brand, number.takeLast(4), m, y, nick.trim(), makeDefault || first)
        // Only brand, last four and expiry go anywhere. Forget the number and the security code now.
        number = ""; cvc = ""; tried = false
        focus.clearFocus()
        busy = true; server = null
        onSubmit(req) { err -> busy = false; server = err; if (err == null) onDone() }
    }

    SecureScreen()
    Column(Modifier.fillMaxSize().background(p.bg)) {
        if (step != null) { Spacer(Modifier.statusBarsPadding()); StepBar(step); Spacer(Modifier.height(8.dp)) } else TopBar(if (busy) null else onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(if (step != null) 16.dp else 8.dp))
            ScreenTitle("Add a card", if (first) "Leaving the store pays with this card. No checkout." else null)
            Spacer(Modifier.height(32.dp))
            Field("Card number", number, { number = Cards.digits(it).take(Cards.maxLength(Cards.brand(Cards.digits(it)))) },
                error = numErr, enabled = !busy, keyboard = KeyboardType.NumberPassword, num = true,
                visual = CardNumberTransformation, onIme = { expF.requestFocus() },
                trailing = if (number.length >= 2 && brand != "Card") ({ Txt(brand, Type.caption, p.muted, bold = true) }) else null)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Field("Expiry", exp, { exp = Cards.digits(it).take(4) }, Modifier.weight(1f).focusRequester(expF), error = expErr, enabled = !busy,
                    keyboard = KeyboardType.Number, num = true, visual = ExpiryTransformation, placeholder = "MM/YY", onIme = { cvcF.requestFocus() })
                Field("Security code", cvc, { cvc = Cards.digits(it).take(Cards.cvcLength(brand)) }, Modifier.weight(1f).focusRequester(cvcF),
                    error = cvcErr, enabled = !busy, keyboard = KeyboardType.NumberPassword, num = true,
                    visual = PasswordVisualTransformation(), placeholder = if (brand == "Amex") "4 digits" else "3 digits", onIme = { nickF.requestFocus() })
            }
            Spacer(Modifier.height(24.dp))
            Field("Nickname (optional)", nick, { nick = it.take(24) }, Modifier.focusRequester(nickF), enabled = !busy,
                capitalization = KeyboardCapitalization.Words, ime = ImeAction.Done, onIme = { submit() }, placeholder = "Personal, Work…")
            Spacer(Modifier.height(16.dp))
            if (!first) InkSwitch(makeDefault, "Pay with this card", "Use it when you walk out.", enabled = !busy) { makeDefault = it }
            Spacer(Modifier.height(16.dp))
            TextAction("Use demo card", enabled = !busy, modifier = Modifier.offset(x = (-12).dp)) {
                number = "4242424242424242"; exp = "1229"; cvc = "123"; nick = "Demo"; tried = false
            }
            Spacer(Modifier.height(8.dp))
            Txt("Payments are simulated. Only the card type and last four digits are saved; the full number never leaves your phone.",
                Type.caption, p.faint)
            Spacer(Modifier.height(24.dp))
        }
        BottomArea {
            ErrorLine(server)
            BusyPill("Add card", busy) { submit() }
            if (onLater != null) { Spacer(Modifier.height(4.dp)); TextAction("Add later", Modifier.fillMaxWidth(), color = p.muted, enabled = !busy, onClick = onLater) }
        }
    }
}

/** Wallet tab. */
@Composable
fun WalletScreen(acct: AccountState, bottomInset: Dp, onCard: (CardInfo) -> Unit, onAdd: () -> Unit) {
    val p = LocalPalette.current
    val def = acct.defaultCard
    val others = acct.cards.filter { !it.isDefault }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        TabHeader("Wallet")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            if (acct.cards.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Mark(48.dp, slot = null, ink = p.strong)
                    Spacer(Modifier.height(16.dp))
                    Txt("No cards yet", Type.body, bold = true, align = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Txt("Add a card and leaving the store pays for you.", Type.caption, p.muted, align = TextAlign.Center, balanced = true)
                }
                Pill("Add a card", Modifier.fillMaxWidth(), onClick = onAdd)
            } else {
                Spacer(Modifier.height(24.dp))
                if (def != null) {
                    Eyebrow("Pays when you walk out")
                    Spacer(Modifier.height(8.dp))
                    Hairline()
                    DefaultCardRow(def) { onCard(def) }
                    Hairline()
                    Spacer(Modifier.height(32.dp))
                }
                if (others.isNotEmpty()) {
                    Eyebrow("Other cards")
                    Spacer(Modifier.height(8.dp))
                    Hairline()
                    others.forEach { c ->
                        key(c.id) {
                            ListRow(c.title(), c.caption(),
                                icon = Glyph.card, chevron = true, description = "${c.label}, expires ${c.expiry}") { onCard(c) }
                            Hairline()
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
                Pill("Add a card", Modifier.fillMaxWidth(), primary = false, onClick = onAdd)
            }
            Spacer(Modifier.height(16.dp))
            Txt("Payments are simulated. No money moves.", Type.caption, p.faint)
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.height(bottomInset))
        }
    }
}

internal const val NBSP = '\u00A0'

@Composable
private fun DefaultCardRow(c: CardInfo, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 88.dp)
            .clickableRow(onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${c.label}, expires ${c.expiry}. Pays when you walk out." }
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphImage(Glyph.card, p.muted)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Txt(c.brand, Type.caption, p.muted, bold = true)
            Txt("••••$NBSP${c.last4}", Type.title, bold = true, num = true)
            Txt(c.caption(), Type.caption, p.muted, num = true)
        }
        GlyphImage(Glyph.chevron, p.faint, 20.dp)
    }
}

internal fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(role = Role.Button, onClick = onClick)

/** Shop dock -> "which card pays": tap one to make it the default, or add a new one. */
@Composable
fun CardPickerSheet(acct: AccountState, onPick: (CardInfo, (String?) -> Unit) -> Unit, onAdd: () -> Unit, onClose: () -> Unit = {}) {
    val p = LocalPalette.current
    var busy by remember { mutableStateOf<Long?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    Txt("Pay with", Type.title, bold = true, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(16.dp))
    ErrorLine(err)
    Hairline()
    acct.cards.forEach { c ->
        key(c.id) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clickableRow {
                    if (busy != null) return@clickableRow
                    if (c.isDefault) { onClose(); return@clickableRow }
                    busy = c.id; err = null
                    onPick(c) { e -> busy = null; err = e; if (e == null) onClose() }
                }
                    .semantics(mergeDescendants = true) { contentDescription = c.label + if (c.isDefault) ", selected" else "" }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphImage(Glyph.card, p.muted)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Txt(c.title(), Type.body, bold = true)
                    Txt(c.caption(), Type.caption, p.muted, num = true)
                }
                if (busy == c.id) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), color = p.ink, strokeWidth = 2.dp)
                else if (c.isDefault) GlyphImage(Glyph.check, p.ink, 20.dp)
            }
            Hairline()
        }
    }
    ListRow("Add a card", icon = Glyph.plus) { onAdd() }
}

/** Wallet -> one card: make it pay, or remove it (inline confirmation says which card takes over). */
@Composable
fun CardSheet(acct: AccountState, c: CardInfo, onDefault: (CardInfo, (String?) -> Unit) -> Unit, onRemove: (CardInfo, (String?) -> Unit) -> Unit, onClose: () -> Unit, initialConfirm: Boolean = false) {
    val p = LocalPalette.current
    var confirm by remember { mutableStateOf(initialConfirm) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Txt(c.title(), Type.title, bold = true, modifier = Modifier.semantics { heading() })
    Txt(c.caption() + if (c.isDefault) " · Pays when you walk out" else "", Type.caption, p.muted, num = true)
    Spacer(Modifier.height(16.dp))
    if (!confirm) {
        Hairline()
        if (!c.isDefault) {
            ListRow("Pay with this card", icon = Glyph.check) {
                busy = true; onDefault(c) { e -> busy = false; err = e; if (e == null) onClose() }
            }
            Hairline()
        }
        ListRow("Remove card", titleColor = p.bad, icon = Glyph.card) { confirm = true }
        Hairline()
        Spacer(Modifier.height(8.dp))
        ErrorLine(err)
    } else {
        // Which card takes over: the server promotes the oldest remaining card.
        val next = acct.cards.filter { it.id != c.id }.minByOrNull { it.id }
        val what = when {
            !c.isDefault -> "It won't be used again unless you add it back."
            next != null -> "${next.title()} will pay when you walk out."
            else -> "With no card, leaving the store won't pay. You'll pay at the store."
        }
        Txt("Remove ${c.title()}?", Type.body, bold = true)
        Spacer(Modifier.height(4.dp))
        Txt(what, Type.body, p.muted)
        Spacer(Modifier.height(24.dp))
        ErrorLine(err)
        BusyPill("Remove card", busy, primary = false, danger = true) {
            busy = true; err = null; onRemove(c) { e -> busy = false; err = e; if (e == null) onClose() }
        }
        Spacer(Modifier.height(8.dp))
        TextAction("Cancel", Modifier.fillMaxWidth(), enabled = !busy) { confirm = false }
    }
}
