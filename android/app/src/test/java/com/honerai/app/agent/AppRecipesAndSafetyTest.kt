package com.honerai.app.agent

import com.honerai.app.core.agent.AgentSafety
import com.honerai.app.core.agent.AppRecipes
import com.honerai.app.core.agent.ScreenNode
import com.honerai.app.core.agent.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRecipesAndSafetyTest {
    @Test fun recipeLookupByPackage() {
        assertEquals("Wildberries", AppRecipes.forPackage("com.wildberries.ru")?.label)
        assertEquals("Яндекс Go", AppRecipes.forPackage("ru.yandex.taxi")?.label)
        assertNotNull(AppRecipes.hintFor("org.telegram.messenger"))
        assertNull(AppRecipes.forPackage("com.unknown.app"))
        assertNull(AppRecipes.hintFor(null))
    }

    @Test fun recipesCoverRussianFirstList() {
        val packages = AppRecipes.all().map { it.packageName }.toSet()
        listOf(
            "com.vkontakte.android", "org.telegram.messenger", "com.wildberries.ru", "ru.ozon.app.android",
            "ru.yandex.taxi", "ru.yandex.yandexmaps", "ru.yandex.mail", "ru.yandex.rasp",
            "com.delimobil", "com.google.android.gm", "com.android.vending", "com.github.android",
            "ru.mts.mymts", "ru.beeline.services", "ru.megafon.mlk", "ru.tele2.mytele2",
        ).forEach { assertTrue("нет рецепта для $it", it in packages) }
    }

    @Test fun sensitiveLabelsDetected() {
        listOf("Оплатить", "Оплатить 1990 ₽", "Купить", "Оформить заказ", "Отправить", "Опубликовать", "Удалить",
            "Pay", "Buy now", "Checkout", "Confirm order").forEach {
            assertTrue("«$it» должно быть важным", AgentSafety.isSensitiveLabel(it))
        }
    }

    @Test fun ordinaryLabelsAreNotSensitive() {
        listOf("Поиск", "В корзину", "Каталог", "Назад", "Показать ещё", "").forEach {
            assertFalse("«$it» не должно быть важным", AgentSafety.isSensitiveLabel(it))
        }
    }

    @Test fun amountExtraction() {
        assertEquals("1 990 ₽", AgentSafety.extractAmount("Оплатить 1 990 ₽"))
        assertEquals("350 руб", AgentSafety.extractAmount("К оплате 350 руб"))
        assertNull(AgentSafety.extractAmount("Оформить заказ"))
    }

    @Test fun credentialFieldsDetected() {
        val pwd = ScreenNode(0, "", "", "EditText", false, true, false, false, true, "0,0,1,1")
        assertTrue(AgentSafety.isCredentialField(pwd))
        val codeField = ScreenNode(1, "Код из СМС", "", "EditText", false, true, false, false, false, "0,0,1,1")
        assertTrue(AgentSafety.isCredentialField(codeField))
        val plain = ScreenNode(2, "Поиск", "", "EditText", false, true, false, false, false, "0,0,1,1")
        assertFalse(AgentSafety.isCredentialField(plain))
    }

    @Test fun captchaDetection() {
        val captcha = ScreenSnapshot("app", "App", listOf(
            ScreenNode(0, "Подтвердите, что вы человек", "", "TextView", false, false, false, false, false, "0,0,1,1"),
        ))
        assertTrue(AgentSafety.looksLikeCaptcha(captcha))
        val normal = ScreenSnapshot("app", "App", listOf(
            ScreenNode(0, "Каталог", "", "TextView", true, false, false, false, false, "0,0,1,1"),
        ))
        assertFalse(AgentSafety.looksLikeCaptcha(normal))
    }
}
