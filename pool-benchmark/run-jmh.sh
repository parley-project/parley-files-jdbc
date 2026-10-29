#!/bin/sh
# usage: run-jmh.sh <output dir>   (runs 1, 8 and 40 threads, one JSON file each)
out="${1:?output dir}"
mkdir -p "$out"
for t in 1 8 40; do
  java -Djava.io.tmpdir="$out" -jar target/benchmarks.jar -t $t -rf json -rff "$out/jmh-t$t.json" > "$out/jmh-t$t.log" 2>&1
done
echo done > "$out/DONE"
