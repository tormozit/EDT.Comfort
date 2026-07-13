import io
import os
import re
import shutil
import subprocess
import time
import zipfile

deploy_dir = os.environ.get("DEPLOY_DIR", "deploy")
latest = os.environ.get("LATEST_VERSION", "")
CATEGORY_ID = "comfort"
BUNDLE_ID = "tormozit.comfort"
FEATURE_GROUP_IU = "tormozit.comfort.feature.feature.group"
SITE_BASE_URL = "https://tormozit.github.io/EDT.Comfort/"

ROOT_P2_ITEMS = (
    "features",
    "plugins",
    "content.jar",
    "content.xml",
    "artifacts.jar",
    "artifacts.xml",
    "p2.index",
)

COMPOSITE_ITEMS = (
    "compositeContent.jar",
    "compositeContent.xml",
    "compositeArtifacts.jar",
    "compositeArtifacts.xml",
)

simple_p2_index = """version=1
metadata.repository.factory.order=content.xml,content.jar,!
artifact.repository.factory.order=artifacts.xml,artifacts.jar,!
"""


def sanitize_child_content_xml(xml_text: str) -> str:
    """Убрать ссылки на parent composite из дочернего p2-репозитория.

    <update> внутри unit-ов (feature.group, bundle) не трогаем: это цепочка
    "эта версия обновляет предыдущую", без неё p2 при установке с сайта не
    распознаёт апдейт корневого IU и плагин пропадает из "Установленного ПО"
    (issue: "плагин пропадает после обновления, только не из ZIP").
    """
    xml_text = re.sub(
        r"\s*<references\b[^>]*>.*?</references>\s*",
        "\n",
        xml_text,
        count=1,
        flags=re.DOTALL,
    )
    return xml_text


UPDATE_CHAIN_UNITS = (BUNDLE_ID, FEATURE_GROUP_IU)


def ensure_update_elements(xml_text: str) -> str:
    """Добавить <update> тем unit-ам (bundle/feature.group), у которых его нет.

    Архивные версии сайта публиковались до этого фикса и физически не содержат
    <update> в своём content.xml (он не проставляется задним числом сам по себе).
    Без него p2 не признаёт переход со старой архивной версии на другую архивную
    апдейтом — плагин пропадает из "Установленного ПО" даже при обновлении между
    двумя старыми версиями, не только на последнюю.
    """
    parts = re.split(r"(?=<unit )", xml_text)
    out = []
    for part in parts:
        m = re.match(r"<unit id='([^']*)' version='([^']*)'", part)
        if m and m.group(1) in UPDATE_CHAIN_UNITS and "<update " not in part:
            unit_id, version = m.group(1), m.group(2)
            insert_at = part.index(">") + 1
            update_tag = f"\n      <update id='{unit_id}' range='[0.0.0,{version})' severity='0'/>"
            part = part[:insert_at] + update_tag + part[insert_at:]
        out.append(part)
    return "".join(out)


def fix_content_xml(xml_text: str) -> str:
    """Category-IU fix + санитизация метаданных дочернего репозитория."""
    xml_text = sanitize_child_content_xml(xml_text)
    xml_text = ensure_update_elements(xml_text)
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
    content_xml_path = os.path.join(target_dir, "content.xml")
    artifacts_jar = os.path.join(target_dir, "artifacts.jar")

    content_xml = None
    if os.path.isfile(content_jar):
        with zipfile.ZipFile(content_jar, "r") as zin:
            content_xml = fix_content_xml(zin.read("content.xml").decode("utf-8"))
    elif os.path.isfile(content_xml_path):
        with open(content_xml_path, encoding="utf-8") as f:
            content_xml = fix_content_xml(f.read())
    else:
        return

    artifacts_xml = None
    if os.path.isfile(artifacts_jar):
        with zipfile.ZipFile(artifacts_jar, "r") as zin:
            if "artifacts.xml" in zin.namelist():
                artifacts_xml = zin.read("artifacts.xml").decode("utf-8")

    with open(content_xml_path, "w", encoding="utf-8", newline="\n") as f:
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


def remove_composite_repository() -> None:
    """Удалить composite p2 из корня (ломает регистрацию feature.group в EDT)."""
    for item in COMPOSITE_ITEMS:
        path = os.path.join(deploy_dir, item)
        if os.path.isfile(path):
            os.remove(path)


def remove_root_simple_repository() -> None:
    """Очистить простой p2-репозиторий в корне перед повторной публикацией."""
    for item in ROOT_P2_ITEMS:
        path = os.path.join(deploy_dir, item)
        if os.path.isdir(path):
            shutil.rmtree(path)
        elif os.path.isfile(path):
            os.remove(path)


def read_content_xml(path: str) -> str:
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            return f.read()
    jar_path = path.replace(".xml", ".jar")
    if os.path.isfile(jar_path):
        with zipfile.ZipFile(jar_path, "r") as zin:
            return zin.read("content.xml").decode("utf-8")
    return ""


def read_artifacts_xml(dir_path: str) -> str:
    xml_path = os.path.join(dir_path, "artifacts.xml")
    if os.path.isfile(xml_path):
        with open(xml_path, encoding="utf-8") as f:
            return f.read()
    jar_path = os.path.join(dir_path, "artifacts.jar")
    if os.path.isfile(jar_path):
        with zipfile.ZipFile(jar_path, "r") as zin:
            return zin.read("artifacts.xml").decode("utf-8")
    raise SystemExit(f"ERROR: artifacts metadata missing in {dir_path}")


UNIT_BLOCK_RE = re.compile(r"<unit\b[^>]*>.*?</unit>", re.DOTALL)
ARTIFACT_BLOCK_RE = re.compile(r"<artifact\b[^>]*>.*?</artifact>", re.DOTALL)
UNIT_KEY_RE = re.compile(r"<unit id='([^']+)'\s+version='([^']+)'")
ARTIFACT_KEY_RE = re.compile(
    r"<artifact classifier='([^']+)'\s+id='([^']+)'\s+version='([^']+)'"
)


def _block_key(block: str, key_re: re.Pattern[str]) -> tuple[str, ...]:
    match = key_re.search(block)
    if not match:
        raise SystemExit(f"ERROR: cannot parse p2 metadata block: {block[:120]!r}")
    return match.groups()


def _merge_repository_xml(
    xml_texts: list[str],
    block_re: re.Pattern[str],
    key_re: re.Pattern[str],
    container_tag: str,
) -> str:
    if not xml_texts:
        raise SystemExit("ERROR: no p2 metadata to merge for root repository")

    blocks: dict[tuple[str, ...], str] = {}
    for xml_text in xml_texts:
        for block in block_re.findall(xml_text):
            blocks[_block_key(block, key_re)] = block

    template = xml_texts[0]
    merged_inner = "\n    ".join(blocks.values())
    merged = re.sub(
        rf"<{container_tag} size='\d+'>.*?</{container_tag}>",
        f"<{container_tag} size='{len(blocks)}'>\n    {merged_inner}\n  </{container_tag}>",
        template,
        count=1,
        flags=re.DOTALL,
    )
    timestamp = str(int(time.time() * 1000))
    merged = re.sub(
        r"<property name='p2.timestamp' value='[^']*'/>",
        f"<property name='p2.timestamp' value='{timestamp}'/>",
        merged,
        count=1,
    )
    return merged


def write_p2_xml_jar(xml_path: str, jar_path: str, inner_name: str) -> None:
    with open(xml_path, encoding="utf-8") as f:
        xml_text = f.read()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zout:
        zout.writestr(inner_name, xml_text.encode("utf-8"))
    with open(jar_path, "wb") as f:
        f.write(buf.getvalue())


def copy_artifact_dirs(children: list[str]) -> None:
    for subdir in ("plugins", "features"):
        dst = os.path.join(deploy_dir, subdir)
        os.makedirs(dst, exist_ok=True)
        for child in children:
            src_dir = os.path.join(deploy_dir, child, subdir)
            if not os.path.isdir(src_dir):
                raise SystemExit(f"ERROR: {child}/{subdir} missing")
            for name in os.listdir(src_dir):
                shutil.copy2(
                    os.path.join(src_dir, name),
                    os.path.join(dst, name),
                )


def publish_root_aggregated_repository(children: list[str]) -> None:
    """Корень — простой p2-репозиторий со всеми версиями (видны в установщике EDT)."""
    if not children:
        raise SystemExit("ERROR: no version folders to aggregate at site root")

    remove_composite_repository()
    remove_root_simple_repository()

    content_xmls: list[str] = []
    artifacts_xmls: list[str] = []
    for child in children:
        child_dir = os.path.join(deploy_dir, child)
        content_xmls.append(read_content_xml(os.path.join(child_dir, "content.xml")))
        artifacts_xmls.append(read_artifacts_xml(child_dir))

    merged_content = _merge_repository_xml(
        content_xmls, UNIT_BLOCK_RE, UNIT_KEY_RE, "units"
    )
    merged_artifacts = _merge_repository_xml(
        artifacts_xmls, ARTIFACT_BLOCK_RE, ARTIFACT_KEY_RE, "artifacts"
    )

    copy_artifact_dirs(children)

    root_content = os.path.join(deploy_dir, "content.xml")
    root_artifacts = os.path.join(deploy_dir, "artifacts.xml")
    with open(root_content, "w", encoding="utf-8", newline="\n") as f:
        f.write(merged_content)
    with open(root_artifacts, "w", encoding="utf-8", newline="\n") as f:
        f.write(merged_artifacts)

    write_p2_xml_jar(root_content, os.path.join(deploy_dir, "content.jar"), "content.xml")
    write_p2_xml_jar(
        root_artifacts, os.path.join(deploy_dir, "artifacts.jar"), "artifacts.xml"
    )
    with open(os.path.join(deploy_dir, "p2.index"), "w", encoding="utf-8") as f:
        f.write(simple_p2_index)


def feature_group_versions(xml_text: str) -> list[str]:
    pattern = rf"id='{re.escape(FEATURE_GROUP_IU)}'\s+version='([^']+)'"
    return re.findall(pattern, xml_text)


def verify_root_aggregated_repository(children: list[str]) -> None:
    root_content = read_content_xml(os.path.join(deploy_dir, "content.xml"))
    if FEATURE_GROUP_IU not in root_content:
        raise SystemExit(f"ERROR: root content.xml missing {FEATURE_GROUP_IU}")

    root_groups = set(feature_group_versions(root_content))
    expected_groups: set[str] = set()
    for child in children:
        child_content = read_content_xml(
            os.path.join(deploy_dir, child, "content.xml")
        )
        child_groups = feature_group_versions(child_content)
        if len(child_groups) != 1:
            raise SystemExit(
                f"ERROR: {child}/content.xml must contain exactly one feature.group, "
                f"found {len(child_groups)}"
            )
        expected_groups.add(child_groups[0])

    missing = expected_groups - root_groups
    if missing:
        raise SystemExit(
            "ERROR: root repository missing feature.group versions: "
            + ", ".join(sorted(missing))
        )

    p2_index_path = os.path.join(deploy_dir, "p2.index")
    with open(p2_index_path, encoding="utf-8") as f:
        p2_index = f.read()
    if "compositeContent" in p2_index:
        raise SystemExit("ERROR: root p2.index still references compositeContent")

    for item in COMPOSITE_ITEMS:
        if os.path.isfile(os.path.join(deploy_dir, item)):
            raise SystemExit(f"ERROR: composite file still present at root: {item}")


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


def docs_cache_bust(repo_root: str) -> str:
    sha = os.environ.get("GITHUB_SHA", "").strip()
    if sha:
        return sha[:8]
    try:
        out = subprocess.check_output(
            ["git", "rev-parse", "--short=8", "HEAD"],
            cwd=repo_root,
            stderr=subprocess.DEVNULL,
            text=True,
        )
        return out.strip()
    except (OSError, subprocess.CalledProcessError):
        return str(int(time.time()))


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
    dest_index = os.path.join(help_dst, "index.html")
    cache_bust = docs_cache_bust(repo_root)
    if os.path.isfile(site_help_index):
        with open(site_help_index, encoding="utf-8") as f:
            index_html = f.read()
        index_html = index_html.replace("{{DOCS_CACHE_BUST}}", cache_bust)
        with open(dest_index, "w", encoding="utf-8", newline="\n") as f:
            f.write(index_html)
    else:
        print("WARN: site/help/index.html not found")

    print(
        "Published help:",
        help_dst,
        "files:",
        len(os.listdir(help_dst)),
        "cache_bust:",
        cache_bust,
    )


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
    publish_root_aggregated_repository(children)
    verify_root_aggregated_repository(children)


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
    print("Version folders:", children)
    print("Root p2: aggregated simple repository (all versions)")
    print("Docs only:", docs_only)


if __name__ == "__main__":
    main()
