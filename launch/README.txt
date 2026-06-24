ВОССТАНОВЛЕНИЕ (EDT не стартует, нерешённые бандлы)
====================================================
  powershell -File "C:\VC\EDT.Comfort\launch\restore-main.ps1"

Скрипт останавливает eclipse.exe, копирует OSGi из backup, правит launch.

КРИТИЧНО в Run Configurations → Eclipse Application:
  [ ] Clear configuration before launching  (clearConfig)
  [ ] Generate OSGi profile                 (generateProfile)

Обе галочки должны быть СНЯТЫ. Иначе PDE пересобирает bundles.info
(249 КБ вместо 262 КБ) и EDT падает на jdt.core.compiler.batch.

После restore-main bundles.info и config.ini — read-only (защита от перезаписи).

ПОСЛЕ RELEASE / REPUBLISH
=========================
  site\release.bat    — новая линейка (comfort.release + PDE headless Build All)
  site\republish.bat  — пересборка той же линейки (новый qualifier)

  Закоммитить site/ → GitHub Actions → Publish p2 site
  release.bat / republish.bat сами вызывают restore-main.ps1 (Eclipse будет закрыт)

EDT БЕЗ ПЛАГИНА КОМФОРТ
========================
  powershell -File "C:\VC\EDT.Comfort\launch\install-no-comfort.ps1"

PDE-workspace (хост): C:\VC\EDT-plugin-WS (проект плагина).
Runtime EDT (-data):  C:\VC\runtime-EclipseApplication (БСП_3, Конфигурация…).

После install-no-comfort.ps1 полностью перезапустите PDE (не только runtime).
Run → Eclipse Application No Comfort
Та же runtime-область, что у Eclipse Application; без tormozit.comfort.
Одновременно обе конфигурации не запускать (общий -data и JDWP :5005).

Проверка в консоли runtime: -data C:\VC\runtime-EclipseApplication (не NoComfort).
Папку C:\VC\runtime-EclipseApplication-NoComfort можно удалить (устарела).

restore-main.ps1 основную конфигурацию не трогает.

Если No Comfort падает с ошибкой «#encoding=UTF-8» (UTF-8 BOM в bundles.info):
  powershell -File "C:\VC\EDT.Comfort\launch\build-osgi-no-comfort.ps1"
  powershell -File "C:\VC\EDT.Comfort\launch\install-no-comfort.ps1"

После restore-main пересоберите osgi-no-comfort (build-osgi-no-comfort.ps1),
если обновился основной backup/osgi.

КРИТИЧНО в Run Configurations → Eclipse Application No Comfort:
  [ ] Clear configuration before launching  (clearConfig)
  [ ] Generate OSGi profile                 (generateProfile)
