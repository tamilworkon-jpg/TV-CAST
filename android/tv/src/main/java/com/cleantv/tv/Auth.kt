package com.cleantv.tv

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing + request signing.
 *
 * A phone pairs once: the TV asks "Allow <phone>?" and, after Allow, hands it a random 256-bit key.
 * After that every request carries X-Phone / X-Ts / X-Sig, where
 * X-Sig = HMAC-SHA256(key, method \n path \n query \n ts \n sha256(body)).
 * The key itself never travels again, requests older than 5 minutes are rejected,
 * and unpaired devices on the Wi-Fi can't control the TV.
 */
object Auth {
    private const val PIN_TTL_MS = 2 * 60 * 1000L
    private const val MAX_SKEW_MS = 5 * 60 * 1000L
    private val rng = SecureRandom()

    private fun sp(ctx: Context) = ctx.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    fun tvId(ctx: Context): String = sp(ctx).getString("tv_id", null)
        ?: hex(ByteArray(8).also { rng.nextBytes(it) }).also { sp(ctx).edit().putString("tv_id", it).apply() }

    private fun phones(ctx: Context): JSONObject =
        runCatching { JSONObject(sp(ctx).getString("phones", "{}")!!) }.getOrDefault(JSONObject())

    fun pairedNames(ctx: Context): List<String> {
        val p = phones(ctx)
        return p.keys().asSequence().map { p.getJSONObject(it).optString("name", it) }.toList()
    }

    fun forgetAll(ctx: Context) {
        sp(ctx).edit().remove("phones").apply()
    }

    // --------------------------------------------------------------- pairing
    //
    // A phone asks to pair (with its random id); the TV shows "Allow <phone>?" with Allow / Deny.
    // The phone keeps asking /pair/finish with the same id and receives its key only after Allow.

    enum class State { WAITING, ALLOWED, DENIED }

    private data class Request(val phoneId: String, val name: String, val at: Long, var state: State, var key: String? = null)

    @Volatile private var request: Request? = null
    @Volatile var pairingName: String = ""
    @Volatile private var deniedCount = 0
    @Volatile private var lockedUntil = 0L

    /** Starts a request. Returns false if pairing is temporarily locked (too many denied requests). */
    @Synchronized
    fun startPairing(name: String, phoneId: String): Boolean {
        val now = System.currentTimeMillis()
        if (now < lockedUntil || phoneId.isBlank() || phoneId.length > 64) return false
        pairingName = name.take(40)
        request = Request(phoneId, pairingName, now, State.WAITING)
        return true
    }

    /** The request waiting for Allow/Deny on the TV, if any (expires after 2 minutes). */
    fun pending(): String? = request?.takeIf { it.state == State.WAITING && System.currentTimeMillis() - it.at < PIN_TTL_MS }
        ?.let { "${it.phoneId}:${it.at}" }

    @Synchronized
    fun answer(ctx: Context, allow: Boolean) {
        val r = request ?: return
        if (r.state != State.WAITING) return
        if (allow) {
            val key = hex(ByteArray(32).also { rng.nextBytes(it) })
            val all = phones(ctx).put(r.phoneId, JSONObject().put("key", key).put("name", r.name))
            sp(ctx).edit().putString("phones", all.toString()).apply()
            r.key = key
            r.state = State.ALLOWED
            deniedCount = 0
        } else {
            r.state = State.DENIED
            // Someone keeps asking and being refused: ignore pair requests for 10 minutes.
            if (++deniedCount >= 3) lockedUntil = System.currentTimeMillis() + 10 * 60 * 1000L
        }
    }

    /** Polled by the phone. Returns WAITING / DENIED, or ALLOWED with the key (handed out once). */
    @Synchronized
    fun poll(phoneId: String): Pair<State, String?> {
        val r = request ?: return State.DENIED to null
        if (r.phoneId != phoneId || System.currentTimeMillis() - r.at > PIN_TTL_MS + 30_000) return State.DENIED to null
        return when (r.state) {
            State.ALLOWED -> { val k = r.key; request = null; State.ALLOWED to k }
            else -> r.state to null
        }
    }

    // -------------------------------------------------------------- requests

    fun verify(ctx: Context, method: String, path: String, query: String?, body: ByteArray, headers: Map<String, String>): Boolean {
        val phoneId = headers["x-phone"] ?: return false
        val ts = headers["x-ts"]?.toLongOrNull() ?: return false
        val sig = headers["x-sig"] ?: return false
        if (Math.abs(System.currentTimeMillis() - ts) > MAX_SKEW_MS) return false
        val key = phones(ctx).optJSONObject(phoneId)?.optString("key")?.takeIf { it.isNotEmpty() } ?: return false
        val expected = sign(key, method, path, query, ts, body)
        return MessageDigest.isEqual(expected.toByteArray(), sig.lowercase().toByteArray())
    }

    /** Decrypts a value the phone sealed with its pairing key (phone side: PhoneAuth.seal). */
    fun unseal(ctx: Context, phoneId: String, sealed: String): String? = try {
        val key = phones(ctx).optJSONObject(phoneId)?.optString("key")?.takeIf { it.isNotEmpty() }!!
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(unhex(key), "HmacSHA256"))
        val aes = SecretKeySpec(mac.doFinal("tvcast-seal".toByteArray()), "AES")
        val raw = android.util.Base64.decode(sealed, android.util.Base64.NO_WRAP)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, aes, javax.crypto.spec.GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) { null }

    fun sign(keyHex: String, method: String, path: String, query: String?, ts: Long, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(unhex(keyHex), "HmacSHA256"))
        val msg = "$method\n$path\n${query.orEmpty()}\n$ts\n${sha256(body)}"
        return hex(mac.doFinal(msg.toByteArray()))
    }

    private fun sha256(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
