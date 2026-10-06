package app.goga.assistant.session

enum class ListenFailure {
    NO_MATCH,
    TIMEOUT,
    AUDIO,
    PERMISSION,
    BUSY,
    NETWORK,
    LANGUAGE,
    UNAVAILABLE,
    UNKNOWN,
}

fun listenFailureMessage(failure: ListenFailure): String = when (failure) {
    ListenFailure.NO_MATCH, ListenFailure.TIMEOUT -> "Не расслышал."
    ListenFailure.AUDIO -> "Микрофон недоступен."
    ListenFailure.PERMISSION -> "Нет доступа к микрофону."
    ListenFailure.BUSY -> "Распознавание занято. Повторите."
    ListenFailure.NETWORK -> "Без сети распознавание не вышло. Напишите текстом или скачайте офлайн-пакет русского."
    ListenFailure.LANGUAGE -> "Нет русского языка для распознавания на устройстве. Скачайте пакет или напишите текстом."
    ListenFailure.UNAVAILABLE -> "Распознавание речи недоступно. Напишите текстом."
    ListenFailure.UNKNOWN -> "Не получилось распознать. Напишите текстом."
}
