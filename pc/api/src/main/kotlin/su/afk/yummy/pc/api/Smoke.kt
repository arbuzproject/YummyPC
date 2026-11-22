// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

import kotlinx.coroutines.runBlocking

/** Дымовой тест сетевого слоя: дергает реальные ручки yani и печатает результат. */
fun main() = runBlocking {
    val repo = YaniRepository()
    println("== feed ==")
    val home = repo.home()
    println("hero: ${home.hero.size}")
    home.rows.forEach { println("${it.title}: ${it.items.size} карточек") }
    home.hero.take(3).forEach { println("  hero: ${it.id} ${it.title} ★${it.rating}") }

    println("== типы каталога ==")
    repo.types().forEach { println("  ${it.value}: ${it.name} (${it.count})") }

    println("== топ (Сериал) ==")
    val top = repo.top(type = 1, limit = 5)
    top.forEach { println("  #${it.rank} ${it.id} | ${it.title} | ${it.year} | ${it.rating}") }

    println("== поиск «наруто» ==")
    val found = repo.search("наруто")
    println("найдено: ${found.size}")
    found.take(3).forEach { println("  ${it.id} | ${it.title} | ${it.year}") }

    println("== расписание ==")
    repo.schedule().take(2).forEach { day ->
        println("  день ${day.timestampSec}: ${day.items.size}")
        day.items.take(2).forEach { println("    ${it.card.title} след. серия ${it.nextEpisodeAtSec}") }
    }

    println("== страница 119 ==")
    val page = repo.page(119)
    println("title=${page.title} type=${page.typeName} status=${page.status} episodes=${page.episodesCount}/${page.airedCount}")
    println("genres=${page.genres}")
    println("dubbings=${page.dubbings.size} videos=${page.videos.size} episodeCards=${page.episodes.size}")
    page.dubbings.take(3).forEach { println("  ${it.name}: ${it.players} (${it.episodes} эп.)") }
    page.episodes.take(2).forEach { println("  серия ${it.number}: ${it.videos.size} источников") }
    println("viewingOrder=${page.viewingOrder.size} screenshots=${page.screenshots.size}")
}
