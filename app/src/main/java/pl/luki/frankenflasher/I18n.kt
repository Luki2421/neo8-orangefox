package pl.luki.frankenflasher

/** Prosty wybor jezyka: PL / EN (domyslnie wg jezyka systemu, zmiana przyciskiem w aplikacji). */
object I18n {
    @Volatile var en: Boolean = java.util.Locale.getDefault().language != "pl"
}

fun tr(pl: String, en: String): String = if (I18n.en) en else pl
