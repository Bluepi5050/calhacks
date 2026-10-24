package space.mana.smartbag

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** What leaves the phone when a card is added: brand, last four, expiry, nickname. Never the number or CVC. */
data class NewCard(val brand: String, val last4: String, val expMonth: Int, val expYear: Int, val nickname: String, val makeDefault: Boolean)

/** Local card checks (the database only ever sees brand + last four). Pure Kotlin, unit tested in CardsTest. */
object Cards {
    fun digits(s: String) = s.filter { it in '0'..'9' }

    fun brand(n: String): String {
        val two = n.take(2).toIntOrNull() ?: -1
        val four = if (n.length >= 4) n.take(4).toInt() else -1
        return when {
            n.startsWith("4") -> "Visa"
            two in 51..55 || four in 2221..2720 -> "Mastercard"
            n.startsWith("34") || n.startsWith("37") -> "Amex"
            n.startsWith("6011") || n.startsWith("65") -> "Discover"
            else -> "Card"
        }
    }

    private fun lengths(brand: String): IntRange = when (brand) {
        "Amex" -> 15..15
        "Mastercard" -> 16..16
        "Visa" -> 13..19
        "Discover" -> 16..19
        else -> 12..19
    }
    fun maxLength(brand: String) = lengths(brand).last
    fun cvcLength(brand: String) = if (brand == "Amex") 4 else 3
    /** Visible grouping: Amex 4-6-5, everything else 4-4-4-4(-3). */
    fun groups(brand: String): List<Int> = if (brand == "Amex") listOf(4, 6, 5) else listOf(4, 4, 4, 4, 3)

    fun luhn(n: String): Boolean {
        if (n.isEmpty()) return false
        var sum = 0
        n.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }

    fun numberError(n: String): String? = when {
        n.isEmpty() -> "Enter the card number."
        n.length !in lengths(brand(n)) || !luhn(n) -> "Check the card number."
        else -> null
    }

    /** "MMYY" digits -> (month, 4-digit year), or null. */
    fun expiry(s: String): Pair<Int, Int>? {
        if (s.length != 4) return null
        val m = s.take(2).toInt(); val y = 2000 + s.takeLast(2).toInt()
        return if (m in 1..12) m to y else null
    }

    fun expiryError(s: String, nowYear: Int, nowMonth: Int): String? {
        if (s.isEmpty()) return "Enter the expiry date."
        val e = expiry(s) ?: return "Check the expiry date."
        return when {
            e.second < nowYear || (e.second == nowYear && e.first < nowMonth) -> "This card has expired."
            e.second > nowYear + 25 -> "Check the expiry date."
            else -> null
        }
    }

    fun cvcError(c: String, brand: String): String? = if (c.length != cvcLength(brand)) "Check the security code." else null

    /** Group digits with spaces as they are typed ("4242 4242 4242 4242"; Amex "3782 822463 10005"). */
    fun grouped(n: String): String {
        val out = StringBuilder(); var i = 0
        for (g in groups(brand(n))) { if (i >= n.length) break; if (out.isNotEmpty()) out.append(' '); out.append(n, i, minOf(n.length, i + g)); i += g }
        return out.toString()
    }
}

/** Shows card digits grouped by brand while the field holds digits only. */
object CardNumberTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val shown = Cards.grouped(raw)
        // original index -> transformed index: count the spaces inserted before it
        val o2t = IntArray(raw.length + 1)
        var t = 0; var oi = 0
        for (ch in shown) { if (ch == ' ') { t++; continue }; o2t[oi] = t; oi++; t++ }
        o2t[raw.length] = shown.length
        return TransformedText(AnnotatedString(shown), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = o2t[offset.coerceIn(0, raw.length)]
            override fun transformedToOriginal(offset: Int): Int {
                val c = offset.coerceIn(0, shown.length)
                return shown.substring(0, c).count { it != ' ' }
            }
        })
    }
}

/** "MMYY" digits shown as "MM/YY". */
object ExpiryTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val shown = if (raw.length > 2) raw.take(2) + "/" + raw.drop(2) else raw
        return TransformedText(AnnotatedString(shown), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset > 2 && raw.length > 2) offset + 1 else offset
            override fun transformedToOriginal(offset: Int) = if (offset > 2 && raw.length > 2) offset - 1 else minOf(offset, raw.length)
        })
    }
}
