set windows-shell := ["powershell.exe", "-NoLogo", "-Command"]

push-server:
    ./gradlew assembleRelease
    adb push ./server/build/outputs/apk/release/server-release-unsigned.apk /data/local/tmp/souls