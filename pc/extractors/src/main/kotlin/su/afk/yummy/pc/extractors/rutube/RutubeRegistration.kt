// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.rutube

import su.afk.yummy.pc.extractors.StreamResolver

/**
 * Готовый экземпляр Rutube-экстрактора для [su.afk.yummy.pc.extractors.ExtractorRegistry]:
 * ведущий агент добавляет его в список реестра одной строкой.
 */
internal val rutubeResolver: StreamResolver = RutubeResolver()
