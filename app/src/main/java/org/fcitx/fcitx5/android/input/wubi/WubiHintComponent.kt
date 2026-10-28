/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wubi

import android.graphics.Rect
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.annotation.Keep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.KeyPressEvent
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler

class WubiHintComponent(
    private val resolveVariant: suspend (InputMethodEntry) -> WubiVariant?,
    private val keyboardBounds: () -> Rect?
) : UniqueComponent<WubiHintComponent>(), Dependent, InputBroadcastReceiver,
    ManagedHandler by managedHandler() {

    private val context by manager.context()
    private val theme by manager.theme()
    private val enabledPreference = AppPrefs.getInstance().keyboard.showWubiHints
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pressState = WubiHintPressState()

    private var activeIme: InputMethodEntry? = null
    private var lookupJob: Job? = null
    private var lingerJob: Job? = null
    private var lookupGeneration = 0
    private var variant: WubiVariant? = null
    private var keyboardAttached = false
    // Wait for the editor's capabilities before showing a newly created hint.
    private var password = true
    private var disposed = false
    private var listenerRegistered = false

    private val hintView = lazy {
        WubiHintView(context, theme).apply {
            hideHint()
            onHidden = {
                lingerJob?.cancel()
                lingerJob = null
                pressState.clear()
            }
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (oldRight > oldLeft && oldBottom > oldTop &&
                    (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
                ) {
                    dismiss()
                }
            }
        }
    }

    val view: WubiHintView by hintView

    @Keep
    private val enabledListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        if (!enabled) dismiss()
    }

    override fun onScopeSetupFinished(scope: DynamicScope) {
        if (disposed || listenerRegistered) return
        enabledPreference.registerOnChangeListener(enabledListener)
        listenerRegistered = true
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        if (disposed) return
        resolveIme(ime)
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        if (disposed) return
        dismiss()
        password = capFlags.has(CapabilityFlag.Password) ||
            (info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER &&
                info.inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        // Re-check compiled metadata after deploy, including unchanged captions.
        activeIme?.let { resolveIme(it, force = true) }
    }

    override fun onKeyPress(event: KeyPressEvent) {
        if (disposed) return
        when (event.phase) {
            KeyPressEvent.Phase.Down -> {
                lingerJob?.cancel()
                lingerJob = null
                // Non-letter keys invalidate an earlier letter's hint too.
                pressState.down(event.token, event.letter)
                renderCurrentHint()
            }
            KeyPressEvent.Phase.Release -> {
                val ticket = pressState.release(event.token) ?: return
                lingerJob?.cancel()
                lingerJob = scope.launch {
                    delay(LINGER_MS)
                    if (pressState.expire(ticket) && hintView.isInitialized()) {
                        view.hideHint(immediate = false)
                    }
                }
            }
            KeyPressEvent.Phase.Cancel -> {
                if (pressState.cancel(event.token)) dismiss()
            }
        }
    }

    override fun onWindowAttached(window: InputWindow) {
        if (disposed) return
        keyboardAttached = window is KeyboardWindow
        if (!keyboardAttached) dismiss()
    }

    override fun onWindowDetached(window: InputWindow) {
        if (disposed) return
        if (window is KeyboardWindow) {
            keyboardAttached = false
            dismiss()
        }
    }

    private fun resolveIme(ime: InputMethodEntry, force: Boolean = false) {
        val changed = activeIme != ime
        if (!changed && !force) return
        activeIme = ime
        val generation = ++lookupGeneration
        lookupJob?.cancel()
        lookupJob = null
        // Hide the previous chart before an asynchronous lookup, including after deploy.
        // A key pressed while the lookup runs may then show only the resolved variant.
        dismiss()
        variant = null
        if (!WubiHintVisibility.canResolve(ime.uniqueName, ime.subMode.icon)) {
            return
        }
        lookupJob = scope.launch {
            // The supplied resolver reads metadata off the UI thread. Fcitx
            // exposes the schema's display caption here, not its native ID.
            val resolved = runCatching { resolveVariant(ime) }.getOrElse {
                if (it is CancellationException) throw it
                null
            }
            if (!disposed && generation == lookupGeneration) {
                variant = resolved
                if (resolved != null) renderCurrentHint() else dismiss()
            }
        }
    }

    private fun canShow() = !disposed && WubiHintVisibility.shouldShow(
        enabled = enabledPreference.getValue(),
        isWubi = variant != null,
        keyboardAttached = keyboardAttached,
        password = password
    )

    private fun renderCurrentHint() {
        val selection = variant?.let { currentVariant ->
            pressState.current?.letter?.let {
                WubiHintSelection.forKey(it, WubiCharts.forVariant(currentVariant))
            }
        }
        val bounds = if (canShow() && selection != null) keyboardBounds() else null
        if (bounds != null && !bounds.isEmpty && selection != null) {
            view.showHint(selection, Rect(bounds))
        } else if (hintView.isInitialized()) {
            view.hideHint()
        }
    }

    fun dismiss() {
        lingerJob?.cancel()
        lingerJob = null
        pressState.clear()
        if (hintView.isInitialized()) view.hideHint()
    }

    fun finishInput() = dismiss()

    fun dispose() {
        if (disposed) return
        disposed = true
        ++lookupGeneration
        if (hintView.isInitialized()) view.onHidden = null
        dismiss()
        scope.cancel()
        activeIme = null
        variant = null
        if (listenerRegistered) {
            enabledPreference.unregisterOnChangeListener(enabledListener)
            listenerRegistered = false
        }
    }

    private companion object {
        const val LINGER_MS = 850L
    }
}
