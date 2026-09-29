package com.honerai.app.core.agent

/**
 * Жёсткие правила безопасности агента: что считать важным/необратимым действием (требует подтверждения),
 * где стоит поле пароля (агент туда не пишет) и как распознать капчу (агент останавливается).
 * Чистая логика — проверяется тестами.
 */
object AgentSafety {
    /** Слова на кнопках, после которых нельзя действовать без подтверждения пользователя. */
    private val sensitive = listOf(
        // Оплата и деньги
        "оплат", "оплачива", "заплат", "к оплате", "перевести", "перевод", "перечислить", "пополнить",
        "купить", "покупк", "заказать", "оформить заказ", "оформить", "подтвердить заказ", "checkout",
        "pay", "buy", "purchase", "order", "checkout", "subscribe", "оформить подписку",
        // Отправка и публикация
        "отправить", "послать", "send", "опубликовать", "запостить", "post", "publish", "разместить",
        // Удаление и необратимое
        "удалить", "delete", "remove", "очистить", "стереть",
        // Общее подтверждение форм
        "подтвердить", "confirm", "submit", "применить и оплатить", "разместить заказ",
    )

    /** Слова, которые говорят о поле кода/пароля/CVV — сюда агент не вводит ничего. */
    private val credentialWords = listOf(
        "пароль", "password", "пин", "pin-код", "pin код", "pincode", "cvv", "cvc", "код из смс", "смс-код",
        "код подтверждения", "одноразовый код", "otp", "2fa", "секретный код", "номер карты", "card number",
    )

    private val captchaWords = listOf(
        "captcha", "капча", "recaptcha", "hcaptcha", "я не робот", "i'm not a robot", "im not a robot",
        "подтвердите, что вы человек", "подтвердите что вы человек", "verify you are human", "проверка безопасности",
        "выберите все", "select all images", "нажмите на все",
    )

    fun isSensitiveLabel(label: String?): Boolean {
        val text = normalize(label) ?: return false
        return sensitive.any { text.contains(it) }
    }

    fun mentionsCredential(label: String?): Boolean {
        val text = normalize(label) ?: return false
        return credentialWords.any { text.contains(it) }
    }

    /** Узел — поле для ввода секретного: либо помечен паролем, либо рядом текст «пароль/код/CVV». */
    fun isCredentialField(node: ScreenNode): Boolean =
        node.isPassword || (node.editable && (mentionsCredential(node.text) || mentionsCredential(node.contentDescription)))

    /** На экране есть капча/проверка «я не робот». */
    fun looksLikeCaptcha(snapshot: ScreenSnapshot): Boolean =
        snapshot.nodes.any { node -> captchaWords.any { normalize(node.label)?.contains(it) == true } }

    /** Сумма из текста кнопки/описания: «Оплатить 1 990 ₽» → «1 990 ₽». */
    fun extractAmount(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = Regex("(\\d[\\d\\s.,]*)\\s*(₽|руб|р\\.|rub|\\$|€|тенге|₸)", RegexOption.IGNORE_CASE).find(text)
        return match?.value?.replace(Regex("\\s+"), " ")?.trim()
    }

    private fun normalize(value: String?): String? =
        value?.lowercase()?.replace('ё', 'е')?.trim()?.takeIf { it.isNotEmpty() }
}
