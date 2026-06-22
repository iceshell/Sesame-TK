@echo off
cd /d D:\Sesame-TK\Sesame-TK
set TEMP=E:\tmp
set TMP=E:\tmp
set GRADLE_OPTS=-Djava.io.tmpdir=E:\tmp
call gradlew.bat assembleRelease --gradle-user-home=E:\gradle-home
echo BUILD_EXIT_CODE=%ERRORLEVEL%
