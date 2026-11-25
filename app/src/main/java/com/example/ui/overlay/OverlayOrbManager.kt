package com.example.ui.overlay

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.service.AssistantRuntimeState
import com.example.service.OrbPhase
import com.example.ui.components.CosmicVoiceOrb
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianSurfaceBorder
import com.example.ui.theme.TelefonimTheme
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

private class OverlayViewLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}

class OverlayOrbManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayViewLifecycleOwner? = null

    fun showOverlay(onDismissRequest: () -> Unit) {
        AssistantRuntimeState.update { it.copy(isOverlayVisible = true) }

        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isLockedOrAsleep = km.isKeyguardLocked || !pm.isInteractive

        // Use lock-screen capable VoiceOverlayActivity when screen is locked/asleep or overlay perm missing
        if (isLockedOrAsleep || !Settings.canDrawOverlays(context)) {
            removeWindowViewOnly()
            val intent = Intent(context, VoiceOverlayActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            context.startActivity(intent)
            return
        }

        if (composeView != null) return

        val owner = OverlayViewLifecycleOwner().apply { onCreate() }
        lifecycleOwner = owner

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 140
        }

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                TelefonimTheme {
                    FloatingOrbOverlayContent(onDismiss = onDismissRequest)
                }
            }
        }

        try {
            windowManager.addView(view, params)
            composeView = view
        } catch (_: Exception) {
            owner.onDestroy()
            lifecycleOwner = null
        }
    }

    fun hideOverlay() {
        AssistantRuntimeState.update {
            it.copy(isOverlayVisible = false, countdownSeconds = null)
        }
        removeWindowViewOnly()
    }

    private fun removeWindowViewOnly() {
        composeView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
        composeView = null
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
    }
}

@Composable
fun FloatingOrbOverlayContent(
    onDismiss: () -> Unit
) {
    val state by AssistantRuntimeState.state.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = state.isOverlayVisible,
        enter = fadeIn() + scaleIn(initialScale = 0.78f),
        exit = fadeOut() + scaleOut(targetScale = 0.78f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                CosmicVoiceOrb(
                    phase = state.orbPhase,
                    audioLevel = state.audioLevel,
                    size = 164.dp,
                    onClick = null,
                    testTagName = "floating_overlay_orb"
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(ObsidianBg.copy(alpha = 0.85f))
                        .border(1.dp, ObsidianSurfaceBorder, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Yopish",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .widthIn(min = 220.dp, max = 320.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(ObsidianBg.copy(alpha = 0.94f))
                    .border(
                        1.dp,
                        if (state.countdownSeconds != null) AmberThinking.copy(alpha = 0.65f)
                        else ElectricCyan.copy(alpha = 0.42f),
                        RoundedCornerShape(22.dp)
                    )
                    .clickable { onDismiss() }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val titleText = when {
                        state.countdownSeconds != null -> "Yopilishiga ${state.countdownSeconds} soniya..."
                        state.orbPhase == OrbPhase.ACTIVE_LISTENING -> "Tinglamoqdaman, gapiring..."
                        state.orbPhase == OrbPhase.THINKING -> "O'ylayapman..."
                        state.orbPhase == OrbPhase.EXECUTING -> "Bajarmoqda..."
                        state.orbPhase == OrbPhase.SPEAKING -> "${state.wakeWord} gapirmoqda"
                        else -> state.wakeWord
                    }
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (state.countdownSeconds != null) AmberThinking else ElectricCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (state.liveTranscript.isNotBlank()) {
                    Text(
                        text = "\"${state.liveTranscript}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (state.aiResponse.isNotBlank() && state.orbPhase != OrbPhase.ACTIVE_LISTENING) {
                    Text(
                        text = state.aiResponse,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
