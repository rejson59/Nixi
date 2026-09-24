package dev.nixi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Licznik, który rośnie przy każdym powrocie do aplikacji (ON_RESUME).
 *
 * Ekrany sprawdzające stan uprawnień (mikrofon, dostęp do powiadomień,
 * usługa dostępności, autostart w HyperOS) muszą się odświeżyć po powrocie
 * z ustawień systemowych — bez tego checklista pokazywała stan sprzed
 * wyjścia z aplikacji.
 */
@Composable
fun rememberOnResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}
