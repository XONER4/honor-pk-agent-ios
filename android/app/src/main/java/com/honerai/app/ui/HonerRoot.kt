package com.honerai.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.AppContainer
import com.honerai.app.MainActivity
import com.honerai.app.R
import com.honerai.app.ui.theme.HonerAppTheme
import com.honerai.app.ui.theme.HonerTheme

/** Корень интерфейса: тема, первый экран или чат. */
@Composable
fun HonerRoot(activity: MainActivity) {
    val container = AppContainer.get(LocalContext.current)
    val appearance by container.settings.appearance.collectAsState()
    HonerAppTheme(appearance = appearance) {
        Column(
            modifier = Modifier.fillMaxSize().background(HonerTheme.colors.background),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.honer_logo), contentDescription = "Honer AI", modifier = Modifier.size(120.dp))
            Text("Honer AI", color = HonerTheme.colors.foreground, fontSize = 32.sp)
        }
    }
}
