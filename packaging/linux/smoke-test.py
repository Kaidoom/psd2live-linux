#!/usr/bin/env python3
"""Check the packaged launcher, JVM workers and relocation; optionally rebuild a saved project."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
from urllib.parse import unquote, urlsplit
import zipfile


def check_classpath(app):
    java = app / "lib/runtime/bin/java"
    assert java.is_file() and os.access(java, os.X_OK), "The packaged runtime must retain bin/java for saved plugin workers"
    release = (app / "lib/runtime/release").read_text()
    modules = next(line.split("=", 1)[1].strip('"').split() for line in release.splitlines() if line.startswith("MODULES="))
    assert "java.management" in modules, "Packaged freeze diagnostics require java.management"
    directory = app / "lib/app"
    config = (directory / "PSD2Live.cfg").read_text()
    entries = [line for line in config.splitlines() if line.startswith("app.classpath=")]
    assert entries == ["app.classpath=$APPDIR/psd2live-classpath.jar"], entries
    with zipfile.ZipFile(directory / "psd2live-classpath.jar") as jar:
        manifest = jar.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
    classpath = next(line.removeprefix("Class-Path: ") for line in manifest.splitlines() if line.startswith("Class-Path: "))
    paths = []
    for entry in classpath.split():
        uri = urlsplit(entry)
        assert not uri.scheme and not uri.netloc and not uri.query and not uri.fragment, entry
        path = (directory / unquote(uri.path)).resolve()
        assert path.is_relative_to(directory.resolve()) and path.is_file(), path
        paths.append(path)
    expected = {p.resolve() for p in directory.glob("*.jar") if p.name != "psd2live-classpath.jar"}
    assert set(paths) == expected, "Manifest must retain every packaged dependency"
    assert paths[0].name.startswith("psd2live-"), "Preserve the application JAR's classpath precedence"
    print(f"Verified {len(paths)} packaged classpath entries")


def check_launcher(app, home, project=None, plugins=None):
    home.mkdir()
    empty_path = home / "empty-path"
    empty_path.mkdir()
    env = dict(os.environ, JAVA_HOME="/nonexistent-jdk", PATH=str(empty_path), JAVA_TOOL_OPTIONS=(
        f'-Duser.home="{home}" -Djava.util.prefs.userRoot="{home / "prefs"}"'))
    for key in ("_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
        env.pop(key, None)
    for args in (("--help", "--lang", "en"), ("targets", "--lang", "en")):
        result = subprocess.run([str(app / "bin/PSD2Live"), *args], cwd=home, env=env,
                                capture_output=True, text=True, timeout=30)
        assert result.returncode == 0, f"{args}: exit {result.returncode}\n{result.stderr}"
        if args[0] == "--help":
            assert "--input" in result.stdout, result.stdout
        else:
            targets = {line.split()[0] for line in result.stdout.splitlines() if line and not line[0].isspace()}
            assert {"cmo3", "moc3", "p2lrt"} <= targets, targets
        print(f"{app.name}: {' '.join(args)} passed")

    # Exercise the same fat JAR and Java launcher used by built-in plugin subprocesses.
    # EOF cleanly ends the worker's request loop after loading its runtime and algorithm registrations.
    resource = "algorithm-plugins/shared/runtime.jar"
    worker = home / "worker.jar"
    for jar in (app / "lib/app").glob("*.jar"):
        with zipfile.ZipFile(jar) as packaged:
            if resource in packaged.namelist():
                worker.write_bytes(packaged.read(resource))
                break
    assert worker.is_file(), "Missing bundled binding worker"
    worker_env = dict(env, PSD2LIVE_PLUGIN_PROTOCOL="2", PSD2LIVE_PLUGIN_RESOURCES=str(home))
    result = subprocess.run([str(app / "lib/runtime/bin/java"), "-jar", str(worker)],
                            input="", cwd=home, env=worker_env, capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, f"Packaged worker: exit {result.returncode}\n{result.stderr}"
    print(f"{app.name}: bundled Java worker started without a system JDK")

    if project is not None:
        # Copy only the immutable packages referenced by the archive. Never use or modify the live
        # editor's workspace/preferences/plugin store, and never save over the input project.
        references = set()
        def collect(value):
            if isinstance(value, dict):
                plugin = value.get("plugin")
                if isinstance(plugin, dict) and isinstance(plugin.get("digest"), str):
                    references.add(plugin["digest"])
                for child in value.values():
                    collect(child)
            elif isinstance(value, list):
                for child in value:
                    collect(child)
        with zipfile.ZipFile(project) as archive:
            for name in archive.namelist():
                if name.endswith(".json"):
                    collect(json.loads(archive.read(name)))
        for digest in references:
            assert len(digest) == 64 and all(c in "0123456789abcdef" for c in digest), digest
            source = plugins / digest
            assert source.is_dir(), f"Missing archived package: {digest}"
            shutil.copytree(source, home / ".psd2live/plugins" / digest)
        before = hashlib.sha256(project.read_bytes()).digest()
        process = subprocess.Popen([str(app / "bin/PSD2Live"), "export", str(project), "--target", "p2lrt",
                                    "--output", str(home / "export"), "--name", "smoke", "--lang", "en"],
                                   cwd=home, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        workers = set()
        stopped = threading.Event()
        def observe_workers():
            tasks = Path(f"/proc/{process.pid}/task")
            while not stopped.is_set():
                try:
                    # ProcessBuilder runs on background JVM threads; Linux tracks children per task.
                    for children in tasks.glob("*/children"):
                        try:
                            for pid in children.read_text().split():
                                try:
                                    if Path(f"/proc/{pid}/exe").resolve(strict=True) == (app / "lib/runtime/bin/java").resolve():
                                        workers.add(pid)
                                except (FileNotFoundError, ProcessLookupError):
                                    pass
                        except (FileNotFoundError, ProcessLookupError):
                            pass
                except (FileNotFoundError, ProcessLookupError):
                    pass
                stopped.wait(.02)
        observer = threading.Thread(target=observe_workers, daemon=True)
        observer.start()
        try:
            stdout, stderr = process.communicate(timeout=180)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate()
            raise
        finally:
            stopped.set()
            observer.join()
        assert hashlib.sha256(project.read_bytes()).digest() == before, "The input project changed"
        assert process.returncode == 0, f"Packaged project load/export: exit {process.returncode}\n{stderr}\n{stdout}"
        assert workers, "The project must exercise archived JVM plugins, not only in-process generators"
        assert list((home / "export").glob("*.p2lrt")), "No runtime model was exported"
        print(f"{app.name}: loaded and exported {project.name}; observed {len(workers)} bundled-Java workers")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    parser.add_argument("--project", type=Path, help="Read-only load/export check; the project must exercise archived JVM plugins")
    parser.add_argument("--plugins", type=Path, help="Read-only archive of the project's saved plugin packages")
    args = parser.parse_args()
    if (args.project is None) != (args.plugins is None):
        parser.error("--project and --plugins must be supplied together")
    app = args.app.resolve()
    project = args.project.resolve() if args.project else None
    plugins = args.plugins.resolve() if args.plugins else None
    check_classpath(app)
    # Copy, rather than link, so the relocation test cannot mutate the original app image.
    with tempfile.TemporaryDirectory(prefix="psd2live-launcher-") as directory:
        directory = Path(directory)
        check_launcher(app, directory / "home", project, plugins)
        relocated = directory / "A user's 模型" / 'PSD2Live "test"'
        shutil.copytree(app, relocated, symlinks=True)
        check_classpath(relocated)
        check_launcher(relocated, directory / "relocated-home", project, plugins)


if __name__ == "__main__":
    main()
