#!/usr/bin/env python3
"""Validate explicit private candidate JARs against a retained peer snapshot.

No publication or writes to the client checkout. Only scratch module configuration
uses a loopback HTTPS repository; candidate bytes and source hashes are recorded.
"""
import argparse
import functools
import hashlib
import http.server
import json
import os
import pathlib
import re
import shutil
import subprocess
import tarfile
import tempfile
import ssl
import threading
import zipfile


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(client, *args):
    return subprocess.check_output(["git", "-C", str(client), *args])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--client", type=pathlib.Path, required=True)
    parser.add_argument("--core-jar", type=pathlib.Path, required=True)
    parser.add_argument("--metadata-jar", type=pathlib.Path, required=True)
    parser.add_argument("--scratch-dir", type=pathlib.Path, required=True)
    parser.add_argument("--dependency-model-dir", type=pathlib.Path, required=True)
    parser.add_argument("--candidate-sha", required=True)
    args = parser.parse_args()
    client = args.client.resolve()
    require(re.fullmatch(r"[0-9a-f]{40}", args.candidate_sha), "Candidate commit must be a full SHA")
    require(args.scratch_dir.is_dir() and args.core_jar.is_file() and args.metadata_jar.is_file(),
            "Scratch directory and both candidate JARs must exist")
    status = git(client, "status", "--porcelain=v1", "--untracked-files=all")
    require(not status, "Client must be clean; no source/config changes will be discarded")
    peer_sha = git(client, "rev-parse", "HEAD").decode().strip()
    scratch = pathlib.Path(tempfile.mkdtemp(prefix="engine-candidate-", dir=args.scratch_dir))
    snapshot = scratch / "client"
    snapshot.mkdir()
    archive = scratch / "source.tar"
    archive.write_bytes(git(client, "archive", "HEAD", "brikk-engine", "project.yaml", "kotlin", "kotlin.bat", "mise.toml", "AGENTS.md"))
    with tarfile.open(archive) as bundle:
        bundle.extractall(snapshot, filter="data")
    inputs = {str(p.relative_to(snapshot)): sha(p) for p in snapshot.rglob("*") if p.is_file()}
    repo = scratch / "candidate-repo"
    version = "0.19.0-candidate-" + args.candidate_sha[:12]
    candidate = {}
    for name, source in (("brikk-sql-jvm", args.core_jar), ("brikk-sql-metadata-jvm", args.metadata_jar)):
        directory = repo / "dev/brikk/house" / name / version
        directory.mkdir(parents=True)
        jar = directory / f"{name}-{version}.jar"
        shutil.copy2(source, jar)
        model = args.dependency_model_dir / name / "0.18.0" / f"{name}-0.18.0.pom"
        require(model.is_file(), f"Missing dependency model: {model}")
        # Same Kotlin/serialization dependency model; only private SQL artifact
        # versions change. This does not claim publication/POM validation.
        text = model.read_text().replace("<version>0.18.0</version>", f"<version>{version}</version>")
        text = text.replace("<!-- do_not_remove: published-with-gradle-metadata -->", "<!-- private JAR validation model; no publication -->")
        (directory / f"{name}-{version}.pom").write_text(text)
        candidate[name] = {"sha256": sha(jar), "jar": str(jar), "version": version,
                           "dependency_model_sha256": sha(model)}
    # Toolchain repositories require HTTPS. Serve only this private repository
    # on loopback; trust is scoped to this child process, never installed globally.
    tls = scratch / "tls"
    tls.mkdir(mode=0o700)
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
        "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1",
        "-keyout", str(tls / "key.pem"), "-out", str(tls / "cert.pem")], check=True, capture_output=True)
    home = subprocess.run(["java", "-XshowSettings:properties", "-version"], check=True, capture_output=True, text=True)
    java_home = pathlib.Path(re.search(r"java.home\s*=\s*(.+)", home.stderr).group(1).strip())
    shutil.copy2(java_home / "lib/security/cacerts", tls / "cacerts")
    subprocess.run([str(java_home / "bin/keytool"), "-importcert", "-noprompt", "-alias", "private-engine-candidate",
        "-file", str(tls / "cert.pem"), "-keystore", str(tls / "cacerts"), "-storepass", "changeit"], check=True, capture_output=True)
    for artifact in list(repo.rglob("*")):
        if artifact.is_file():
            for algorithm in ("sha1", "sha256", "sha512"):
                artifact.with_name(artifact.name + "." + algorithm).write_text(hashlib.new(algorithm, artifact.read_bytes()).hexdigest())
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(repo))
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(tls / "cert.pem", tls / "key.pem")
    server.socket = context.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    repository_url = f"https://127.0.0.1:{server.server_port}"
    changes = []
    for module in snapshot.rglob("module.yaml"):
        old = module.read_text()
        new = old.replace("dev.brikk.house:brikk-sql-jvm:0.18.0", f"dev.brikk.house:brikk-sql-jvm:{version}")
        if old == new:
            continue
        if "repositories:\n" in new:
            new = new.replace("repositories:\n", f"repositories:\n  - {repository_url}\n", 1)
        else:
            new += f"\nrepositories:\n  - {repository_url}\n"
        module.write_text(new)
        changes.append(str(module.relative_to(snapshot)))
    require(len(changes) == 5, f"Expected five scratch-only SQL dependency overrides: {changes}")
    manifest = {"candidate_commit": args.candidate_sha, "peer_commit": peer_sha, "candidate": candidate,
                "source_inputs": inputs, "scratch_only_dependency_overrides": changes,
                "publication": False, "client_source_writes": False}
    (scratch / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("Scratch: " + str(scratch), flush=True)
    results = []
    environment = dict(os.environ)
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "KOTLIN_CLI_JAVA_OPTIONS"):
        environment.pop(key, None)
    environment["KOTLIN_CLI_JAVA_OPTIONS"] = f"-Djavax.net.ssl.trustStore={tls / 'cacerts'} -Djavax.net.ssl.trustStorePassword=changeit"

    def run(name, command):
        log = scratch / f"{name}.log"
        with log.open("w") as out:
            result = subprocess.run(command, cwd=snapshot, env=environment, stdout=out, stderr=subprocess.STDOUT)
        results.append({"step": name, "exit": result.returncode, "log": str(log)})
        (scratch / "results.json").write_text(json.dumps(results, indent=2) + "\n")
        require(result.returncode == 0, f"{name} failed; inspect {log}")

    try:
        run("compiler-runtime-tests", ["./kotlin", "test", "-m", "brikk-engine-kotlin", "-m", "brikk-engine-kotlin-compiler-plugin",
            "-m", "brikk-engine-kotlin-schema-inputs", "-m", "brikk-engine-kotlin-tooling"])
        run("assemble-plugin", ["./kotlin", "do", "assemblePluginJar", "-m", "brikk-engine-kotlin-compiler-plugin"])
        run("smoke-build", ["./kotlin", "build", "-m", "brikk-engine-kotlin-smoke"])
        run("ide-compiler-check", ["./kotlin", "do", "verifyIdePlugin", "-m", "brikk-engine-kotlin-compiler-ide"])
        # Require actual JVM success counts, not an empty test run.
        counts = [int(n) for n in re.findall(r"\[\s*(\d+) tests successful", (scratch / "compiler-runtime-tests.log").read_text())]
        require(len(counts) == 4 and all(n > 0 for n in counts), f"Expected four nonempty test suites: {counts}")
        manifest["successful_test_counts"] = counts
        plugins = {}
        for relative in ("build/plugin/brikk-engine-kotlin-compiler-plugin-2.4.10-0.2.0.jar",
                         "build/plugin-ide/2.4.20-ij262-34/brikk-engine-kotlin-compiler-plugin-2.4.20-ij262-34-0.2.0.jar"):
            jar = snapshot / relative
            with zipfile.ZipFile(jar) as bundle:
                pins = [p for p in bundle.namelist() if p.endswith("/BrikkSql.class")]
                require(pins and all(b"v30.22.0-5-ga00dc2e5c" in bundle.read(p) for p in pins),
                        f"Assembled plugin does not contain the frozen target pin: {jar}")
            plugins[relative] = sha(jar)
        manifest["assembled_plugin_sha256"] = plugins
    finally:
        server.shutdown()
        server.server_close()
        require(git(client, "rev-parse", "HEAD").decode().strip() == peer_sha, "Peer HEAD changed")
        require(git(client, "status", "--porcelain=v1", "--untracked-files=all") == status, "Peer checkout changed")
        require(sha(args.core_jar) == candidate["brikk-sql-jvm"]["sha256"], "Core candidate bytes changed")
        require(sha(args.metadata_jar) == candidate["brikk-sql-metadata-jvm"]["sha256"], "Metadata candidate bytes changed")
    # Publish success only after the byte/source invariants above have passed.
    manifest["validation_passed"] = True
    (scratch / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (scratch / "PASS").write_text("Private candidate compiler/runtime/assembly/smoke/IDE validation passed\n")
    print("PASS: private candidate bytes verified; peer source checkout unchanged", flush=True)


if __name__ == "__main__":
    main()
