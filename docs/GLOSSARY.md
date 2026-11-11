# Kiroku glossary (English and Russian)

Every recurring term has one English and one Russian form. Use these exactly; do not introduce synonyms. Read this
before writing or reviewing any string. A new recurring term is added here in the same pull request that first uses it.
The owner is the final authority on the Russian column.

## List and progress

| Concept | English | Russian | Notes |
|---|---|---|---|
| The user's list | My list | Мой список | The top app bar title |
| The list destination | List | Список | The navigation label |
| Another user's list | <name>'s list | Список <name> | The name is not declined |
| Status: watching | Watching | Смотрю | |
| Status: plan to watch | Planned | Запланировано | |
| Status: completed | Completed | Просмотрено | |
| Status: on hold | On hold | Отложено | |
| Status: dropped | Dropped | Брошено | Not «Заброшено» |
| Episode | episode | серия | Not «эпизод». Plurals: серия, серии, серий |
| Short form in tight places | ep. 14 | сер. 14 | English: only in the notification action. Russian: not used in 1.0. In Russian a number before the noun reads as a quantity («14 сер.» is "14 episodes"), so an episode named by its number always has the number after the noun |
| Progress | `12 / 24` | `12 / 24` | Digits and a slash; no words. In the episode stepper |
| Progress in a list row | `Watched 12 of 24` / `Watched 212` | `Просмотрено 12 из 24` / `Просмотрено 212` | The second form when the total is unknown. The count is written as it is, also above the total: `Watched 25 of 24`. A line may break after the verb, never inside `12 of 24` |
| Aired count on the title page | `14 aired` | `Вышло 14` | Written without the total, which the page states once elsewhere. Estimated: `~14 aired` / `Вышло ~14` |
| Episodes to watch | `2 to watch` | `К просмотру: 2` | Colon form avoids a plural. Lower case after «·»: `· к просмотру: 2` |
| Episodes to watch, estimated | `~2 to watch` | `К просмотру: ~2` | |
| Next episode | `Episode 15 · Sat 17:30` | `Серия 15 · сб 17:30` | One form in the list row and the airing block. Time by ARCHITECTURE section 10: today / сегодня, tomorrow / завтра, the weekday within six days, then the date (`14 Nov, 17:30` / `14 нояб., 17:30`). The time words are in lower case, because an air time always follows other text: `Episode 15 · today 17:30` / `Серия 15 · сегодня 17:30`. A wrap falls before «·» |
| Premiere, time known | Premieres Sat 17:30 | Премьера сб 17:30 | When the first episode's air time is known; it replaces the date forms below. Time as for the next episode: `Premieres today 17:30` / `Премьера сегодня 17:30`, `Premieres 4 Jan, 17:30` / `Премьера 4 янв., 17:30`. No colon, like the date forms |
| Premiere, full date | Premieres 4 Jan / Premieres 4 Jan 2027 | Премьера 4 янв. / Премьера 4 янв. 2027 г. | Without the year in the list row, with it on the title page |
| Premiere, month known | Premieres January 2027 | Премьера в январе 2027 г. | |
| Premiere, year known | Premieres 2027 | Премьера в 2027 г. | |
| Not yet aired | Not yet aired | Ещё не вышло | When neither a time nor a date is known |
| The user's score | Your score / Score 8 | Ваша оценка / Оценка: 8 | The first labels the score on the title page, the second is the Completed tab's line |
| No score | Not scored | Без оценки | |
| Rate | Rate | Оценить | |
| Add to list | Add to list | Добавить в список | |
| Remove from list | Remove from list | Удалить из списка | |
| Undo | Undo | Отменить | |
| Edit a list entry | Edit | Изменить | A list row's long press and its spoken action; it opens the edit sheet |
| Snackbar after "+1" | Episode 13 watched · Undo | Серия 13 просмотрена · Отменить | |
| Snackbar when a change completes the title | Moved to Completed · Undo | Перенесено в Просмотрено · Отменить | On the List after "+1" and on the title page after the stepper. The status name is not declined. A bare «Просмотрено» would read as "the episode is watched" |
| Snackbar after "Add to list" | Added to Planned · Change | Добавлено: Запланировано · Изменить | Status names are not declined, hence the colon |
| The episode stepper's field | Episodes watched | Просмотрено серий | Its label on the title page and in the edit sheet, and what a screen reader says for it. Its buttons: Minus one episode / Plus one episode, «Минус одна серия» / «Плюс одна серия» |
| Discard confirmation | Discard changes? | Не сохранять изменения? | Buttons: Discard / Keep editing, «Не сохранять» / «Вернуться». Not «Отменить», which is Undo |
| Save | Save | Сохранить | |
| Edit sheet: dates | Start date / Finish date | Дата начала / Дата окончания | Field labels, and the date picker's title. Spoken: `Clear the start date` / «Очистить дату начала», `Choose a date` / «Выбрать дату» |
| Edit sheet: score | 8 · Very good / Not scored | 8 · Очень хорошо / Без оценки | The slider's value, with the score names below |
| Remove confirmation | Remove from list? | Удалить из списка? | Text: Progress, score and dates will be lost / «Прогресс, оценка и даты будут потеряны». Buttons: Remove / Cancel, «Удалить» / «Отмена» |
| Date picker buttons | OK / Cancel | ОК / Отмена | «Отмена» dismisses; it is not «Отменить», which is Undo |
| Filter the list by title | Filter by title | Фильтр по названию | The icon's label and the field's placeholder |
| Sort "Up next" | Up next | На очереди | |
| Sort by title | Title | Название | |
| Sort by last update | Last updated | Последнее изменение | |
| Sort by score | Score | Оценка | |
| Sort by start date | Start date | Дата начала | |
| The sort icon and the sort sheet's heading | Sort / Sort by | Сортировка | One Russian word for both |
| Clear the filter | Clear | Очистить | The filter field's button; it ends filtering |
| A tab with no titles | No titles in On hold | В «Отложено» нет тайтлов | The status name is not declined, hence the quotes. Its button: Search / Поиск |
| A filter that matches nothing | No titles match the filter | Нет тайтлов с таким названием | |
| The List, signed out | Sign in with MyAnimeList to see your list here | Войдите через MyAnimeList, чтобы видеть здесь свой список | Its button: Sign in / Войти |
| The list could not be loaded | Could not load the list | Не удалось загрузить список | With nothing cached; with no network, No connection |

## Score names

The edit sheet's slider shows the number with MAL's name for it.

| Score | English | Russian |
|---|---|---|
| 0 | Not scored | Без оценки |
| 10 | Masterpiece | Шедевр |
| 9 | Great | Великолепно |
| 8 | Very good | Очень хорошо |
| 7 | Good | Хорошо |
| 6 | Fine | Неплохо |
| 5 | Average | Средне |
| 4 | Bad | Плохо |
| 3 | Very bad | Очень плохо |
| 2 | Horrible | Ужасно |
| 1 | Appalling | Отвратительно |

## Title page

| Concept | English | Russian | Notes |
|---|---|---|---|
| Title (an anime) | title | тайтл | In running text; screens mostly show the name itself |
| MAL score | MAL score | Оценка MAL | |
| Rank | Ranked #12 | №12 в рейтинге | Never the bare number, except in front of the title in a ranked browse list (`1.`) |
| Facts line | TV · 2024 · 24 episodes / Movie · 2024 | ТВ · 2024 · 24 серии / Фильм · 2024 | The episode count is a plural and is left out when it is 1 or unknown |
| Synopsis | Synopsis | Описание | |
| Expand / collapse the synopsis | More / Less | Ещё / Свернуть | The text buttons under a synopsis cut at four lines |
| Overflow menu (spoken) | More options | Другие действия | |
| Open the title on MAL | Open on MyAnimeList | Открыть на MyAnimeList | |
| Share | Share | Поделиться | |
| Loading details (spoken) | Loading details | Загрузка подробностей | The progress line that stands in for the lower half of the title page |
| A title MAL does not know | This title is not on MyAnimeList | Этого тайтла нет на MyAnimeList | |
| Any other failure to load a title | Could not load this title | Не удалось загрузить тайтл | |
| Related | Related | Связанное | |
| Statistics | Statistics | Статистика | |
| A share under one percent | <1% | <1 % | A non-zero count whose share rounds to 0 in a `StatBar`; spoken "less than 1 percent" / «менее 1 процента» |
| Members | 1,234,567 members | 1 234 567 участников | With a number; plural: участник / участника / участников |
| Details | Details | Подробности | |
| Recommendations | Recommendations | Рекомендации | |
| Airing status: airing | Airing | Выходит | |
| Airing status: finished | Finished | Завершено | |
| Sequel / Prequel | Sequel / Prequel | Продолжение / Предыстория | "Sequel" is also the header of the title page's sequel row |
| Side story / Spin-off | Side story / Spin-off | Побочная история / Спин-офф | |
| Parent story / Full story | Parent story / Full story | Основная история / Полная версия | |
| Alternative setting / version | Alternative setting / Alternative version | Альтернативный сеттинг / Альтернативная версия | On "See all" |
| The same two, strip labels | Alt. setting / Alt. version | Альт. сеттинг / Альт. версия | Only as caption 1 of a `PosterCard` in the Related strip |
| Summary / Other / Character | Summary / Other / Character | Краткое изложение / Прочее / Общий персонаж | |
| Seasons | Winter, Spring, Summer, Fall 2024 | Зима, Весна, Лето, Осень 2024 | |
| Media types | TV, Movie, OVA, ONA, Special, Music | ТВ, Фильм, OVA, ONA, Спешл, Клип | |
| Rarer media types | TV special, Commercial, Promo video | ТВ-спешл, Реклама, Промо | |

Roadmap milestone M2 compares the Russian relation names with the names Shikimori uses and puts the differences to
the owner; a name changes only on the owner's answer.

## Genres

MAL's genres, themes and demographics, by MAL id. Russian names are the ones Russian anime sites use, as Shikimori
names them («Сверхъестественное», «Психологическое», «Сёнен», «Сэйнэн»), with one exception: MAL keeps Mystery and
Detective apart, so Mystery is «Тайна» and Detective «Детектив». The table holds the genres the app has names for; a
genre not in it is shown by its MAL name (ARCHITECTURE section 10). Milestone M2 completes it from recorded MAL
responses.

| MAL id | MAL name | English | Russian |
|---|---|---|---|
| 1 | Action | Action | Экшен |
| 2 | Adventure | Adventure | Приключения |
| 4 | Comedy | Comedy | Комедия |
| 5 | Avant Garde | Avant garde | Авангард |
| 6 | Mythology | Mythology | Мифология |
| 7 | Mystery | Mystery | Тайна |
| 8 | Drama | Drama | Драма |
| 10 | Fantasy | Fantasy | Фэнтези |
| 13 | Historical | Historical | Исторический |
| 14 | Horror | Horror | Ужасы |
| 18 | Mecha | Mecha | Меха |
| 19 | Music | Music | Музыка |
| 22 | Romance | Romance | Романтика |
| 23 | School | School | Школа |
| 24 | Sci-Fi | Sci-fi | Фантастика |
| 27 | Shounen | Shounen | Сёнен |
| 29 | Space | Space | Космос |
| 30 | Sports | Sports | Спорт |
| 31 | Super Power | Super power | Суперсила |
| 36 | Slice of Life | Slice of life | Повседневность |
| 37 | Supernatural | Supernatural | Сверхъестественное |
| 38 | Military | Military | Военное |
| 39 | Detective | Detective | Детектив |
| 40 | Psychological | Psychological | Психологическое |
| 41 | Suspense | Suspense | Триллер |
| 42 | Seinen | Seinen | Сэйнэн |
| 46 | Award Winning | Award winning | Удостоено наград |
| 47 | Gourmet | Gourmet | Гурман |
| 62 | Isekai | Isekai | Исэкай |
| 63 | Iyashikei | Iyashikei | Иясикэй |

## Notifications

| Concept | English | Russian | Notes |
|---|---|---|---|
| Channel | New episodes | Новые серии | |
| Text | Episode 16 aired | Вышла серия 16 | |
| Text, several unwatched | Episode 16 aired · 3 to watch | Вышла серия 16 · к просмотру: 3 | |
| Action | Mark ep. 14 watched | Отметить серию 14 | The number after the noun |
| Action failed | Could not save · Open | Не удалось сохранить · Открыть | |
| Progress, Android 11 and earlier | Saving… | Сохранение… | |
| Channel for that progress | Saving changes | Сохранение изменений | |
| Summary | 3 new episodes | plural: %d новая серия, новые серии, новых серий | |
| Upcoming | Upcoming | Ближайшие | |
| Recently sent | Recently sent | Недавно отправленные | |

## App and account

| Concept | English | Russian | Notes |
|---|---|---|---|
| Sign in / Sign out | Sign in / Sign out | Войти / Выйти | |
| Sign-in button | Sign in with MyAnimeList | Войти через MyAnimeList | |
| Browse without an account | Browse without an account | Продолжить без аккаунта | |
| No client id in this build | This build has no MyAnimeList client id | В этой сборке нет идентификатора клиента MyAnimeList | |
| Open a title by its id | Open title #12345 | Открыть тайтл №12345 | Search, for a query of digits only |
| Session expired | Session expired | Сессия истекла | |
| Search | Search | Поиск | |
| Profile | Profile | Профиль | |
| Settings | Settings | Настройки | |
| Retry | Retry | Повторить | |
| Back (spoken) | Back | Назад | The top app bar's navigation icon |
| Refused by MAL | MyAnimeList is refusing requests, try again later | MyAnimeList отклоняет запросы, попробуйте позже | |
| Loading (spoken by a screen reader) | Loading | Загрузка | |
| See all | See all | Показать все | The button of a section header. Spoken with its section: `See all: Related` / `Показать все: Связанное` |
| Could not save | Could not save | Не удалось сохранить | |
| Could not refresh | Could not refresh | Не удалось обновить | |
| No connection | No connection | Нет соединения | |
| Days watched | Days watched | Дней просмотра | |
| Mean score | Mean score | Средняя оценка | |
| Unofficial notice | Unofficial. Not affiliated with MyAnimeList. | Неофициальное приложение. Не связано с MyAnimeList. | |

## Rules

- Address the user as «вы», lower case.
- Sentence case in both languages: only the first word and proper names are capitalised.
- No full stop at the end of labels, buttons and single-sentence messages.
- Numbers with a noun use plural resources with all four Russian forms (`one`, `few`, `many`, `other`).
- MAL and AniList are written in Latin letters in both languages.
