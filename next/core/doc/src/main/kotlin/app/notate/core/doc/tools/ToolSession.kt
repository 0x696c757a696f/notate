package app.notate.core.doc.tools

import app.notate.core.model.InkPoint

/** One pen gesture driven by a tool. Created per gesture by [ToolSessionFactory]. Main thread only. */
interface ToolSession {
    fun begin(p: InkPoint)
    fun move(batch: List<InkPoint>)
    fun end(p: InkPoint)
    fun cancel()
}

/** Session for tools without pen behaviour yet; ignores everything. */
object NoopSession : ToolSession {
    override fun begin(p: InkPoint) = Unit
    override fun move(batch: List<InkPoint>) = Unit
    override fun end(p: InkPoint) = Unit
    override fun cancel() = Unit
}
