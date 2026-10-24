package space.mana.smartbag

import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ======================================================================================
// Bag It design system. Spec: Mana apps/smart-bag/app/mobile-design/android.
// 60 zinc neutrals / 30 ink / 10 coral. Coral means one thing: the bag's light / money moving.
// Green and red only for real status. Buttons are ink pills. Hairlines, no card-in-card.
// ======================================================================================

data class Palette(
    val bg: Color, val surface: Color, val ink: Color, val muted: Color, val faint: Color,
    val hair: Color, val strong: Color, val coral: Color, val good: Color, val bad: Color, val onInk: Color, val dark: Boolean,
)

val LightPalette = Palette(
    bg = Color(0xFFFAFAFA), surface = Color(0xFFFFFFFF), ink = Color(0xFF18181B), muted = Color(0xFF52525B), faint = Color(0xFF71717A),
    hair = Color(0xFFE4E4E7), strong = Color(0xFFD4D4D8), coral = Color(0xFFE8654A), good = Color(0xFF157A4A), bad = Color(0xFFB42318),
    onInk = Color(0xFFFAFAFA), dark = false,
)
val DarkPalette = Palette(
    bg = Color(0xFF0B0B0C), surface = Color(0xFF151517), ink = Color(0xFFF4F4F5), muted = Color(0xFFA1A1AA), faint = Color(0xFF8E8E97),
    hair = Color(0xFF27272A), strong = Color(0xFF3F3F46), coral = Color(0xFFE8654A), good = Color(0xFF34C47C), bad = Color(0xFFF07171),
    onInk = Color(0xFF18181B), dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }
/** True when the system "Remove animations" setting is on (or in screenshot tests): show end states, no motion. */
val LocalReduceMotion = staticCompositionLocalOf { false }
/** False inside the tab host: the tab bar already sits on the navigation bar, so bottom bars must not pad for it again. */
val LocalNavPad = staticCompositionLocalOf { true }

/** Four sizes, two weights (400 / 600). Numbers use tabular figures; the exit code is monospace. */
object Type {
    val display = 34.sp   // the receipt amount, the welcome headline
    val title = 24.sp     // store name, screen titles, the bag total
    val body = 16.sp      // everything you read
    val caption = 13.sp   // labels and secondary lines
}
val Mono = FontFamily.Monospace
private const val NB = '\u00A0'

fun money(c: Int): String = "\$" + String.format(Locale.US, "%.2f", c / 100.0)
fun distance(m: Int): String = if (m < 1000) "$m${NB}m" else String.format(Locale.US, if (m < 10_000) "%.1f${NB}km" else "%.0f${NB}km", m / 1000.0)
fun items(n: Int): String = "$n item" + if (n == 1) "" else "s"
/** Card labels never wrap inside "•••• 4242". */
fun card(s: String): String = s.replace(' ', NB)
/** "2:54 PM" today, "Oct 23, 2:54 PM" otherwise; never wraps inside the time. */
fun whenText(ms: Long): String {
    val t = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
    val c = Calendar.getInstance(); val today = c.get(Calendar.DAY_OF_YEAR) to c.get(Calendar.YEAR)
    c.timeInMillis = ms
    val s = if ((c.get(Calendar.DAY_OF_YEAR) to c.get(Calendar.YEAR)) == today) t
            else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms)) + ", " + t
    return s.replace(' ', NB).replace('\u202F', NB)
}

@Composable
fun BagItTheme(dark: Boolean = isSystemInDarkTheme(), reduceMotion: Boolean? = null, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val ctx = LocalContext.current
    val reduce = reduceMotion ?: remember {
        try { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Exception) { false }
    }
    val scheme = if (dark) darkColorScheme(primary = p.ink, onPrimary = p.onInk, background = p.bg, surface = p.surface, onSurface = p.ink, onBackground = p.ink)
                 else lightColorScheme(primary = p.ink, onPrimary = p.onInk, background = p.bg, surface = p.surface, onSurface = p.ink, onBackground = p.ink)
    CompositionLocalProvider(LocalPalette provides p, LocalReduceMotion provides reduce) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

internal val big: Boolean @Composable get() = LocalDensity.current.fontScale > 1.3f

@Composable
fun Txt(
    text: String, size: TextUnit = Type.body, color: Color = LocalPalette.current.ink, bold: Boolean = false,
    num: Boolean = false, mono: Boolean = false, balanced: Boolean = false,
    modifier: Modifier = Modifier, align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE, letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    val lh = when (size) { Type.display -> 40.sp; Type.title -> 30.sp; Type.caption -> 18.sp; else -> 24.sp }
    Text(
        text, modifier = modifier, color = color, fontSize = size, lineHeight = lh,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, fontFamily = if (mono) Mono else null,
        textAlign = align, maxLines = maxLines, overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        letterSpacing = letterSpacing,
        style = MaterialTheme.typography.bodyLarge.copy(
            lineBreak = if (balanced || size >= Type.title) LineBreak.Heading else LineBreak.Paragraph,
            fontFeatureSettings = if (num) "tnum" else null,
        ),
    )
}

// ---------- brand vectors (geometry from website/brand: mark.svg, favicon.svg, logo.svg)

private const val TOTE = "M10.5 12h27a2.5 2.5 0 0 1 2.5 2.6l-1 26.6a2.8 2.8 0 0 1-2.8 2.8H12.8a2.8 2.8 0 0 1-2.8-2.8l-1-26.6A2.5 2.5 0 0 1 10.5 12zM18.9 17.5h10.2a2.4 2.4 0 0 1 0 4.8H18.9a2.4 2.4 0 0 1 0-4.8z"
private const val SLOT = "M18.9 17.5h10.2a2.4 2.4 0 0 1 0 4.8H18.9a2.4 2.4 0 0 1 0-4.8z"
// favicon.svg: the same tote redrawn for small sizes, with a bigger slot (brand.md: use below 24 px).
private const val TOTE_S = "M4.5 4h23a2 2 0 0 1 2 2.1l-.8 21.6a2.4 2.4 0 0 1-2.4 2.3H5.7a2.4 2.4 0 0 1-2.4-2.3L2.5 6.1A2 2 0 0 1 4.5 4zM12.75 8.5h6.5a2.25 2.25 0 0 1 0 4.5h-6.5a2.25 2.25 0 0 1 0-4.5z"
private const val SLOT_S = "M12.75 8.5h6.5a2.25 2.25 0 0 1 0 4.5h-6.5a2.25 2.25 0 0 1 0-4.5z"
private val WORD = listOf(
    "M2 12V40", "M2 32a8 8 0 1 0 16 0a8 8 0 1 0 -16 0", "M25.5 32a8 8 0 1 0 16 0a8 8 0 1 0 -16 0", "M41.5 24V40",
    "M49.5 32a8 8 0 1 0 16 0a8 8 0 1 0 -16 0", "M65.5 24V43a8 8 0 0 1-8 8h-4", "M83 24V40", "M95 14V35a5 5 0 0 0 5 5h1.5M90 24H100",
)
private const val DOT = "M79.9 15.2a3.1 3.1 0 1 0 6.2 0a3.1 3.1 0 1 0 -6.2 0"

private fun layer(name: String, d: String, w: Float, tx: Float, ty: Float, evenOdd: Boolean) =
    ImageVector.Builder(name, w.dp, w.dp, w, w).apply {
        addGroup(translationX = tx, translationY = ty)
        addPath(addPathNodes(d), pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero, fill = SolidColor(Color.Black))
        clearGroup()
    }.build()
// Tote and slot are separate single-colour layers, tinted at draw time (no vector rebuild while the slot animates).
private val toteL = layer("tote", TOTE, 32f, -8.5f, -12f, true)
private val slotL = layer("slot", SLOT, 32f, -8.5f, -12f, false)
private val toteS = layer("toteS", TOTE_S, 28f, -2f, -2f, true)
private val slotS = layer("slotS", SLOT_S, 28f, -2f, -2f, false)

/** The slot mark: a die-cut tote whose handle slot is the bag's status light. [slot] null = knocked out (unlit). */
@Composable
fun Mark(size: Dp, slot: Color? = LocalPalette.current.coral, ink: Color = LocalPalette.current.ink, modifier: Modifier = Modifier) {
    val small = size < 24.dp
    Box(modifier.size(size)) {
        Image(if (small) toteS else toteL, null, colorFilter = ColorFilter.tint(ink), modifier = Modifier.fillMaxSize())
        if (slot != null) Image(if (small) slotS else slotL, null, colorFilter = ColorFilter.tint(slot), modifier = Modifier.fillMaxSize())
    }
}

fun logoVector(ink: Color, slot: Color): ImageVector = ImageVector.Builder("logo", 146.dp, 45.dp, 146f, 45f).apply {
    addGroup(translationY = -9f)
    addGroup(translationX = -8f, translationY = -2f)
    addPath(addPathNodes(TOTE), pathFillType = PathFillType.EvenOdd, fill = SolidColor(ink))
    addPath(addPathNodes(SLOT), fill = SolidColor(slot))
    clearGroup()
    addGroup(translationX = 41f)
    WORD.forEach { addPath(addPathNodes(it), stroke = SolidColor(ink), strokeLineWidth = 4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
    addPath(addPathNodes(DOT), fill = SolidColor(ink))
    clearGroup()
    clearGroup()
}.build()

/** The lowercase "bag it" wordmark alone, cropped ascender to descender (y 10..53). */
private val wordmark = ImageVector.Builder("wordmark", 104.dp, 43.dp, 104f, 43f).apply {
    addGroup(translationX = 0f, translationY = -10f)
    WORD.forEach { addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
    addPath(addPathNodes(DOT), fill = SolidColor(Color.Black))
    clearGroup()
}.build()

/** Full lockup (mark + wordmark): Welcome only. */
@Composable
fun Logo(height: Dp = 28.dp) {
    val p = LocalPalette.current
    val v = remember(p) { logoVector(p.ink, p.coral) }
    Image(v, contentDescription = "Bag It", modifier = Modifier.height(height).aspectRatio(146f / 45f))
}

/** Wordmark only: the shop header, where the bag chip's tote is the one mark (and the one coral) on screen. */
@Composable
fun Wordmark(height: Dp = 24.dp) {
    Image(wordmark, contentDescription = "Bag It", colorFilter = ColorFilter.tint(LocalPalette.current.ink),
        modifier = Modifier.height(height).aspectRatio(104f / 43f))
}

/** Line icons on a 24 grid, 1.65 stroke, round caps: same family as the Mana app icons. Tinted at use. */
object Glyph {
    private fun icon(name: String, vararg d: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        d.forEach { addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 1.65f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
    }.build()
    val nearby = icon("nearby", "M7 7.5l10 9-5 4.5V3l5 4.5-10 9")
    val location = icon("location", "M12 21s-6.5-5.8-6.5-11a6.5 6.5 0 0 1 13 0c0 5.2-6.5 11-6.5 11z", "M14.5 10a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0z")
    val bell = icon("bell", "M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15z", "M10 21h4")
    val check = icon("check", "M5 12.5l4.5 4.5L19 7.5")
    val store = icon("store", "M4.5 10.5V20h15v-9.5", "M3 10.5L5 4h14l2 6.5z", "M10 20v-5h4v5")
    val receipt = icon("receipt", "M6 3h12v18l-3-2-3 2-3-2-3 2z", "M9 8h6", "M9 12h6")
    val chevron = icon("chevron", "M10 6l6 6-6 6")
    val back = icon("back", "M14 6l-6 6 6 6")
    val plus = icon("plus", "M12 5v14", "M5 12h14")
    // Tabs: the tote echoes the slot mark (die-cut body, slot under the top edge).
    val bag = icon("bag", "M4.5 5h15l-.8 14.9a1.2 1.2 0 0 1-1.2 1.1H6.5a1.2 1.2 0 0 1-1.2-1.1z", "M10 8.5h4")
    val card = icon("card", "M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M3 10h18", "M7 14.5h3")
    val person = icon("person", "M15.5 8a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0z", "M5 20a7 7 0 0 1 14 0")
}

@Composable
fun GlyphImage(v: ImageVector, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) =
    Image(v, contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = modifier.size(size))

// ---------- building blocks

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = LocalPalette.current.hair) =
    Box(modifier.fillMaxWidth().height(1.dp).background(color))

/** Ink pill (primary) or hairline pill (secondary). 52dp tall: comfortably over the 48dp target. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, primary: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier.heightIn(min = 52.dp).clip(shape)
            .background(if (primary) p.ink else Color.Transparent)
            .then(if (primary) Modifier else Modifier.border(1.dp, p.strong, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, Type.body, if (primary) p.onInk else p.ink, bold = true, align = TextAlign.Center) }
}

/** Small hairline pill with a full 48dp touch target. */
@Composable
fun SmallPill(text: String, description: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.border(1.dp, p.strong, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Txt(text, Type.caption, p.ink, bold = true)
        }
    }
}

@Composable
fun Eyebrow(text: String, color: Color = LocalPalette.current.muted) = Txt(text, Type.caption, color, bold = true)

@Composable
internal fun BottomArea(modifier: Modifier = Modifier, minBody: Dp = 0.dp, topLine: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().background(p.surface)) {
        if (topLine != null) topLine() else Hairline()
        // minBody keeps the bar as tall as the one it replaces (content bottom-aligned), so the top line does not move.
        Box(Modifier.fillMaxWidth().then(if (LocalNavPad.current) Modifier.navigationBarsPadding() else Modifier).imePadding().heightIn(min = minBody), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), content = content)
        }
    }
}

/** The coral line: one hairline-thick progress line on the top edge of the bottom bar, drawn to [fill] in [color]. */
@Composable
internal fun EdgeLine(fill: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        Hairline(Modifier.align(Alignment.Center))
        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(color))
    }
}

// ======================================================================================
// Welcome (first run)
// ======================================================================================

data class Perms(
    val nearby: Boolean, val location: Boolean, val notifications: Boolean, val asked: Boolean,
    val approximate: Boolean = false, val canAskAgain: Boolean = true,
    val background: Boolean = false,       // "Allow all the time" (geofences wake Bag It while it's closed)
) {
    val ready get() = nearby && location
}

/**
 * Permissions (setup step 1 after sign-up, or whenever a signed-in phone is missing one). [step] shows the setup
 * step bar; null = a returning shopper who lost a permission. Denied / approximate states keep the 3.1 recovery copy.
 */
@Composable
fun PermissionsScreen(perms: Perms, onContinue: () -> Unit, onSettings: () -> Unit, step: Int? = null) {
    val p = LocalPalette.current
    val denied = perms.asked && !perms.ready
    val problem = when {
        !denied -> null
        perms.approximate && perms.nearby -> "Bag It needs precise location to tell which store you're in. " +
            if (perms.canAskAgain) "Tap Ask again and choose Precise." else "In Settings, open Location and turn on Use precise location."
        !perms.location && !perms.nearby -> "Nearby devices and precise location are off. Bag It can't shop without them."
        !perms.location -> "Precise location is off. Bag It can't tell which store you're in without it."
        else -> "Nearby devices is off. Bag It can't hear your bag without it."
    }
    val actions = @Composable {
        if (problem != null) {
            if (perms.canAskAgain) {
                Pill("Ask again", Modifier.fillMaxWidth(), onClick = onContinue)
                Spacer(Modifier.height(8.dp))
                Pill("Open Settings", Modifier.fillMaxWidth(), primary = false, onClick = onSettings)
            } else Pill("Open Settings", Modifier.fillMaxWidth(), onClick = onSettings)
        } else Pill("Continue", Modifier.fillMaxWidth(), onClick = onContinue)
    }
    // At large text sizes "Continue" comes after the list (so nobody accepts prompts they never saw);
    // the recovery buttons stay pinned, because the reason is already at the top of the page.
    val inline = big && problem == null
    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
        if (step != null) StepBar(step)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(if (step != null) 24.dp else 32.dp))
            if (problem != null) {
                Txt("Almost there", Type.title, bold = true, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Txt(problem, Type.body, p.bad, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            } else {
                Txt("Set up your phone", Type.title, bold = true, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Txt("Bag It needs these to hear your bag and to know when you leave the store.", Type.body, p.muted)
            }
            Spacer(Modifier.height(32.dp))
            Eyebrow("Bag It needs")
            Spacer(Modifier.height(8.dp))
            Need(Glyph.nearby, "Nearby devices", "To hear from your bag.", if (perms.nearby) "ok" else if (perms.asked) "Off" else "")
            Hairline()
            Need(Glyph.location, "Precise location", "To know which store you're in, and when you've left.",
                if (perms.location) "ok" else if (perms.approximate) "Approximate" else if (perms.asked) "Off" else "")
            Hairline()
            Need(Glyph.bell, "Notifications", "For your receipt. Optional.", if (perms.notifications) "ok" else "")
            Spacer(Modifier.height(24.dp))
            if (inline) { actions(); Spacer(Modifier.height(24.dp)); Spacer(Modifier.navigationBarsPadding()) }
        }
        if (!inline) BottomArea { actions() }
    }
}

/** [state]: "ok" = allowed (green check), "" = not asked yet, anything else = shown in red ("Off", "Approximate"). */
@Composable
private fun Need(icon: ImageVector, title: String, why: String, state: String) {
    val p = LocalPalette.current
    val spoken = when (state) { "ok" -> " Allowed."; "" -> ""; else -> " $state." }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 16.dp).semantics(mergeDescendants = true) {
            contentDescription = "$title. $why$spoken"
        },
        verticalAlignment = Alignment.Top,
    ) {
        val stacked = big
        GlyphImage(icon, p.muted)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, Type.body, bold = true)
            if (stacked && state != "" && state != "ok") Txt(state, Type.caption, p.bad, bold = true)
            Txt(why, Type.caption, p.muted)
        }
        when (state) {
            "ok" -> { Spacer(Modifier.width(8.dp)); GlyphImage(Glyph.check, p.good, 20.dp, Modifier.padding(top = 2.dp)) }
            "" -> {}
            else -> if (!stacked) { Spacer(Modifier.width(8.dp)); Txt(state, Type.caption, p.bad, bold = true) }
        }
    }
}

// ======================================================================================
// Shop: one screen, state driven
// ======================================================================================

data class ShopUi(
    val bag: BagState, val store: StoreState, val payment: String, val total: Int, val count: Int,
    val lines: List<Line>, val paid: Receipt?, val flash: Pair<String, Int>?, val syncError: String?,
    val lastTrip: Receipt? = null,
    val noBag: Boolean = false,          // signed in with no paired bag: the chip and the dock ask to pair one
    val noCard: Boolean = false,         // empty wallet: walk-outs would be payment due
)

interface ShopActions {
    fun connectBag()
    fun locationSettings()
    fun retry()
    fun remove(sku: String)
    fun finishReceipt()
    fun demoDrop()
    fun demoWalkOut()
    fun openPairing() {}
    fun openAddCard() {}
    /** The dock's "Paying with <card>" line: the card picker sheet. */
    fun chooseCard() {}
}

private val BagState.connected get() = this is BagState.Connected
/** Off only exists for the moment before the first connect attempt, so it reads as "looking", not as a failure. */
private val BagState.busy get() = this is BagState.Connecting || this == BagState.Searching || this == BagState.Off
private val ShopUi.leaving get() = (store as? StoreState.InStore)?.leaving == true
private val ShopUi.blocked get() = store == StoreState.NoLocation || store is StoreState.Offline
private val ShopUi.needsPairing get() = noBag && !bag.connected
private val ShopUi.unsold get() = if (store is StoreState.InStore) lines.filter { !it.sold }.sumOf { it.qty } else 0

/**
 * The shop screen and the receipt share one slot. The walk-out charge cross-fades the shop into the receipt
 * while the coral line on the bottom edge finishes drawing (it stays where the eye left it).
 */
@Composable
fun ShopScreen(s: ShopUi, a: ShopActions, bottomInset: Dp = 0.dp, onFullScreen: (Boolean) -> Unit = {}) {
    val reduce = LocalReduceMotion.current
    var reviewing by rememberSaveable { mutableStateOf(-1L) }        // a "Last trip" receipt reopened by the shopper
    val live = s.paid
    // The charge empties the bag and clears the store in the same update that brings the receipt. While the shop
    // fades out, keep showing it as it was a moment ago (leaving, half-drawn line), not the emptied state.
    val frozen = remember { arrayOfNulls<ShopUi>(1) }
    if (live == null) frozen[0] = s
    val barBody = remember { floatArrayOf(0f) }                     // height of the walk-out bar's body (px), for the receipt
    val density = LocalDensity.current
    val review = s.lastTrip?.takeIf { it.id == reviewing && live == null }
    val navPadOff = !LocalNavPad.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val target: Pair<Receipt, Boolean>? = live?.let { it to true } ?: review?.let { it to false }
    val full = target != null
    LaunchedEffect(full) { onFullScreen(full) }
    AnimatedContent(
        targetState = target,
        contentKey = { it?.first?.id },
        transitionSpec = { fadeIn(tween(if (reduce) 0 else 240, delayMillis = if (reduce) 0 else 60)) togetherWith fadeOut(tween(if (reduce) 0 else 160)) },
        label = "receipt",
    ) { t ->
        if (t != null) {
            val done = { if (t.second) a.finishReceipt() else reviewing = -1L }
            BackHandler { done() }
            // In the tab host the walk-out bar sits above the tab bar: the receipt's bar is that much taller, so the
            // coral line completes exactly where it was half drawn while the tab bar fades away.
            // After the peak the bar settles to the 3.1 height (walk-out bar + navigation bar), so nothing is wasted.
            val bar = with(density) { barBody[0].toDp() }
            CompositionLocalProvider(LocalNavPad provides true) {
                ReceiptScreen(t.first, peak = t.second, onDone = done, minBar = if (t.second) bar + bottomInset else 0.dp,
                    settleBar = if (t.second) bar + (if (navPadOff) navBottom else 0.dp) else null)
            }
        } else Box(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
            ShopBody(if (live != null) frozen[0] ?: s else s, a, onLastTrip = { reviewing = it }, onBar = { if (live == null) barBody[0] = it })
        }
    }
}

@Composable
private fun ShopBody(s: ShopUi, a: ShopActions, onLastTrip: (Long) -> Unit, onBar: (Float) -> Unit = {}) {
    val p = LocalPalette.current
    // At large font sizes a fixed bottom panel would eat the screen: the status moves into the scrolling page
    // (right under the store) and only the one action, if any, stays pinned in the thumb zone.
    val big = big
    val status = hasStatus(s)
    val action = hasAction(s)
    val fill by animateFloatAsState(if (s.leaving) 0.5f else 0f, tween(if (LocalReduceMotion.current) 0 else 600), label = "leave")
    Column(Modifier.fillMaxSize().background(p.bg)) {
        Header(s.bag, a, pair = s.needsPairing)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(16.dp))
            StoreBlock(s, a)
            PlaceRows(s, onLastTrip)
            if (big && status) {
                Spacer(Modifier.height(24.dp))
                EdgeLine(fill, p.coral)
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) { ShopStatus(s, a) }
                Hairline()
            }
            Spacer(Modifier.height(32.dp))
            BagBlock(s, a)
            Spacer(Modifier.height(24.dp))
        }
        if (!big && (status || action)) BottomArea(Modifier.onSizeChanged { onBar(it.height.toFloat()) }, topLine = { EdgeLine(fill, p.coral) }) {
            if (status) ShopStatus(s, a)
            if (status && action) Spacer(Modifier.height(16.dp))
            if (action) ShopAction(s, a)
        } else if (big && action) BottomArea { ShopAction(s, a) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Header(b: BagState, a: ShopActions, pair: Boolean = false) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    val canConnect = !b.connected && !b.busy || pair
    val label = when {
        pair -> "Pair your bag"
        else -> when (b) {
        is BagState.Connected -> "Bag connected"
        is BagState.Connecting -> "Connecting…"
        BagState.Searching, BagState.Off -> "Looking for your bag…"
        else -> "Bag not connected"
        }
    }
    val connectNow = { if (pair) a.openPairing() else a.connectBag() }
    val reduce = LocalReduceMotion.current
    val big = big
    // Connected = a status, not a button: the outline and side padding fade away with the same 400ms as the slot lighting.
    val pad by animateDpAsState(if (b.connected) 0.dp else 12.dp, tween(if (reduce) 0 else 400), label = "pad")
    val outline by animateColorAsState(
        when { b.connected -> p.hair.copy(alpha = 0f); b is BagState.Problem && !pair -> p.bad.copy(alpha = 0.5f); else -> p.hair },
        tween(if (reduce) 0 else 400), label = "outline")
    val slot by animateColorAsState(if (b.connected) p.coral else p.bg, tween(if (reduce) 0 else 400), label = "slot")
    val chip = @Composable {
        // Tap = connect (when not connected). Hidden demo: long-press drops a random item. TalkBack only hears the
        // state and, when it applies, "Connect bag" (clearAndSetSemantics hides the long press).
        Box(
            Modifier.heightIn(min = 48.dp)
                .clearAndSetSemantics {
                    contentDescription = label
                    liveRegion = LiveRegionMode.Polite
                    if (canConnect) { role = Role.Button; onClick(if (pair) "Pair your bag" else "Connect bag") { connectNow(); true } }
                }
                .clip(RoundedCornerShape(50))
                .combinedClickable(
                    indication = if (canConnect) LocalIndication.current else null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { if (canConnect) connectNow() },
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); a.demoDrop() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val shape = if (big) RoundedCornerShape(20.dp) else RoundedCornerShape(50)
            Row(
                Modifier.clip(shape).border(1.dp, outline, shape)
                    .padding(start = pad, end = pad + pad / 3, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The chip's mark is the bag: its slot is lit (coral) only while the bag is connected.
                Mark(16.dp, slot = slot, ink = if (b.connected) p.ink else p.faint)
                Spacer(Modifier.width(8.dp))
                Txt(label, Type.caption, if (b.connected) p.ink else p.muted, bold = true, maxLines = if (big) 2 else 1)
            }
        }
    }
    // At large font sizes the status chip gets its own line instead of truncating next to the wordmark.
    if (big) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 16.dp)) {
            Wordmark(20.dp)
            Spacer(Modifier.height(8.dp))
            chip()
        }
    } else Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp).heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wordmark(22.dp)
        Spacer(Modifier.weight(1f))
        chip()
    }
}

@Composable
private fun StoreBlock(s: ShopUi, a: ShopActions) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    Column(Modifier.padding(horizontal = 24.dp)) {
        when (val st = s.store) {
            StoreState.Locating -> {
                Txt("Finding your store", Type.title, bold = true, modifier = Modifier.semantics { heading() })
                if (!LocalReduceMotion.current) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(Modifier.width(96.dp).height(2.dp), color = p.faint, trackColor = p.hair)
                }
            }
            StoreState.NoLocation -> {
                Txt("Location is off", Type.title, bold = true, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Txt("Bag It needs your location to find your store, and to charge your bag when you leave.", Type.body, p.muted)
            }
            is StoreState.Offline -> {
                Eyebrow("Offline", p.bad)
                Spacer(Modifier.height(8.dp))
                Txt("Can't reach Bag It", Type.title, bold = true, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Txt("Check your connection. What's already in your bag is saved, but new items may not be added until Bag It reconnects." +
                    (if (s.count > 0) " If you leave now, you'll be charged once it does." else "") +
                    " If an item is missing later, take it out and drop it in again.", Type.body, p.muted)
            }
            is StoreState.Away -> {
                Txt("Not in a Bag It store", Type.title, bold = true, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Txt("Walk into one and your bag starts on its own.", Type.body, p.muted, balanced = true)
            }
            is StoreState.InStore -> {
                Eyebrow(if (st.leaving) "Leaving" else "You're at")
                Spacer(Modifier.height(8.dp))
                // Hidden demo: long-press the store name to simulate walking out (plain text to TalkBack).
                Txt(st.name, Type.title, bold = true, modifier = Modifier.semantics { heading() }
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); a.demoWalkOut() }) })
                if (st.address.isNotBlank()) Txt(st.address, Type.caption, p.muted)
            }
        }
    }
}

/**
 * Rows under the store: the nearest store (when away) and the last trip (the end, kept: its receipt and exit code stay
 * one tap away). One hairline list. A fresh trip (within the hour) comes first, because the shopper is at the door.
 * Inside a store the last trip stays while the bag is empty, and always when it is still payment due.
 */
@Composable
private fun PlaceRows(s: ShopUi, onLastTrip: (Long) -> Unit) {
    val away = s.store as? StoreState.Away
    val last = s.lastTrip?.takeIf { s.store !is StoreState.InStore || s.count == 0 || it.status != "paid" }
    val rows = mutableListOf<@Composable () -> Unit>()
    val nearest: (@Composable () -> Unit)? = if (away?.nearestName != null && away.nearestDistance != null) ({ NearestRow(away.nearestName, away.nearestDistance) }) else null
    val trip: (@Composable () -> Unit)? = last?.let { r -> { LastTripRow(r) { onLastTrip(r.id) } } }
    val fresh = last != null && System.currentTimeMillis() - last.paidAt < 60 * 60_000L
    if (fresh) { trip?.let(rows::add); nearest?.let(rows::add) } else { nearest?.let(rows::add); trip?.let(rows::add) }
    if (rows.isEmpty()) return
    Spacer(Modifier.height(24.dp))
    Column(Modifier.padding(horizontal = 24.dp)) {
        Hairline()
        rows.forEach { it(); Hairline() }
    }
}

@Composable
private fun NearestRow(name: String, meters: Int) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Nearest store: $name, ${distance(meters)} away" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphImage(Glyph.store, p.muted)
        Spacer(Modifier.width(16.dp))
        val stacked = big
        Column(Modifier.weight(1f)) {
            Txt("Nearest store", Type.caption, p.muted)
            Txt(name, Type.body, bold = true)
            if (stacked) Txt(distance(meters), Type.body, p.muted, num = true)
        }
        if (!stacked) { Spacer(Modifier.width(16.dp)); Txt(distance(meters), Type.body, p.muted, num = true) }
    }
}

@Composable
private fun LastTripRow(r: Receipt, onOpen: () -> Unit) {
    val p = LocalPalette.current
    val due = r.status != "paid"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClickLabel = "Open receipt", onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = "Last trip, ${money(r.total)} at ${r.storeName}, ${whenText(r.paidAt)}" + if (due) ", payment due" else ""
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphImage(Glyph.receipt, p.muted)
        Spacer(Modifier.width(16.dp))
        val stacked = big
        Column(Modifier.weight(1f)) {
            val label = if (due) "Last trip · payment due" else "Last trip"
            Txt(if (stacked) "$label · ${whenText(r.paidAt)}" else label, Type.caption, if (due) p.bad else p.muted, bold = due)
            Txt(if (stacked) r.storeName else "${r.storeName} · ${whenText(r.paidAt)}", Type.body, bold = true)
            if (stacked) Txt(money(r.total), Type.body, num = true)
        }
        if (!stacked) { Spacer(Modifier.width(16.dp)); Txt(money(r.total), Type.body, num = true) }
    }
}

@Composable
private fun BagBlock(s: ShopUi, a: ShopActions) {
    val p = LocalPalette.current
    val inStore = s.store as? StoreState.InStore
    val unsold = s.unsold
    val countText = if (s.count > 0 || unsold > 0) items(s.count) + if (unsold > 0) " · $unsold not sold here" else "" else null
    if (big) Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).semantics(mergeDescendants = true) { heading() }) {
        Eyebrow("In your bag")
        countText?.let { Spacer(Modifier.height(4.dp)); Txt(it, Type.caption, p.muted) }
    } else Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
        Eyebrow("In your bag")
        Spacer(Modifier.weight(1f))
        countText?.let { Txt(it, Type.caption, p.muted) }
    }
    Spacer(Modifier.height(8.dp))
    Hairline(Modifier.padding(horizontal = 24.dp))
    if (s.lines.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // An empty tote, slot unlit: what the bag looks like before anything goes in.
            Mark(48.dp, slot = null, ink = p.strong)
            Spacer(Modifier.height(16.dp))
            Txt(if (s.needsPairing) "No bag yet" else "Drop something in", Type.body, bold = true, align = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Txt(
                when {
                    s.needsPairing -> "Pair your bag and items show up here as they go in."
                    !s.bag.connected && !s.bag.busy -> "Connect your bag first. Items show up here as they go in."
                    inStore != null -> "Items show up here with ${inStore.name}'s prices."
                    else -> "Items show up here, priced when you're in a store."
                },
                Type.caption, p.muted, align = TextAlign.Center, balanced = true,
            )
        }
        return
    }
    s.lines.forEach { l ->
        key(l.sku) { LineRow(l, inStore, s.flash, s.total, a) }
        Hairline(Modifier.padding(horizontal = 24.dp))
    }
    if (inStore != null && unsold > 0) {
        Spacer(Modifier.height(12.dp))
        Txt("Items ${inStore.name} doesn't sell won't be charged.", Type.caption, p.faint, modifier = Modifier.padding(horizontal = 24.dp))
    }
}

@Composable
private fun LineRow(l: Line, inStore: StoreState.InStore?, flash: Pair<String, Int>?, total: Int, a: ShopActions) {
    val p = LocalPalette.current
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val glow = remember { Animatable(0f) }
    val flashKey = if (l.sold && flash != null && flash.first == l.sku) flash.second else -1
    val name = if (l.sold) l.name else "Unknown item"
    LaunchedEffect(flashKey) {
        // A newer flash on another row (or the flash expiring) restarts this effect and cancels a running fade:
        // finish it quickly so no row keeps a coral tint.
        if (flashKey < 0) { if (glow.value > 0f) glow.animateTo(0f, tween(if (reduce) 0 else 300)); return@LaunchedEffect }
        // Coral = money moving. The model only sets a flash when a priced item's quantity went up, and clears it after 1.6s.
        run {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            view.announceForAccessibility("Added $name, ${money(l.priceCents)}. Total ${money(total)}.")
            glow.snapTo(1f)
            if (reduce) { delay(1500); glow.snapTo(0f) } else glow.animateTo(0f, tween(1400, easing = FastOutSlowInEasing))
        }
    }
    val missing = inStore != null && !l.sold
    val sub = when {
        l.sold -> "${l.qty} × ${money(l.priceCents)}"
        missing -> "Not sold here · won't be charged"
        else -> "${l.qty} in bag · priced in a store"
    }
    Row(
        Modifier.fillMaxWidth().background(p.coral.copy(alpha = (if (p.dark) 0.20f else 0.12f) * glow.value)).heightIn(min = 64.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = name + ", " + (if (l.sold) "${l.qty} at ${money(l.priceCents)}, ${money(l.lineCents)}" else sub.replace(" · ", ", "))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(name, Type.body, if (missing) p.faint else p.ink, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Txt(sub, Type.caption, if (missing) p.muted else p.faint, num = l.sold)
        }
        Spacer(Modifier.width(16.dp))
        when {
            l.sold -> Txt(money(l.lineCents), Type.body, num = true)
            missing -> SmallPill("Dismiss", "Hide this item from the list") { a.remove(l.sku) }
        }
    }
}

// No bag paired: outside a store the chip ("Pair your bag") is the one ask; in a store the dock asks too.
private fun hasAction(s: ShopUi) = s.blocked || (s.needsPairing && !s.leaving && s.store is StoreState.InStore) ||
    (!s.needsPairing && !s.bag.connected && !s.bag.busy && !s.leaving) || (s.noCard && !s.needsPairing)
private fun hasStatus(s: ShopUi) = (!s.blocked && (s.store is StoreState.InStore || s.count > 0 || (s.bag.busy && s.bag != BagState.Off && !s.needsPairing) || s.noCard)) ||
    (s.syncError != null && s.store !is StoreState.Offline)

/** The thumb zone, part 1: the walk-out status and the bag total. There is no Pay button. */
@Composable
private fun ShopStatus(s: ShopUi, a: ShopActions) {
    val p = LocalPalette.current
    val inStore = s.store as? StoreState.InStore
    val bagDown = !s.bag.connected && !s.bag.busy
    if (!s.blocked) {
        when {
            inStore != null && (bagDown || s.needsPairing) && s.count == 0 && !inStore.leaving -> {
                Txt(if (s.needsPairing) "Pair your bag" else "Connect your bag", Type.title, bold = true, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(4.dp))
                Txt("Then drop groceries in and just walk out.", Type.caption, p.muted)
            }
            inStore != null -> WalkOutStatus(s, inStore, s.payment.ifEmpty { "your card" }, a)
            s.count > 0 -> TotalRow("Bag total", s.total, "Priced when you're in a store.")
            s.noCard -> Txt("Add a card so leaving pays for you.", Type.caption, p.muted, balanced = true)
        }
        if (s.bag.busy && s.bag != BagState.Off && !s.leaving && !s.needsPairing) {
            if (inStore != null || s.count > 0) Spacer(Modifier.height(12.dp))
            Txt("Looking for your bag. Keep it nearby and switched on.", Type.caption, p.muted, balanced = true)
        }
    }
    if (s.syncError != null && s.store !is StoreState.Offline) {
        Spacer(Modifier.height(8.dp))
        Txt("Something didn't sync. If an item is missing, take it out and drop it in again.", Type.caption, p.bad,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** The thumb zone, part 2: the one action that unblocks shopping, if any. */
@Composable
private fun ShopAction(s: ShopUi, a: ShopActions) {
    val p = LocalPalette.current
    when {
        s.store == StoreState.NoLocation -> Pill("Turn on location", Modifier.fillMaxWidth(), onClick = a::locationSettings)
        s.store is StoreState.Offline -> Pill("Try again", Modifier.fillMaxWidth(), onClick = a::retry)
        s.needsPairing && !s.leaving && s.store is StoreState.InStore -> Pill("Pair your bag", Modifier.fillMaxWidth(), primary = s.count == 0, onClick = a::openPairing)
        s.needsPairing -> {}
        !s.bag.connected && !s.bag.busy && !s.leaving -> {
            val b = s.bag
            if (b is BagState.Problem) {
                Txt(b.message + if (s.count > 0 && s.store is StoreState.InStore) " Items you add now won't count until it's connected." else "",
                    Type.caption, p.bad, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Spacer(Modifier.height(12.dp))
            }
            Pill("Connect bag", Modifier.fillMaxWidth(), primary = s.count == 0 || b is BagState.Problem, onClick = a::connectBag)
        }
        s.noCard -> Pill("Add card", Modifier.fillMaxWidth(), onClick = a::openAddCard)
    }
}

@Composable
private fun WalkOutStatus(s: ShopUi, st: StoreState.InStore, pay: String, a: ShopActions) {
    val p = LocalPalette.current
    // No card on file: say plainly what leaving means now (payment due at the store).
    if (s.noCard || s.payment.isEmpty()) {
        val due = "No card on file. If you leave now, you'll pay at the store."
        when {
            st.leaving -> TotalRow("On your way out", s.total, due, p.bad)
            s.count > 0 -> TotalRow("Bag total", s.total, due, p.bad)
            else -> {
                Txt("Fill your bag, then just walk out", Type.title, bold = true, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(4.dp))
                Txt("Add a card so leaving pays for you.", Type.caption, p.muted, balanced = true)
            }
        }
        return
    }
    // The card line opens the card picker (the dock is the one place you'd want to switch cards mid-trip).
    val pick = a::chooseCard
    when {
        st.leaving -> TotalRow("On your way out", s.total, "We'll charge ${card(pay)} in a moment. Step back inside to keep shopping.", onSub = pick)
        s.count > 0 -> TotalRow("Just walk out", s.total, "We'll charge ${card(pay)} when you leave ${st.name}.", onSub = pick)
        else -> Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Change card", onClick = pick)
                .semantics(mergeDescendants = true) { },
        ) {
            Txt("Fill your bag, then just walk out", Type.title, bold = true, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(4.dp))
            CardLine("Paying with ${card(pay)}. No checkout, no scanning.")
        }
    }
}

/** A caption ending in a small chevron: tap to change the card. */
@Composable
private fun CardLine(text: String, color: Color = LocalPalette.current.muted) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt(text, Type.caption, color, balanced = true, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(4.dp))
        GlyphImage(Glyph.chevron, LocalPalette.current.faint, 16.dp)
    }
}

@Composable
private fun TotalRow(title: String, total: Int, sub: String, subColor: Color? = null, onSub: (() -> Unit)? = null) {
    val p = LocalPalette.current
    val shown by animateIntAsState(total, tween(if (LocalReduceMotion.current) 0 else 500), label = "total")
    Column(
        Modifier.fillMaxWidth()
            .then(if (onSub != null) Modifier.clickable(role = Role.Button, onClickLabel = "Change card", onClick = onSub) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = "$title. Total ${money(total)}. $sub" },
    ) {
        if (big) {
            Txt(title, Type.title, bold = true)
            Txt(money(shown), Type.title, bold = true, num = true)
        } else Row(verticalAlignment = Alignment.Bottom) {
            Txt(title, Type.title, bold = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Txt(money(shown), Type.title, bold = true, num = true)
        }
        Spacer(Modifier.height(4.dp))
        if (onSub != null) CardLine(sub) else Txt(sub, Type.caption, subColor ?: p.muted, balanced = true)
    }
}

// ======================================================================================
// Receipt: the peak (charged as you walked out) and the end
// ======================================================================================

fun qr(text: String, size: Int = 360, ink: Int = 0xFF18181B.toInt()): ImageBitmap {
    val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val px = IntArray(size * size) { i -> if (bits.get(i % size, i / size)) ink else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * [peak] = this receipt was just charged (play the moment once); false = reopened from "Last trip" (end state, quiet).
 */
@Composable
fun ReceiptScreen(r: Receipt, peak: Boolean, onDone: () -> Unit, minBar: Dp = 0.dp, settleBar: Dp? = null) {
    val p = LocalPalette.current
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val ctx = LocalContext.current
    val due = r.status != "paid"
    val time = whenText(r.paidAt)
    val code = remember(r.id) { qr("SMARTBAG-EXIT:${r.code}:${r.id}:${r.total}") }
    // Play the peak once per receipt (survives rotation / theme change without replaying the haptic).
    var played by rememberSaveable(r.id) { mutableStateOf(!peak) }
    val still = reduce || played
    // Three beats: the coral line (half drawn while leaving) completes, the slot (the bag's light) lights,
    // the amount settles. One confirm haptic when the light comes on.
    val line = remember(r.id) { Animatable(if (still || due) 1f else 0.5f) }
    val lit = remember(r.id) { Animatable(if (still) 1f else 0f) }
    val rise = remember(r.id) { Animatable(if (still) 1f else 0f) }
    val summary = (if (due) "Payment due at ${r.storeName}: ${money(r.total)}. Nothing was charged."
        else "Paid ${money(r.total)} as you left ${r.storeName}.") + " $time." + (if (r.payment.isNotEmpty()) " ${r.payment}." else "") + " Exit code ${r.code.toCharArray().joinToString(" ")}."
    LaunchedEffect(r.id) {
        if (!peak || played) return@LaunchedEffect
        Notify.cancelReceipt(ctx, r.code)
        view.announceForAccessibility(summary)
        if (!reduce) {
            delay(300)                                                  // let the cross-fade land, so the line is seen completing
            line.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
            if (!due) lit.animateTo(1f, tween(250))
        }
        view.performHapticFeedback(when {
            Build.VERSION.SDK_INT < 30 -> HapticFeedbackConstants.LONG_PRESS
            due -> HapticFeedbackConstants.REJECT
            else -> HapticFeedbackConstants.CONFIRM
        })
        played = true
    }
    LaunchedEffect(r.id) { if (!still) { delay(300); rise.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) } }
    val slotColor = if (due) null else androidx.compose.ui.graphics.lerp(p.strong, p.coral, lit.value)
    val stacked = big

    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().semantics { paneTitle = if (due) "Payment due" else "Receipt" }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(40.dp))
            Mark(56.dp, slot = slotColor, ink = p.ink)
            Spacer(Modifier.height(24.dp))
            Column(
                Modifier.graphicsLayer { alpha = 0.3f + 0.7f * rise.value; translationY = (1f - rise.value) * 8.dp.toPx() }
                    .semantics(mergeDescendants = true) { contentDescription = summary },
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    if (!due && !stacked) { GlyphImage(Glyph.check, p.good, 20.dp, Modifier.padding(top = 2.dp)); Spacer(Modifier.width(8.dp)) }
                    Txt(if (due) "Payment due at ${r.storeName}" else "Paid as you left ${r.storeName}", Type.body, if (due) p.bad else p.good, bold = true)
                }
                Spacer(Modifier.height(8.dp))
                Txt(money(r.total), Type.display, bold = true, num = true)
                Spacer(Modifier.height(8.dp))
                Txt(time + if (r.payment.isNotEmpty()) " · ${card(r.payment)}" else "", Type.body, p.muted)
                if (due) {
                    Spacer(Modifier.height(16.dp))
                    Txt("There was no card on file, so nothing was charged. Show this code to staff to pay.", Type.body, balanced = true)
                }
            }
            Spacer(Modifier.height(40.dp))
            // Exit code: what staff scan to check the bag. The one tile on screen (a QR needs its white quiet zone).
            Hairline()
            val pass = @Composable {
                Box(Modifier.size(if (stacked) 160.dp else 112.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp)) {
                    Image(code, contentDescription = "Exit code as a QR code", modifier = Modifier.fillMaxSize())
                }
            }
            val words = @Composable {
                Column {
                    Eyebrow("Exit code")
                    Spacer(Modifier.height(4.dp))
                    Txt(r.code, Type.title, mono = true, letterSpacing = 3.sp, modifier = Modifier.clearAndSetSemantics { })
                    Spacer(Modifier.height(4.dp))
                    Txt("Show this if staff ask to check your bag.", Type.caption, p.muted, balanced = true)
                }
            }
            if (stacked) Column(Modifier.padding(vertical = 24.dp)) { pass(); Spacer(Modifier.height(16.dp)); words() }
            else Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) { pass(); Spacer(Modifier.width(24.dp)); Box(Modifier.weight(1f)) { words() } }
            Hairline()
            Spacer(Modifier.height(32.dp))
            Eyebrow(items(r.count))
            Spacer(Modifier.height(8.dp))
            r.items.forEach { it ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.Top) {
                    Txt((if (it.qty > 1) "${it.qty} × " else "") + it.name, Type.body, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(16.dp))
                    Txt(money(it.price * it.qty), Type.body, num = true)
                }
                Hairline()
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.Top) {
                Txt("Total", Type.body, bold = true, modifier = Modifier.weight(1f))
                Txt(money(r.total), Type.body, bold = true, num = true)
            }
            if (r.unpriced > 0) Txt("${items(r.unpriced)} not sold there, not charged.", Type.caption, p.faint)
            Spacer(Modifier.height(32.dp))
            Txt(if (due) "Keep this code handy on your way out. It stays under Last trip."
                else "Thanks for shopping at ${r.storeName}. Your bag is ready for next time.", Type.body, p.muted, balanced = true)
            Spacer(Modifier.height(32.dp))
        }
        // The coral line finishes on the same edge where it was half drawn while leaving. Only Done is pinned,
        // so this bar is the same height as the walk-out bar it replaces.
        val navBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
        // In the tab host the bar starts taller (the line completes where it was drawn, above the tab bar) and settles
        // to the walk-out bar's own height once the peak has played. Reduced motion: settled from the start.
        val barH by animateDpAsState(if (settleBar != null && still) settleBar else minBar, tween(if (reduce) 0 else 240, easing = FastOutSlowInEasing), label = "bar")
        BottomArea(minBody = (barH - 2.dp - navBottom).coerceAtLeast(0.dp), topLine = { EdgeLine(line.value, if (due) p.bad else p.coral) }) {
            Pill("Done", Modifier.fillMaxWidth(), onClick = onDone)
        }
    }
}
