#!/data/data/com.termux/files/usr/bin/bash
# Run inside Termux after installing the engine:
#   npm install -g @deepseek-ai/dsh
# Produces ~/engine.tar (node_modules tree), then pull it with adb.
set -eu
PREFIX=/data/data/com.termux/files/usr
cd "$PREFIX/lib"
rm -f "$HOME/engine.tar"
tar -cf "$HOME/engine.tar" node_modules
ls -l "$HOME/engine.tar"
echo "pull it with: adb exec-out run-as com.termux cat $HOME/engine.tar > engine.tar"
