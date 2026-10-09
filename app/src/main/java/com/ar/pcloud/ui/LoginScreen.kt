package com.ar.pcloud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ar.pcloud.data.model.ServerRegion
import com.ar.pcloud.ui.theme.PCloudColors

private const val TAB_PASSWORD = 0
private const val TAB_TOKEN = 1

/**
 * Sign-in.
 *
 * Two routes, because pCloud's own 2FA makes password login awkward on a TV:
 *  - email + password (+ 6-digit code when the account has 2FA). The password is
 *    sent as a digest, never in the clear — see PCloudRepository.passwordDigest.
 *  - an API auth token pasted from pCloud's web console, which sidesteps 2FA
 *    entirely and is far easier to enter once with a remote.
 *
 * The region picker is a hint only: an account lives in exactly one data center,
 * and login retries the other one automatically if the first is wrong.
 */
@Composable
fun LoginScreen(
    uiState: UiState,
    onRegionSelected: (ServerRegion) -> Unit,
    onLoginCredentials: (String, String, String?) -> Unit,
    onLoginToken: (String) -> Unit
) {
    var tab by remember { mutableIntStateOf(TAB_PASSWORD) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var securityCode by remember { mutableStateOf("") }
    var apiToken by remember { mutableStateOf("") }

    val firstField = remember { FocusRequester() }

    LaunchedEffect(tab) { runCatching { firstField.requestFocus() } }

    fun submit() {
        if (uiState.isLoggingIn) return
        if (tab == TAB_PASSWORD) {
            onLoginCredentials(username.trim(), password, securityCode.trim().ifBlank { null })
        } else {
            onLoginToken(apiToken.trim())
        }
    }

    // The card grows as the form does — an error message, and the 2FA field once
    // pCloud asks for a code, together push the Sign in button past the bottom of
    // a 1080p TV. Without a scroll container there is then no way to reach it:
    // D-pad focus moves to a button that is off-screen and nothing follows it.
    // Overscan padding keeps the card clear of the bezel on a real set.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PCloudColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 27.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(600.dp)
                .background(PCloudColors.Surface, RoundedCornerShape(20.dp))
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "pCloud for Android TV",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = PCloudColors.TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Stream your videos, photos and music",
                fontSize = 15.sp,
                color = PCloudColors.TextSecondary
            )

            Spacer(Modifier.height(28.dp))

            SectionLabel("Data center")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ServerRegion.entries.forEach { region ->
                    val isSelected = uiState.selectedRegion == region
                    ChoiceChip(
                        label = if (region == ServerRegion.US) "United States" else "Europe",
                        selected = isSelected,
                        onClick = { onRegionSelected(region) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "If this is wrong, the other region is tried automatically.",
                fontSize = 12.sp,
                color = PCloudColors.TextTertiary,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ChoiceChip(
                    label = "Email & password",
                    selected = tab == TAB_PASSWORD,
                    onClick = { tab = TAB_PASSWORD },
                    modifier = Modifier.weight(1f)
                )
                ChoiceChip(
                    label = "API auth token",
                    selected = tab == TAB_TOKEN,
                    onClick = { tab = TAB_TOKEN },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(20.dp))

            if (tab == TAB_PASSWORD) {
                LoginField(
                    value = username,
                    onValueChange = { username = it },
                    label = "pCloud email",
                    keyboardType = KeyboardType.Email,
                    modifier = Modifier.focusRequester(firstField)
                )
                Spacer(Modifier.height(14.dp))
                LoginField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    keyboardType = KeyboardType.Password,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    imeAction = if (uiState.needsTwoFactor) ImeAction.Next else ImeAction.Done,
                    onImeAction = { if (!uiState.needsTwoFactor) submit() },
                    trailing = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (passwordVisible) {
                                    "Hide password"
                                } else {
                                    "Show password"
                                },
                                tint = PCloudColors.TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                )

                // Only shown once pCloud has actually asked for a code, so the normal
                // case is two fields rather than three.
                if (uiState.needsTwoFactor) {
                    Spacer(Modifier.height(14.dp))
                    LoginField(
                        value = securityCode,
                        onValueChange = { securityCode = it.filter(Char::isDigit).take(6) },
                        label = "6-digit authenticator code",
                        keyboardType = KeyboardType.NumberPassword,
                        onImeAction = { submit() }
                    )
                }
            } else {
                LoginField(
                    value = apiToken,
                    onValueChange = { apiToken = it },
                    label = "API auth token",
                    keyboardType = KeyboardType.Text,
                    onImeAction = { submit() },
                    modifier = Modifier.focusRequester(firstField)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Get one from pCloud on a computer, then paste it here. " +
                        "Works with two-factor authentication.",
                    fontSize = 12.sp,
                    color = PCloudColors.TextTertiary,
                    modifier = Modifier.align(Alignment.Start)
                )
            }

            uiState.loginError?.let { message ->
                Spacer(Modifier.height(18.dp))
                Text(
                    text = message,
                    color = PCloudColors.Error,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(26.dp))

            TvButton(
                onClick = ::submit,
                enabled = !uiState.isLoggingIn,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.isLoggingIn) {
                        Spinner()
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        text = if (uiState.isLoggingIn) "Signing in…" else "Sign in",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PCloudColors.TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = PCloudColors.TextSecondary,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvButton(onClick = onClick, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (selected) "✓  $label" else label,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) PCloudColors.Accent else PCloudColors.TextSecondary
            )
        }
    }
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 14.sp) },
        singleLine = true,
        interactionSource = interactionSource,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction() },
            onGo = { onImeAction() }
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = PCloudColors.TextPrimary,
            unfocusedTextColor = PCloudColors.TextPrimary,
            focusedBorderColor = PCloudColors.Accent,
            unfocusedBorderColor = PCloudColors.TextTertiary,
            focusedLabelColor = PCloudColors.Accent,
            unfocusedLabelColor = PCloudColors.TextSecondary,
            cursorColor = PCloudColors.Accent,
            focusedContainerColor = PCloudColors.SurfaceVariant,
            unfocusedContainerColor = PCloudColors.SurfaceVariant
        ),
        modifier = modifier
            .fillMaxWidth()
            // A 2dp accent ring: the TextField's own border is too thin to spot
            // from across a room.
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) PCloudColors.Accent else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
    )
}
