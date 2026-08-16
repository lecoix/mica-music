package com.mica.music.media.usb

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Read-only generation observer fan-out. The existing Native publisher is always invoked first
 * and exactly once; observer failures are isolated after publication.
 */
internal class UsbOutputGenerationObserverFanout(
    private val onObserverFailure: (Long, Throwable) -> Unit = { _, _ -> },
) {
    private val publisher = AtomicReference<(Long) -> Unit>({})
    private val observers = CopyOnWriteArrayList<(Long) -> Unit>()

    fun publish(generation: Long) {
        publisher.get()(generation)
        observers.forEach { observer ->
            try {
                observer(generation)
            } catch (error: Throwable) {
                onObserverFailure(generation, error)
            }
        }
    }

    fun installPublisher(callback: (Long) -> Unit) {
        publisher.set(callback)
    }

    fun installObserver(observer: (Long) -> Unit): () -> Unit {
        observers += observer
        return { observers -= observer }
    }
}

/** Read-only post-publication facts fan-out. Observer failures cannot affect P2 owner state. */
internal class UsbOutputFactsObserverFanout(
    private val onObserverFailure: (PlaybackOutputFacts, Throwable) -> Unit = { _, _ -> },
) {
    private val observers = CopyOnWriteArrayList<(PlaybackOutputFacts) -> Unit>()

    fun publish(facts: PlaybackOutputFacts) {
        observers.forEach { observer ->
            try {
                observer(facts)
            } catch (error: Throwable) {
                onObserverFailure(facts, error)
            }
        }
    }

    fun installObserver(observer: (PlaybackOutputFacts) -> Unit): () -> Unit {
        observers += observer
        return { observers -= observer }
    }
}
