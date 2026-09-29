package com.honerai.admin.ui.login

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.honerai.admin.AdminContainer
import com.honerai.admin.BuildConfig
import com.honerai.admin.R
import com.honerai.admin.core.AdminSession
import com.honerai.admin.data.LoginResponse
import com.honerai.admin.net.friendlyError
import com.honerai.admin.ui.common.HonerField
import com.honerai.admin.ui.common.PrimaryButton
import com.honerai.admin.ui.common.SecondaryButton
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Вход: Google (Credential Manager) или ключ администратора; адрес сервера, если его нет в сборке. */
@Composable
fun LoginScreen(container: AdminContainer) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    val english = LocalEnglish.current
    val scope = rememberCoroutineScope()
    val savedUrl by container.session.serverUrl.collectAsStateWithLifecycle()
    val logoutReason by container.session.logoutReason.collectAsStateWithLifecycle()
    val buildUrl = container.session.buildServerUrl
    var url by rememberSaveable { mutableStateOf(savedUrl) }
    var editUrl by rememberSaveable { mutableStateOf(buildUrl.isEmpty()) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val googleClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID

    fun finish(response: LoginResponse) {
        container.session.save(AdminSession(response.token, response.email, response.name))
    }

    fun applyUrl(): Boolean {
        if (url.isBlank()) {
            error = if (english) "Enter the server address." else "Введите адрес сервера."
            return false
        }
        container.session.setServerUrl(url)
        return true
    }

    fun loginWithKey() {
        if (!applyUrl()) return
        if (key.isBlank()) { error = if (english) "Enter the admin key." else "Введите ключ администратора."; return }
        busy = "key"; error = null
        scope.launch {
            try {
                finish(container.api.loginWithKey(key.trim()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e, english)
            } finally {
                busy = null
            }
        }
    }

    fun loginWithGoogle() {
        if (!applyUrl()) return
        val activity = context.findActivity() ?: return
        busy = "google"; error = null
        scope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(googleClientId).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = CredentialManager.create(activity).getCredential(activity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val google = GoogleIdTokenCredential.createFrom(credential.data)
                    finish(container.api.loginWithGoogle(google.idToken))
                } else {
                    error = if (english) "Google did not return an account." else "Google не вернул аккаунт."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: GetCredentialCancellationException) {
                // Администратор закрыл окно выбора аккаунта.
            } catch (_: NoCredentialException) {
                error = if (english) "No Google account on this phone. Add one in system settings or use the admin key."
                else "На телефоне нет аккаунта Google. Добавьте его в настройках или войдите по ключу."
            } catch (e: GetCredentialException) {
                error = (if (english) "Google sign-in failed: " else "Не удалось войти через Google: ") + (e.message ?: e.type)
            } catch (e: Exception) {
                error = friendlyError(e, english)
            } finally {
                busy = null
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(26.dp))) {
                Image(painterResource(R.drawable.ic_admin_background), null, Modifier.fillMaxSize())
                Image(painterResource(R.drawable.ic_admin_foreground), null, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(20.dp))
            Text("Honer Admin", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = colors.foreground)
            Spacer(Modifier.height(6.dp))
            Text(tr("Панель администратора Honer AI", "Honer AI admin console"), fontSize = 15.sp, color = colors.secondary,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))

            logoutReason?.let {
                Text(it, color = colors.away, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp))
            }

            if (editUrl) {
                HonerField(url, { url = it; error = null }, tr("Адрес сервера", "Server address"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done))
                Text(tr("Например: https://honer-cloud.up.railway.app", "E.g. https://honer-cloud.up.railway.app"),
                    fontSize = 12.sp, color = colors.secondary, modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp))
                Spacer(Modifier.height(20.dp))
            }

            if (googleClientId.isNotBlank()) {
                PrimaryButton(tr("Войти через Google", "Sign in with Google"), ::loginWithGoogle, Modifier.fillMaxWidth(),
                    busy = busy == "google", enabled = busy == null)
            } else {
                Text(
                    tr("Вход через Google не настроен в этой сборке (нет GOOGLE_WEB_CLIENT_ID). Войдите по ключу администратора.",
                        "Google sign-in is not configured in this build (no GOOGLE_WEB_CLIENT_ID). Use the admin key."),
                    fontSize = 13.sp, color = colors.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(14.dp),
                )
            }
            Spacer(Modifier.height(12.dp))

            if (!showKey) {
                SecondaryButton(tr("Войти по ключу администратора", "Sign in with admin key"), { showKey = true; error = null },
                    Modifier.fillMaxWidth(), icon = Icons.Rounded.Key)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HonerField(key, { key = it; error = null }, tr("Ключ администратора", "Admin key"),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go))
                    SecondaryButton(
                        if (busy == "key") tr("Вход…", "Signing in…") else tr("Войти", "Sign in"),
                        ::loginWithKey, Modifier.fillMaxWidth(), icon = Icons.Rounded.Key, enabled = busy == null,
                    )
                }
            }

            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = colors.danger, fontSize = 14.sp, textAlign = TextAlign.Center)
            }

            if (!editUrl) {
                Spacer(Modifier.height(24.dp))
                Text(
                    tr("Сервер: ", "Server: ") + savedUrl + tr(" · изменить", " · change"),
                    fontSize = 12.sp, color = colors.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { editUrl = true }.padding(8.dp),
                )
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
