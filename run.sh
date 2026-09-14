#!/bin/bash
cd "$(dirname "$0")"
if [ ! -d "out" ]; then mkdir -p out; fi
javac -d out src/Main.java
java -cp out Main
