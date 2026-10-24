package space.mana.smartbag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ======================================================================================
// Signed out: Welcome, Create account, Log in. Passwords live only in this screen's memory
// (remember, never rememberSaveable) and go straight to the TLS socket.
// ======================================================================================

private val EMAIL = Regex("^[^\\s@]{1,64}@[^\\s@]{1,190}\\.[^\\s@]{2,}$")
private fun emailError(e: String) = if (EMAIL.matches(e.trim())) null else "Enter a valid email address."
private fun passwordError(p: String) = if (p.length < 8) "Use at least 8 characters for your password." else null

@Composable
fun WelcomeScreen(onCreate: () -> Unit, onLogIn: () -> Unit) {
    val p = LocalPalette.current
    val actions = @Composable {
        Pill("Create account", Modifier.fillMaxWidth(), onClick = onCreate)
        Spacer(Modifier.height(4.dp))
        TextAction("I already have an account", Modifier.fillMaxWidth(), onClick = onLogIn)
    }
    val inline = big
    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(32.dp))
            Logo(32.dp)
            Spacer(Modifier.height(48.dp))
            Txt("The bag that checks you out.", Type.display, bold = true, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(16.dp))
            Txt("Drop groceries in your bag and just walk out. Your receipt lands here as you leave.", Type.body, p.muted)
            Spacer(Modifier.height(40.dp))
            Beat(1, "Drop it in", "Put groceries in your bag as you shop.")
            Hairline()
            Beat(2, "It knows", "Each item shows up here with the store's price.")
            Hairline()
            Beat(3, "Walk out", "Your card is charged as you leave the store.")
            Spacer(Modifier.height(24.dp))
            if (inline) { actions(); Spacer(Modifier.height(24.dp)); Spacer(Modifier.navigationBarsPadding()) }
        }
        if (!inline) BottomArea { actions() }
    }
}

@Composable
private fun Beat(n: Int, title: String, body: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 16.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.Top) {
        Box(Modifier.width(24.dp)) { Txt("$n", Type.body, p.faint, num = true) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, Type.body, bold = true)
            Txt(body, Type.caption, p.muted)
        }
    }
}

/** Create account (signUp = true) or Log in. [onSubmit] gets null on success or the server's message. */
@Composable
fun AuthScreen(
    signUp: Boolean, onBack: () -> Unit, onSwitch: () -> Unit,
    onSubmit: (name: String, email: String, password: String, done: (String?) -> Unit) -> Unit,
    preview: Triple<String, String, String>? = null, previewError: String? = null, previewBusy: Boolean = false, previewTried: Boolean = false,
) {
    val p = LocalPalette.current
    val focus = LocalFocusManager.current
    var name by remember { mutableStateOf(preview?.first ?: "") }
    var email by remember { mutableStateOf(preview?.second ?: "") }
    var password by remember { mutableStateOf(preview?.third ?: "") }
    var show by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(previewTried) }
    var busy by remember { mutableStateOf(previewBusy) }
    var server by remember { mutableStateOf(previewError) }
    val nameErr = if (tried && signUp && name.isBlank()) "Enter your name." else null
    val emailErr = if (tried) emailError(email) else null
    val pwErr = if (tried) (if (signUp) passwordError(password) else if (password.isEmpty()) "Enter your password." else null) else null
    val pwFocus = remember { FocusRequester() }

    fun submit() {
        tried = true
        val bad = (signUp && name.isBlank()) || emailError(email) != null ||
            (if (signUp) passwordError(password) != null else password.isEmpty())
        if (bad || busy) return
        focus.clearFocus()
        busy = true; server = null
        onSubmit(name.trim(), email.trim(), password) { err ->
            busy = false
            server = err
            if (err != null) password = ""          // never keep a password around after a failed try
        }
    }

    SecureScreen()
    Column(Modifier.fillMaxSize().background(p.bg)) {
        TopBar(onBack = if (busy) null else onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(8.dp))
            ScreenTitle(if (signUp) "Create your account" else "Log in",
                if (signUp) "Your cards and bags stay with your account, on any phone." else "Welcome back.")
            Spacer(Modifier.height(32.dp))
            if (signUp) {
                Field("Name", name, { name = it.take(60) }, error = nameErr, enabled = !busy, capitalization = KeyboardCapitalization.Words)
                Spacer(Modifier.height(24.dp))
            }
            Field("Email", email, { email = it.take(254).replace(" ", "") }, error = emailErr, enabled = !busy, keyboard = KeyboardType.Email,
                onIme = { pwFocus.requestFocus() })
            Spacer(Modifier.height(24.dp))
            Field(
                "Password", password, { password = it.take(128) }, Modifier.focusRequester(pwFocus), error = pwErr,
                hint = if (signUp) "At least 8 characters" else null, enabled = !busy,
                keyboard = KeyboardType.Password, ime = ImeAction.Done, onIme = { submit() },
                visual = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = { TextAction(if (show) "Hide" else "Show", Modifier.semantics { contentDescription = if (show) "Hide password" else "Show password" }) { show = !show } },
            )
            Spacer(Modifier.height(24.dp))
        }
        BottomArea {
            ErrorLine(server)
            BusyPill(if (signUp) "Create account" else "Log in", busy) { submit() }
            Spacer(Modifier.height(4.dp))
            TextAction(if (signUp) "Log in instead" else "Create an account", Modifier.fillMaxWidth(), enabled = !busy, onClick = onSwitch)
        }
    }
}
