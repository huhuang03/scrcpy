set windows-shell := ["powershell.exe", "-NoLogo", "-Command"]

start_server: push-server close-server-if-need
    adb shell "nohup sh -c 'CLASSPATH=/data/local/tmp/souls app_process / com.genymobile.scrcpy.Server 3.3.1' > /data/local/tmp/souls.log 2>&1 &"

push-server:
    ./gradlew assembleRelease
    adb push ./server/build/outputs/apk/release/server-release-unsigned.apk /data/local/tmp/souls

close-server-if-need:
#netstat: /proc/kshrink_lruvecd_status: Permission denied
#netstat: /proc/voocphy_batt_fake_temp: Permission denied
#tcp6       0      0 [::]:30018              [::]:*                  LISTEN      28394/app_process
#tcp6       0      0 ::1:44134               ::1:30018               TIME_WAIT   -
#tcp6       0      0 ::1:39992               ::1:30018               TIME_WAIT   -
#OP5A15L1:/data/local/tmp $ netstat -tulpn | grep 30018
    adb shell "for pid_path in /proc/[0-9]*; do \
        pid=$${pid_path#/proc/}; \
        if grep -q ':7572' /proc/$$pid/net/tcp 2>/dev/null; then \
            kill -9 $$pid; \
            echo killed $$pid; \
        fi; \
    done"
