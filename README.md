Плагин широкого назначения для **1C EDT 2025.2+**: навигация, наборы объектов, редактор BSL, отладка, сравнение конфигураций, интеграция с [Инструментами разработчика Tormozit (ИР)](docs/obshchie-mekhanizmy.md#integraciya-s-ir).

## Справка

- В репозитории: [docs/README.md](docs/README.md)
- На сайте: [https://tormozit.github.io/EDT.Comfort/help/](https://tormozit.github.io/EDT.Comfort/help/)

## Установка

### Через интернет (рекомендуется)

1. Главное меню → **Справка** → **Установить новое ПО…** → **Добавить**.
2. Указать URL сайта обновления (последняя версия, composite): `https://tormozit.github.io/EDT.Comfort/`
3. Отметить **EDT Comfort** → **Далее** → **Готово**.
4. Если всё сделано правильно, EDT предложит **перезапуск** — согласитесь.

### Без интернета (архив)

1. Скачать ZIP с [релизов GitHub](https://github.com/tormozit/EDT.Comfort/releases) или с URL фиксированной версии, например `https://tormozit.github.io/EDT.Comfort/1.0.0.13/`
2. Главное меню → **Справка** → **Установить новое ПО…** → **Добавить** → **Архив…** → выбрать ZIP → **Добавить**.
3. Отметить **EDT Comfort** → **Далее** → **Готово** → перезапуск EDT.

### Обновление

Тот же URL или архив новой версии. Если установщик **не видит новую версию**:

1. В диалоге установки — **Управление…**
2. В списке сайтов — **Обновить** у `https://tormozit.github.io/EDT.Comfort/`
3. Либо удалить сайт и **добавить заново** (из архива — с именем установочного файла)

Полный текст: [docs/ustanovka-i-obnovlenie.md](docs/ustanovka-i-obnovlenie.md). Уведомление о версии — [настройки](docs/nastroyki.md).

**Параметры → Комфорт** — настройки на верхнем уровне.

## Публикация

### p2 + справка

1. `site\release.bat` — версия в [site/version.txt](site/version.txt)
2. **Build All** (Eclipse) или `site\build.bat`
3. Закоммитить `site/features/`, `site/plugins/`, `site/content.jar`, `site/artifacts.jar`
4. Actions → **Publish p2 site**

### Только справка

Actions → **Publish docs** (без пересборки p2)

### Редактирование справки

Файлы в [docs/](docs/); шаблон окна: [docs/_shablon-okna.md](docs/_shablon-okna.md)
