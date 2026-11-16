#!/usr/bin/env python3
import os
import re
import json
import datetime
import subprocess

def main():
    tag = os.environ.get("TAG_NAME", "").strip()
    # 如果 tag 来自 ref，例如 refs/tags/v0.0.3，提取 tag 名
    if tag.startswith("refs/tags/"):
        tag = tag.replace("refs/tags/", "")

    repo = os.environ.get("GITHUB_REPOSITORY", "5151561/Trackr")
    release_body = os.environ.get("RELEASE_BODY", "").strip()

    # 版本名称：如果 tag 为形如 v0.0.3，则去掉前导 v
    version_name = tag.lstrip("v") if tag else "0.0.1"

    # 从 androidApp/build.gradle.kts 中解析 versionCode 与 versionName（若 tag 未指定）
    version_code = 1
    gradle_path = "androidApp/build.gradle.kts"
    if os.path.exists(gradle_path):
        with open(gradle_path, "r", encoding="utf-8") as f:
            content = f.read()
            match_code = re.search(r"versionCode\s*=\s*(\d+)", content)
            if match_code:
                version_code = int(match_code.group(1))
            if not tag:
                match_name = re.search(r'versionName\s*=\s*"([^"]+)"', content)
                if match_name:
                    version_name = match_name.group(1)

    # 提取 changelog
    changelog = release_body
    if not changelog:
        try:
            # 尝试获取最近一次提交信息作为 changelog 兜底
            commit_msg = subprocess.check_output(
                ["git", "log", "-1", "--pretty=format:%s\n%b"],
                text=True
            ).strip()
            changelog = commit_msg if commit_msg else f"发布版本 {tag or version_name}"
        except Exception:
            changelog = f"发布版本 {tag or version_name}"

    # 版本比较只看 versionCode：每个测试版、以及之后的正式版，versionCode 都要比上一个发出去的大。
    # 带后缀的 tag（v0.1.13-beta.1、v0.2.0-rc.1）是测试版：只写 update-beta.json，
    # 打开「接收测试版」的 App 才会去读；没打开的和旧版本 App 只读 update.json，看不到测试版。
    is_beta = "-" in version_name
    out_dir = os.environ.get("OUTPUT_DIR", "update-branch")
    out_files = ["update-beta.json"] if is_beta else ["update.json", "version.json"]
    out_json_path = os.path.join(out_dir, out_files[0])

    min_supported_version_code = 1
    force_update = False

    # 读取旧 update.json 以继承旧有配置
    if os.path.exists(out_json_path):
        try:
            with open(out_json_path, "r", encoding="utf-8") as f:
                old_data = json.load(f)
                min_supported_version_code = old_data.get("minSupportedVersionCode", 1)
                force_update = old_data.get("forceUpdate", False)
        except Exception as e:
            print(f"Warning: Failed to read existing {out_json_path}: {e}")

    publish_time = datetime.date.today().isoformat()
    download_url = (
        f"https://github.com/{repo}/releases/tag/{tag}"
        if tag
        else f"https://github.com/{repo}/releases"
    )

    # 按 ABI 分包：release 资产统一命名 trackr-<版本>-<abi>.apk，App 按设备架构直接下对应包。
    # 没有 tag（workflow_dispatch 手动触发）时不写细分地址，App 回退到 downloadUrl。
    download_urls = {}
    if tag:
        asset_base = f"https://github.com/{repo}/releases/download/{tag}"
        download_urls = {
            "arm64-v8a": f"{asset_base}/trackr-{version_name}-arm64-v8a.apk",
            "armeabi-v7a": f"{asset_base}/trackr-{version_name}-armeabi-v7a.apk",
        }

    update_data = {
        "versionCode": version_code,
        "versionName": version_name,
        "minSupportedVersionCode": min_supported_version_code,
        "changelog": changelog,
        "downloadUrl": download_url,
        "downloadUrls": download_urls,
        "publishTime": publish_time,
        "forceUpdate": force_update
    }

    os.makedirs(out_dir, exist_ok=True)
    for filename in out_files:
        target = os.path.join(out_dir, filename)
        with open(target, "w", encoding="utf-8") as f:
            json.dump(update_data, f, ensure_ascii=False, indent=2)
            f.write("\n")

    print(f"Successfully updated {', '.join(out_files)} in {out_dir}:")
    print(json.dumps(update_data, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
