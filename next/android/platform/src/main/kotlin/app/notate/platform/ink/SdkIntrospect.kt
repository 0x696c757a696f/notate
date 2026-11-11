package app.notate.platform.ink

import java.lang.reflect.Modifier

/**
 * Diagnostic: a compact dump of an SDK object's state by reflection (primitives, strings, nulls, class names of
 * references, and one more level into fields that look like the pen/input plumbing). Lets the HUD show why
 * `TouchHelper` never calls back, without the SDK source. Never throws.
 */
object SdkIntrospect {
    private val DEEP = Regex("input|reader|manager|helper|touch|raw|pen|render|region|callback|state", RegexOption.IGNORE_CASE)

    fun dump(obj: Any?, maxChars: Int = 900): String {
        if (obj == null) return "null"
        val sb = StringBuilder()
        try {
            walk(obj, "", 0, sb, maxChars)
        } catch (t: Throwable) {
            sb.append(" !").append(t.javaClass.simpleName)
        }
        return sb.toString().take(maxChars)
    }

    private fun walk(obj: Any, prefix: String, depth: Int, sb: StringBuilder, max: Int) {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java && sb.length < max) {
            for (f in c.declaredFields) {
                if (sb.length >= max) return
                if (Modifier.isStatic(f.modifiers)) continue
                val v = try {
                    f.isAccessible = true
                    f.get(obj)
                } catch (_: Throwable) {
                    continue
                }
                val name = prefix + f.name
                when {
                    v == null -> sb.append(name).append("=null ")
                    v is Boolean || v is Number || v is Char -> sb.append(name).append('=').append(v).append(' ')
                    v is String -> sb.append(name).append("=\"").append(v.take(24)).append("\" ")
                    v.javaClass.isArray -> sb.append(name).append("=[]").append(java.lang.reflect.Array.getLength(v)).append(' ')
                    v is Collection<*> -> sb.append(name).append("=#").append(v.size).append(' ')
                    v is Map<*, *> -> sb.append(name).append("=#").append(v.size).append(' ')
                    else -> {
                        sb.append(name).append(':').append(v.javaClass.simpleName).append(' ')
                        if (depth < 1 && DEEP.containsMatchIn(f.name + v.javaClass.simpleName) &&
                            !v.javaClass.name.startsWith("android.") && !v.javaClass.name.startsWith("java.")
                        ) {
                            walk(v, "$name.", depth + 1, sb, max)
                        }
                    }
                }
            }
            c = c.superclass
        }
    }
}
