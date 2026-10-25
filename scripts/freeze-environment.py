"""核对官方元数据并冻结版本；只有明确更新基线时才重新运行。"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from hashlib import sha256
from pathlib import Path
from urllib.request import Request, urlopen
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def fetch(url, headers=None):
    request = Request(url, headers={"User-Agent": "yueji-stage-a/1.0", **(headers or {})})
    with urlopen(request, timeout=60) as response:
        return response.read(), dict(response.headers)


def get_json(url, headers=None):
    return json.loads(fetch(url, headers)[0])


def maven(group, artifact, version_line):
    url = f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{artifact}/maven-metadata.xml"
    data = fetch(url)[0]
    versions = [v.text for v in ET.fromstring(data).findall("./versioning/versions/version")]
    matches = [v for v in versions if re.fullmatch(re.escape(version_line) + r"\.\d+", v)]
    return {"version": max(matches, key=lambda v: tuple(map(int, v.split('.')))), "source": url,
            "metadataSha256": sha256(data).hexdigest()}


def npm(name, major):
    url = f"https://registry.npmjs.org/{name}"
    versions = get_json(url)["versions"]
    matches = [v for v in versions if re.fullmatch(str(major) + r"\.\d+\.\d+", v)]
    version = max(matches, key=lambda v: tuple(map(int, v.split('.'))))
    metadata = versions[version]
    return {"version": version, "source": url + '/' + version,
            "integrity": metadata["dist"]["integrity"], "engines": metadata.get("engines", {})}


def image(name, tag):
    # Docker Hub 官方标签 API 不要求读取用户 Docker 凭据，也不依赖本机守护进程。
    url = f"https://hub.docker.com/v2/repositories/library/{name}/tags/{tag}"
    metadata = get_json(url)
    digest = metadata['digest']
    platform = next(m for m in metadata['images'] if m.get('os') == 'linux'
                    and m.get('architecture') == 'amd64')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
        raise ValueError('Docker Hub returned an invalid digest')
    return {"tag": f"{name}:{tag}", "indexDigest": digest, "linuxAmd64Digest": platform['digest'],
            "reference": f"{name}@{digest}", "source": url,
            "verification": "registry metadata verified; container not run"}


def main():
    packages = {'vue': 3, 'vue-router': 4, 'pinia': 3, 'element-plus': 2, 'vite': 7,
                '@vitejs/plugin-vue': 6, 'typescript': 5, 'vue-tsc': 3, 'vitest': 4,
                '@vue/test-utils': 2, 'jsdom': 27, 'pnpm': 10, '@redocly/cli': 2,
                '@types/node': 24}
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = {name: pool.submit(npm, name, major) for name, major in packages.items()}
        frontend = {name: future.result() for name, future in futures.items()}
    node = next(v for v in get_json('https://nodejs.org/dist/index.json') if v['version'].startswith('v24.'))
    node_version = node['version'].lstrip('v')
    checksum_url = f"https://nodejs.org/dist/v{node_version}/SHASUMS256.txt"
    checksums = fetch(checksum_url)[0].decode()
    node_file = f"node-v{node_version}-win-x64.zip"
    node_hash = next(line.split()[0] for line in checksums.splitlines() if line.endswith('  ' + node_file))
    jdk_source = 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    jdk = get_json(jdk_source)[0]
    maven_version = maven('org.apache.maven', 'apache-maven', '3.9')['version']
    maven_url = f"https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/{maven_version}/apache-maven-{maven_version}-bin.zip"
    maven_sha512 = fetch(maven_url + '.sha512')[0].decode().strip().split()[0]
    lock = {
        'schemaVersion': 1, 'stage': 'A', 'verifiedAtUtc': datetime.now(timezone.utc).isoformat(),
        'clientDate': '2026-10-23',
        'java': {'version': jdk['version']['openjdk_version'], 'source': jdk_source,
                 'url': jdk['binary']['package']['link'], 'sha256': jdk['binary']['package']['checksum']},
        'node': {'version': node_version, 'npm': node['npm'],
                 'url': f'https://nodejs.org/dist/v{node_version}/{node_file}', 'sha256': node_hash,
                 'checksumSource': checksum_url},
        'maven': {'version': maven_version, 'url': maven_url, 'sha512': maven_sha512},
        'backend': {'springBoot': maven('org.springframework.boot', 'spring-boot-starter-parent', '3.5'),
                    'springdoc': maven('org.springdoc', 'springdoc-openapi-starter-webmvc-ui', '2.8')},
        'frontend': frontend,
        'images': [image('eclipse-temurin', '21-jre-jammy'), image('node', '24-bookworm-slim'),
                   image('nginx', 'stable-alpine')],
        'deferred': ['MySQL/MyBatis-Plus/Flyway: stage B', 'Redis/RabbitMQ: business integration stages',
                     'Prometheus/Grafana/Loki/Compose/CD: stage I'],
        'compatibility': ['Java21 and Boot3.5 satisfy official requirements',
                          'springdoc2.8 targets Boot3.5; https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot',
                          'npm engines recorded per exact package; pnpm peer checks and builds validate combination']}
    target = ROOT / 'infra' / 'versions.lock.json'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps({'java': lock['java']['version'], 'node': node_version, 'maven': maven_version,
                      'backend': lock['backend'], 'frontend': {k: v['version'] for k, v in frontend.items()},
                      'images': lock['images']}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
