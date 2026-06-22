import io
import os
import re
import shutil
import time
import zipfile

deploy_dir = os.environ.get("DEPLOY_DIR", "deploy")
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


def list_version_children() -> list[str]:
    if not os.path.isdir(deploy_dir):
        return []
    children = [
        d for d in os.listdir(deploy_dir)
        if os.path.isdir(os.path.join(deploy_dir, d)) and d[0:1].isdigit()
    ]
    children.sort(reverse=True)
    return children


def order_version_children(children: list[str]) -> list[str]:
    global latest
    if latest and latest in children:
        ordered = [c for c in children if c != latest]
        ordered.insert(0, latest)
        return ordered
    if children and not latest:
        latest = children[0]
    return children


def write_version_index_pages(children: list[str]) -> None:
    for child in children:
        child_dir = os.path.join(deploy_dir, child)
        version_index = f"""<!DOCTYPE html>
<html lang="ru">
<head><meta charset="utf-8"><title>EDT Comfort {child}</title></head>
<body>
  <h1>EDT Comfort — версия {child}</h1>
  <p>URL для EDT «Установить новое ПО»:</p>
  <p><code>{SITE_BASE_URL}{child}/</code></p>
  <p><a href="../">← Все версии</a> · <a href="../help/">Справка</a></p>
</body>
</html>
"""
        with open(os.path.join(child_dir, "index.html"), "w", encoding="utf-8") as f:
            f.write(version_index)


def publish_help_site(repo_root: str) -> None:
    docs_src = os.path.join(repo_root, "docs")
    help_dst = os.path.join(deploy_dir, "help")
    if not os.path.isdir(docs_src):
        print("WARN: docs/ not found, skipping help site")
        return

    if os.path.isdir(help_dst):
        shutil.rmtree(help_dst)
    shutil.copytree(
        docs_src,
        help_dst,
        ignore=shutil.ignore_patterns("_shablon-okna.md"),
    )

    site_help_index = os.path.join(repo_root, "site", "help", "index.html")
    if os.path.isfile(site_help_index):
        shutil.copy2(site_help_index, os.path.join(help_dst, "index.html"))
    else:
        print("WARN: site/help/index.html not found")

    print("Published help:", help_dst, "files:", len(os.listdir(help_dst)))


def write_root_index_html(children: list[str]) -> None:
    version_links = "\n".join(
        f'    <li><a href="{c}/">{c}</a> — фиксированная версия</li>' for c in children
    )
    template_path = os.path.join(
        os.environ.get("REPO_ROOT", "."), "site", "index.html")
    if os.path.isfile(template_path):
        with open(template_path, encoding="utf-8") as f:
            index_html = f.read()
        index_html = index_html.replace("{{SITE_BASE_URL}}", SITE_BASE_URL)
        index_html = index_html.replace("{{VERSION_LINKS}}", version_links)
    else:
        index_html = f"""<!DOCTYPE html>
<html lang="ru">
<head><meta charset="utf-8"><title>EDT Comfort</title></head>
<body>
  <h1>EDT Comfort — p2 update site</h1>
  <p><a href="help/">Справка</a></p>
  <p>Установить новое ПО:<br><code>{SITE_BASE_URL}</code></p>
  <ul>
{version_links}
  </ul>
</body>
</html>
"""
    with open(os.path.join(deploy_dir, "index.html"), "w", encoding="utf-8") as f:
        f.write(index_html)


def publish_p2_site(children: list[str]) -> None:
    for child in children:
        publish_p2_files(os.path.join(deploy_dir, child))
    write_version_index_pages(children)
    remove_root_simple_repository()
    if children:
        write_composite_repository(children)


def main() -> None:
    global latest
    repo_root = os.environ.get("REPO_ROOT", ".")
    docs_only = os.environ.get("DOCS_ONLY") == "1"

    children = list_version_children()
    children = order_version_children(children)

    if not docs_only:
        publish_p2_site(children)

    publish_help_site(repo_root)
    write_root_index_html(children)
    open(os.path.join(deploy_dir, ".nojekyll"), "w").close()

    print("Latest version:", latest)
    print("Composite children:", children)
    print("Docs only:", docs_only)


if __name__ == "__main__":
    main()
