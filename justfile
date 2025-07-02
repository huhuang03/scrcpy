set windows-shell := ["powershell.exe", "-NoLogo", "-Command"]
http_port := "30069"

start_server: push-server close-server-by-netstat
    adb shell "nohup sh -c 'CLASSPATH=/data/local/tmp/souls app_process / com.genymobile.scrcpy.Server 3.3.1' > /data/local/tmp/souls.log 2>&1 &"

push-server:
    ./gradlew assembleRelease
    adb push ./server/build/outputs/apk/release/server-release-unsigned.apk /data/local/tmp/souls

echo:
    adb shell "curl http://localhost:{{http_port}}"

close-server-by-netstat:
    python script/close_server_by_netstat.py {{http_port}}
