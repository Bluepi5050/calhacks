package space.mana.smartbag

import org.json.JSONObject

data class BagEvent(val op: String, val sku: String, val uid: String?, val qty: Int)

/**
 * Text the bag sends, one message per line:
 *   1005 | +1005 | ADD 1005        add one
 *   -1005 | DEL 1005               remove one
 *   +1005@E28011                   add, ignoring repeat reads of the same tag
 *   {"op":"add","sku":"1005","uid":"E28011","qty":1}
 */
object Protocol {
    private val verb = Regex("^(ADD|DEL|REMOVE|RM)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val unsafe = Regex("[.#\$\\[\\]/]")

    fun key(s: String): String = s.trim().replace(unsafe, "_")

    fun parse(raw: String): BagEvent? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        if (line.startsWith("{")) {
            return try {
                val j = JSONObject(line)
                val op = j.optString("op", "add").lowercase()
                val sku = listOf("sku", "id", "code").firstNotNullOfOrNull { k -> if (j.has(k) && !j.isNull(k)) j.get(k).toString().trim() else null }.orEmpty()
                if (sku.isEmpty()) null
                else BagEvent(
                    if (op == "remove" || op == "del" || op == "rm") "remove" else "add",
                    key(sku),
                    if (j.has("uid") && !j.isNull("uid")) j.get("uid").toString() else null,
                    maxOf(1, j.optInt("qty", 1))
                )
            } catch (e: Exception) { null }
        }
        var op = "add"
        var rest = line
        val m = verb.find(line)
        if (m != null) {
            op = if (m.groupValues[1].equals("ADD", ignoreCase = true)) "add" else "remove"
            rest = m.groupValues[2]
        } else if (line.startsWith("+")) {
            rest = line.substring(1)
        } else if (line.startsWith("-")) {
            op = "remove"; rest = line.substring(1)
        }
        val parts = rest.trim().split("@", limit = 2)
        val sku = parts[0].trim()
        if (sku.isEmpty()) return null
        val uid = parts.getOrNull(1)?.trim()?.ifEmpty { null }
        return BagEvent(op, key(sku), uid, 1)
    }
}

/** What the NUS bag is told after a walk-out: "PAID <code>" when charged, "DUE <code>" when payment is due (firmware v3). */
fun walkOutSignal(r: Receipt): String = if (r.status == "paid") "PAID ${r.code}" else "DUE ${r.code}"
