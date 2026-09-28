package com.honerai.app.ui.tables

import androidx.compose.runtime.Composable

// ЗАГОТОВКА (модуль «Отображение ответов»).

/** Карточки таблиц под ответом; нажатие открывает редактор на весь экран. */
@Composable
fun ChatTableCards(ids: List<String>, fontScale: Float = 1f) {}

/** Таблица на весь экран: правка ячеек, строк, столбцов; экспорт CSV. */
@Composable
fun TableEditorScreen(tableId: String, onClose: () -> Unit) {}
