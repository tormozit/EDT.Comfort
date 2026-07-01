Before editing any file, create a snapshot copy in `.tmp/` with the same relative path structure. Use the snapshot for reverting instead of `git checkout HEAD`, since the working tree may contain useful uncommitted changes.

Example:
```powershell
# Before editing:
$file = "plugin/src/tormozit/ContentAssistSessionReloader.java"
$snapshot = ".tmp/$file"
New-Item -ItemType Directory -Path (Split-Path -Parent $snapshot) -Force | Out-Null
Copy-Item -Path $file -Destination $snapshot -Force

# To revert:
Copy-Item -Path $snapshot -Destination $file -Force
```
