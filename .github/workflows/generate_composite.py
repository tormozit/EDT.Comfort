import io
import os
import re
import shutil
import time
import zipfile

deploy_dir = "deploy"
latest = os.environ.get("LATEST_VERSION", "")
CATEGORY_ID = "comfort"
BUNDLE_ID = "tormozit.comfort"
SITE_BASE_URL = "https://tormozit.github.io/EDT.Comfort/"

simple_p2_index = """version=1
metadata.repository.factory.order=content.xml,content.jar,!
artifact.repository.factory.order=artifacts.xml,artifacts.jar,!
"""

composite_p2_index = """version=1
metadata.repository.factory.order=compositeContent.xml,compositeContent.jar,!
artifact.repository.factory.order=compositeArtifacts.xml,compositeArtifacts.jar,!
"""


def fix_content_xml(xml_text: str) -> str:
    """Переименовать только category-IU, если его id совпал с bundle id."""
    if "p2.type.category" not in xml_text:
        return xml_text

    parts = re.split(r"(?=<unit )", xml_text)
    fixed = []
    for part in parts:
        if (
            part.strip().startswith("<unit")
            and "p2.type.category" in part
            and f"id='{BUNDLE_ID}'" in part
        ):
            part = part.replace(f"id='{BUNDLE_ID}'", f"id='{CATEGORY_ID}'", 1)
            part = part.replace(
                f"namespace='org.eclipse.equinox.p2.iu' name='{BUNDLE_ID}'",
                f"namespace='org.eclipse.equinox.p2.iu' name='{CATEGORY_ID}'",
                1,
            )
        fixed.append(part)
    return "".join(fixed)


def publish_p2_files(target_dir: str) -> None:
    content_jar = os.path.join(target_dir, "content.jar")
    artifacts_jar = os.path.join(target_dir, "artifacts.jar")
    if not os.path.isfile(content_jar):
        return

    with zipfile.ZipFile(content_jar, "r") as zin:
        content_xml = fix_content_xml(zin.read("content.xml").decode("utf-8"))

    artifacts_xml = None
    if os.path.isfile(artifacts_jar):
        with zipfile.ZipFile(artifacts_jar, "r") as zin:
            if "artifacts.xml" in zin.namelist():
                artifacts_xml = zin.read("artifacts.xml").decode("utf-8")

    with open(os.path.join(target_dir, "content.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(content_xml)

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zout:
        zout.writestr("content.xml", content_xml.encode("utf-8"))
    with open(content_jar, "wb") as f:
        f.write(buf.getvalue())

    if artifacts_xml:
        with open(os.path.join(target_dir, "artifacts.xml"), "w", encoding="utf-8", newline="\n") as f:
            f.write(artifacts_xml)
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zout:
            zout.writestr("artifacts.xml", artifacts_xml.encode("utf-8"))
        with open(artifacts_jar, "wb") as f:
            f.write(buf.getvalue())

    with open(os.path.join(target_dir, "p2.index"), "w", encoding="utf-8") as f:
        f.write(simple_p2_index)


def remove_root_simple_repository() -> None:
    """Удалить простой p2-репозиторий из корня (устаревший вариант деплоя)."""
    for item in (
        "features",
        "plugins",
        "content.jar",
        "content.xml",
        "artifacts.jar",
        "artifacts.xml",
    ):
        path = os.path.join(deploy_dir, item)
        if os.path.isdir(path):
            shutil.rmtree(path)
        elif os.path.isfile(path):
            os.remove(path)


def write_composite_repository(children: list[str]) -> None:
    """
    Корень сайта — composite-репозиторий со ссылками на каталоги версий.
    EDT «Показывать только последние версии»: снята — все версии, включена — одна.
    """
    timestamp = str(int(time.time() * 1000))
    children_xml = "\n".join(f"    <child location='{child}/'/>" for child in children)

    def composite_xml(repo_kind: str, repo_type: str) -> str:
        return f"""<?xml version='1.0' encoding='UTF-8'?>
<?composite{repo_kind}Repository version='1.0.0'?>
<repository name='EDT Comfort' type='{repo_type}' version='1.0.0'>
  <properties size='1'>
    <property name='p2.timestamp' value='{timestamp}'/>
  </properties>
  <children size='{len(children)}'>
{children_xml}
  </children>
</repository>
"""

    composites = (
        (
            "compositeContent",
            "Metadata",
            "org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository",
        ),
        (
            "compositeArtifacts",
            "Artifact",
            "org.eclipse.equinox.internal.p2.artifact.repository.CompositeArtifactRepository",
        ),
    )

    for base_name, repo_kind, repo_type in composites:
        xml = composite_xml(repo_kind, repo_type)
        xml_path = os.path.join(deploy_dir, f"{base_name}.xml")
        jar_path = os.path.join(deploy_dir, f"{base_name}.jar")
        with open(xml_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(xml)
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zout:
            zout.writestr(f"{base_name}.xml", xml.encode("utf-8"))
        with open(jar_path, "wb") as f:
            f.write(buf.getvalue())

    with open(os.path.join(deploy_dir, "p2.index"), "w", encoding="utf-8") as f:
        f.write(composite_p2_index)


def main() -> None:
    global latest
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

    for child in children:
        child_dir = os.path.join(deploy_dir, child)
        publish_p2_files(child_dir)
        version_index = f"""<!DOCTYPE html>
<html lang="ru">
<head><meta charset="utf-8"><title>EDT Comfort {child}</title></head>
<body>
  <h1>EDT Comfort — версия {child}</h1>
  <p>URL для EDT «Установить новое ПО»:</p>
  <p><code>{SITE_BASE_URL}{child}/</code></p>
  <p><a href="../">← Все версии</a></p>
</body>
</html>
"""
        with open(os.path.join(child_dir, "index.html"), "w", encoding="utf-8") as f:
            f.write(version_index)

    remove_root_simple_repository()
    if children:
        write_composite_repository(children)

    open(os.path.join(deploy_dir, ".nojekyll"), "w").close()

    version_links = "\n".join(
        f'    <li><a href="{c}/">{c}</a> — фиксированная версия</li>' for c in children
    )
    index_html = f"""<!DOCTYPE html>
<html lang="ru">
<head><meta charset="utf-8"><title>EDT Comfort p2</title></head>
<body>
  <h1>EDT Comfort — p2 update site</h1>
  <p>Установить новое ПО (все версии, composite):<br>
  <code>{SITE_BASE_URL}</code></p>
  <p>Архивные версии (отдельный каталог):</p>
  <ul>
{version_links}
  </ul>
</body>
</html>
"""
    with open(os.path.join(deploy_dir, "index.html"), "w", encoding="utf-8") as f:
        f.write(index_html)

    print("Latest version:", latest)
    print("Composite children:", children)


if __name__ == "__main__":
    main()
