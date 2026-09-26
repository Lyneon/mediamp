/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.features.AudioOnlyMode
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Guards the [AudioOnlyMode] path of [MpvMediampPlayer] against issue #78: an audio-only player
 * must be able to open media with **no render surface attached**.
 *
 * The producer render context can only be chosen once the window has a live Skiko redrawer, so
 * `openImpl` suspends in `awaitRenderContextForLoad()` until a surface attaches (spec §6, degraded
 * `surface-independent-open`) — on Windows and Linux an audio-only player would otherwise hold in
 * `Opening` indefinitely. [AudioOnlyMode] is the declared video-disabled mode (spec §6) that removes
 * that requirement, and both of its effects are asserted here: the mpv side (`vo=null`) and the
 * mediamp side (the open no longer waits). It is reached through
 * [org.openani.mediamp.MediampPlayer.features], so it works for players obtained from any entry
 * point, including the common factories.
 *
 * Runs only via `:mediamp-mpv:zeroConfigTest`, which puts the platform runtime jar on the
 * classpath and starts a fresh JVM (the native loader keeps global state per JVM).
 */
class MpvSurfaceGateZeroConfigTest {

    private val settleDeadline = 15.seconds

    /**
     * The state machine checks command threads by physical identity, and `limitedParallelism(1)`
     * only serializes — a continuation may resume on a different pool worker, which flips
     * `close()` onto its asynchronous trampoline or trips the machine's check depending on
     * scheduler luck (Windows CI flake; see `MpvMediampPlayerSmokeTest.runPlayerTest`). These
     * tests own exactly one thread.
     */
    private fun singleThreadDispatcher(): ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mpv-zeroconfig-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private fun zeroConfigEnabled(): Boolean {
        if (System.getProperty("mediamp.mpv.zeroconfig") == "true") return true
        println("[SurfaceGateTest] skipped: run via :mediamp-mpv:zeroConfigTest")
        return false
    }

    /**
     * Answers 404 for every request, so a load fails inside `setMediaData` with a diagnosable
     * error. Deliberately not an unreachable port: whether connecting to a closed port fails
     * immediately (RST) or lingers is OS- and firewall-dependent, which would make these tests
     * flaky — and it would hide the very contract they check.
     */
    private class NotFoundServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/video.mp4") { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            start()
        }

        val uri: String get() = "http://127.0.0.1:${server.address.port}/video.mp4"

        override fun close() {
            server.stop(0)
        }
    }

    @OptIn(InternalMediampApi::class)
    @Test
    fun `AudioOnlyMode opens media with no surface attach`() {
        if (!zeroConfigEnabled()) return

        val mainDispatcher = singleThreadDispatcher()
        mainDispatcher.use { mainDispatcher ->
            NotFoundServer().use { server ->
                runBlocking(mainDispatcher) {
                    val player = MpvMediampPlayer(Any(), coroutineContext, mainDispatcher = mainDispatcher)
                    player.use { player ->
                        val audioOnly = assertNotNull(
                            player.features[AudioOnlyMode.Key],
                            "the mpv backend must expose AudioOnlyMode",
                        )
                        audioOnly.setEnabled(true)
                        assertTrue(audioOnly.enabled.value, "the feature must report the new state")

                        // Effect 1 (mpv side): video output is declared disabled (spec §6).
                        val handle = player.impl as MPVHandle
                        assertEquals(
                            "null",
                            handle.getPropertyString("vo"),
                            "enabling AudioOnlyMode must set vo=null",
                        )

                        // Effect 2 (mediamp side): the open is no longer gated on a surface attach.
                        val outcome = withTimeoutOrNull(settleDeadline) {
                            runCatching {
                                player.setMediaData(
                                    UriMediaData(server.uri, emptyMap(), MediaExtraFiles.EMPTY),
                                )
                            }
                        }
                        assertNotNull(
                            outcome,
                            "with AudioOnlyMode enabled the open must not be gated on a surface " +
                                    "attach, on any platform: setMediaData was still in Opening after " +
                                    "$settleDeadline (issue #78).",
                        )
                        // The gate is open, so `loadfile` really ran — and the 404 must surface as
                        // a PlaybackException inside setMediaData, per the v2 open contract. This
                        // distinguishes "opened and failed properly" from "never got past the gate".
                        assertTrue(
                            outcome.exceptionOrNull() is PlaybackException,
                            "expected the 404 to surface as PlaybackException inside setMediaData, " +
                                    "got ${outcome.exceptionOrNull()}",
                        )
                    }
                }
            }
        }
    }

    @OptIn(InternalMediampApi::class)
    @Test
    fun `AudioOnlyMode can be toggled back before the first open`() {
        if (!zeroConfigEnabled()) return

        val mainDispatcher = singleThreadDispatcher()
        mainDispatcher.use { mainDispatcher ->
            runBlocking(mainDispatcher) {
                val player = MpvMediampPlayer(Any(), coroutineContext, mainDispatcher = mainDispatcher)
                player.use { player ->
                    val handle = player.impl as MPVHandle
                    val platformVideoOutput = assertNotNull(
                        handle.getPropertyString("vo"),
                        "precondition: the backend configures a video output",
                    )
                    val audioOnly = assertNotNull(player.features[AudioOnlyMode.Key])

                    audioOnly.setEnabled(true)
                    assertEquals("null", handle.getPropertyString("vo"))

                    // Disabling restores exactly what the backend had configured (libmpv on
                    // desktop, gpu-next on Android) instead of a hardcoded value.
                    audioOnly.setEnabled(false)
                    assertEquals(platformVideoOutput, handle.getPropertyString("vo"))
                    assertTrue(!audioOnly.enabled.value)
                }
            }
        }
    }
}
