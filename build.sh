#!/bin/sh
# app.html is the source. This wraps it into index.html (the installable PWA page).
cd "$(dirname "$0")"
{ cat <<'HEAD'
<!doctype html>
<html lang="ja">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#0E1114">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="タイマー">
<link rel="manifest" href="manifest.webmanifest">
<link rel="icon" href="icon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="icon-180.png">
<style>body{margin:0}:root{padding-top:env(safe-area-inset-top,0px)}</style>
</head>
<body>
HEAD
cat app.html; printf '\n</body>\n</html>\n'; } > index.html
