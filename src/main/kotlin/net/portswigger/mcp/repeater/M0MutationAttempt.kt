package net.portswigger.mcp.repeater

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Monotonic, metadata-only evidence independent of any phase result/future. */
internal class M0MutationAttempt {
    private enum class State { OPEN, STARTED, CLOSED }
    private val state = AtomicReference(State.OPEN)
    private val returned = AtomicBoolean()
    val open get() = state.get() == State.OPEN
    val attempted get() = state.get() == State.STARTED
    val returnedNormally get() = returned.get()
    fun admit() = state.compareAndSet(State.OPEN, State.STARTED)
    fun recordNormalReturn() { check(attempted); returned.set(true) }
    fun close() { state.compareAndSet(State.OPEN, State.CLOSED) }
}

/** Separate read-only comparison material, with no original/write-plan capability. */
internal class M0ReadbackExpectation(bytes: ByteArray, private val host: String, private val port: Int,
    private val tls: Boolean, private val version: String) {
    private val expected = bytes.copyOf()
    fun compare(bytes: ByteArray, host: String, port: Int, tls: Boolean, version: String) =
        M0FreshReadbackObservation(expected.contentEquals(bytes), this.host.equals(host, true) &&
            this.port == port && this.tls == tls, this.version == version)
}
