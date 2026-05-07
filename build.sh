#!/bin/bash
echo "Compiling LocalServer..."
mkdir -p out
find src -name "*.java" > sources.txt
javac -d out @sources.txt
echo "Compilation complete. Executables are in the 'out' directory."
