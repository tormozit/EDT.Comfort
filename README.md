Плагин широкого назначения для **1C EDT 2025.2+**: навигация, наборы объектов, редактор BSL, отладка, сравнение конфигураций, интеграция с [Инструментами разработчика Tormozit (ИР)](docs/obshchie-mekhanizmy.md#integraciya-s-ir).

## Справка

- В репозитории: [docs/README.md](docs/README.md)
- На сайте: [https://tormozit.github.io/EDT.Comfort/help/](https://tormozit.github.io/EDT.Comfort/help/)

## Установка

1. **Справка** → **Установить новое ПО…** → **Добавить** → `https://tormozit.github.io/EDT.Comfort/`
2. Без интернета — **Архив…** и ZIP с [релизов](https://github.com/tormozit/EDT.Comfort/releases)
3. **EDT Comfort** → перезапуск EDT

Подробнее: [docs/ustanovka-i-obnovlenie.md](docs/ustanovka-i-obnovlenie.md)

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
