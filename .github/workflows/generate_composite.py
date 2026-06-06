import os
import shutil

deploy_dir = "deploy"
latest = os.environ.get("LATEST_VERSION", "")

# Папки версий (только каталоги с цифрами в имени)
children = [
    d for d in os.listdir(deploy_dir)
    if os.path.isdir(os.path.join(deploy_dir, d)) and d[0:1].isdigit()
]
children.sort(reverse=True)

if latest and latest in children:
    children.remove(latest)
    children.insert(0, latest)
elif children:
    latest = children[0]

simple_p2_index = """version=1
metadata.repository.factory.order=content.jar,!
artifact.repository.factory.order=artifacts.jar,!
"""

# p2.index в каждой папке версии (для прямого URL .../1.0.0.10/)
for child in children:
    child_dir = os.path.join(deploy_dir, child)
    with open(os.path.join(child_dir, "p2.index"), "w", encoding="utf-8") as f:
        f.write(simple_p2_index)

# Корень = простой p2-сайт (последняя версия), Eclipse/EDT понимают без composite
if latest:
    latest_dir = os.path.join(deploy_dir, latest)
    for item in ("features", "plugins", "content.jar", "artifacts.jar"):
        src = os.path.join(latest_dir, item)
        dst = os.path.join(deploy_dir, item)
        if not os.path.exists(src):
            continue
        if os.path.isdir(src):
            if os.path.exists(dst):
                shutil.rmtree(dst)
            shutil.copytree(src, dst)
        else:
            shutil.copy2(src, dst)

with open(os.path.join(deploy_dir, "p2.index"), "w", encoding="utf-8") as f:
    f.write(simple_p2_index)

# Удалить composite-файлы прошлой схемы (ломали Eclipse)
for old in ("compositeContent.jar", "compositeArtifacts.jar"):
    path = os.path.join(deploy_dir, old)
    if os.path.exists(path):
        os.remove(path)

open(os.path.join(deploy_dir, ".nojekyll"), "w").close()

version_links = "\n".join(
    f'    <li><a href="{c}/">{c}</a> — фиксированная версия</li>' for c in children
)
index_html = f"""<!DOCTYPE html>
<html lang="ru">
<head><meta charset="utf-8"><title>EDT Comfort p2</title></head>
<body>
  <h1>EDT Comfort — p2 update site</h1>
  <p>Установить новое ПО (последняя версия):<br>
  <code>https://tormozit.github.io/EDT.Comfort/</code></p>
  <p>Архивные версии:</p>
  <ul>
{version_links}
  </ul>
</body>
</html>
"""
with open(os.path.join(deploy_dir, "index.html"), "w", encoding="utf-8") as f:
    f.write(index_html)

print("Latest at root:", latest)
print("Version folders:", children)
