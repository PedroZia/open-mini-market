@echo off
rem Lancador do PDV no Windows (passo 1119): abre a TUI numa janela de console de verdade.
rem Uso: pdv.cmd [url-da-api]
rem Sem argumento: usa MINIMARKET_API_URL; sem a variavel, http://localhost:8081 (dev).
setlocal
title PDV minimercado
if not defined MINIMARKET_API_URL set "MINIMARKET_API_URL=http://localhost:8081"
if not "%~1"=="" set "MINIMARKET_API_URL=%~1"
cd /d "%~dp0"
call npm start
set "EXIT_CODE=%errorlevel%"
if not "%EXIT_CODE%"=="0" pause
exit /b %EXIT_CODE%
