import subprocess
import re
import sys


def run_adb_shell(cmd: str) -> str:
    """运行 adb shell 命令，并返回 stdout"""
    result = subprocess.run(["adb", "shell", cmd], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if result.stderr:
        print(f"[stderr] {result.stderr.strip()}")
    return result.stdout.strip()


def find_pid_by_port(port: int) -> str | None:
    """通过 netstat 查找监听指定端口的 PID"""
    print(f"🔍 查找端口 {port} 对应的进程 PID ...")
    output = run_adb_shell("netstat -tulpn 2>/dev/null")
    for line in output.splitlines():
        if f":{port}" in line:
            print(f"🧾 匹配行: {line}")
            match = re.search(r"\s(\d+)/", line)
            if match:
                pid = match.group(1)
                print(f"✅ 找到 PID: {pid}")
                return pid
    print(f"❌ 没有进程监听端口 {port}")
    return None


def kill_pid(pid: str):
    """kill 远程 Android 上的进程"""
    print(f"💥 kill -9 {pid}")
    run_adb_shell(f"kill -9 {pid}")


def main():
    if len(sys.argv) != 2:
        print("用法: python close_server.py <port>")
        sys.exit(1)

    port = int(sys.argv[1])
    pid = find_pid_by_port(port)
    if pid:
        kill_pid(pid)


if __name__ == "__main__":
    main()
