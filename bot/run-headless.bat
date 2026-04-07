@echo off
cd /d "%~dp0"
start /B javaw -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8 -jar watchdog.jar wowchat.jar wowchat.conf
