#!/usr/bin/env bash
set -euo pipefail

serial="${1:-}"
output_dir="${2:-sim-pilot-logs-$(date +%Y%m%d-%H%M%S)}"
adb_command=(adb)
if [[ -n "$serial" ]]; then
  adb_command+=(-s "$serial")
fi

mkdir -p "$output_dir"
device_directory="/data/user_de/0/dev.simpilot/files/diagnostics"
filenames=(sim-pilot-current.jsonl)
for index in $(seq 1 16); do
  filenames+=("sim-pilot.$index.jsonl.gz" "sim-pilot.$index.jsonl")
done
if "${adb_command[@]}" shell run-as dev.simpilot test -d "$device_directory" 2>/dev/null; then
  for filename in "${filenames[@]}"; do
    if "${adb_command[@]}" shell run-as dev.simpilot test -f "$device_directory/$filename"; then
      "${adb_command[@]}" exec-out run-as dev.simpilot cat "$device_directory/$filename" > "$output_dir/$filename"
    fi
  done
else
  echo "SIM Pilot is not debuggable; skipping app-private JSONL files." >&2
fi

"${adb_command[@]}" shell dumpsys package dev.simpilot > "$output_dir/package.txt"
"${adb_command[@]}" shell dumpsys activity services dev.simpilot > "$output_dir/service.txt"
"${adb_command[@]}" shell logcat -d -v threadtime -s SimPilotDiag:V SimPilotMonitor:V '*:S' > "$output_dir/logcat.txt"
{
  echo "data=$("${adb_command[@]}" shell settings get global multi_sim_data_call)"
  echo "voice=$("${adb_command[@]}" shell settings get global multi_sim_voice_call)"
  echo "sms=$("${adb_command[@]}" shell settings get global multi_sim_sms)"
} > "$output_dir/default-sims.txt"

echo "$output_dir"
