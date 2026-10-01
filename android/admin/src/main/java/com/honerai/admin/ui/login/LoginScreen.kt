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
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.honerai.admin.net.ApiException
import com.honerai.admin.net.friendlyError
import com.honerai.admin.ui.common.HonerField
import com.honerai.admin.ui.common.PrimaryButton
import com.honerai.admin.ui.common.SecondaryButton
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Что показывает экран входа. */
private enum class LoginMode { CHECKING, SETUP, LOGIN }

/**
 * Вход администратора. Основной путь — логин и пароль. Если аккаунта ещё нет (GET /v1/admin/setup-status),
 * экран «Создать администратора»: логин, пароль дважды и текущий ключ администратора (один раз).
 * Запасные пути: ключ администратора и Google. Адрес сервера — если его нет в сборке.
 */
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
    var mode by remember { mutableStateOf(LoginMode.CHECKING) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var recheck by remember { mutableIntStateOf(0) }
    var login by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var setupKey by remember { mutableStateOf("") }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val googleClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID

    // Есть ли аккаунт администратора на этом сервере.
    LaunchedEffect(savedUrl, recheck) {
        if (savedUrl.isBlank()) { mode = LoginMode.LOGIN; return@LaunchedEffect }
        mode = LoginMode.CHECKING
        statusError = null
        try {
            mode = if (container.api.setupStatus().hasAccount) LoginMode.LOGIN else LoginMode.SETUP
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Старый сервер без setup-status или нет связи: показываем обычный вход и запасные пути.
            mode = LoginMode.LOGIN
            statusError = friendlyError(e, english)
        }
    }

    fun finish(response: LoginResponse) {
        container.session.save(AdminSession(response.token, response.email.ifBlank { response.login.orEmpty() }, response.name, response.role))
    }

    /** Текст ошибки входа: 401/400/409 — сообщение сервера («Неверный логин или пароль»), иначе общий. */
    fun loginError(e: Exception): String =
        if (e is ApiException && e.status in setOf(400, 401, 409) && e.serverMessage.isNotBlank()) e.serverMessage
        else friendlyError(e, english)

    fun applyUrl(): Boolean {
        if (url.isBlank()) {
            error = if (english) "Enter the server address." else "Введите адрес сервера."
            return false
        }
        container.session.setServerUrl(url)
        return true
    }

    fun attempt(tag: String, block: suspend () -> Unit) {
        busy = tag; error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.status == 409 && tag == "setup") mode = LoginMode.LOGIN // аккаунт уже создан кем-то
                error = loginError(e)
            } catch (e: Exception) {
                error = loginError(e)
            } finally {
                busy = null
            }
        }
    }

    fun loginWithPassword() {
        if (!applyUrl()) return
        if (login.isBlank() || password.isEmpty()) { error = if (english) "Enter login and password." else "Введите логин и пароль."; return }
        attempt("password") { finish(container.api.loginWithPassword(login.trim(), password)) }
    }

    fun createAccount() {
        if (!applyUrl()) return
        val problem = when {
            login.trim().length < 3 -> if (english) "Login must be at least 3 characters." else "Логин — не короче 3 символов."
            password.length < 8 -> if (english) "Password must be at least 8 characters." else "Пароль — не короче 8 символов."
            password != password2 -> if (english) "Passwords do not match." else "Пароли не совпадают."
            else -> null
        }
        if (problem != null) { error = problem; return }
        attempt("setup") { finish(container.api.setupAccount(login.trim(), password, setupKey)) }
    }

    fun loginWithKey() {
        if (!applyUrl()) return
        if (key.isBlank()) { error = if (english) "Enter the admin key." else "Введите ключ администратора."; return }
        attempt("key") { finish(container.api.loginWithKey(key.trim())) }
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
                error = if (english) "No Google account on this phone. Add one in system settings or use your login."
                else "На телефоне нет аккаунта Google. Добавьте его в настройках или войдите по логину."
            } catch (e: GetCredentialException) {
                error = (if (english) "Google sign-in failed: " else "Не удалось войти через Google: ") + (e.message ?: e.type)
            } catch (e: Exception) {
                error = loginError(e)
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
            Text(
                when (mode) {
                    LoginMode.SETUP -> tr("Создайте аккаунт администратора", "Create the admin account")
                    else -> tr("Панель администратора Honer AI", "Honer AI admin console")
                },
                fontSize = 15.sp, color = colors.secondary, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            logoutReason?.let {
                Text(it, color = colors.away, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp))
            }

            if (editUrl) {
                HonerField(url, { url = it; error = null }, tr("Адрес сервера", "Server address"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done))
                Text(tr("Например: https://honer.xoner4.deno.net", "E.g. https://honer.xoner4.deno.net"),
                    fontSize = 12.sp, color = colors.secondary, modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp))
                Spacer(Modifier.height(8.dp))
                SecondaryButton(tr("Проверить сервер", "Check server"), {
                    if (applyUrl()) recheck++
                }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(20.dp))
            }

            when (mode) {
                LoginMode.CHECKING -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                }
                LoginMode.SETUP -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        tr("Аккаунта ещё нет. Придумайте логин и пароль — дальше вы всегда будете входить по ним. " +
                            "Ключ администратора (ADMIN_KEY с сервера) нужен один раз, чтобы подтвердить, что сервер ваш.",
                            "There is no account yet. Choose a login and password — you will sign in with them from now on. " +
                                "The admin key (ADMIN_KEY on the server) is needed once to prove the server is yours."),
                        fontSize = 13.sp, color = colors.secondary,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(14.dp),
                    )
                    HonerField(login, { login = it; error = null }, tr("Логин", "Login"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                    HonerField(password, { password = it; error = null }, tr("Пароль (не короче 8 символов)", "Password (8+ characters)"),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next))
                    HonerField(password2, { password2 = it; error = null }, tr("Повторите пароль", "Repeat the password"),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next))
                    HonerField(setupKey, { setupKey = it; error = null }, tr("Ключ администратора", "Admin key"),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
                    PrimaryButton(tr("Создать администратора", "Create admin"), ::createAccount, Modifier.fillMaxWidth(),
                        busy = busy == "setup", enabled = busy == null,
                        icon = { Icon(Icons.Rounded.Person, null, tint = colors.background, modifier = Modifier.size(19.dp)) })
                }
                LoginMode.LOGIN -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HonerField(login, { login = it; error = null }, tr("Логин", "Login"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                    HonerField(password, { password = it; error = null }, tr("Пароль", "Password"),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go))
                    PrimaryButton(tr("Войти", "Sign in"), ::loginWithPassword, Modifier.fillMaxWidth(),
                        busy = busy == "password", enabled = busy == null)
                    statusError?.let {
                        Text(it + tr(" · Повторить", " · Retry"), fontSize = 12.sp, color = colors.secondary, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { recheck++ }.padding(6.dp))
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = colors.danger, fontSize = 14.sp, textAlign = TextAlign.Center)
            }

            // Запасные способы входа.
            Spacer(Modifier.height(24.dp))
            if (googleClientId.isNotBlank()) {
                SecondaryButton(
                    if (busy == "google") tr("Вход…", "Signing in…") else tr("Войти через Google", "Sign in with Google"),
                    ::loginWithGoogle, Modifier.fillMaxWidth(), enabled = busy == null,
                )
                Spacer(Modifier.height(10.dp))
            }
            if (!showKey) {
                Text(tr("Войти по ключу администратора", "Sign in with the admin key"), fontSize = 14.sp, color = colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showKey = true; error = null }.padding(8.dp))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HonerField(key, { key = it; error = null }, tr("Ключ администратора", "Admin key"),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go))
                    SecondaryButton(
                        if (busy == "key") tr("Вход…", "Signing in…") else tr("Войти по ключу", "Sign in with key"),
                        ::loginWithKey, Modifier.fillMaxWidth(), icon = Icons.Rounded.Key, enabled = busy == null,
                    )
                }
            }

            if (!editUrl) {
                Spacer(Modifier.height(20.dp))
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
