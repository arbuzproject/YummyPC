// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

/**
 * Подпись `Borth` для запроса `POST /bnsi/movies/{id}`: `<fingerprint>|<viewporti после перестановок>`.
 *
 * Порт `AllohaBorth.kt` из Android-версии, шаг в шаг. `viewporti` — зерно текущей загрузки страницы из
 * `<meta name="viewporti">`. Страница его не шифрует, а переставляет символы тремя проходами; сервер
 * так отличает клиента, выполнившего код страницы, от переигранной ссылки. Перестановка должна совпасть
 * символ в символ, иначе `bnsi` отвечает отказом.
 */
internal fun allohaBorthHeader(fingerprint: String, viewporti: String): String =
    "$fingerprint|${allohaBorthTail(viewporti)}"

/** Зерно после трёх перестановок страницы. */
internal fun allohaBorthTail(seed: String): String =
    unscramble(reorderByBitWidth(reorderByBitDepth(seed)))

private fun ceilLog2(length: Int): Int {
    var u = 0
    while ((1 shl u) < length) u++
    return u
}

/** Группирует символы по длине двоичной записи их индекса; группы идут от большей к меньшей. */
private fun reorderByBitDepth(source: String): String {
    val length = source.length
    if (length <= 0) return source
    val u = ceilLog2(length)

    fun bucketIndex(index: Int): Int = if (index == 0) 0 else Int.SIZE_BITS - Integer.numberOfLeadingZeros(index)

    val sizes = IntArray(u + 1)
    for (index in 0 until length) sizes[bucketIndex(index)]++

    val buckets = Array(u + 1) { "" }
    var pos = 0
    for (i in u downTo 0) {
        buckets[i] = source.substring(pos, pos + sizes[i])
        pos += sizes[i]
    }
    return takeFromBuckets(length, u, buckets, ::bucketIndex)
}

/** Группирует символы по числу нулей в конце двоичной записи индекса (индекс 0 — в верхней группе). */
private fun reorderByBitWidth(source: String): String {
    val length = source.length
    if (length <= 0) return source
    val u = ceilLog2(length)

    fun bucketIndex(index: Int): Int = if (index == 0) u else Integer.numberOfTrailingZeros(index)

    val sizes = IntArray(u + 1)
    for (index in 0 until length) sizes[bucketIndex(index)]++

    val buckets = Array(u + 1) { "" }
    var pos = 0
    for (i in 0..u) {
        buckets[i] = source.substring(pos, pos + sizes[i])
        pos += sizes[i]
    }
    return takeFromBuckets(length, u, buckets, ::bucketIndex)
}

private fun takeFromBuckets(
    length: Int,
    maxBucket: Int,
    buckets: Array<String>,
    bucketIndex: (Int) -> Int,
): String {
    val pointers = IntArray(maxBucket + 1)
    val result = CharArray(length)
    for (index in 0 until length) {
        val bucket = bucketIndex(index)
        result[index] = buckets[bucket][pointers[bucket]++]
    }
    return String(result)
}

/** Ставит символы, шагая по индексам с шагом 2 по модулю первого простого числа больше длины. */
private fun unscramble(source: String): String {
    val length = source.length
    if (length <= 1) return source

    val modulus = nextPrimeAtOrAbove(length + 1)
    val visited = BooleanArray(length)
    val order = IntArray(length)
    var filled = 0
    var current = 0
    while (filled < length) {
        current = (current + 2) % modulus
        if (current < length && !visited[current]) {
            order[filled++] = current
            visited[current] = true
        }
    }

    val decoded = CharArray(length)
    for (index in 0 until length) decoded[order[index]] = source[index]
    return String(decoded)
}

private fun isPrime(n: Int): Boolean {
    if (n < 2) return false
    if (n % 2 == 0) return n == 2
    var i = 3
    while (i * i <= n) {
        if (n % i == 0) return false
        i += 2
    }
    return true
}

private fun nextPrimeAtOrAbove(value: Int): Int {
    var candidate = maxOf(1, value)
    while (!isPrime(candidate)) candidate++
    return candidate
}
