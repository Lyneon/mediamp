/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.features

import kotlinx.coroutines.flow.StateFlow
import org.openani.mediamp.InternalForInheritanceMediampApi

/**
 * An optional feature of the [org.openani.mediamp.MediampPlayer] that turns video output off
 * entirely: a video-disabled player still decodes video tracks but never displays them, so it needs
 * no render surface, and opening media no longer waits for one to attach
 * (spec `docs/playback-state-v2.md` §6, capability `surface-independent-open`).
 *
 * This is the supported path for audio-only players — and for load-before-UI — on backends whose
 * default path requires a render environment before `loadfile` (Windows, Linux): there the Ready
 * point of an open is otherwise gated on the surface attach.
 *
 * [setEnabled] must be called before the first [org.openani.mediamp.MediampPlayer.setMediaData] of
 * the player: the mode decides whether a producer render context is needed at open time, so
 * changing it once a session exists is rejected with an [IllegalStateException] instead of being
 * applied silently (a renderer that is already bound to a loaded file cannot be swapped; see the
 * spec §6 on `vo=libmpv`).
 *
 * ## Availability
 *
 * Currently provided by the **`mediamp-mpv` backend on the JVM family** — desktop (Windows, macOS,
 * Linux) and Android. Other backends (ExoPlayer, AVPlayer/avkit, web, the test player) do **not**
 * expose it: [PlayerFeatures.get] returns `null` and [PlayerFeatures.supports] is `false`, so check
 * before use instead of assuming the feature exists.
 *
 * The iOS mpv backend does not expose it either, and does not need to — that backend is always
 * audio-only (`vo=null`).
 */
@SubclassOptInRequired(InternalForInheritanceMediampApi::class)
public interface AudioOnlyMode : Feature {
    /**
     * A hot flow of whether audio-only mode is currently enabled for this player.
     */
    public val enabled: StateFlow<Boolean>

    /**
     * Enables or disables the audio-only mode.
     *
     * @throws IllegalStateException if this player already has a session, i.e. `setMediaData` has
     * been called at least once.
     */
    public fun setEnabled(enabled: Boolean)

    /**
     * Key for [AudioOnlyMode]. See that interface's **Availability** section: this feature is not
     * exposed by every backend, so obtain it via [PlayerFeatures.get] (or check
     * [PlayerFeatures.supports]) and guard against `null`.
     * 
     * @see AudioOnlyMode
     */
    public companion object Key : FeatureKey<AudioOnlyMode>
}
