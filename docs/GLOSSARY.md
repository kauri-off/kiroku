# Kiroku glossary (English and Russian)

The string resources (`app/src/main/res/values` and `values-ru`) are the list of the app's strings, in both
languages. This glossary holds what a resource cannot say: which term was chosen where another was possible, a rule
of grammar that several strings share, the form of a line built from parts, and the wording of features that have
no resources yet. Read it before writing or reviewing any string.

A row is added only when the next author could plausibly choose differently. A string that merely exists is not a
row. Review changes to the Russian column as part of localisation.

## Terms

| Concept | English | Russian | Rule |
|---|---|---|---|
| List statuses | Watching, Planned, Completed, On hold, Dropped | Смотрю, Запланировано, Просмотрено, Отложено, Брошено | Not «Заброшено». A status name is never declined: the sentence is built around it with a colon or quotes («Добавлено: Запланировано», «В «Отложено» нет тайтлов», «Перенесено в Просмотрено») |
| Episode | episode | серия | Not «эпизод». Plurals: серия, серии, серий |
| An episode named by its number | Episode 15, ep. 14 | Серия 15 | In Russian the number always follows the noun: before it, it reads as a quantity («14 сер.» is "14 episodes"). "ep." only in the notification action; «сер.» is not used |
| Title (an anime) | title | тайтл | In running text |
| The user's list / the destination | My list / List | Мой список / Список | The top app bar title / the navigation label |
| Another user's list | <name>'s list | Список <name> | The name is not declined |
| Undo | Undo | Отменить | Only for Undo. A dialog is dismissed with Cancel / «Отмена»; unsaved changes with Discard / Keep editing, «Не сохранять» / «Вернуться» |
| Edit | Edit / Change | Изменить | A row's long press and its spoken action; the action of the "Added" snackbar |
| The user's score | Your score / Not scored / Rate | Ваша оценка / Без оценки / Оценить | |
| Score names, 10 down to 1 | Masterpiece, Great, Very good, Good, Fine, Average, Bad, Very bad, Horrible, Appalling | Шедевр, Великолепно, Очень хорошо, Хорошо, Неплохо, Нормально, Слабо, Плохо, Очень плохо, Хуже некуда | The set Russian MAL users know from Shikimori |
| Titles with a score, counted | Scored / Not scored / 7 of 31 scored / Nothing scored | Оценено / Без оценки / Оценено 7 из 31 / Оценок нет | A profile's count: "scored" as in "Not scored", never "rated" |
| MAL's score and rank | MAL score / No score / #12, Rank | Оценка MAL / Нет оценки / №12, Место | MAL's missing score is kept apart from the user's Not scored. Never the bare number, except in front of the title in a ranked browse list (`1.`) and after a star (Forms). The title page's header captions `#12` with Rank |
| MAL's score distribution | MAL scores / Score 10 | Оценки MAL / Оценка 10 | One score of it is spoken with the word, "Score 10" / «Оценка 10», never as the bare number |
| The default sort | Up next | На очереди | |
| Synopsis, Related, Details | Synopsis, Related, Details | Описание, Связанное, Подробности | |
| Details rows | Source, Broadcast | Первоисточник, Время показа | |
| MAL's members | Members | В списках | The users who have the title on a list; not «Участники» |
| Airing status | Airing / Finished / Not yet aired | Выходит / Завершено / Ещё не вышло | |
| Relations | Sequel, Prequel, Side story, Parent story, Full story, Spin-off, Summary, Character, Other | Продолжение, Предыстория, Побочная история, Основная история, Полная версия, Спин-офф, Краткое содержание, Общий персонаж, Прочее | The two alternative types are shortened only as a strip caption: Alt. setting / Alt. version, «Альт. вселенная» / «Альт. версия» |
| The chain of sequels | Watch order / This title | Порядок просмотра / Этот тайтл | The title page's section, and its mark of the page's own title. Not «Хронология»: the order is MAL's sequels, not the story's time |
| Media types | Movie, Special, Music | Фильм, Спешл, Клип | The rest are kept as MAL writes them (TV / ТВ, OVA, ONA) |
| Genres | as MAL names them, in sentence case | the names Russian anime sites use, as Shikimori has them | An exception: MAL keeps Mystery and Detective apart, so Mystery is «Тайна» and Detective «Детектив» |
| Super power genre | Super power | Суперсила | An exception: Shikimori writes «Супер сила», but «супер-» is a prefix and is written together |
| MAL, AniList, MyAnimeList | | | Written in Latin letters in both languages |
| An estimate | estimated, ~ | приблизительно, ~ | Never «оценка», which is a score |
| Units of time | min, s | мин, с | Not «сек» |
| Try again | Try again later / try again | Попробуйте позже / попробуйте ещё раз | Not «Повторите»; the button is Retry / «Повторить» |
| Android's own settings | Alarms and reminders, Force stop, a notification category | Будильники и напоминания, «Остановить», категория уведомлений | As Android names them, so that the user finds them there; never "exact alarms" or "channel" |

## Forms

Lines built from parts. The examples are in a day-first, 24-hour form; on a device the locale and the 12/24-hour
setting decide.

| Line | English | Russian | Rule |
|---|---|---|---|
| Progress in the stepper | `12 / 24` | `12 / 24` | Digits and a slash; no words |
| Progress in a list row | `Watched 12 of 24` / `Watched 212` | `Просмотрено 12 из 24` / `Просмотрено 212` | The second form when the total is unknown. The count is written as it is, also above the total. A line may break after the verb, never inside `12 of 24` |
| Progress beside a status | `Watching · 12 / 24` / `Watching · watched 212` | `Смотрю · 12 / 24` / `Смотрю · просмотрено 212` | In search results and "See all". The second form when the total is unknown, with the List's word in lower case |
| Aired count | `14 aired` / `~14 aired` | `Вышло 14` / `Вышло ~14` | Without the total. "~" marks an estimate |
| A score after a star | `★ 8.71` / `Watched 12 of 24 · ★ 10` | `★ 8,71` / `Просмотрено 12 из 24 · ★ 10` | MAL's score in a row, and the user's in a List tab sorted by score. The star never parts from its number and is spoken as the score's name: "MAL score 8.71", "Score 10". A wrap falls before the score's «·» |
| Episodes to watch | `2 to watch` / `~2 to watch` | `К просмотру: 2` / `К просмотру: ~2` | The colon avoids a plural. Lower case after «·»: `· к просмотру: 2` |
| Next episode | `Episode 15 · Sat 17:30` | `Серия 15 · сб 17:30` | One form everywhere. The time is "today" / «сегодня», "tomorrow" / «завтра», the weekday within six days, then the date (`14 Nov, 17:30` / `14 нояб., 17:30`), in lower case, because an air time always follows other text. A wrap falls before «·» |
| A past time | `today 17:30` / `yesterday 17:30` / `Mon 17:30` / `7 Jan, 17:30` | `сегодня 17:30` / `вчера 17:30` / `пн 17:30` / `7 янв., 17:30` | As an air time, counted back from today: the weekday within six days, then the date |
| Premiere, time known | `Premieres Sat 17:30` | `Премьера сб 17:30` | Time as for the next episode. No colon |
| Premiere, date known | `Premieres 4 Jan` / `Premieres 4 Jan 2027` / `Premieres January 2027` / `Premieres 2027` | `Премьера 4 янв.` / `Премьера 4 янв. 2027 г.` / `Премьера в январе 2027 г.` / `Премьера в 2027 г.` | By the precision of MAL's date. A full date has no year in the list row and has it on the title page |
| A message with its action | `Episode 13 watched · Undo` | `Серия 13 просмотрена · Отменить` | The message, «·», the action |
| "Mark watched" from a notification that failed | `Could not save: episode 13 watched` | `Не удалось сохранить: серия 13 просмотрена` | In the title's notification, in its place; a tap opens the title, so it has no action of its own |
| Facts line | `TV · 2024 · 24 episodes` | `ТВ · 2024 · 24 серии` | The episode count is a plural |
| A number with a noun | `1,234,567 votes` | `1 234 567 голосов` | Plural resources; digits grouped by the locale |
| A share under one percent | `<1%` | `<1 %` | Spoken "less than 1 percent" / «менее 1 процента» |
| A button spoken with its section | `See all: Related` | `Показать все: Связанное` | |

## Not built yet

Wording decided before its feature exists. A row leaves this table when its resource is written.

| Concept | English | Russian | Notes |
|---|---|---|---|
| Sign out | Sign out | Выйти | |

## Rules

- Address the user as «вы», lower case.
- Sentence case in both languages: only the first word and proper names are capitalised.
- No full stop at the end of labels, buttons and single-sentence messages.
- Numbers with a noun use plural resources with all four Russian forms (`one`, `few`, `many`, `other`).
- A line never breaks between a number and its word, unit or slash.
