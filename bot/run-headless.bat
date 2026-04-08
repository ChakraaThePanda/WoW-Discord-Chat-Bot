@echo off
cd /d "%~dp0"
java -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8 -jar watchdog.jar wowchat.jar wowchat.conf > watchdog.log 2>&1
