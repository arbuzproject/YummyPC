// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import su.afk.yummy.pc.extractors.StreamResolver

/**
 * Готовая регистрация экстрактора Alloha для реестра балансеров ПК-порта.
 *
 * Ведущий агент подключает её в `ExtractorRegistry` (общий файл, порт этого не трогает):
 * `resolvers = listOf(KodikResolver(), allohaResolver)`.
 */
internal val allohaResolver: StreamResolver = AllohaResolver()
