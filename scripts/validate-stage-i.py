"""Static checks for Stage I release, container and recovery artifacts."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
checks: list[dict[str, object]] = []


def check(name: str, ok: bool, detail: str) -> None:
    checks.append({"name": name, "passed": bool(ok), "detail": detail})
    if not ok:
        raise SystemExit(f"FAIL {name}: {detail}")


lock_path = ROOT / "infra/versions.stage-i.lock.json"
lock = json.loads(lock_path.read_text(encoding="utf-8"))
actions = {item["name"]: item["commit"] for item in lock["actions"]}
check("stage-i-lock-json", lock["stage"] == "I" and len(actions) >= 7, "Stage I action/image lock is valid JSON")
check("action-sha-length", all(re.fullmatch(r"[0-9a-f]{40}", sha) for sha in actions.values()), "all frozen actions use full 40-character commits")

workflow_text = "\n".join(path.read_text(encoding="utf-8") for path in sorted((ROOT / ".github/workflows").glob("*.yml")))
uses = re.findall(r"^\s*(?:-\s*)?uses:\s*([^\s#]+)", workflow_text, flags=re.MULTILINE)
unpinned = [ref for ref in uses if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*@[0-9a-f]{40}", ref)]
check("workflow-actions-pinned", not unpinned, f"unpinned actions: {unpinned}")
for name, sha in actions.items():
    action_refs = [ref for ref in uses if ref.startswith(name + "/") and ref.endswith("@" + sha)] if name == "github/codeql-action" else [ref for ref in uses if ref == name + "@" + sha]
    check(f"locked-action-{name}", bool(action_refs), "workflow reference matches frozen action lock")

compose = (ROOT / "infra/compose.yml").read_text(encoding="utf-8")
observability = (ROOT / "infra/compose.observability.yml").read_text(encoding="utf-8")
dockerfiles = "\n".join((ROOT / path).read_text(encoding="utf-8") for path in ("backend/Dockerfile", "frontend/Dockerfile"))
image_lines = [line.strip() for line in (compose + "\n" + observability + "\n" + dockerfiles).splitlines() if re.match(r"\s*(image|FROM)\s+", line)]
check("all-container-images-digest-locked", all(("@sha256:" in line or "${BACKEND_IMAGE" in line or "${FRONTEND_IMAGE" in line) and "latest" not in line.lower() for line in image_lines), f"digest-locked image declarations or runtime digest variables: {len(image_lines)}")
check("compose-no-local-build", not re.search(r"^\s+build:\s*$", compose + observability, re.MULTILINE), "deployment consumes prebuilt digest references only")
check("web-only-public-ingress", '"80:80"' in compose and '"443:443"' in compose and '"3306:' not in compose, "only web ports are host-published in base Compose")
check("management-port-binding", "MANAGEMENT_SERVER_ADDRESS: 0.0.0.0" in compose and 'MANAGEMENT_SERVER_PORT: "9081"' in compose, "Spring Boot management server uses the internal readiness/metrics port")
check("deployment-environment-is-explicit", "APP_ENV: ${DEPLOY_ENV:?set DEPLOY_ENV" in compose and "DEPLOY_ENV=test" in (ROOT / "infra/runtime.env.example").read_text(encoding="utf-8"), "application environment is explicitly separated from the secure prod Spring profile")
check("compose-health-and-db-migration-policy", "actuator/health/readiness" in compose and "clean-disabled: true" in (ROOT / "backend/src/main/resources/application.yml").read_text(encoding="utf-8"), "readiness is checked and Flyway clean remains disabled")
check("secret-file-references", all(token in compose for token in ("DB_PASSWORD_FILE", "JWT_SIGNING_KEY_FILE", "ALIPAY_APP_PRIVATE_KEY_FILE", "ALIPAY_PUBLIC_KEY_FILE")), "runtime private credentials are mounted from protected files")

deploy = (ROOT / ".github/workflows/deploy-test.yml").read_text(encoding="utf-8")
release = (ROOT / ".github/workflows/quality-release.yml").read_text(encoding="utf-8")
deploy_script = (ROOT / "scripts/deploy-yueji-test-linux.sh").read_text(encoding="utf-8")
backup_script = (ROOT / "scripts/backup-yueji-linux.sh").read_text(encoding="utf-8")
restore_script = (ROOT / "scripts/restore-yueji-isolated-linux.sh").read_text(encoding="utf-8")
rollback_script = (ROOT / "scripts/rollback-yueji-app-linux.sh").read_text(encoding="utf-8")
check("deploy-test-only", "environment: test" in deploy and "environment: prod" not in deploy and "prod" not in deploy.lower(), "CD has no production environment or route")
check("digest-manifest-from-release-run", "actions/download-artifact@" in deploy and "run-id: ${{ inputs.release_run_id }}" in deploy and "release-manifest.json" in deploy, "test CD downloads the exact selected successful-run manifest")
check("quality-gates-before-publish", "needs: [frontend, backend, security, codeql]" in release and "retention-days: 90" in release, "candidate manifest is emitted only after all required jobs and image scans")
check("pr-no-deploy-secrets", "TEST_DEPLOY_SSH_PRIVATE_KEY" not in (ROOT / ".github/workflows/quality-release.yml").read_text(encoding="utf-8"), "PR quality workflow has no deploy secret references")
check("maintenance-window-and-release-provenance", "maintenance_window_id" in deploy and "release_head_sha" in deploy_script and "maintenance_window_id" in deploy_script, "test deployment requires explicit maintenance id and records the selected release SHA")
check("encrypted-offsite-backup", "age -r" in backup_script and "rclone copyto" in backup_script and "RCLONE_CONFIG_FILE" in backup_script, "backup encrypts before offsite copy through a protected rclone config")
check("no-destructive-recovery-commands", not re.search(r"DROP\s+(DATABASE|TABLE)|docker\s+volume\s+rm|compose\s+down\s+-v|flyway\s+clean", deploy_script + restore_script, re.IGNORECASE), "deploy/restore scripts contain no database/volume cleanup or reverse-DDL command")
check("rollback-runtime-environment-test-only", 'yueji_env DEPLOY_ENV || true)" == test' in rollback_script, "application rollback checks both its invocation and protected runtime environment are test")
check("isolated-restore-test-source-only", 'manifest.get("environment") != "test"' in restore_script, "isolated restore rejects production backup data")

record = {"stage": "I", "result": "STATIC_ARTIFACT_CHECKS_PASSED", "checks": checks}
print(json.dumps(record, ensure_ascii=False, indent=2))
