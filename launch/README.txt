ВОССТАНОВЛЕНИЕ (EDT не стартует, нерешённые бандлы)
====================================================
  powershell -File "C:\VC\EDT.Comfort\site\scripts\restore-main.ps1"

Скрипт останавливает eclipse.exe, копирует OSGi из backup, правит launch.
Эталоны OSGi/launch лежат в launch/backup/.

СИМПТОМ ПОСЛЕ RELEASE (не путать с пропажей иконки в JAR)
========================================================
В логе: «The image could not be loaded» для icons/obj16/... или
«Невозможно преобразовать модуль tormozit.comfort».

Причина: рассинхрон версии tormozit.comfort в трёх местах PDE-профиля:
  plugin/META-INF/MANIFEST.MF
  Eclipse Application/bundles.info
  Eclipse Application/dev.properties

Релизный site/plugins/*.jar при этом обычно в порядке.

ПРОВЕРКА ПЕРЕД Run Eclipse Application
======================================
  powershell -File "C:\VC\EDT.Comfort\site\scripts\assert-comfort-osgi.ps1"

Быстрый ремонт только workspace (Eclipse закрыт):
  powershell -File "C:\VC\EDT.Comfort\site\scripts\assert-comfort-osgi.ps1" -Repair

КРИТИЧНО в Run Configurations → Eclipse Application:
  [ ] Clear configuration before launching  (clearConfig)
  [ ] Generate OSGi profile                 (generateProfile)
  [ ] Use generated config                  (pde.generated.config) — тоже СНЯТО

clearConfig / generateProfile: иначе PDE пересобирает bundles.info
(249 КБ вместо 262 КБ) и EDT падает на jdt.core.compiler.batch.

pde.generated.config=true при каждом Run мержит dev.properties без
обновления bundles.info → снова рассинхрон после bump comfort.release.

После restore-main read-only: bundles.info и config.ini.
dev.properties должен оставаться записываемым — PDE создаёт/обновляет его при Run.

ПОСЛЕ RELEASE / REPUBLISH
=========================
  site\release.bat    — новая линейка (comfort.release + PDE headless Build All)
  site\republish.bat  — пересборка той же линейки (новый qualifier)

  release.bat / republish.bat: restore-main.ps1 + проверка версии comfort.
  Закоммитить site/ → GitHub Actions → Publish p2 site

  Перед первым Run после release: assert-comfort-osgi.ps1 (должен выйти без ошибки).

EDT БЕЗ ПЛАГИНА КОМФОРТ
========================
  powershell -File "C:\VC\EDT.Comfort\site\scripts\install-no-comfort.ps1"

PDE-workspace (хост): C:\VC\EDT-plugin-WS (проект плагина).
Runtime EDT (-data):  C:\VC\runtime-EclipseApplication (БСП_3, Конфигурация…).

После install-no-comfort.ps1 полностью перезапустите PDE (не только runtime).
Run → Eclipse Application No Comfort
Та же runtime-область, что у Eclipse Application; без tormozit.comfort.
Одновременно обе конфигурации не запускать (общий -data и JDWP :5005).

Проверка в консоли runtime: -data C:\VC\runtime-EclipseApplication (не NoComfort).
Папку C:\VC\runtime-EclipseApplication-NoComfort можно удалить (устарела).

restore-main.ps1 основную конфигурацию No Comfort не трогает.

Если No Comfort падает с ошибкой «#encoding=UTF-8» (UTF-8 BOM в bundles.info):
  powershell -File "C:\VC\EDT.Comfort\site\scripts\build-osgi-no-comfort.ps1"
  powershell -File "C:\VC\EDT.Comfort\site\scripts\install-no-comfort.ps1"

После restore-main пересоберите osgi-no-comfort (build-osgi-no-comfort.ps1),
если обновился основной backup/osgi.

КРИТИЧНО в Run Configurations → Eclipse Application No Comfort:
  [ ] Clear configuration before launching  (clearConfig)
  [ ] Generate OSGi profile                 (generateProfile)
