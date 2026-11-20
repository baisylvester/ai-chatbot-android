@echo off
setlocal
cd /d "%~dp0"
echo Building and testing Mail Assistant. Results: codex-verification.log
call gradlew.bat assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest -Pchatbot.platformUrl.debug=https://ai-chatbot-9wg.pages.dev -Pchatbot.chatbotId=cb_DwGszOHPvAdGxI5E83oyjrru --console=plain --max-workers=2 --no-daemon > codex-verification.log 2>&1
set "MAIL_VERIFY_EXIT=%ERRORLEVEL%"
echo.
echo Verification finished with exit code %MAIL_VERIFY_EXIT%.
echo Please leave codex-verification.log in this folder for Codex to inspect.
pause
exit /b %MAIL_VERIFY_EXIT%
