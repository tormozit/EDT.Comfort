import os, time, zipfile

deploy_dir = "deploy"
timestamp = str(int(time.time() * 1000))

# List child repos (version folders), newest first
children = [d for d in sorted(os.listdir(deploy_dir), reverse=True)
            if os.path.isdir(os.path.join(deploy_dir, d)) and not d.startswith('.')]

# compositeContent.xml
content_xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<repository name="EDT Comfort Update Site" type="org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository" version="1.0.0">
  <properties size="1">
    <property name="p2.timestamp" value="{timestamp}"/>
  </properties>
  <children size="{len(children)}">
'''
for child in children:
    content_xml += f'    <child location="{child}"/>\n'
content_xml += '''  </children>
</repository>
'''

# compositeArtifacts.xml
artifacts_xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<repository name="EDT Comfort Update Site" type="org.eclipse.equinox.internal.p2.artifact.repository.CompositeArtifactRepository" version="1.0.0">
  <properties size="1">
    <property name="p2.timestamp" value="{timestamp}"/>
  </properties>
  <children size="{len(children)}">
'''
for child in children:
    artifacts_xml += f'    <child location="{child}"/>\n'
artifacts_xml += '''  </children>
</repository>
'''

# Pack to JAR
def write_jar(name, xml_content):
    jar_path = os.path.join(deploy_dir, name)
    xml_name = name.replace('.jar', '.xml')
    with zipfile.ZipFile(jar_path, 'w', zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(xml_name, xml_content)

write_jar('compositeContent.jar', content_xml)
write_jar('compositeArtifacts.jar', artifacts_xml)

# p2.index for composite site
p2_index = '''version=1
metadata.repository.factory.order=compositeContent.jar,!
artifact.repository.factory.order=compositeArtifacts.jar,!
'''
with open(os.path.join(deploy_dir, 'p2.index'), 'w') as f:
    f.write(p2_index)

# GitHub Pages: отключить Jekyll (иначе *.jar и p2-структура не отдаются)
open(os.path.join(deploy_dir, '.nojekyll'), 'w').close()

# Простая страница для браузера (p2-клиенты используют p2.index)
links = '\n'.join(
    f'    <li><a href="{c}/p2.index">{c}</a></li>' for c in children
)
index_html = f'''<!DOCTYPE html>
<html lang="ru">
<head><meta charset="utf-8"><title>EDT Comfort p2</title></head>
<body>
  <h1>EDT Comfort — p2 update site</h1>
  <p>В Eclipse/EDT: <code>https://tormozit.github.io/EDT.Comfort/</code></p>
  <ul>
{links}
  </ul>
</body>
</html>
'''
with open(os.path.join(deploy_dir, 'index.html'), 'w', encoding='utf-8') as f:
    f.write(index_html)

print("Generated composite site with children:", children)
