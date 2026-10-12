"""Compile and run the pure personal-updater contract checks without Android test dependencies."""
from pathlib import Path
import os
import subprocess

repo = Path(__file__).resolve().parents[2]
cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
java_home = Path(os.environ["JAVA_HOME"])
classes = repo / "android/build/personal-update-policy-checks"
classes.mkdir(parents=True, exist_ok=True)


def find_jar(name, required=True):
    matches = sorted(cache.rglob(name))
    if not matches and required:
        raise RuntimeError(f"Missing {name}; build assembleFossDebug once first")
    return matches[0] if matches else None


version = "2.3.21"
stdlib = find_jar(f"kotlin-stdlib-{version}.jar")
jars = [find_jar(f"kotlin-compiler-embeddable-{version}.jar"), stdlib]
for name in [f"kotlin-script-runtime-{version}.jar", "kotlin-reflect-1.6.10.jar",
             f"kotlin-daemon-embeddable-{version}.jar", "annotations-13.0.jar",
             "kotlinx-coroutines-core-jvm-1.8.0.jar"]:
    jar = find_jar(name, required=False)
    if jar:
        jars.append(jar)

subprocess.run([str(java_home / "bin/java"), "-cp", os.pathsep.join(map(str, jars)),
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                "-jvm-target", "17", "-classpath", str(stdlib), "-d", str(classes),
                str(repo / "android/app/src/main/java/me/kavishdevar/librepods/data/updates/PersonalUpdatePolicy.kt")],
               check=True)
classpath = os.pathsep.join([str(classes), str(stdlib)])
subprocess.run([str(java_home / "bin/javac"), "--release", "17", "-cp", classpath,
                "-d", str(classes), str(Path(__file__).with_name("PersonalUpdatePolicyChecks.java"))],
               check=True)
subprocess.run([str(java_home / "bin/java"), "-cp", classpath, "PersonalUpdatePolicyChecks"],
               check=True)
