package com.honerai.app.ui.games

import androidx.compose.runtime.Composable

// ЗАГОТОВКА (модуль «Игры и редактор»).

/** Витрина игр. [onPlay] получает "chess", "checkers", "durak" или "slots". */
@Composable
fun GameHub(onPlay: (String) -> Unit, onClose: () -> Unit) {}

/** Экран игры на весь экран. [onResult] — итог партии уходит в чат. */
@Composable
fun GameScreen(kind: String, onResult: (String) -> Unit, onClose: () -> Unit) {}
