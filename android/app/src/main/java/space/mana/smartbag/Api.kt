package space.mana.smartbag

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class Line(val sku: String, val name: String, val qty: Int, val priceCents: Int, val lineCents: Int, val sold: Boolean, val addedMicros: Long)
data class ReceiptLine(val sku: String, val name: String, val price: Int, val qty: Int)
data class Receipt(
    val id: Long, val code: String, val storeName: String, val total: Int, val count: Int, val unpriced: Int,
    val status: String, val payment: String, val paidAt: Long, val items: List<ReceiptLine>,
    val paidMicros: Long = paidAt * 1000,
)
data class CartState(
    val storeId: String, val storeName: String, val storeAddress: String, val distanceM: Int,
    val nearestName: String, val nearestM: Int, val bag: String, val payment: String, val leaving: Boolean,
    val totalCents: Int, val itemCount: Int, val lines: List<Line>, val lastReceipt: Receipt?,
)

data class CardInfo(val id: Long, val brand: String, val last4: String, val expMonth: Int, val expYear: Int, val nickname: String, val isDefault: Boolean) {
    /** Same label the server uses on receipts: "<nickname> <brand> •••• <last4>". */
    val label: String get() = (if (nickname.isNotEmpty()) "$nickname " else "") + "$brand •••• $last4"
    val expiry: String get() = String.format(java.util.Locale.US, "%02d/%02d", expMonth, expYear % 100)
}
data class BagInfo(val id: Long, val bagId: String, val name: String, val pairedMicros: Long) {
    val isDemo: Boolean get() = bagId == DEMO_BAG
}
data class AccountState(
    val signedIn: Boolean, val name: String, val email: String,
    val cards: List<CardInfo>, val bags: List<BagInfo>, val payment: String,
) {
    val defaultCard: CardInfo? get() = cards.firstOrNull { it.isDefault }
    val firstName: String get() = name.trim().split(' ').firstOrNull().orEmpty()
}
const val DEMO_BAG = "DEMO"
/** Server push state for this phone (module v5.1). [serverPush] = the database has an FCM project configured. */
data class PushState(val serverPush: Boolean, val registered: Boolean, val prefs: String)
/** A store near the phone, for background geofences. */
data class NearbyStore(val id: String, val name: String, val address: String, val lat: Double, val lng: Double, val radiusM: Int, val distanceM: Int)
val SIGNED_OUT = AccountState(false, "", "", emptyList(), emptyList(), "")

class ApiException(message: String) : Exception(message)

/** Procedure errors arrive as "The module instance encountered a fatal error: <message>"; the message is already shopper copy. */
fun cleanError(raw: String?): String {
    val s = (raw ?: "").substringAfter("encountered a fatal error: ").removePrefix("SenderError: ").trim()
    return s.ifEmpty { "Something went wrong. Try again." }.take(200)
}

/** Little-endian BSATN, the SpacetimeDB wire format. */
class Bw {
    private val out = ByteArrayOutputStream()
    private fun le(n: Int, f: (ByteBuffer) -> Unit) = apply { val b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); f(b); out.write(b.array()) }
    fun u8(v: Int) = apply { out.write(v) }
    fun u32(v: Int) = le(4) { it.putInt(v) }
    fun u64(v: Long) = le(8) { it.putLong(v) }
    fun i64(v: Long) = le(8) { it.putLong(v) }
    fun bool(v: Boolean) = u8(if (v) 1 else 0)
    fun f64(v: Double) = le(8) { it.putDouble(v) }
    fun str(s: String) = apply { val b = s.toByteArray(); u32(b.size); out.write(b) }
    fun bytes(b: ByteArray) = apply { u32(b.size); out.write(b) }
    fun done(): ByteArray = out.toByteArray()
}

class Br(private val b: ByteArray, start: Int = 0) {
    private val buf: ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).also { it.position(start) }
    fun u8() = buf.get().toInt() and 0xff
    fun u32() = buf.int
    fun i64() = buf.long
    fun f64() = buf.double
    fun bool() = u8() != 0
    fun raw(n: Int) = ByteArray(n).also { buf.get(it) }
    fun str() = String(raw(u32()))
    fun bytes() = raw(u32())
    val remaining: Int get() = buf.remaining()
}

/**
 * SmartBag's backend is a SpacetimeDB module (database smartcart-5dt35 on Maincloud).
 * The phone keeps ONE WebSocket open (SpacetimeDB's v2 binary protocol) and calls the module's
 * procedures over it, request/response: each call runs in a database transaction and returns the
 * shopper's fresh, priced bag. No HTTP per action, no subscriptions.
 * Payment happens inside the database when a location fix shows the shopper has left the store.
 */
object Api {
    private val host = BuildConfig.STDB_HOST.trimEnd('/')
    private val db = BuildConfig.STDB_DB
    private const val PROTOCOL = "v2.bsatn.spacetimedb"
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private var prefs: SharedPreferences? = null
    @Volatile private var token: String? = null
    @Volatile private var socket: WebSocket? = null
    private val connectLock = Mutex()
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("smartbag", 0)
        token = prefs?.getString("stdb_token", null)
    }

    // Token requests get real timeouts (the socket client must not: readTimeout 0 keeps the WebSocket open),
    // so a stalled reply can't freeze the call queue.
    private val tokenHttp = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    private fun post(path: String, auth: String?): Pair<Int, String> {
        val req = Request.Builder().url(host + path).post(ByteArray(0).toRequestBody("application/json".toMediaType()))
        if (auth != null) req.header("Authorization", "Bearer $auth")
        return try { tokenHttp.newCall(req.build()).execute().use { r -> r.code to (r.body?.string() ?: "") } }
        catch (e: java.io.IOException) { throw ApiException("Couldn't reach Bag It. Check your connection.") }
    }

    private fun identityToken(): String {
        token?.let { return it }
        val (code, text) = post("/v1/identity", null)
        if (code !in 200..299) throw ApiException("SmartBag is unavailable right now.")
        return JSONObject(text).getString("token").also { token = it; prefs?.edit()?.putString("stdb_token", it)?.apply() }
    }

    /** Short-lived token for the WebSocket handshake. */
    private fun socketToken(): String {
        var (code, text) = post("/v1/identity/websocket-token", identityToken())
        if (code == 401) {
            token = null; prefs?.edit()?.remove("stdb_token")?.apply()
            val r = post("/v1/identity/websocket-token", identityToken()); code = r.first; text = r.second
        }
        if (code !in 200..299) throw ApiException("SmartBag is unavailable right now ($code).")
        return JSONObject(text).getString("token")
    }

    private suspend fun connected(): WebSocket = connectLock.withLock {
        socket?.let { return it }
        withContext(Dispatchers.IO) {
            val wsToken = socketToken()
            val opened = CompletableDeferred<WebSocket>()
            val url = host.replaceFirst("http", "ws") + "/v1/database/$db/subscribe?compression=None&token=$wsToken"
            http.newWebSocket(Request.Builder().url(url).header("Sec-WebSocket-Protocol", PROTOCOL).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(webSocket) }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onFrame(bytes.toByteArray())
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, "Connection closed.")
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!opened.isCompleted) opened.completeExceptionally(ApiException("Can't reach SmartBag."))
                    lost(webSocket, "Connection lost.")
                }
            })
            try { withTimeout(15_000) { opened.await() }.also { socket = it } }
            catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw ApiException("Couldn't reach Bag It. Check your connection.") }
        }
    }

    private fun lost(ws: WebSocket, why: String) {
        if (socket === ws) socket = null
        pending.keys.toList().forEach { id -> pending.remove(id)?.completeExceptionally(ApiException(why)) }
    }

    // Server frames: [compression=0][ServerMessage]. Tag 7 = ProcedureResult{status, timestamp, duration, requestId}.
    private fun onFrame(f: ByteArray) {
        if (f.size < 2 || f[0].toInt() != 0) return
        val r = Br(f, 1)
        if (r.u8() != 7) return
        val status = r.u8()
        val payload = if (status == 0) r.bytes() else null
        val error = if (status != 0) r.str() else null
        r.i64(); r.i64()
        val id = r.u32()
        val d = pending.remove(id) ?: return
        if (payload != null) d.complete(payload) else d.completeExceptionally(ApiException(cleanError(error)))
    }

    /** ClientMessage tag 4 = CallProcedure{requestId, flags, name, args}. */
    /** Raw procedure call (BSATN in, BSATN out). Internal so the codec tests can see the server's exact bytes. */
    internal suspend fun call(procedure: String, args: ByteArray): ByteArray {
        val ws = connected()
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<ByteArray>()
        pending[id] = d
        val frame = Bw().u8(4).u32(id).u8(0).str(procedure).bytes(args).done()
        if (!ws.send(frame.toByteString())) { pending.remove(id); lost(ws, "Connection lost."); throw ApiException("Connection lost.") }
        return try { withTimeout(20_000) { d.await() } }
        catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw ApiException("Bag It didn't answer in time. Try again.") }
        finally { pending.remove(id) }
    }

    private fun receiptAny(r: Br): Receipt {
        val id = r.i64(); val code = r.str(); val store = r.str(); val total = r.u32(); val count = r.u32(); val unpriced = r.u32()
        val status = r.str(); val payment = r.str(); val paid = r.i64()
        val items = List(r.u32()) { ReceiptLine(r.str(), r.str(), r.u32(), r.u32()) }
        return Receipt(id, code, store, total, count, unpriced, status, payment, paid / 1000, items, paid)
    }
    private fun receipt(r: Br): Receipt? = receiptAny(r).takeIf { it.id != 0L }

    // ---- codecs (internal for the codec tests; every decoder checks it consumed the whole payload)
    private fun <T> whole(b: ByteArray, f: (Br) -> T): T {
        val r = Br(b); val v = f(r)
        if (r.remaining != 0) throw ApiException("Bag It needs an update to read this reply.")
        return v
    }
    internal fun decodeAccount(b: ByteArray): AccountState = whole(b) { r ->
        val signedIn = r.bool(); val name = r.str(); val email = r.str()
        val cards = List(r.u32()) { CardInfo(r.i64(), r.str(), r.str(), r.u32(), r.u32(), r.str(), r.bool()) }
        val bags = List(r.u32()) { BagInfo(r.i64(), r.str(), r.str(), r.i64()) }
        AccountState(signedIn, name, email, cards, bags, r.str())
    }
    internal fun decodeTrips(b: ByteArray): List<Receipt> = whole(b) { r -> List(r.u32()) { receiptAny(r) } }
    internal fun decodeState(b: ByteArray): CartState = whole(b) { state(it) }

    private fun state(b: ByteArray): CartState = state(Br(b))
    private fun state(r: Br): CartState {
        val storeId = r.str(); val storeName = r.str(); val addr = r.str(); val dist = r.u32()
        val nearest = r.str(); val nearestM = r.u32(); val bag = r.str(); val payment = r.str(); val leaving = r.bool()
        val total = r.u32(); val count = r.u32()
        val lines = List(r.u32()) { Line(r.str(), r.str(), r.u32(), r.u32(), r.u32(), r.bool(), r.i64()) }
        return CartState(storeId, storeName, addr, dist, nearest, nearestM, bag, payment, leaving, total, count, lines, receipt(r))
    }

    suspend fun current() = state(call("shop_state", ByteArray(0)))
    /** A GPS fix. The database places the phone in a store, or, if it has just left one, charges the bag. */
    suspend fun locate(lat: Double, lng: Double, acc: Float) = state(call("shop_locate", Bw().f64(lat).f64(lng).f64(acc.toDouble()).done()))
    suspend fun bagLine(line: String) = state(call("shop_bag_line", Bw().str(line).done()))
    suspend fun removeItem(sku: String) = state(call("shop_remove", Bw().str(sku).done()))
    suspend fun setBag(name: String) = state(call("shop_set_bag", Bw().str(name).done()))
    /** Test/admin aid only: the app takes its payment from the wallet (account default card), never from here. */
    suspend fun setPayment(label: String) = state(call("shop_set_payment", Bw().str(label).done()))

    // ---- accounts, wallet, bags, trips (module v5). Every call returns the fresh AccountState.
    private val none = ByteArray(0)
    /** Raw AccountState bytes as well, so the model can cache exactly what the server sent. */
    suspend fun accountRaw(procedure: String, args: ByteArray = none): Pair<AccountState, ByteArray> =
        call(procedure, args).let { decodeAccount(it) to it }
    suspend fun accountState() = accountRaw("account_state")
    /** The password goes over the TLS socket only; nothing about it is kept on the phone. */
    suspend fun signUp(name: String, email: String, password: String) = accountRaw("account_sign_up", Bw().str(name).str(email).str(password).done())
    suspend fun signIn(email: String, password: String) = accountRaw("account_sign_in", Bw().str(email).str(password).done())
    suspend fun signOut() = accountRaw("account_sign_out")
    suspend fun setName(name: String) = accountRaw("account_set_name", Bw().str(name).done())
    suspend fun deleteAccount(password: String) = accountRaw("account_delete", Bw().str(password).done())
    /** Only brand, last four digits and expiry leave the phone. */
    suspend fun cardAdd(brand: String, last4: String, expMonth: Int, expYear: Int, nickname: String, makeDefault: Boolean) =
        accountRaw("card_add", Bw().str(brand).str(last4).u32(expMonth).u32(expYear).str(nickname).bool(makeDefault).done())
    suspend fun cardRemove(id: Long) = accountRaw("card_remove", Bw().u64(id).done())
    suspend fun cardSetDefault(id: Long) = accountRaw("card_set_default", Bw().u64(id).done())
    suspend fun bagPair(bagId: String, name: String) = accountRaw("bag_pair", Bw().str(bagId).str(name).done())
    suspend fun bagRename(id: Long, name: String) = accountRaw("bag_rename", Bw().u64(id).str(name).done())
    suspend fun bagUnpair(id: Long) = accountRaw("bag_unpair", Bw().u64(id).done())
    suspend fun trips(limit: Int = 50): List<Receipt> = decodeTrips(call("shop_trips", Bw().u32(limit).done()))

    // ---- push + geofencing (module v5.1)
    internal fun decodePush(b: ByteArray): PushState = whole(b) { r -> PushState(r.bool(), r.bool(), r.str()) }
    internal fun decodeNearby(b: ByteArray): List<NearbyStore> = whole(b) { r ->
        List(r.u32()) { NearbyStore(r.str(), r.str(), r.str(), r.f64(), r.f64(), r.u32(), r.u32()) }
    }
    internal fun decodeString(b: ByteArray): String = whole(b) { it.str() }
    /** [prefs] = comma list of server categories ("trips,security"). Signed-in phones only. */
    suspend fun pushRegister(token: String, prefs: String) = decodePush(call("push_register", Bw().str("android").str(token).str(prefs).done()))
    suspend fun pushSetPrefs(prefs: String) = decodePush(call("push_set_prefs", Bw().str(prefs).done()))
    suspend fun pushUnregister() = decodePush(call("push_unregister", none))
    suspend fun pushStatus() = decodePush(call("push_status", none))
    /** What the server did: "sent 1/1", "push not configured", "not registered", or an error. Never proof of delivery. */
    suspend fun pushTest() = decodeString(call("push_test", none))
    suspend fun nearbyStores(lat: Double, lng: Double, limit: Int = 20) = decodeNearby(call("shop_nearby_stores", Bw().f64(lat).f64(lng).u32(limit).done()))
}
