package space.mana.smartbag

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ======================================================================================
// Bag It kit, part 2 (3.2): forms, step bar, sheets, list rows. Same system as UI.kt: zinc, ink pills,
// hairlines (fields are a label over an underline, not boxes), coral never used here.
// ======================================================================================

/** Setup step bar: three segments (Permissions, Pair your bag, Add a card); done and current are ink. */
/** (index, count) of the current setup step, from the queue built at sign-in; null outside setup. */
val LocalSetupProgress = staticCompositionLocalOf<Pair<Int, Int>?> { null }

@Composable
fun StepBar(stepHint: Int, ofHint: Int = 3) {
    val p = LocalPalette.current
    val (step, of) = LocalSetupProgress.current ?: (stepHint to ofHint)
    // A one-step setup (e.g. logging in on a new phone that only needs a card) shows no bar at all.
    if (of <= 1) { Spacer(Modifier.height(19.dp)); return }
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp)
            .semantics { contentDescription = "Step $step of $of" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (i in 1..of) Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(50)).background(if (i <= step) p.ink else p.hair))
    }
}

/** Top of a pushed screen: back chevron (48dp target) on the left, optional quiet action on the right. */
@Composable
fun TopBar(onBack: (() -> Unit)?, action: String? = null, onAction: () -> Unit = {}) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack)
                .semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) { GlyphImage(Glyph.back, p.ink) }
        Spacer(Modifier.weight(1f))
        if (action != null) TextAction(action, onClick = onAction)
    }
}

/** Title + optional body, the way every screen in the app opens. */
@Composable
fun ScreenTitle(title: String, body: String? = null, bodyColor: Color = LocalPalette.current.muted) {
    Txt(title, Type.title, bold = true, modifier = Modifier.semantics { heading() })
    if (body != null) { Spacer(Modifier.height(8.dp)); Txt(body, Type.body, bodyColor) }
}

/** Header of a tab: the page name (or the shopper's name) at title size, 64dp like the shop header. */
@Composable
fun TabHeader(title: String, sub: String? = null) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp).heightIn(min = 64.dp), verticalArrangement = Arrangement.Center) {
        Spacer(Modifier.height(16.dp))
        Txt(title, Type.title, bold = true, modifier = Modifier.semantics { heading() })
        if (sub != null) Txt(sub, Type.caption, p.muted)
    }
}

/** Secondary action as text only (ink, semibold), with a 48dp target. */
@Composable
fun TextAction(text: String, modifier: Modifier = Modifier, color: Color = LocalPalette.current.ink, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, Type.body, if (enabled) color else LocalPalette.current.faint, bold = true, align = TextAlign.Center) }
}

/** The primary pill with a busy state: the label gives way to a small spinner, taps are ignored. */
@Composable
fun BusyPill(text: String, busy: Boolean, modifier: Modifier = Modifier, primary: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(50)
    val fg = when { danger -> p.bad; primary -> p.onInk; else -> p.ink }
    Box(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
            .background(if (primary && !danger) p.ink else Color.Transparent)
            .then(if (primary && !danger) Modifier else Modifier.border(1.dp, if (danger) p.bad.copy(alpha = 0.6f) else p.strong, shape))
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
            .semantics { if (busy) stateDescription = "Working" }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = fg, strokeWidth = 2.dp)
        else Txt(text, Type.body, fg, bold = true, align = TextAlign.Center)
    }
}

/** A red line above the pinned button: what the server said, verbatim (it is already shopper copy). */
@Composable
fun ErrorLine(text: String?) {
    if (text == null) return
    Txt(text, Type.caption, LocalPalette.current.bad, modifier = Modifier.padding(bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Assertive })
}

/**
 * Text field: caption label, the value on an underline (hairline; ink when focused; red with an error),
 * then the error or a hint. [trailing] sits at the right end of the line (brand name, Show/Hide).
 */
@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    error: String? = null, hint: String? = null, enabled: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text, ime: ImeAction = ImeAction.Next, onIme: () -> Unit = {},
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    visual: VisualTransformation = VisualTransformation.None, num: Boolean = false, placeholder: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val line = when { error != null -> p.bad; focused -> p.ink; else -> p.strong }
    Column(modifier.fillMaxWidth()) {
        Txt(label, Type.caption, p.muted, bold = true)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder != null) Txt(placeholder, Type.body, p.faint, num = num)
                BasicTextField(
                    value, onChange, enabled = enabled, singleLine = true, interactionSource = src,
                    visualTransformation = visual,
                    keyboardOptions = KeyboardOptions(capitalization = capitalization, autoCorrect = false, keyboardType = keyboard, imeAction = ime),
                    keyboardActions = KeyboardActions(onAny = { onIme() }),
                    cursorBrush = SolidColor(p.ink),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = if (enabled) p.ink else p.muted, fontSize = Type.body, lineHeight = 24.sp,
                        fontFeatureSettings = if (num) "tnum" else null,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics {
                        contentDescription = label
                        if (error != null) error(error)
                    },
                )
            }
            if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
        }
        Box(Modifier.fillMaxWidth().height(if (focused || error != null) 2.dp else 1.dp).background(line))
        when {
            error != null -> { Spacer(Modifier.height(6.dp)); Txt(error, Type.caption, p.bad) }
            hint != null -> { Spacer(Modifier.height(6.dp)); Txt(hint, Type.caption, p.faint) }
        }
    }
}

/** On/off switch in ink (no coral, no Material tint): a 52x32 track with a sliding knob. */
@Composable
fun InkSwitch(checked: Boolean, label: String, sub: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .semantics(mergeDescendants = true) { stateDescription = if (checked) "On" else "Off" }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(label, Type.body)
            if (sub != null) Txt(sub, Type.caption, p.muted)
        }
        Spacer(Modifier.width(16.dp))
        Box(
            Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(50)).background(if (checked) p.ink else p.hair).padding(4.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(24.dp).clip(RoundedCornerShape(50)).background(if (checked) p.onInk else p.surface)) }
    }
}

/**
 * One row of a hairline list: optional glyph, title, sub line, trailing text or chevron. 64dp minimum.
 * [titleColor] lets destructive rows ("Delete account") read red.
 */
@Composable
fun ListRow(
    title: String, sub: String? = null, icon: ImageVector? = null, trailing: String? = null, trailingColor: Color? = null,
    chevron: Boolean = false, titleColor: Color? = null, bold: Boolean = true, subColor: Color? = null,
    description: String? = null, onClick: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { if (description != null) contentDescription = description }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { GlyphImage(icon, titleColor ?: p.muted); Spacer(Modifier.width(16.dp)) }
        val stacked = big && trailing != null
        Column(Modifier.weight(1f)) {
            Txt(title, Type.body, titleColor ?: p.ink, bold = bold)
            if (sub != null) Txt(sub, Type.caption, subColor ?: p.muted, num = true)
            if (stacked) Txt(trailing!!, Type.caption, trailingColor ?: p.muted, bold = trailingColor != null, num = true)
        }
        if (trailing != null && !stacked) { Spacer(Modifier.width(16.dp)); Txt(trailing, Type.caption, trailingColor ?: p.muted, bold = trailingColor != null, num = true) }
        if (chevron) { Spacer(Modifier.width(8.dp)); GlyphImage(Glyph.chevron, p.faint, 20.dp) }
    }
}

/** Thin indeterminate line for "working on it"; with reduced motion, a quiet caption instead. */
@Composable
fun BusyLine(text: String = "Loading…") {
    val p = LocalPalette.current
    if (!LocalReduceMotion.current) LinearProgressIndicator(Modifier.width(96.dp).height(2.dp), color = p.faint, trackColor = p.hair)
    else Txt(text, Type.caption, p.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** Keeps a screen with card numbers or passwords out of screenshots and the Recents thumbnail. */
@Composable
fun SecureScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(Unit) {
        var c: android.content.Context? = ctx
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        val w = (c as? android.app.Activity)?.window
        w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

// ---------- sheets: drawn in the layout (not a popup window), above the tab bar

class Sheets {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null); private set
    fun show(c: @Composable () -> Unit) { content = c }
    fun hide() { content = null }
}
val LocalSheets = staticCompositionLocalOf { Sheets() }

/** Scrim + bottom sheet (surface, 20dp top corners, hairline, no shadow). Back or a tap outside closes it. */
@Composable
fun SheetLayer(sheets: Sheets) {
    val p = LocalPalette.current
    val reduce = LocalReduceMotion.current
    val c = sheets.content
    var last by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    if (c != null) last = c
    if (c != null) BackHandler { sheets.hide() }
    AnimatedVisibility(c != null, enter = fadeIn(tween(if (reduce) 0 else 160)), exit = fadeOut(tween(if (reduce) 0 else 160))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (p.dark) 0.6f else 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { sheets.hide() })
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(c != null,
            enter = if (reduce) fadeIn(tween(0)) else slideInVertically(tween(220)) { it },
            exit = if (reduce) fadeOut(tween(0)) else slideOutVertically(tween(180)) { it }) {
            val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            val maxH = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.9f).dp
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxH).clip(shape).background(p.surface).border(1.dp, p.hair, shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .navigationBarsPadding().imePadding()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .semantics { paneTitle = "Sheet" }
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
            ) {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(50)).background(p.strong))
                }
                last?.let { c2 -> key(c2) { c2() } }
            }
        }
    }
}
