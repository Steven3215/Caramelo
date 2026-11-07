#!/usr/bin/env bash
set -euo pipefail

app=com.lagradost.cloudstream3
prefs="/data/data/$app/shared_prefs"
adb install -g "$RUNNER_TEMP/cloudstream.apk"
adb root
adb wait-for-device

cat > "$RUNNER_TEMP/rebuild_preference.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8" standalone="yes"?>
<map>
  <string name="HAS_DONE_SETUP">true</string>
  <string name="jsdelivr_proxy_key">true</string>
  <string name="REPOSITORIES_KEY">[{"name":"Caramelo","url":"https://raw.githubusercontent.com/Steven3215/Caramelo/main/repo.json"}]</string>
</map>
EOF
cat > "$RUNNER_TEMP/app_preferences.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8" standalone="yes"?>
<map>
  <int name="auto_download_plugins_key2" value="1" />
</map>
EOF
adb shell mkdir -p "$prefs"
adb push "$RUNNER_TEMP/rebuild_preference.xml" "$prefs/rebuild_preference.xml"
adb push "$RUNNER_TEMP/app_preferences.xml" "$prefs/${app}_preferences.xml"
uid="$(adb shell "stat -c %u /data/data/$app" | tr -d '\r')"
adb shell "chown -R $uid:$uid '$prefs'; restorecon -RF '$prefs'"
adb logcat -c
adb shell am start -n "$app/.MainActivity"
for _ in {1..48}; do
  adb logcat -d > "$RUNNER_TEMP/car-install.log"
  if grep -q 'Plugin download done!' "$RUNNER_TEMP/car-install.log"; then
    break
  fi
  sleep 5
done
grep -E 'PluginManager|Extension hash mismatch|FATAL EXCEPTION|Failed to load plugin|VerifyError' "$RUNNER_TEMP/car-install.log" | tail -n 170 || true
python3 - <<'PY'
import json, os, pathlib
log = (pathlib.Path(os.environ["RUNNER_TEMP"]) / "car-install.log").read_text(errors="replace")
plugins = json.loads(pathlib.Path("plugins.json").read_text())
missing = [entry["internalName"] for entry in plugins if f'Loaded plugin {entry["internalName"]} successfully' not in log]
if missing:
    raise SystemExit(f"Failed to verify {len(missing)} extensions: {', '.join(missing)}")
assert "Downloading plugin: https://raw.githubusercontent.com/" in log
print(f"With GitHub Proxy enabled, the official Cloudstream APK downloaded and loaded all {len(plugins)} extensions.")
PY
