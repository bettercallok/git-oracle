#!/usr/bin/env bash
# GitOracle LLM Server Launch Script
# This starts the llama.cpp server with macOS Metal acceleration enabled.

MODEL_PATH="./models/qwen2.5-coder-7b-instruct-q4_k_m.gguf"

if [ ! -f "$MODEL_PATH" ]; then
    echo "Error: Model file not found at $MODEL_PATH"
    echo "Please download the qwen2.5-coder GGUF model and place it in the models/ directory."
    exit 1
fi

echo "Starting llama-server with Metal acceleration..."

llama-server \
  --model "$MODEL_PATH" \
  --port 8090 \
  --ctx-size 8192 \
  --n-gpu-layers -1 \
  --parallel 1 \
  --log-disable \
  --grammar-first
