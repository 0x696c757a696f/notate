package app.notate.core.model.log

/** Minimal logging port; Android provides the real one in `:next:platform`. Any thread. */
interface Logger {
    fun d(tag: String, msg: String, t: Throwable? = null)
    fun i(tag: String, msg: String, t: Throwable? = null)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)

    companion object {
        /** Drops everything; the default for tests. */
        val NONE: Logger = object : Logger {
            override fun d(tag: String, msg: String, t: Throwable?) {}
            override fun i(tag: String, msg: String, t: Throwable?) {}
            override fun w(tag: String, msg: String, t: Throwable?) {}
            override fun e(tag: String, msg: String, t: Throwable?) {}
        }
    }
}
