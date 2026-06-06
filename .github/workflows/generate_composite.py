import io
import os
import re
import shutil
import zipfile

deploy_dir = "deploy"
latest = os.environ.get("LATEST_VERSION", "")
CATEGORY_ID = "comfort"
BUNDLE_ID = "tormozit.comfort"

simple_p2_index = """version=1
metadata.repository.factory.order=content.xml,content.jar,!
artifact.repository.factory.order=artifacts.xml,artifacts.jar,!
"""


def fix_content_xml(xml_text: str) -> str:
    """Категория не должна иметь тот же id, что и bundle (ломает p2)."""
    if "p2.type.category" not in xml_text:
        return xml_text

    pattern = re.compile(
        rf"<unit id='{re.escape(BUNDLE_ID)}'[^>]*>.*?p2\.type\.category.*?</unit>",
        re.DOTALL,
    )

    def repl(match: re.Match) -> str:
        unit_xml = match.group(0)
        unit_xml = unit_xml.replace(f"id='{BUNDLE_ID}'", f"id='{CATEGORY_ID}'", 1)
        unit_xml = unit_xml.replace(
            f"namespace='org.eclipse.equinox.p2.iu' name='{BUNDLE_ID}'",
            f"namespace='org.eclipse.equinox.p2.iu' name='{CATEGORY_ID}'",
            1,
        )
        return unit_xml

    return pattern.sub(repl, xml_text)


def publish_p2_files(target_dir: str) -> None:
    content_jar = os.path.join(target_dir, "content.jar")
    artifacts_jar = os.path.join(target_dir, "artifacts.jar")
    if not os.path.isfile(content_jar):
        return

    with zipfile.ZipFile(content_jar, "r") as zin:
        content_xml = fix_content_xml(zin.read("content.xml").decode("utf-8"))
        artifacts_xml = zin.read("artifacts.xml").decode("utf-8") if "artifacts.xml" in zin.namelist() else None

    with open(os.path.join(target_dir, "content.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(content_xml)

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zout:
        zout.writestr("content.xml", content_xml.encode("utf-8"))
    with open(content_jar, "wb") as f:
        f.write(buf.getvalue())

    if artifacts_xml and os.path.isfile(artifacts_jar):
        with open(os.path.join(target_dir, "artifacts.xml"), "w", encoding="utf-8", newline="\n") as f:
            f.write(artifacts_xml)
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zout:
            zout.writestr("artifacts.xml", artifacts_xml.encode("utf-8"))
        with open(artifacts_jar, "wb") as f:
            f.write(buf.getvalue())

    with open(os.path.join(target_dir, "p2.index"), "w", encoding="utf-8") as f:
        f.write(simple_p2_index)


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
        publish_p2_files(os.path.join(deploy_dir, child))

    if latest:
        latest_dir = os.path.join(deploy_dir, latest)
        for item in ("features", "plugins", "content.jar", "artifacts.jar", "content.xml", "artifacts.xml", "p2.index"):
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
        publish_p2_files(deploy_dir)

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


if __name__ == "__main__":
    main()
