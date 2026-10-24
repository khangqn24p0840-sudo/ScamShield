package vn.scamshield
import android.content.Context
import org.json.JSONArray

object Store {
    private fun p(c: Context) = c.getSharedPreferences("shield", 0)
    fun digits(s: String) = s.filter { it.isDigit() }.takeLast(9)
    fun blocklist(c: Context): Set<String> = p(c).getStringSet("bl", emptySet())!!.toSet()
    fun addBlock(c: Context, n: String) { if (n.isNotBlank()) p(c).edit().putStringSet("bl", blocklist(c) + n.trim()).apply() }
    fun removeBlock(c: Context, n: String) = p(c).edit().putStringSet("bl", blocklist(c) - n).apply()
    fun isBlocked(c: Context, num: String) = digits(num).length >= 7 && blocklist(c).any { digits(it) == digits(num) }
    fun blockForeign(c: Context) = p(c).getBoolean("fg", false)
    fun setBlockForeign(c: Context, v: Boolean) = p(c).edit().putBoolean("fg", v).apply()
    fun count(c: Context, k: String) = p(c).getInt(k, 0)
    fun markCalled(c: Context, n: String) = p(c).edit().putLong("seen_" + digits(n), System.currentTimeMillis()).apply()
    fun calledRecently(c: Context, n: String) = digits(n).length >= 7 && System.currentTimeMillis() - p(c).getLong("seen_" + digits(n), 0) < 86_400_000L
    fun str(c: Context, k: String) = p(c).getString(k, "") ?: ""
    fun putStr(c: Context, k: String, v: String) = p(c).edit().putString(k, v).apply()
    fun flag(c: Context, k: String) = p(c).getBoolean(k, false)
    fun putFlag(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).apply()
    fun inc(c: Context, k: String) = p(c).edit().putInt(k, count(c, k) + 1).apply()
    fun history(c: Context): List<List<String>> {
        val a = JSONArray(p(c).getString("h", "[]"))
        return (0 until a.length()).map { i -> a.getString(i).split("\u0001") }
    }
    /** level: 1 cảnh báo, 2 đã chặn */
    fun log(c: Context, level: Int, title: String, detail: String) {
        val old = JSONArray(p(c).getString("h", "[]"))
        val n = JSONArray().put(listOf(System.currentTimeMillis(), level, title, detail.take(160)).joinToString("\u0001"))
        for (i in 0 until minOf(old.length(), 99)) n.put(old.get(i))
        p(c).edit().putString("h", n.toString()).apply()
        inc(c, if (level == 2) "blocked" else "warned")
        Sync.push(c, level, title)
    }
}
