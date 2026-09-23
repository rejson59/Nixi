package dev.nixi.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nixi.NixiApp
import dev.nixi.NixiState
import dev.nixi.db.SupabaseHub
import dev.nixi.notif.NixiNotificationListener
import dev.nixi.notif.ReminderScheduler
import dev.nixi.store.LocalStore
import dev.nixi.ui.home.HomeScreen
import dev.nixi.ui.logs.LogsScreen
import dev.nixi.ui.onboarding.OnboardingScreen
import dev.nixi.ui.settings.SettingsScreen
import dev.nixi.ui.tables.TablesScreen
import dev.nixi.ui.theme.NixiBg
import dev.nixi.ui.theme.NixiPurple
import dev.nixi.ui.theme.NixiText
import dev.nixi.ui.theme.NixiTextDim
import dev.nixi.ui.theme.NixiTheme
import dev.nixi.wake.WakeWordService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            NixiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Root()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SupabaseHub.refreshAll()
        // 1) nasłuch ma działać zawsze, gdy jest włączony w ustawieniach
        if (LocalStore.onboarded && LocalStore.wakeEnabled && !WakeWordService.running) {
            WakeWordService.start(this)
        }
        // 2) HyperOS/system mógł wyczyścić alarmy (force-stop) — odtwórz przypomnienia
        NixiApp.scope.launch {
            runCatching { ReminderScheduler.rescheduleAll(applicationContext) }
            runCatching { NixiNotificationListener.instance?.refreshRules() }
        }
    }
}

@Composable
fun Root() {
    var onboarded by remember { mutableStateOf(LocalStore.onboarded) }
    val appCtx = LocalContext.current
    if (!onboarded) {
        OnboardingScreen(
            onFinish = {
                LocalStore.onboarded = true
                SupabaseHub.rebuild()
                SupabaseHub.refreshAll()
                if (LocalStore.wakeEnabled) WakeWordService.start(appCtx)
                onboarded = true
            }
        )
    } else {
        MainShell()
    }
}

@Composable
private fun MainShell() {
    var tab by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (!WakeWordService.running && LocalStore.wakeEnabled) {
            WakeWordService.start(ctx)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> HomeScreen()
                1 -> TablesScreen()
                2 -> LogsScreen()
                3 -> SettingsScreen()
            }
        }
        BottomNav(selected = tab, onSelected = { tab = it })
    }
}

private data class NavItem(val label: String, val icon: ImageVector)

@Composable
fun BottomNav(selected: Int, onSelected: (Int) -> Unit) {
    val items = listOf(
        NavItem("Główna", Icons.Filled.Home),
        NavItem("Tabele", Icons.Filled.TableRows),
        NavItem("Logi", Icons.Filled.List),
        NavItem("Ustawienia", Icons.Filled.Settings),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NixiBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEachIndexed { index, item ->
            val active = index == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelected(index) }
                    .padding(vertical = 4.dp),
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = if (active) NixiPurple else NixiTextDim,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    item.label,
                    color = if (active) NixiPurple else NixiTextDim,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
