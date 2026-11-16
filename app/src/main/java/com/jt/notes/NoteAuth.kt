package com.jt.notes

import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** One biometric check for opening a locked note, following the app-lock prompt. */
object NoteAuth {
    fun prompt(activity: FragmentActivity, title: String, onAuthenticated: () -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onAuthenticated()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(MainActivity.LOCK_AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }
}
