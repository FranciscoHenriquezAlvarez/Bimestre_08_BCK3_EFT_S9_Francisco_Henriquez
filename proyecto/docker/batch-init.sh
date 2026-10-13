#!/bin/sh
set -eu

run_job() {
  job_name="$1"
  execution_id="$2"
  log_file="/tmp/s6_batch_${3}.log"
  run_stamp=$(date +%s)
  execution_id="${run_stamp}${execution_id}"

  java -jar /app/app.jar \
    --server.port=0 \
    --eureka.client.enabled=false \
    --spring.batch.job.enabled=true \
    --spring.kafka.listener.auto-startup=false \
    --spring.batch.job.name="$job_name" \
    "ejecucion=${execution_id},java.lang.Long,true" \
    > "$log_file" 2>&1 &
  pid=$!

  for i in $(seq 1 60); do
    if grep -qE "${job_name}.*(estado=COMPLETED|status: \[COMPLETED\])" "$log_file" 2>/dev/null; then
      echo "${job_name} COMPLETED"
      grep -E "${job_name}.*(estado=COMPLETED|status: \[COMPLETED\])" "$log_file" | tail -1 || true
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
      return 0
    fi
    if grep -qE "${job_name}.*(estado=FAILED|status: \[FAILED\])|APPLICATION FAILED TO START" "$log_file" 2>/dev/null; then
      tail -40 "$log_file"
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
      return 1
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      tail -40 "$log_file"
      return 1
    fi
    sleep 2
  done

  echo "ERROR: timeout esperando ${job_name}" >&2
  tail -40 "$log_file"
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  return 1
}

run_job dailyTransactionsJob 601 daily
run_job monthlyInterestJob 602 monthly
run_job annualStatementsJob 603 annual

echo "BATCH INIT COMPLETED"
