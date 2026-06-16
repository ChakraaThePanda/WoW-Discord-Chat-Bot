@echo off
chcp 65001
title WoWChat
cls
echo.
echo  ====================================================================
echo   _    _       _    _ _____ _           _  ______       _   
echo  ^| ^|  ^| ^|     ^| ^|  ^| /  __ \ ^|         ^| ^| ^| ___ \     ^| ^|  
echo  ^| ^|  ^| ^| ___ ^| ^|  ^| ^| /  \/ ^|__   __ _^| ^|_^| ^|_/ / ___ ^| ^|_ 
echo  ^| ^|/\^| ^|/ _ \^| ^|/\^| ^| ^|   ^| '_ \ / _` ^| __^| ___ \/ _ \^| __^|
echo  \  /\  / (_) \  /\  / \__/\ ^| ^| ^| (_^| ^| ^|_^| ^|_/ / (_) ^| ^|_ 
echo   \/  \/ \___/ \/  \/ \____/_^| ^|_^|\__,_^|\__\____/ \___/ \__^|                                                                                                                    
echo.
echo   Maintained by / Discord: Chakraa
echo   GitHub: github.com/ChakraaThePanda/WoW-Discord-Chat-Bot
echo  ====================================================================
echo.
echo  Starting bot...
echo.
java -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8 -jar watchdog.jar wowchat.jar wowchat.conf
pause
